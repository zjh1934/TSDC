package com.listenbook.model;

import org.json.JSONException;
import org.json.JSONObject;

/** 书库里的一本书。全部字段可 JSON 化，不依赖任何第三方库。 */
public class Book {

    public static final String FORMAT_TXT = "TXT";
    public static final String FORMAT_EPUB = "EPUB";

    public String id = "";
    public String title = "";
    public String author = "";
    public String format = FORMAT_TXT;
    /** 已复制到 App 私有目录的真实文件路径（绝不用 content:// 的虚拟路径） */
    public String path = "";
    public long addedAt;
    public int chapterCount;
    public int charCount;
    /** 阅读进度：章节内容指纹 */
    public String lastChapterId = "";
    /** 阅读进度：章节序号 */
    public int lastChapterIndex;
    /** 阅读进度：章节内句子序号 */
    public int lastSentence;
    /** 字号 sp */
    public int fontSp = 17;
    /** 语速 */
    public float rate = 1.0f;
    /** 语调（音调），0.5~2.0 */
    public float pitch = 1.0f;
    /** 指定的音色名，空表示引擎自选 */
    public String voiceName = "";
    /** TTS 引擎包名，空表示系统默认 */
    public String enginePkg = "";

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title);
            o.put("author", author);
            o.put("format", format);
            o.put("path", path);
            o.put("addedAt", addedAt);
            o.put("chapterCount", chapterCount);
            o.put("charCount", charCount);
            o.put("lastChapterId", lastChapterId);
            o.put("lastChapterIndex", lastChapterIndex);
            o.put("lastSentence", lastSentence);
            o.put("fontSp", fontSp);
            o.put("rate", (double) rate);
            o.put("pitch", (double) pitch);
            o.put("voiceName", voiceName);
            o.put("enginePkg", enginePkg);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Book fromJson(JSONObject o) {
        Book b = new Book();
        b.id = o.optString("id", "");
        b.title = o.optString("title", "");
        b.author = o.optString("author", "");
        b.format = o.optString("format", FORMAT_TXT);
        b.path = o.optString("path", "");
        b.addedAt = o.optLong("addedAt", 0L);
        b.chapterCount = o.optInt("chapterCount", 0);
        b.charCount = o.optInt("charCount", 0);
        b.lastChapterId = o.optString("lastChapterId", "");
        b.lastChapterIndex = o.optInt("lastChapterIndex", 0);
        b.lastSentence = o.optInt("lastSentence", 0);
        b.fontSp = o.optInt("fontSp", 17);
        b.rate = (float) o.optDouble("rate", 1.0d);
        b.pitch = (float) o.optDouble("pitch", 1.0d);
        b.voiceName = o.optString("voiceName", "");
        b.enginePkg = o.optString("enginePkg", "");
        return b;
    }

    public boolean fileExists() {
        return path != null && path.length() > 0 && new java.io.File(path).isFile();
    }
}
