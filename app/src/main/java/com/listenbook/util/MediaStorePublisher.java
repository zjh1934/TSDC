package com.listenbook.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.OutputStream;

/**
 * 把导出的 WAV 同步一份到公共音乐目录，方便 VLC / Audiobookshelf / Poweramp 直接播。
 *
 * 为什么必须多这一步：TTS 引擎是独立 APK，只能写 getExternalFilesDir()，
 * 但 Android 11+ 其它 App 读不了 /Android/data/<pkg>/files。
 * 所以「TTS 写 App 专属目录 → 我们再发布到 MediaStore」是唯一稳妥链路。
 *
 * API 29+：走 MediaStore，不需要任何存储权限。
 * API <=28：直接复制到公共目录，需要 WRITE_EXTERNAL_STORAGE（Manifest 已限 maxSdkVersion=28）。
 */
public final class MediaStorePublisher {

    private MediaStorePublisher() {
    }

    public static final String APP_DIR = "ListenBook";

    /** Android 9 及以下写公共目录需要存储权限；29+ 走 MediaStore 不需要。 */
    public static boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT <= 28;
    }

/**
 * 往公共目录发布一份成品。v1.2 起同时写「音乐」和「下载」两处：
 * 不同厂商的文件管理器对这两个目录的默认可见性不一样，
 * 用户反馈过「导出完去音乐里找不到」，双写一份能把这类找不到的问题消掉。
 *
 * 关键点：两处必须用**不同的 collection**。
 * 往 Download 目录写文件要用 MediaStore.Downloads；
 * 拿 Audio collection 硬塞 RELATIVE_PATH=Download/... 会被系统拒绝，
 * 异常又被catch 吞掉，结果就是「下载」那份永远不出现，而界面却说两处都有。
 *
 * @return 任意一个目的地成功即返回描述，全部失败返回 null
 */
    public static String publish(Context context, File wav, String bookTitle) {
        String a = publishTo(context, wav, bookTitle, Environment.DIRECTORY_MUSIC, true);
        String b = publishTo(context, wav, bookTitle, Environment.DIRECTORY_DOWNLOADS, false);
        if (a != null) {
            return a;
        }
        return b;
    }

    /** 界面上「文件在哪」卡片用的两个目的地说明 */
    public static String publicDirHint(String bookTitle) {
        String folder = SafeName.file(bookTitle, 40);
        return Environment.DIRECTORY_MUSIC + "/" + APP_DIR + "/" + folder
                + " 与 " + Environment.DIRECTORY_DOWNLOADS + "/" + APP_DIR + "/" + folder;
    }

    /** 单个目的地的完整相对路径，供界面显示与复制 */
    public static String relPath(String parent, String bookTitle) {
        return parent + "/" + APP_DIR + "/" + SafeName.file(bookTitle, 40);
    }

    /** audio=true 走 MediaStore.Audio；false 走 MediaStore.Downloads */
    private static String publishTo(Context context, File wav, String bookTitle,
            String parent, boolean audio) {
        if (wav == null || !wav.isFile()) {
            return null;
        }
        String folder = SafeName.file(bookTitle, 40);
        if (Build.VERSION.SDK_INT >= 29) {
            return publishViaMediaStore(context, wav, folder, parent, audio);
        }
        return publishViaLegacyFile(wav, folder, parent);
    }

    private static String publishViaMediaStore(Context context, File wav, String folder,
            String parent, boolean audio) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, wav.getName());
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, parent + "/" + APP_DIR + "/" + folder);
        values.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(1));
        Uri collection;
        if (audio) {
            values.put(MediaStore.Audio.Media.TITLE, stripExt(wav.getName()));
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav");
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        } else {
            values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav");
            collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        }

        Uri uri = null;
        OutputStream out = null;
        try {
            uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                return null;
            }
            out = context.getContentResolver().openOutputStream(uri);
            if (out == null) {
                // 插入成功但打不开流：必须把这条 IS_PENDING=1 的僵尸记录删掉，
                // 否则它在系统里永远不可见，还白占一份空间。
                safeDelete(context, uri);
                return null;
            }
            Io.copyFileToStream(wav, out);
            out.flush();
            Io.close(out);
            out = null;

            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(0));
            context.getContentResolver().update(uri, values, null, null);
            return uri.toString();
        } catch (Exception e) {
            safeDelete(context, uri);
            return null;
        } finally {
            Io.close(out);
        }
    }

    private static void safeDelete(Context context, Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }

    private static String publishViaLegacyFile(File wav, String folder, String parent) {
        try {
            File dir = new File(new File(
                    Environment.getExternalStoragePublicDirectory(parent),
                    APP_DIR), folder);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File dest = new File(dir, wav.getName());
            Io.copyFile(wav, dest);
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
