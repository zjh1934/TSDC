package com.listenbook.util;

/** 文件名净化：章节标题会进文件名，必须清掉非法字符。 */
public final class SafeName {

    private SafeName() {
    }

    private static final String ILLEGAL = "/\\:*?\"<>|\r\n\t";

    public static String file(String raw) {
        return file(raw, 40);
    }

    public static String file(String raw, int maxLen) {
        if (raw == null) {
            return "untitled";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || ILLEGAL.indexOf(c) >= 0) {
                sb.append('_');
            } else if (c == '\u3000' || c == '\uFEFF' || c == '\u200B') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() > maxLen) {
            s = s.substring(0, maxLen);
        }
        if (s.length() == 0) {
            s = "untitled";
        }
        return s;
    }

    /** 一行文本压成适合当标题的样子 */
    public static String title(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\uFEFF', ' ').replace('\u200B', ' ').replace('\u3000', ' ');
        s = s.replaceAll("[ \\t]{2,}", " ").trim();
        if (s.length() > maxLen) {
            s = s.substring(0, maxLen);
        }
        return s;
    }
}
