package com.listenbook.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 最小 IO 工具集，全部 JDK 原生。 */
public final class Io {

    private Io() {
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static byte[] readAll(File f) throws IOException {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(f));
            return readAll(in);
        } finally {
            close(in);
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    public static long copyFile(File src, File dst) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new BufferedInputStream(new FileInputStream(src));
            out = new FileOutputStream(dst);
            copy(in, out);
        } finally {
            close(out);
            close(in);
        }
        return dst.length();
    }

    public static long copyFileToStream(File src, OutputStream out) throws IOException {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(src));
            byte[] buf = new byte[16384];
            int n;
            long total = 0L;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            return total;
        } finally {
            close(in);
        }
    }

    public static String readString(File f, String charset) throws IOException {
        return new String(readAll(f), charset);
    }

    public static void writeString(File f, String s) throws IOException {
        OutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(s.getBytes("UTF-8"));
            out.flush();
        } finally {
            close(out);
        }
    }

    public static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean deleteQuietly(File f) {
        if (f == null) {
            return false;
        }
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (int i = 0; i < kids.length; i++) {
                        deleteQuietly(kids[i]);
                    }
                }
            }
            return f.delete();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean ensureParent(File f) {
        File p = f.getParentFile();
        return p == null || p.isDirectory() || p.mkdirs();
    }
}
