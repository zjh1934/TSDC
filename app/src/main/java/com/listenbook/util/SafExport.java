package com.listenbook.util;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义导出目录。走 Storage Access Framework（SAF），只用 android.provider 的 API，
 * 不需要 androidx 的 DocumentFile —— 那是本项目唯一不能妥协的约束。
 *
 * 为什么不能一步到位把输出目录指定出去：
 *   TextToSpeech.synthesizeToFile 的目标是「另一个 APK 去写」，
 *   TTS 引擎进程只被授权写我们 App 的外部专属目录，给个别处的路径一律 EACCES。
 *   所以正确姿势是两段式：先落到 App 专属目录（快、稳、能断点续传），
 *   合成完再把成品搬去用户挑的目录。
 *
 * MANAGE_EXTERNAL_STORAGE / 直接 File 写 SD 卡都不考虑：
 *   前者 Android 11+ 要特殊权限且上架要审，后者在 Android 10+ 上基本写不进去。
 */
public final class SafExport {

    public static final int REQ_TREE = 3002;

    private static final String PREF = "listenbook";
    private static final String K_URI = "saf_tree_uri";
    private static final String K_NAME = "saf_tree_name";

    private SafExport() {
    }

    private static SharedPreferences pref(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 让用户挑一个目录。结果回到 Activity.onActivityResult → handleResult()。 */
    public static void requestTree(Activity activity) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            AppLog.w("SafExport", "没有可用的目录选择器：" + e.getMessage());
            android.widget.Toast.makeText(activity, "这个系统上没有找到目录选择器",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 收下用户选的目录。一定要在这里 takePersistableUriPermission，
     * 否则进程重启后这个 Uri 就失效了 —— 导出是挂机跑的，进程随时可能被回收。
     */
    public static String handleResult(Context context, Intent data) {
        if (data == null) {
            return null;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return null;
        }
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            context.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception e) {
            AppLog.w("SafExport", "保存目录权限失败：" + e.getMessage());
        }
        String name = displayName(context, uri);
        pref(context).edit()
                .putString(K_URI, uri.toString())
                .putString(K_NAME, name)
                .apply();
        AppLog.i("SafExport", "自定义导出目录：" + name + "  " + uri);
        return name;
    }

    public static Uri treeUri(Context context) {
        String s = pref(context).getString(K_URI, "");
        if (s.length() == 0) {
            return null;
        }
        try {
            return Uri.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static String treeName(Context context) {
        return pref(context).getString(K_NAME, "");
    }

    public static boolean hasTree(Context context) {
        return treeUri(context) != null;
    }

    public static void clear(Context context) {
        Uri uri = treeUri(context);
        if (uri != null) {
            try {
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                context.getContentResolver().releasePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
            }
        }
        pref(context).edit().remove(K_URI).remove(K_NAME).apply();
        AppLog.i("SafExport", "已清除自定义导出目录");
    }

    public static String displayName(Context context, Uri tree) {
        Cursor c = null;
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            c = context.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                return n == null ? tree.getLastPathSegment() : n;
            }
        } catch (Exception e) {
            AppLog.w("SafExport", "读目录名失败：" + e.getMessage());
        } finally {
            Io.close(c);
        }
        return tree == null ? "" : String.valueOf(tree.getLastPathSegment());
    }

    /**
     * 把一个 WAV 搬进自定义目录下的《书名》子目录里。已存在同名文件就覆盖。
     *
     * @return true 表示成功
     */
    public static boolean publishFile(Context context, Uri tree, String bookTitle, File file) {
        if (tree == null || file == null || !file.isFile()) {
            return false;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            Uri bookDir = findOrCreateDir(resolver, tree, rootDoc, SafeName.file(bookTitle, 40));
            if (bookDir == null) {
                AppLog.w("SafExport", "创建子目录失败：" + bookTitle);
                return false;
            }
            Uri existing = findChild(resolver, tree, bookDir, file.getName());
            Uri target = existing;
            if (target == null) {
                target = DocumentsContract.createDocument(
                        resolver, bookDir, "audio/wav", file.getName());
            }
            if (target == null) {
                return false;
            }
            OutputStream out = resolver.openOutputStream(target);
            if (out == null) {
                return false;
            }
            try {
                Io.copyFileToStream(file, out);
            } finally {
                Io.close(out);
            }
            return true;
        } catch (Exception e) {
            AppLog.w("SafExport", "搬运 " + file.getName() + " 失败：" + e.getMessage());
            return false;
        }
    }

    public static int publishAll(Context context, Uri tree, String bookTitle, List<File> files) {
        int n = 0;
        if (files == null) {
            return 0;
        }
        for (int i = 0; i < files.size(); i++) {
            if (publishFile(context, tree, bookTitle, files.get(i))) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 目录操作

    private static Uri findChild(ContentResolver resolver, Uri tree, Uri parentDoc, String name) {
        Cursor c = null;
        try {
            String parentId = DocumentsContract.getDocumentId(parentDoc);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            c = resolver.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String display = c.getString(1);
                    if (name.equals(display)) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w("SafExport", "遍历目录失败：" + e.getMessage());
        } finally {
            Io.close(c);
        }
        return null;
    }

    private static Uri findOrCreateDir(ContentResolver resolver, Uri tree, Uri parentDoc,
                                       String name) {
        Uri existing = findChild(resolver, tree, parentDoc, name);
        if (existing != null) {
            return existing;
        }
        try {
            return DocumentsContract.createDocument(
                    resolver, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (Exception e) {
            AppLog.w("SafExport", "createDocument 失败：" + e.getMessage());
            return null;
        }
    }
}
