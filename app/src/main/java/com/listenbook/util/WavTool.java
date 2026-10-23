package com.listenbook.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 自研 WAV 工具，替代 Media3。
 * 两件事：
 *   probe() —— 用 RIFF 头校验文件是不是完整 WAV（断点续传不能只看文件存在）
 *   merge() —— 把同一章的多个分块 WAV 拼成一个，重写 RIFF 头
 * 不做任何转码压缩：synthesizeToFile 输出什么就存什么。
 */
public final class WavTool {

    private WavTool() {
    }

    public static final class Info {
        public boolean valid;
        public int dataOffset = -1;
        public long dataLen = -1;
        public int audioFormat = -1;
        public int channels = -1;
        public int sampleRate = -1;
        public int bitsPerSample = -1;
        public long fileSize;
        /** 声明的 data 长度超过实际剩余字节 → 半截文件 */
        public boolean truncated;

        public String describe() {
            if (!valid) {
                return truncated ? "半截 WAV" : "非法 WAV";
            }
            return sampleRate + "Hz/" + bitsPerSample + "bit/" + channels + "ch";
        }

        public long millis() {
            if (!valid || sampleRate <= 0 || dataLen <= 0) {
                return 0L;
            }
            int bytesPerSec = sampleRate * channels * Math.max(1, bitsPerSample / 8);
            if (bytesPerSec <= 0) {
                return 0L;
            }
            return dataLen * 1000L / (long) bytesPerSec;
        }
    }

    /** 解析 RIFF/WAVE 结构，定位 fmt 与 data 块 */
    public static Info probe(File f) {
        Info info = new Info();
        if (f == null || !f.isFile()) {
            return info;
        }
        info.fileSize = f.length();
        if (info.fileSize < 44L) {
            info.truncated = info.fileSize > 0L;
            return info;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] four = new byte[4];
            raf.readFully(four);
            if (!new String(four, "US-ASCII").equals("RIFF")) {
                return info;
            }
            readLe32(raf); // RIFF chunk size
            raf.readFully(four);
            if (!new String(four, "US-ASCII").equals("WAVE")) {
                return info;
            }
            long pos = 12L;
            while (pos + 8L <= info.fileSize) {
                raf.seek(pos);
                raf.readFully(four);
                String id = new String(four, "US-ASCII");
                long size = readLe32(raf) & 0xFFFFFFFFL;
                pos += 8L;
                if (id.equals("fmt ")) {
                    long need = Math.min(size, 16L);
                    byte[] fmt = new byte[(int) need];
                    raf.readFully(fmt);
                    info.audioFormat = le16(fmt, 0);
                    info.channels = le16(fmt, 2);
                    info.sampleRate = le32(fmt, 4);
                    info.bitsPerSample = le16(fmt, 14);
                    pos += size + (size % 2L);
                    continue;
                }
                if (id.equals("data")) {
                    info.dataOffset = (int) pos;
                    info.dataLen = size;
                    break;
                }
                pos += size + (size % 2L);
            }
            if (info.dataOffset < 0 || info.dataLen < 0) {
                return info;
            }
            long available = info.fileSize - info.dataOffset;
            if (available < info.dataLen) {
                info.truncated = true;
                return info;
            }
            info.valid = info.channels > 0 && info.sampleRate > 0 && info.bitsPerSample > 0;
            return info;
        } catch (Exception e) {
            return info;
        } finally {
            Io.close(raf);
        }
    }

    public static boolean isValid(File f) {
        return probe(f).valid;
    }

    /**
     * 合并分块 WAV。要求所有分块格式一致（同引擎同参数，正常都一致）。
     * 返回 false 表示格式不一致或读写出错，调用方应保留分块并标记失败。
     */
    public static boolean merge(File[] parts, File out) {
        if (parts == null || parts.length == 0) {
            return false;
        }
        if (parts.length == 1) {
            return parts[0].renameTo(out);
        }
        Info base = probe(parts[0]);
        if (!base.valid) {
            return false;
        }
        long totalData = 0L;
        Info[] infos = new Info[parts.length];
        infos[0] = base;
        for (int i = 0; i < parts.length; i++) {
            Info in = (i == 0) ? base : probe(parts[i]);
            infos[i] = in;
            if (!in.valid || in.channels != base.channels || in.sampleRate != base.sampleRate
                    || in.bitsPerSample != base.bitsPerSample) {
                return false;
            }
            totalData += in.dataLen;
        }
        RandomAccessFile raf = null;
        try {
            Io.ensureParent(out);
            raf = new RandomAccessFile(out, "rw");
            raf.setLength(0L);
            raf.write(buildHeader(base, totalData));
            byte[] buf = new byte[16384];
            for (int i = 0; i < parts.length; i++) {
                Info in = infos[i];
                RandomAccessFile src = null;
                try {
                    src = new RandomAccessFile(parts[i], "r");
                    src.seek(in.dataOffset);
                    long remain = in.dataLen;
                    while (remain > 0L) {
                        int want = (int) Math.min((long) buf.length, remain);
                        int n = src.read(buf, 0, want);
                        if (n <= 0) {
                            return false;
                        }
                        raf.write(buf, 0, n);
                        remain -= n;
                    }
                } finally {
                    Io.close(src);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            Io.close(raf);
        }
    }

    /** 标准 44 字节 PCM 头 */
    public static byte[] buildHeader(Info fmt, long dataLen) {
        int ch = Math.max(1, fmt.channels);
        int bits = Math.max(8, fmt.bitsPerSample);
        int rate = Math.max(1, fmt.sampleRate);
        int byteRate = rate * ch * bits / 8;
        int blockAlign = ch * bits / 8;
        byte[] h = new byte[44];
        putAscii(h, 0, "RIFF");
        putLe32(h, 4, (int) Math.min(0xFFFFFFFFL, dataLen + 36L));
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putLe32(h, 16, 16);
        putLe16(h, 20, 1); // PCM
        putLe16(h, 22, ch);
        putLe32(h, 24, rate);
        putLe32(h, 28, byteRate);
        putLe16(h, 32, blockAlign);
        putLe16(h, 34, bits);
        putAscii(h, 36, "data");
        putLe32(h, 40, (int) Math.min(0xFFFFFFFFL, dataLen));
        return h;
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < 4 && i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static long readLe32(RandomAccessFile raf) throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new IOException("unexpected eof");
        }
        return ((long) b0) | ((long) b1 << 8) | ((long) b2 << 16) | ((long) b3 << 24);
    }

    private static int le16(byte[] b, int off) {
        if (off + 2 > b.length) {
            return 0;
        }
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        if (off + 4 > b.length) {
            return 0;
        }
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** 16bit/22.05kHz/单声道 ≈ 44100 B/s ≈ 2.6 MB/分钟 */
    public static String humanSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0d;
        if (kb < 1024.0d) {
            return String.format(java.util.Locale.US, "%.0f KB", kb);
        }
        double mb = kb / 1024.0d;
        if (mb < 1024.0d) {
            return String.format(java.util.Locale.US, "%.1f MB", mb);
        }
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0d);
    }

    public static String humanDuration(long millis) {
        long s = millis / 1000L;
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        if (h > 0L) {
            return h + "小时" + m + "分";
        }
        if (m > 0L) {
            return m + "分" + sec + "秒";
        }
        return sec + "秒";
    }
}
