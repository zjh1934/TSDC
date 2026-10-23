package com.listenbook.tts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 句子切分，保留每句在原文的 start/end（逐句高亮靠绝对偏移量）。
 *
 * 质量要点：
 *   - 中英文终止符分别处理，省略号/引号尾巴一起吞掉
 *   - 缩写合并修复：TITLE 类（Mr./Dr./Prof.）永远合并；
 *     CONDITIONAL 类（etc./p.m./no.）仅在后句以小写或数字开头时合并
 *   - 硬切分：单句超过 HARD_LIMIT 时在词边界切开，避开 4000 字符上限
 *   - 碎句回收：过短的片段并回上一句，避免朗读时一顿一顿
 */
public final class SentenceSplitter {

    private SentenceSplitter() {
    }

    /** getMaxSpeechInputLength() 默认 4000，留余量 */
    public static final int HARD_LIMIT = 3800;
    /** 导出时的分块预算，比 HARD_LIMIT 再保守一点 */
    public static final int CHUNK_BUDGET = 3500;

    private static final Set<String> TITLE_ABBR = new HashSet<String>(Arrays.asList(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "rev", "capt", "col",
            "gen", "lt", "sgt", "maj", "gov", "sen", "rep", "pres", "supt", "det",
            "hon", "fr", "bro", "sis", "vs", "etc"));

    private static final Set<String> COND_ABBR = new HashSet<String>(Arrays.asList(
            "etc", "e.g", "i.e", "cf", "no", "nos", "vol", "vols", "fig", "figs", "p", "pp",
            "approx", "inc", "ltd", "co", "corp", "dept", "est", "ave", "blvd", "rd",
            "am", "pm", "a.m", "p.m", "jan", "feb", "mar", "apr", "jun", "jul",
            "aug", "sep", "sept", "oct", "nov", "dec"));

    private static final String CJK_TERMINATORS = "。！？；…!?;";
    private static final String CLOSING = "”’\"』」）)》〉】>〕";

    public static List<Sentence> split(String text) {
        List<Sentence> out = new ArrayList<Sentence>();
        if (text == null || text.length() == 0) {
            return out;
        }
        int n = text.length();
        int start = -1;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (start < 0) {
                if (isBlank(c)) {
                    i++;
                    continue;
                }
                start = i;
            }
            int cutEnd = -1;
            if (c == '\n') {
                cutEnd = i;
            } else if (CJK_TERMINATORS.indexOf(c) >= 0) {
                cutEnd = absorbTerminators(text, i);
            } else if (c == '.' || c == '!' || c == '?') {
                if (c == '.' && shouldMerge(text, i)) {
                    i++;
                    continue;
                }
                cutEnd = absorbTerminators(text, i);
            } else if (i - start >= HARD_LIMIT) {
                cutEnd = hardCut(text, start, i);
            }
            if (cutEnd > start) {
                add(out, text, start, cutEnd);
                start = -1;
                i = cutEnd;
                continue;
            }
            i++;
        }
        if (start >= 0 && start < n) {
            add(out, text, start, n);
        }
        return mergeFragments(out, text);
    }

    private static boolean isBlank(char c) {
        // '\n' 必须在内：否则换行会被当成句子起点，句子 text 里混进 \n，
        // 高亮区间和滚动位置都会偏
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f'
                || c == '\u3000' || c == '\u00A0' || c == '\u200B';
    }

    /** 吞掉连续终止符 + 收尾引号，返回句子结束位置（不含） */
    private static int absorbTerminators(String text, int i) {
        int n = text.length();
        int p = i;
        while (p < n && CJK_TERMINATORS.indexOf(text.charAt(p)) >= 0) {
            p++;
        }
        while (p < n && CLOSING.indexOf(text.charAt(p)) >= 0) {
            p++;
        }
        return p > i ? p : i + 1;
    }

    /** 英文超长句在词边界硬切 */
    private static int hardCut(String text, int start, int i) {
        int p = i;
        while (p > start + HARD_LIMIT / 2) {
            char c = text.charAt(p);
            if (c == ' ' || c == '\t' || c == '\n' || c == '，' || c == '、' || c == ',') {
                return p + 1;
            }
            p--;
        }
        return i;
    }

    /** 判断 text[i] == '.' 时是否应该合并（不切句） */
    private static boolean shouldMerge(String text, int i) {
        int n = text.length();
        // 数字小数点：3.14
        if (i > 0 && i + 1 < n && isDigit(text.charAt(i - 1)) && isDigit(text.charAt(i + 1))) {
            return true;
        }
        // 点后紧跟非空白（网址、版本号）
        if (i + 1 < n && !isBlank(text.charAt(i + 1)) && text.charAt(i + 1) != '\n') {
            char next = text.charAt(i + 1);
            if (Character.isLetterOrDigit(next)) {
                return true;
            }
        }
        String word = wordBefore(text, i);
        if (word.length() == 0) {
            return false;
        }
        String lower = word.toLowerCase(java.util.Locale.US);
        if (TITLE_ABBR.contains(lower)) {
            return true;
        }
        // 处理 "e.g." 这类含点缩写：取带点的整体
        String dotted = dottedBefore(text, i).toLowerCase(java.util.Locale.US);
        if (COND_ABBR.contains(dotted) || TITLE_ABBR.contains(dotted)) {
            return mergeIfNextIsLower(text, i);
        }
        if (COND_ABBR.contains(lower)) {
            return mergeIfNextIsLower(text, i);
        }
        // 单字母缩写：A. B. C.
        if (lower.length() == 1 && Character.isLetter(lower.charAt(0))) {
            return true;
        }
        return false;
    }

    private static boolean mergeIfNextIsLower(String text, int i) {
        int p = i + 1;
        while (p < text.length() && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) {
            p++;
        }
        if (p >= text.length()) {
            return false;
        }
        char c = text.charAt(p);
        return Character.isLowerCase(c) || isDigit(c);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String wordBefore(String text, int i) {
        int p = i - 1;
        while (p >= 0 && Character.isLetter(text.charAt(p))) {
            p--;
        }
        return text.substring(p + 1, i);
    }

    /** 取 "e.g" 这种带内部点的缩写整体 */
    private static String dottedBefore(String text, int i) {
        int p = i - 1;
        while (p >= 0) {
            char c = text.charAt(p);
            if (Character.isLetter(c) || c == '.') {
                p--;
            } else {
                break;
            }
        }
        String s = text.substring(p + 1, i);
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        return s;
    }

    private static void add(List<Sentence> out, String text, int start, int end) {
        int s = start;
        int e = Math.min(end, text.length());
        while (s < e && isBlank(text.charAt(s))) {
            s++;
        }
        while (e > s && isBlank(text.charAt(e - 1))) {
            e--;
        }
        if (e - s <= 0) {
            return;
        }
        out.add(new Sentence(text.substring(s, e), s, e));
    }

    /** 碎句回收：正文里出现大量 <3 字的片段时并回上一句 */
    private static List<Sentence> mergeFragments(List<Sentence> in, String text) {
        List<Sentence> out = new ArrayList<Sentence>(in.size());
        for (int i = 0; i < in.size(); i++) {
            Sentence s = in.get(i);
            if (out.size() > 0 && meaningfulLen(s.text) < 3
                    && out.get(out.size() - 1).length() < HARD_LIMIT) {
                Sentence prev = out.remove(out.size() - 1);
                out.add(new Sentence(text.substring(prev.start, s.end), prev.start, s.end));
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private static int meaningfulLen(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isBlank(c) && CJK_TERMINATORS.indexOf(c) < 0 && CLOSING.indexOf(c) < 0) {
                n++;
            }
        }
        return n;
    }

    // ---------- 导出用分块 ----------

    /**
     * 把章节切成若干块，每块 <= CHUNK_BUDGET 字符，在句子边界断开。
     * 导出侧用它喂 synthesizeToFile，绝不整章一次丢进去（超 4000 会被静默丢弃）。
     */
    public static List<String> chunk(String chapterText, int budget) {
        List<String> out = new ArrayList<String>();
        if (chapterText == null || chapterText.length() == 0) {
            return out;
        }
        int max = budget > 0 ? budget : CHUNK_BUDGET;
        List<Sentence> sentences = split(chapterText);
        if (sentences.isEmpty()) {
            for (int p = 0; p < chapterText.length(); p += max) {
                out.add(chapterText.substring(p, Math.min(chapterText.length(), p + max)));
            }
            return out;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            Sentence s = sentences.get(i);
            if (s.length() > max) {
                // 单句就超限（极端情况）：直接硬切
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                String t = s.text;
                for (int p = 0; p < t.length(); p += max) {
                    out.add(t.substring(p, Math.min(t.length(), p + max)));
                }
                continue;
            }
            if (sb.length() > 0 && sb.length() + s.length() + 1 > max) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s.text);
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }
}
