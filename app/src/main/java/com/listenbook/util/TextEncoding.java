package com.listenbook.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * 中文 TXT 编码三级探测：BOM → UTF-8 严格解码 → 退回 GB18030。
 * 网上下载的小说大量是 GBK/GB18030，直接按 UTF-8 读会整篇乱码。
 */
public final class TextEncoding {

    private TextEncoding() {
    }

    /** 返回可用的字符集名称 */
    public static String detect(byte[] data) {
        if (data == null || data.length == 0) {
            return "UTF-8";
        }
        // 1) BOM
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return "UTF-16BE";
        }
        // 2) UTF-8 严格解码
        if (isStrictUtf8(data)) {
            return "UTF-8";
        }
        // 3) 退回 GB18030（GBK 超集）
        if (isAvailable("GB18030")) {
            return "GB18030";
        }
        if (isAvailable("GBK")) {
            return "GBK";
        }
        return "UTF-8";
    }

    /** 解码并剥掉 BOM */
    public static String decode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        String cs = detect(data);
        int off = 0;
        int len = data.length;
        if (cs.equals("UTF-8") && len >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            off = 3;
            len -= 3;
        } else if ((cs.equals("UTF-16LE") || cs.equals("UTF-16BE")) && len >= 2) {
            off = 2;
            len -= 2;
        }
        String s;
        try {
            s = new String(data, off, len, cs);
        } catch (Exception e) {
            s = new String(data, off, len);
        }
        // 行内残留的 BOM / 零宽空格
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        return s;
    }

    private static boolean isStrictUtf8(byte[] data) {
        try {
            CharsetDecoder dec = Charset.forName("UTF-8").newDecoder();
            dec.onMalformedInput(CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(CodingErrorAction.REPORT);
            dec.decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAvailable(String name) {
        try {
            return Charset.isSupported(name);
        } catch (Exception e) {
            return false;
        }
    }

    /** 文本里 CJK 字符占比，用来决定 TTS 语言 */
    public static float cjkRatio(String text) {
        if (text == null || text.length() == 0) {
            return 0f;
        }
        int cjk = 0;
        int limit = Math.min(text.length(), 8000);
        for (int i = 0; i < limit; i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return (float) cjk / (float) limit;
    }
}
