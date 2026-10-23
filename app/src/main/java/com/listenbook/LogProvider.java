package com.listenbook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.listenbook.export.ExportStore;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把诊断日志导出的文件安全地给出去。
 *
 * 为什么需要它：Android 7 起，把 file:// Uri 交给别的 App 会抛
 * FileUriExposedException；官方解法是 FileProvider，但那是 androidx / support 库里的。
 * 项目坚持零第三方依赖，所以这里自己实现一个最小 ContentProvider：
 * 只认文件名，不做路径拼接，天然挡住 ../../ 穿越。
 */
public class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "com.listenbook.diag";

    /** 导出成品所在的一级目录名，见 uriFor() */
    private static final String EXPORT_SEGMENT = "export";

    private static final String[] COLUMNS = new String[]{
            OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};

    /**
     * 拿一个导出成品（WAV）的可分享 Uri。
     *
     * Android 7 起把 file:// 交给别的 App 会抛 FileUriExposedException，
     * 而官方 FileProvider 在 androidx 里拿不到，所以借这个已有的 provider 兼一下。
     *
     * URI 必须带上 bookId：v1.2 的文件名是 0001_第一章.wav 这种序号，
     * 跨书重名是常态，只给文件名的话，分享 A 书第 1 章可能发出去 B 书第 1 章。
     * 形如 content://com.listenbook.diag/export/&lt;bookId&gt;/0001_第一章.wav
     */
    public static Uri uriFor(Context context, File file) {
        if (context == null || file == null || !file.isFile()) {
            return null;
        }
        File parent = file.getParentFile();
        if (parent == null || parent.getParentFile() == null) {
            return null;
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(EXPORT_SEGMENT)
                .appendPath(parent.getName())
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File baseDir() {
        Context c = getContext();
        if (c == null) {
            return null;
        }
        File base = c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) {
            base = c.getExternalFilesDir(null);
        }
        if (base == null) {
            base = c.getFilesDir();
        }
        return new File(base, "diag");
    }

    /**
     * 导出根目录。注意 baseDir() 返回的已经是「.../export」，
     * 这里不能再拼一次 export，否则会解析成 export/export，分享必然失败。
     */
    private File exportDir() {
        Context c = getContext();
        if (c == null) {
            return null;
        }
        return ExportStore.baseDir(c);
    }

    /**
     * 只接受三种形态：
     *   content://auth/&lt;name&gt;→ 诊断目录下的单个文件
     *   content://auth/export/&lt;name&gt;                 → 导出根目录下（兼容旧调用）
     *   content://auth/export/&lt;bookId&gt;/&lt;name&gt;→ 某本书的成品
 * 每段都只认一层文件名，任何"/" 或 ".." 都当非法请求 ——
 * 这样天然挡住路径穿越，又不至于像只认一个名字那样把分享功能也堵死。
 * 路径穿越防护已逐条确认：分段校验 + 单段名禁 "/" "\" "." ".."。
 */
    private File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null) {
            throw new FileNotFoundException("empty uri");
        }
        java.util.List<String> segs = uri.getPathSegments();
        if (segs == null || segs.isEmpty() || segs.size() > 3) {
            throw new FileNotFoundException("bad path");
        }
        String name = segs.get(segs.size() - 1);
        if (isBadSegment(name)) {
            throw new FileNotFoundException("bad name: " + name);
        }
        File base;
        if (segs.size() == 1) {
            base = baseDir();
        } else {
            if (!EXPORT_SEGMENT.equals(segs.get(0))) {
                throw new FileNotFoundException("bad dir: " + segs.get(0));
            }
            File exportRoot = exportDir();
            if (exportRoot == null) {
                throw new FileNotFoundException("no export dir");
            }
            if (segs.size() == 3) {
                //带 bookId：精确定位到那一本书的目录，不跨书搜同名文件
                String bookId = segs.get(1);
                if (isBadSegment(bookId)) {
                    throw new FileNotFoundException("bad bookId: " + bookId);
                }
                base = new File(exportRoot, bookId);
            } else {
                // 兼容只给文件名的旧式Uri：在各书目录里找第一个同名文件
                base = findInBookDirs(exportRoot, name);
                if (base == null) {
                    throw new FileNotFoundException("missing: " + name);
                }
            }
        }
        if (base == null) {
            throw new FileNotFoundException("no dir");
        }
        File f = new File(base, name);
        if (!f.isFile()) {
            throw new FileNotFoundException("missing: " + name);
        }
        return f;
    }

    private static boolean isBadSegment(String s) {
        return s == null || s.length() == 0
                || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0
                || s.equals(".") || s.equals("..");
    }

    /** 成品在 export/&lt;bookId&gt;/ 下，这里逐个书目录找同名文件。 */
    private static File findInBookDirs(File exportRoot, String name) {
        File[] subs = exportRoot.listFiles();
        if (subs == null) {
            return null;
        }
        for (int i = 0; i < subs.length; i++) {
            if (subs[i].isDirectory()) {
                File f = new File(subs[i], name);
                if (f.isFile()) {
                    return subs[i];
                }
            }
        }
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && (mode.contains("w") || mode.contains("W"))) {
            throw new FileNotFoundException("read only");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(projection == null ? COLUMNS : projection);
            Object[] row = new Object[projection == null ? 2 : projection.length];
            if (projection == null) {
                row[0] = f.getName();
                row[1] = Long.valueOf(f.length());
            } else {
                for (int i = 0; i < projection.length; i++) {
                    if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) {
                        row[i] = f.getName();
                    } else if (OpenableColumns.SIZE.equals(projection[i])) {
                        row[i] = Long.valueOf(f.length());
                    }
                }
            }
            c.addRow(row);
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = "";
        if (uri != null) {
            String last = uri.getLastPathSegment();
            name = last == null ? "" : last;
        }
        if (name.endsWith(".wav")) {
            return "audio/x-wav";
        }
        if (name.endsWith(".md")) {
            return "text/markdown";
        }
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
