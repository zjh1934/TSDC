package com.listenbook.tts;

/** 一个句子，带它在章节正文里的绝对偏移量（逐句高亮全靠这个）。 */
public class Sentence {

    public final String text;
    public final int start;
    public final int end;

    public Sentence(String text, int start, int end) {
        this.text = text;
        this.start = start;
        this.end = end;
    }

    public int length() {
        return text == null ? 0 : text.length();
    }

    @Override
    public String toString() {
        return "[" + start + "," + end + ") " + text;
    }
}
