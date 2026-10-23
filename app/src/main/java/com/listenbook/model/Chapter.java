package com.listenbook.model;

/**
 * 一个章节。text 是纯文本（已去 HTML 标签）。
 * id 是内容指纹，不用顺序编号——章节增删/重排时不会张冠李戴。
 */
public class Chapter {

    public int index;
    /** sha256(书名 + 标题 + 正文前 64 字) 前 8 位 */
    public String id = "";
    public String title = "";
    public String text = "";

    public Chapter() {
    }

    public Chapter(int index, String id, String title, String text) {
        this.index = index;
        this.id = id;
        this.title = title;
        this.text = text;
    }

    public int length() {
        return text == null ? 0 : text.length();
    }

    /** 粗略估算朗读时长（秒）。中文约 5 字/秒，英文约 15 字符/秒，取混合估计。 */
    public long estimateSeconds() {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        int cjk = 0;
        int limit = Math.min(text.length(), 20000);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        double ratio = (double) cjk / (double) limit;
        double charsPerSec = ratio * 5.0d + (1.0d - ratio) * 14.0d;
        if (charsPerSec < 1.0d) {
            charsPerSec = 1.0d;
        }
        return (long) ((double) text.length() / charsPerSec);
    }
}
