package com.listenbook.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * 全局偏好。只存「跟某一本书无关」的设置：默认引擎、默认音色、导出合成参数等。
 * 每本书自己的设置存在 Book / ExportProfile 里，两者互不覆盖。
 *
 * 用 SharedPreferences 而不是自己写 JSON：系统保证原子落盘，
 * apply() 异步不卡主线程，getXxx 有内存缓存。API 21 就有，无需 androidx。
 */
public final class Settings {

    private static final String PREF = "listenbook";

    private static final String K_ENGINE = "engine_pkg";
    private static final String K_VOICE = "voice_name";
    private static final String K_RATE = "rate";
    private static final String K_PITCH = "pitch";
    private static final String K_LOCALE = "locale_tag";
    private static final String K_LANG_MODE = "language_mode";
    private static final String K_SYNC = "sync_public";
    private static final String K_AUTO_NEXT = "auto_next_chapter";
    private static final String K_LOG_LEVEL = "log_level";
    private static final String K_VERBOSE = "verbose_tts";
    private static final String K_LAST_DIR_MODE = "last_dir_mode";
    private static final String K_CHUNK_BUDGET = "chunk_budget";
    private static final String K_TIMEOUT_SLACK = "timeout_slack";
    private static final String K_MAX_ATTEMPTS = "max_attempts";
    private static final String K_FLOATING = "floating_window";

    /** 自定义输出目录（SAF tree Uri 字符串 + 显示名），存在 SafExport 里，这里只转发 */
    private static final String K_CUSTOM_TREE = "saf_tree_uri";
    private static final String K_CUSTOM_TREE_NAME = "saf_tree_name";

    /**
     * 导出选区持久化。key 按 bookId 分开。
     *
     * 背景：断点续传在ExportService 里本来就是通的（靠已落盘的 WAV 跳过已完成章节），
     * 但勾选状态只活在 ChapterAdapter 的内存里，一进页面 setData() 就把checked 全清成 FALSE，
     * 于是「跑了 300 章、停了、回来发现要重新勾一遍」。机制存在但不可发现，等于没有续传。
     * 这里把选区落盘，停止后回来可以一键接着跑。
     */
    private static final String K_SEL_PREFIX = "sel_";

    /** 语言策略：跟随正文自动 / 固定某种语言 */
    public static final String LANG_AUTO = "auto";
    public static final String LANG_ZH = "zh";
    public static final String LANG_EN = "en";

    private Settings() {
    }

    private static SharedPreferences pref(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 引擎与音色

    public static String enginePkg(Context c) {
        return pref(c).getString(K_ENGINE, "");
    }

    public static void setEnginePkg(Context c, String pkg) {
        pref(c).edit().putString(K_ENGINE, pkg == null ? "" : pkg).apply();
    }

    public static String voiceName(Context c) {
        return pref(c).getString(K_VOICE, "");
    }

    public static void setVoiceName(Context c, String name) {
        pref(c).edit().putString(K_VOICE, name == null ? "" : name).apply();
    }

    // ------------------------------------------------------------------ 自定义输出目录

    public static String customTreeUri(Context c) {
        return pref(c).getString(K_CUSTOM_TREE, "");
    }

    public static String customTreeName(Context c) {
        return pref(c).getString(K_CUSTOM_TREE_NAME, "");
    }

    public static boolean hasCustomTree(Context c) {
        return customTreeUri(c).length() > 0;
    }

    // ------------------------------------------------------------------ 合成参数

    public static float rate(Context c) {
        float r = pref(c).getFloat(K_RATE, 1.0f);
        return clampRate(r);
    }

    public static void setRate(Context c, float r) {
        pref(c).edit().putFloat(K_RATE, clampRate(r)).apply();
    }

    public static float pitch(Context c) {
        float p = pref(c).getFloat(K_PITCH, 1.0f);
        return clampPitch(p);
    }

    public static void setPitch(Context c, float p) {
        pref(c).edit().putFloat(K_PITCH, clampPitch(p)).apply();
    }

    public static String languageMode(Context c) {
        return pref(c).getString(K_LANG_MODE, LANG_AUTO);
    }

    public static void setLanguageMode(Context c, String mode) {
        pref(c).edit().putString(K_LANG_MODE, mode == null ? LANG_AUTO : mode).apply();
    }

    public static String localeTag(Context c) {
        return pref(c).getString(K_LOCALE, "");
    }

    public static void setLocaleTag(Context c, String tag) {
        pref(c).edit().putString(K_LOCALE, tag == null ? "" : tag).apply();
    }

    public static float clampRate(float r) {
        if (r < 0.5f) {
            return 0.5f;
        }
        if (r > 3.0f) {
            return 3.0f;
        }
        // 保留一位小数，避免浮点尾巴让 UI 显示 1.7000001
        return Math.round(r * 10f) / 10f;
    }

    public static float clampPitch(float p) {
        if (p < 0.5f) {
            return 0.5f;
        }
        if (p > 2.0f) {
            return 2.0f;
        }
        return Math.round(p * 10f) / 10f;
    }

    // ------------------------------------------------------------------ 导出行为

    public static boolean syncPublic(Context c) {
        return pref(c).getBoolean(K_SYNC, true);
    }

    public static void setSyncPublic(Context c, boolean v) {
        pref(c).edit().putBoolean(K_SYNC, v).apply();
    }

    public static boolean autoNextChapter(Context c) {
        return pref(c).getBoolean(K_AUTO_NEXT, true);
    }

    /**
     * 悬浮进度窗开关。默认开。
     *
     * 用户原话：「悬浮窗它要带开关，可开可关」。
     * 悬浮窗要用 SYSTEM_ALERT_WINDOW 权限，是个用户很敏感的权限，
     * 所以绝不能默认偷偷开着还撤不掉 —— 给了开关，想关随时能关。
     */
    public static boolean floatingWindow(Context c) {
        return pref(c).getBoolean(K_FLOATING, true);
    }

    public static void setFloatingWindow(Context c, boolean v) {
        pref(c).edit().putBoolean(K_FLOATING, v).apply();
    }

    public static void setAutoNextChapter(Context c, boolean v) {
        pref(c).edit().putBoolean(K_AUTO_NEXT, v).apply();
    }

    /** 分块大小：控制单次 synthesizeToFile 的字数。字太多更容易超时。 */
    public static int chunkBudget(Context c) {
        int v = pref(c).getInt(K_CHUNK_BUDGET, 3500);
        if (v < 500) {
            v = 500;
        }
        if (v > 3800) {
            v = 3800;
        }
        return v;
    }

    public static void setChunkBudget(Context c, int v) {
        pref(c).edit().putInt(K_CHUNK_BUDGET, v).apply();
    }

    /** 超时余量倍数：引擎慢的机器可以调大。1.0 = 按估算时长的 3 倍。 */
    public static int timeoutSlack(Context c) {
        int v = pref(c).getInt(K_TIMEOUT_SLACK, 3);
        if (v < 1) {
            v = 1;
        }
        if (v > 10) {
            v = 10;
        }
        return v;
    }

    public static void setTimeoutSlack(Context c, int v) {
        pref(c).edit().putInt(K_TIMEOUT_SLACK, v).apply();
    }

    /** 单块重试次数。默认 2（原始实现写死 2，这里开放出来）。 */
    public static int maxAttempts(Context c) {
        int v = pref(c).getInt(K_MAX_ATTEMPTS, 2);
        if (v < 1) {
            v = 1;
        }
        if (v > 5) {
            v = 5;
        }
        return v;
    }

    public static void setMaxAttempts(Context c, int v) {
        pref(c).edit().putInt(K_MAX_ATTEMPTS, v).apply();
    }

    // ------------------------------------------------------------------ 其它

    /** 是否记录每一块的 TTS 回调（日志会变大，只在排查时开）。 */
    public static boolean verboseTts(Context c) {
        return pref(c).getBoolean(K_VERBOSE, false);
    }

    public static void setVerboseTts(Context c, boolean v) {
        pref(c).edit().putBoolean(K_VERBOSE, v).apply();
    }

    public static int logLevel(Context c) {
        int v = pref(c).getInt(K_LOG_LEVEL, AppLog.INFO);
        if (v < AppLog.DEBUG) {
            v = AppLog.DEBUG;
        }
        if (v > AppLog.ERROR) {
            v = AppLog.ERROR;
        }
        return v;
    }

    public static void setLogLevel(Context c, int level) {
        pref(c).edit().putInt(K_LOG_LEVEL, level).apply();
    }

    public static String rateText(Context c) {
        return String.format(Locale.CHINA, "%.1fx", Float.valueOf(rate(c)));
    }

    public static String pitchText(Context c) {
        return String.format(Locale.CHINA, "%.1f", Float.valueOf(pitch(c)));
    }

    // ---------------------------------------------------------------- 导出选区

    /** 存这本书上次勾了哪些章节（下标，逗号分隔）。传null /空数组表示清空。 */
    public static void saveSelection(Context c, String bookId, int[] indices) {
        if (bookId == null || bookId.length() == 0) {
            return;
        }
        if (indices == null || indices.length == 0) {
            pref(c).edit().remove(K_SEL_PREFIX + bookId).apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indices.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(indices[i]);
        }
        pref(c).edit().putString(K_SEL_PREFIX + bookId, sb.toString()).apply();
    }

    /** 读回选区。解析不了就当没有，绝不返回半截数据。 */
    public static int[] loadSelection(Context c, String bookId) {
        if (bookId == null || bookId.length() == 0) {
            return new int[0];
        }
        String raw = pref(c).getString(K_SEL_PREFIX + bookId, "");
        if (raw == null || raw.length() == 0) {
            return new int[0];
        }
        String[] parts = raw.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            int v;
            try {
                v = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // 单个下标坏了不影响其它，跳过即可
                continue;
            }
            // 关键：先解析成功再写入 out[n++]。
            // 写成 out[n++] = Integer.parseInt(...) 的话，Java 会先做n++ 再抛异常，
            // 于是坏值留下一个 0 空洞，后面所有下标整体前移一位 ——
            // 表现是「停止后续传，莫名妙妙从头一章开始重跑」，比直接报错更难查。
            out[n] = v;
            n++;
        }
        if (n == 0) {
            return new int[0];
        }
        int[] exact = new int[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }
}
