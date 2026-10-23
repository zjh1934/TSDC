package com.listenbook.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TXT 章节识别规则，对齐 Legado 的 txtTocRule.json 思想（规则本身重写）。
 *
 * 四条抗误报比正则更重要：
 *   1. 标题必须独占一行（顶格或仅 ASCII 缩进；全角空格缩进视为正文段落）
 *   2. 标题长度 <= 35 字符
 *   3. 排除计数词歧义（第三部分 / 第三节课）
 *   4. 忽略以句号结尾的行
 * 另有隐蔽坑：行首 BOM / 零宽空格必须先剥离，否则正则失效。
 */
public final class TxtChapterRules {

    private TxtChapterRules() {
    }

    public static final int MAX_TITLE_LEN = 35;
    /** TITLE_PATTERNS 中前 N 条是强规则，命中即接受，不再受「句末标点」排除影响 */
    private static final int STRONG_PATTERN_COUNT = 5;
    /** Legado 优先级 40 的兜底规则：无规则命中时按 5000 字自动分段 */
    public static final int FALLBACK_SEGMENT_CHARS = 5000;
    /** 超过这个长度的章节强制再切，避免单个 WAV 过大、TextView 卡顿 */
    public static final int LONG_CHAPTER_SPLIT = 40000;

    private static final Pattern[] TITLE_PATTERNS = new Pattern[]{
            // 优先级 100：中文标准「第X章/回/卷/节/集/篇/部」
            Pattern.compile("^[ \\t]{0,4}第[\\d一二三四五六七八九十百千万零两]+[章回卷节集篇部][ \\t]{0,2}[^\\n]{0,30}$"),
            // 网文变体：「第」与数字之间有空格
            Pattern.compile("^[ \\t]{0,4}第[ \\t]{0,2}[\\d]{1,4}[ \\t]{0,2}[章节回][^\\n]{0,20}$"),
            // 特殊章节名（无序号，必须单独列，否则整章丢失）
            Pattern.compile("^[ \\t]{0,4}(楔子|引子|序章|序言|前言|后记|尾声|终章|番外|外传|附录|正文|大结局|结局)[ \\t]{0,2}[^\\n]{0,30}$"),
            // 英文章节
            Pattern.compile("^[ \\t]{0,4}(Chapter|CHAPTER|chapter|Section|SECTION|Part|PART|Episode|EPISODE)[ \\t]+[\\dIVXLCivxlc]+[.:\\s\\-]{0,2}[^\\n]{0,40}$"),
            // 优先级 80：特殊符号包裹
            Pattern.compile("^[ \\t]{0,4}[★☆◆◇●○※【\\[][^\\n]{1,30}[★☆◆◇●○※】\\]][ \\t]*$"),
            // 全兼容（「第」字可选）
            Pattern.compile("^[ \\t]{0,4}(?:第)?[0-9一二三四五六七八九十百千万零两]{1,10}[章节回卷集篇.、\\s\\-][^\\n]{0,30}$")
    };

    /** Markdown 标题（.md 才启用） */
    private static final Pattern MD_HEADING =
            Pattern.compile("^#{1,4}[ \\t]+\\S[^\\n]{0,60}$");

    private static final Pattern[] EXCLUDE_PATTERNS = new Pattern[]{
            // 计数词歧义：第三部分 / 第三节课 不是章节
            Pattern.compile("^[ \\t]{0,4}第?[0-9一二三四五六七八九十百千]{1,8}(?:部门|部队|部分|节课|集合|次数|年份|版本)"),
            // 以句末标点结尾的行是正文不是标题
            Pattern.compile(".*[。！？；.!?;][ \\t]*$"),
            // 明显是对话或引用
            Pattern.compile("^[ \\t]*[“\"「『（(《]"),
            // 纯数字行（页码）
            Pattern.compile("^[ \\t]{0,4}[0-9]{1,5}[ \\t]*$")
    };

    /** 剥离 BOM / 零宽空格，把全角空格缩进标记出来 */
    public static String cleanLine(String line) {
        if (line == null) {
            return "";
        }
        String s = line;
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        s = s.replace("\u200B", "").replace("\u200C", "").replace("\u200D", "");
        return s;
    }

    /** 行首是否为全角空格缩进（中文小说正文段落惯例） */
    public static boolean hasFullWidthIndent(String line) {
        return line != null && line.length() >= 2
                && line.charAt(0) == '\u3000' && line.charAt(1) == '\u3000';
    }

    public static boolean isTitle(String rawLine, boolean markdown) {
        String line = cleanLine(rawLine);
        if (line.length() == 0) {
            return false;
        }
        // 抗误报 1：全角空格缩进 = 正文段落
        if (hasFullWidthIndent(line)) {
            return false;
        }
        String trimmed = stripAsciiIndent(line);
        if (trimmed.length() == 0) {
            return false;
        }
        // 抗误报 2：标题长度上限
        if (trimmed.length() > MAX_TITLE_LEN) {
            return false;
        }
        // 硬排除：以中文句末标点结尾的行一定是正文，不是标题。
        // 这条对强规则也生效——否则正文里的「正文。」会被特殊章节名规则命中，
        // 标题数虚高进而触发抗误报总闸，整本书退化成兜底分段。
        // 英文句点 '.' 不在此列，"Chapter 1." 这类标题要放过。
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last == '\u3002' || last == '\uFF01' || last == '\uFF1F'
                || last == '\uFF1B' || last == '\u2026') {
            return false;
        }
        // 硬排除：计数词歧义、明显对话/引用（对所有规则生效）
        if (EXCLUDE_PATTERNS[0].matcher(trimmed).find()
                || EXCLUDE_PATTERNS[2].matcher(trimmed).find()) {
            return false;
        }
        if (markdown && MD_HEADING.matcher(trimmed).matches()) {
            return true;
        }
        // 强规则（第X章 / 网文变体 / 楔子序章 / Chapter N / 符号包裹）：
        // 命中即接受，允许 "Chapter 1." 这种以句点结尾的英文标题
        for (int i = 0; i < STRONG_PATTERN_COUNT; i++) {
            if (TITLE_PATTERNS[i].matcher(trimmed).matches()) {
                return true;
            }
        }
        // 弱规则（「第」字可选的全兼容）：还要过「句末标点」「纯页码」两道排除，
        // 否则英文正文里 "Chapter 1." 之类没问题，但 "3 天后他回来了。" 会被误判
        if (EXCLUDE_PATTERNS[1].matcher(trimmed).find()
                || EXCLUDE_PATTERNS[3].matcher(trimmed).find()) {
            return false;
        }
        return TITLE_PATTERNS[TITLE_PATTERNS.length - 1].matcher(trimmed).matches();
    }

    private static String stripAsciiIndent(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t') && i < 4) {
            i++;
        }
        return s.substring(i).trim();
    }

    /** 标题净化：去掉 Markdown 井号、特殊符号包裹、首尾空白 */
    public static String normalizeTitle(String rawLine, int fallbackIndex) {
        String s = stripAsciiIndent(cleanLine(rawLine)).trim();
        if (s.startsWith("#")) {
            s = s.replaceFirst("^#{1,6}[ \\t]*", "");
        }
        s = s.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            boolean wrapped = (a == '★' && b == '★') || (a == '☆' && b == '☆')
                    || (a == '【' && b == '】') || (a == '[' && b == ']')
                    || (a == '◆' && b == '◆') || (a == '※' && b == '※')
                    || (a == '●' && b == '●') || (a == '○' && b == '○')
                    || (a == '◇' && b == '◇');
            if (wrapped) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        if (s.length() == 0) {
            s = "第 " + (fallbackIndex + 1) + " 节";
        }
        return s;
    }

    /** 供 UI/调试展示当前启用的规则条数 */
    public static int ruleCount() {
        return TITLE_PATTERNS.length;
    }

    public static List<String> describeRules() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < TITLE_PATTERNS.length; i++) {
            out.add(TITLE_PATTERNS[i].pattern());
        }
        return out;
    }
}
