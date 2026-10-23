package com.listenbook.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 自研 HTML 剥离器，替代 jsoup（AIDE 拿不到远程 maven 依赖）。
 * 只做 EPUB XHTML → 纯文本这一件事：
 *   1. 去掉注释 / script / style / head
 *   2. 块级标签转换行
 *   3. 剥掉所有标签
 *   4. 解码实体（命名 + 十进制 + 十六进制）
 *   5. 压缩空白，规整段落
 */
public final class HtmlStripper {

    private HtmlStripper() {
    }

    private static final Map<String, String> ENTITIES = new HashMap<String, String>();

    static {
        ENTITIES.put("amp", "&");
        ENTITIES.put("lt", "<");
        ENTITIES.put("gt", ">");
        ENTITIES.put("quot", "\"");
        ENTITIES.put("apos", "'");
        ENTITIES.put("nbsp", " ");
        ENTITIES.put("ensp", " ");
        ENTITIES.put("emsp", " ");
        ENTITIES.put("thinsp", " ");
        ENTITIES.put("hellip", "…");
        ENTITIES.put("mdash", "—");
        ENTITIES.put("ndash", "–");
        ENTITIES.put("lsquo", "‘");
        ENTITIES.put("rsquo", "’");
        ENTITIES.put("ldquo", "“");
        ENTITIES.put("rdquo", "”");
        ENTITIES.put("laquo", "«");
        ENTITIES.put("raquo", "»");
        ENTITIES.put("middot", "·");
        ENTITIES.put("bull", "•");
        ENTITIES.put("deg", "°");
        ENTITIES.put("times", "×");
        ENTITIES.put("divide", "÷");
        ENTITIES.put("plusmn", "±");
        ENTITIES.put("copy", "©");
        ENTITIES.put("reg", "®");
        ENTITIES.put("trade", "™");
    }

    /** 这些标签前后要断行 */
    private static final String[] BLOCK_TAGS = {
            "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "ul", "ol", "tr", "td", "th", "table", "section",
            "article", "blockquote", "pre", "hr", "dt", "dd", "figure",
            "figcaption", "header", "footer", "nav", "aside", "body", "html"
    };

    public static String strip(String html) {
        if (html == null || html.length() == 0) {
            return "";
        }
        String s = removeBlocks(html, "script");
        s = removeBlocks(s, "style");
        s = removeBlocks(s, "head");
        s = removeComments(s);
        s = blockToNewline(s);
        s = removeTags(s);
        s = decodeEntities(s);
        return normalize(s);
    }

    private static String removeBlocks(String s, String tag) {
        StringBuilder sb = new StringBuilder(s.length());
        String open = "<" + tag;
        String close = "</" + tag + ">";
        String lc = s.toLowerCase();
        int i = 0;
        while (i < s.length()) {
            int st = lc.indexOf(open, i);
            if (st < 0) {
                sb.append(s, i, s.length());
                break;
            }
            // 必须紧跟空格或 '>'，避免把 <scripting> 当成 <script>
            int after = st + open.length();
            if (after < s.length() && !isTagBoundary(s.charAt(after))) {
                sb.append(s, i, after);
                i = after;
                continue;
            }
            int en = lc.indexOf(close, st);
            if (en < 0) {
                sb.append(s, i, st);
                break;
            }
            sb.append(s, i, st);
            sb.append('\n');
            i = en + close.length();
        }
        return sb.toString();
    }

    private static boolean isTagBoundary(char c) {
        return c == '>' || c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/';
    }

    private static String removeComments(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int st = s.indexOf("<!--", i);
            if (st < 0) {
                sb.append(s, i, s.length());
                break;
            }
            int en = s.indexOf("-->", st + 4);
            sb.append(s, i, st);
            if (en < 0) {
                break;
            }
            i = en + 3;
        }
        return sb.toString();
    }

    private static String blockToNewline(String s) {
        String out = s;
        for (int i = 0; i < BLOCK_TAGS.length; i++) {
            String t = BLOCK_TAGS[i];
            out = replaceTag(out, t, "\n");
        }
        return out;
    }

    /** 大小写不敏感地把 <tag ...> 与 </tag> 都换成 repl */
    private static String replaceTag(String s, String tag, String repl) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int i = 0;
        int n = s.length();
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0) {
                sb.append(s, i, n);
                break;
            }
            int gt = s.indexOf('>', lt);
            if (gt < 0) {
                sb.append(s, i, n);
                break;
            }
            sb.append(s, i, lt);
            String inner = s.substring(lt + 1, gt).trim();
            if (inner.startsWith("/")) {
                inner = inner.substring(1).trim();
            }
            int sp = 0;
            while (sp < inner.length() && !isTagBoundary(inner.charAt(sp))) {
                sp++;
            }
            String name = inner.substring(0, sp).toLowerCase();
            if (name.equals(tag)) {
                sb.append(repl);
            } else {
                sb.append(s, lt, gt + 1);
            }
            i = gt + 1;
        }
        return sb.toString();
    }

    private static String removeTags(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '<') {
                int gt = s.indexOf('>', i);
                if (gt < 0) {
                    break;
                }
                i = gt + 1;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    public static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) {
                sb.append(c);
                i++;
                continue;
            }
            String body = s.substring(i + 1, semi);
            String rep = resolveEntity(body);
            if (rep == null) {
                sb.append(c);
                i++;
                continue;
            }
            sb.append(rep);
            i = semi + 1;
        }
        return sb.toString();
    }

    private static String resolveEntity(String body) {
        if (body.length() == 0) {
            return null;
        }
        if (body.charAt(0) == '#') {
            try {
                int cp;
                if (body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X')) {
                    cp = Integer.parseInt(body.substring(2), 16);
                } else {
                    cp = Integer.parseInt(body.substring(1), 10);
                }
                if (cp <= 0 || cp > 0x10FFFF) {
                    return null;
                }
                return new String(Character.toChars(cp));
            } catch (Exception e) {
                return null;
            }
        }
        Object v = ENTITIES.get(body);
        return v == null ? null : (String) v;
    }

    /** 压缩空白：行内多空格合一，去掉行首尾空白，空行最多一个 */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace('\r', ' ').replace('\t', ' ');
            line = line.replaceAll(" {2,}", " ").replace('\u00A0', ' ').trim();
            if (line.length() == 0) {
                // 空行直接丢弃：每段一行，靠 TextView 的行距分段，
                // 也避免 TTS 把连续空行读成停顿
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** 从 XHTML 里取 <title> 或第一个 h1~h3 作为章节标题 */
    public static String extractTitle(String html) {
        if (html == null) {
            return "";
        }
        String t = firstTagContent(html, "h1");
        if (t.length() == 0) {
            t = firstTagContent(html, "h2");
        }
        if (t.length() == 0) {
            t = firstTagContent(html, "h3");
        }
        if (t.length() == 0) {
            t = firstTagContent(html, "title");
        }
        t = decodeEntities(removeTags(t)).replace('\n', ' ').replaceAll("[ \\t]{2,}", " ").trim();
        return SafeName.title(t, 60);
    }

    private static String firstTagContent(String s, String tag) {
        String lc = s.toLowerCase();
        String open = "<" + tag;
        int st = lc.indexOf(open);
        if (st < 0) {
            return "";
        }
        int gt = s.indexOf('>', st);
        if (gt < 0) {
            return "";
        }
        int en = lc.indexOf("</" + tag + ">", gt);
        if (en < 0) {
            return "";
        }
        return s.substring(gt + 1, en);
    }
}
