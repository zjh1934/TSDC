package com.listenbook.parse;

import com.listenbook.model.Book;
import com.listenbook.model.Chapter;
import com.listenbook.util.Fingerprint;
import com.listenbook.util.SafeName;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一解析入口。模块 A（听书）和模块 B（导出）唯一的共享层。
 * 带一份单本书内存缓存，避免阅读页与导出页各自重复解析。
 */
public final class BookParser {

    private BookParser() {
    }

    public static final class Result {
        public String title = "";
        public String author = "";
        public String format = Book.FORMAT_TXT;
        public List<Chapter> chapters = new ArrayList<Chapter>();

        public int charCount() {
            return TxtParser.countChars(chapters);
        }

        public Chapter byId(String id) {
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).id.equals(id)) {
                    return chapters.get(i);
                }
            }
            return null;
        }

        public int indexById(String id) {
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).id.equals(id)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static Result cachedResult;
    private static String cachedKey;

    public static String formatOf(String fileName) {
        String ext = Fingerprint.extOf(fileName);
        if (ext.equals("epub")) {
            return Book.FORMAT_EPUB;
        }
        return Book.FORMAT_TXT;
    }

    public static synchronized Result parse(Book book) throws IOException {
        return parse(new File(book.path), book.format, book.title, book.id);
    }

    public static synchronized Result parse(File file, String formatHint, String fallbackTitle,
                                            String cacheKey) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("文件不存在: " + (file == null ? "null" : file.getAbsolutePath()));
        }
        String key = cacheKey + "|" + file.length() + "|" + file.lastModified();
        if (cachedResult != null && key.equals(cachedKey)) {
            return cachedResult;
        }
        String format = formatHint == null || formatHint.length() == 0
                ? formatOf(file.getName()) : formatHint;
        String baseTitle = SafeName.title(stripExt(file.getName()), 60);
        if (fallbackTitle != null && fallbackTitle.trim().length() > 0) {
            baseTitle = SafeName.title(fallbackTitle, 60);
        }

        Result res = new Result();
        res.format = Book.FORMAT_EPUB.equals(format) ? Book.FORMAT_EPUB : Book.FORMAT_TXT;
        if (Book.FORMAT_EPUB.equals(res.format)) {
            EpubParser.Result er = EpubParser.parse(file, baseTitle);
            res.title = er.title.length() > 0 ? er.title : baseTitle;
            res.author = er.author;
            res.chapters = er.chapters;
        } else {
            boolean markdown = Fingerprint.extOf(file.getName()).equals("md");
            List<Chapter> chapters = TxtParser.parse(file, baseTitle, markdown);
            res.title = baseTitle;
            res.chapters = chapters;
        }
        if (res.chapters.isEmpty()) {
            throw new IOException("没有解析出任何章节");
        }
        for (int i = 0; i < res.chapters.size(); i++) {
            Chapter c = res.chapters.get(i);
            c.index = i;
            if (c.id == null || c.id.length() == 0) {
                c.id = Fingerprint.chapterId(res.title, c.title, c.text);
            }
        }
        cachedResult = res;
        cachedKey = key;
        return res;
    }

    public static synchronized void invalidate() {
        cachedResult = null;
        cachedKey = null;
    }

    private static String stripExt(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
