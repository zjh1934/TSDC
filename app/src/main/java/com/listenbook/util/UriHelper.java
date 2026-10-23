package com.listenbook.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Uri → 真实文件。★ 修复「导入后提示文件不存在」的关键。
 *
 * ACTION_GET_CONTENT 返回的是 content:// Uri，对它调 uri.getPath() 得到的是
 * 虚拟路径 /external_files/Download/... ，new File(path) 必然不存在。
 * 正确做法：ContentResolver.openInputStream(uri) 读流，复制到 App 私有目录。
 *
 * 附带好处：
 *   - 不需要 READ_EXTERNAL_STORAGE（ACTION_GET_CONTENT 自带临时读授权）
 *   - 原文件被移动/删除不影响已导入的书
 *   - 不受分区存储影响
 */
public final class UriHelper {

    private UriHelper() {
    }

    public static String displayName(Context context, Uri uri) {
        if (uri == null) {
            return "";
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String p = uri.getPath();
            if (p != null) {
                File f = new File(p);
                return f.getName();
            }
        }
        Cursor c = null;
        try {
            c = context.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !c.isNull(idx)) {
                    String name = c.getString(idx);
                    if (name != null && name.length() > 0) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        String last = uri.getLastPathSegment();
        if (last != null && last.length() > 0) {
            return last;
        }
        return "book.txt";
    }

    public static long sizeOf(Context context, Uri uri) {
        Cursor c = null;
        try {
            c = context.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        return 0L;
    }

    /**
     * 把 Uri 指向的内容复制成 App 私有目录里的真实文件。
     * 之后全流程只认这个真实 File，绝不再碰 content:// 的虚拟路径。
     */
    public static File importTo(Context context, Uri uri, File destDir) throws IOException {
        if (uri == null) {
            throw new IOException("Uri 为空");
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir.getAbsolutePath());
        }
        String name = SafeName.file(displayName(context, uri), 60);
        String ext = Fingerprint.extOf(name);
        if (!ext.equals("txt") && !ext.equals("epub") && !ext.equals("md")) {
            // 有些文件管理器不带扩展名，按内容猜
            ext = guessExt(context, uri);
            if (ext.length() > 0) {
                name = name + "." + ext;
            }
        }
        File dest = uniqueFile(destDir, name);

        InputStream in = null;
        OutputStream out = null;
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String p = uri.getPath();
                if (p == null) {
                    throw new IOException("无法解析 file:// 路径");
                }
                in = new FileInputStream(new File(p));
            } else {
                in = context.getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IOException("无法打开输入流：" + uri);
                }
            }
            out = new FileOutputStream(dest);
            Io.copy(in, out);
        } catch (IOException e) {
            Io.deleteQuietly(dest);
            throw e;
        } finally {
            Io.close(out);
            Io.close(in);
        }
        if (!dest.isFile() || dest.length() == 0L) {
            Io.deleteQuietly(dest);
            throw new IOException("复制结果为空文件");
        }
        return dest;
    }

    /** EPUB 本质是 zip，头两字节是 PK */
    private static String guessExt(Context context, Uri uri) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return "";
            }
            byte[] head = new byte[4];
            int n = in.read(head);
            if (n >= 2 && head[0] == 'P' && head[1] == 'K') {
                return "epub";
            }
            return "txt";
        } catch (Exception e) {
            return "";
        } finally {
            Io.close(in);
        }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 500; i++) {
            File cand = new File(dir, base + "(" + i + ")" + ext);
            if (!cand.exists()) {
                return cand;
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    public static boolean isSupportedName(String name) {
        String ext = Fingerprint.extOf(name);
        return ext.equals("txt") || ext.equals("epub") || ext.equals("md");
    }
}
