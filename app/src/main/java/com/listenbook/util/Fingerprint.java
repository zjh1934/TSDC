package com.listenbook.util;

import java.io.File;
import java.security.MessageDigest;

/**
 * 内容指纹。章节 ID 必须用它，不能用顺序编号：
 * 序号在章节增删/排序变化时会错位，导致已生成的 WAV 张冠李戴。
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (int i = 0; i < d.length; i++) {
                String h = Integer.toHexString(d[i] & 0xFF);
                if (h.length() < 2) {
                    sb.append('0');
                }
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            // 极端情况退化为 hashCode，仍然稳定
            return Integer.toHexString(s.hashCode());
        }
    }

    /** chapterId = sha256(书名 + 章节标题 + 正文前 64 字)[:8] */
    public static String chapterId(String bookTitle, String chapterTitle, String body) {
        String head = body == null ? "" : body.substring(0, Math.min(64, body.length()));
        return sha256(nz(bookTitle) + "\u0001" + nz(chapterTitle) + "\u0002" + head).substring(0, 8);
    }

    /** bookId 用文件路径 + 大小 + 标题，导入同一本书两次会得到同一个 id */
    public static String bookId(String displayName, long size, String title) {
        return sha256(nz(displayName) + "\u0001" + size + "\u0002" + nz(title)).substring(0, 10);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) {
            return "";
        }
        return name.substring(i + 1).toLowerCase();
    }

    public static long sizeOf(File f) {
        try {
            return f == null || !f.isFile() ? 0L : f.length();
        } catch (Exception e) {
            return 0L;
        }
    }
}
