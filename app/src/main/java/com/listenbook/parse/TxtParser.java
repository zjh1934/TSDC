package com.listenbook.parse;

import com.listenbook.model.Chapter;
import com.listenbook.util.Fingerprint;
import com.listenbook.util.Io;
import com.listenbook.util.SafeName;
import com.listenbook.util.TextEncoding;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * TXT / MD 解析。
 * 编码三级探测（BOM → UTF-8 严格 → GB18030）在 TextEncoding 里；
 * 这里负责章节切分 + 兜底分段 + 超长章节再切。
 */
public final class TxtParser {

    private TxtParser() {
    }

    public static List<Chapter> parse(File file, String bookTitle, boolean markdown) throws IOException {
        byte[] bytes = Io.readAll(file);
        if (bytes.length == 0) {
            throw new IOException("文件是空的");
        }
        String content = TextEncoding.decode(bytes);
        return parseText(content, bookTitle, markdown);
    }

    /** 直接从内存文本解析（EPUB 单 spine item 的二次切分也走这里） */
    public static List<Chapter> parseText(String raw, String bookTitle, boolean markdown)
            throws IOException {
        if (raw == null || raw.trim().length() == 0) {
            throw new IOException("文件没有可读文本");
        }
        String content = raw.replace("\r\n", "\n").replace('\r', '\n');

        List<Chapter> chapters = splitByTitles(content, bookTitle, markdown);
        // 注意是 isEmpty 而不是 size()<=1：只有一个标题的书是完全正常的，
        // 用 <=1 会把这种书误判成"规则没命中"，整本被兜底分段打散
        if (chapters.isEmpty()) {
            // Legado 优先级 40 兜底：按 5000 字自动分段
            chapters = fallbackSegments(content, bookTitle);
        }
        chapters = splitOverlong(chapters, bookTitle);
        reindex(chapters, bookTitle);
        return chapters;
    }

    private static List<Chapter> splitByTitles(String content, String bookTitle, boolean markdown) {
        String[] lines = content.split("\n", -1);
        List<Chapter> out = new ArrayList<Chapter>();

        int titleHits = 0;
        for (int i = 0; i < lines.length; i++) {
            if (TxtChapterRules.isTitle(lines[i], markdown)) {
                titleHits++;
            }
        }
        // 抗误报总闸：超过一半的行都像标题，说明规则在误伤正文，直接放弃。
        // 阈值不能更严——短文件（几章几行）正常也会有很高的标题占比。
        if (titleHits > 0 && titleHits * 2 > lines.length) {
            titleHits = 0;
        }
        if (titleHits == 0) {
            return out;
        }

        StringBuilder body = new StringBuilder();
        String curTitle = null;
        String preamble = null;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            boolean title = TxtChapterRules.isTitle(raw, markdown);
            if (title) {
                if (curTitle == null) {
                    // 第一个标题之前的内容
                    String pre = body.toString().trim();
                    if (pre.length() > 30) {
                        preamble = pre;
                    } else if (pre.length() > 0) {
                        // 太短的引子并到第一章开头
                        body.setLength(0);
                        body.append(pre).append('\n');
                        curTitle = TxtChapterRules.normalizeTitle(raw, out.size());
                        continue;
                    }
                    body.setLength(0);
                    curTitle = TxtChapterRules.normalizeTitle(raw, out.size());
                    continue;
                }
                addChapter(out, curTitle, body.toString());
                body.setLength(0);
                curTitle = TxtChapterRules.normalizeTitle(raw, out.size());
                continue;
            }
            body.append(TxtChapterRules.cleanLine(raw)).append('\n');
        }
        if (curTitle != null) {
            addChapter(out, curTitle, body.toString());
        }
        if (preamble != null && out.size() > 0) {
            Chapter pre = new Chapter();
            pre.title = "前言";
            pre.text = preamble;
            out.add(0, pre);
        }
        return out;
    }

    private static void addChapter(List<Chapter> out, String title, String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() == 0) {
            // 空章节：并回上一章标题后面，避免产生 0 秒 WAV
            if (out.size() > 0) {
                Chapter last = out.get(out.size() - 1);
                last.title = last.title + " " + SafeName.title(title, 20);
            }
            return;
        }
        Chapter c = new Chapter();
        c.title = SafeName.title(title, 60);
        c.text = t;
        out.add(c);
    }

    /** 兜底：按段落边界切成 ~5000 字的块 */
    private static List<Chapter> fallbackSegments(String content, String bookTitle) {
        List<Chapter> out = new ArrayList<Chapter>();
        String[] paragraphs = content.split("\n");
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (int i = 0; i < paragraphs.length; i++) {
            String p = TxtChapterRules.cleanLine(paragraphs[i]).trim();
            if (p.length() == 0) {
                continue;
            }
            if (sb.length() + p.length() + 1 > TxtChapterRules.FALLBACK_SEGMENT_CHARS
                    && sb.length() > 0) {
                out.add(segmentChapter(idx++, sb.toString()));
                sb.setLength(0);
            }
            sb.append(p).append('\n');
        }
        if (sb.length() > 0) {
            out.add(segmentChapter(idx, sb.toString()));
        }
        if (out.size() == 0) {
            Chapter c = new Chapter();
            c.title = "全文";
            c.text = content.trim();
            out.add(c);
        }
        return out;
    }

    private static Chapter segmentChapter(int idx, String text) {
        Chapter c = new Chapter();
        c.title = "第 " + (idx + 1) + " 段";
        c.text = text.trim();
        return c;
    }

    /** 超长章节再切，保证单个 WAV 与 TextView 都吃得消 */
    private static List<Chapter> splitOverlong(List<Chapter> in, String bookTitle) {
        List<Chapter> out = new ArrayList<Chapter>(in.size());
        for (int i = 0; i < in.size(); i++) {
            Chapter c = in.get(i);
            if (c.text.length() <= TxtChapterRules.LONG_CHAPTER_SPLIT) {
                out.add(c);
                continue;
            }
            int parts = (c.text.length() + TxtChapterRules.FALLBACK_SEGMENT_CHARS - 1)
                    / TxtChapterRules.FALLBACK_SEGMENT_CHARS;
            int per = c.text.length() / parts + 1;
            int pos = 0;
            int n = 0;
            while (pos < c.text.length()) {
                int end = Math.min(c.text.length(), pos + per);
                if (end < c.text.length()) {
                    int nl = c.text.lastIndexOf('\n', end);
                    if (nl > pos + per / 2) {
                        end = nl + 1;
                    }
                }
                Chapter part = new Chapter();
                part.title = c.title + " (" + (++n) + "/" + parts + ")";
                part.text = c.text.substring(pos, end).trim();
                if (part.text.length() > 0) {
                    out.add(part);
                }
                pos = end;
            }
        }
        return out;
    }

    private static void reindex(List<Chapter> chapters, String bookTitle) {
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            c.index = i;
            c.id = Fingerprint.chapterId(bookTitle, c.title, c.text);
        }
    }

    public static int countChars(List<Chapter> chapters) {
        int n = 0;
        for (int i = 0; i < chapters.size(); i++) {
            n += chapters.get(i).length();
        }
        return n;
    }
}
