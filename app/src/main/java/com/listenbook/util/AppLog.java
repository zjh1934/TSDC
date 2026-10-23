package com.listenbook.util;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 诊断日志。零第三方依赖，自己实现「内存环形缓冲 + 文件追加 + 崩溃捕获」。
 *
 * 为什么不用 android.util.Log：
 *   Logcat 用户在手机上拿不到，出问题时我们只能靠猜。这里把日志同时落进
 *   filesDir/diag/app.log，用户可以从「诊断日志」页导出 txt / md 发回来。
 *
 * 三条硬约束：
 *   1. 写盘永远不在调用线程 —— 丢给 HandlerThread，UI 线程调 junction() 也零开销
 *   2. 内存上限 3000 条 —— 超了丢最旧的，长时间挂机导出不会 OOM
 *   3. 崩溃兜底 —— 装 UncaughtExceptionHandler，把栈写进日志再交给系统，
 *      这样「闪退后什么都没留下」的问题不复存在
 */
public final class AppLog {

    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    /** 内存环形缓冲上限。 */
    private static final int MEMORY_MAX = 3000;
    /** 单个日志文件上限，超过就轮转成 app.old.log。 */
    private static final long FILE_MAX = 1024L * 1024L;

    public static final class Entry {
        public final long time;
        public final int level;
        public final String tag;
        public final String msg;

        Entry(long time, int level, String tag, String msg) {
            this.time = time;
            this.level = level;
            this.tag = tag;
            this.msg = msg;
        }

        public String levelName() {
            switch (level) {
                case DEBUG:
                    return "D";
                case INFO:
                    return "I";
                case WARN:
                    return "W";
                case ERROR:
                    return "E";
                default:
                    return "V";
            }
        }

        public String timeText() {
            return STAMP.format(new Date(time));
        }
    }

    private static final LinkedList<Entry> memory = new LinkedList<Entry>();
    private static final Object lock = new Object();
    /** 文件写锁。同步落盘后多个线程会并发写，必须串行化。 */
    private static final Object fileLock = new Object();

    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);
    private static final SimpleDateFormat FULL =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);

    private static File logFile;
    private static File backupFile;
    private static Handler writer;
    private static volatile boolean ready;
    private static volatile boolean verbose;
    private static Thread.UncaughtExceptionHandler previousHandler;

    private AppLog() {
    }

    // ------------------------------------------------------------------ 初始化

    public static void init(Context context) {
        if (ready) {
            return;
        }
        synchronized (lock) {
            if (ready) {
                return;
            }
            try {
                File dir = new File(context.getApplicationContext().getFilesDir(), "diag");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                logFile = new File(dir, "app.log");
                backupFile = new File(dir, "app.old.log");
            } catch (Exception ignored) {
                logFile = null;
            }
            HandlerThread t = new HandlerThread("listenbook-log", Thread.NORM_PRIORITY - 1);
            t.start();
            writer = new Handler(t.getLooper());
            ready = true;
            installCrashHandler();
        }
        i("AppLog", "日志系统就绪 dir=" + (logFile == null ? "none" : logFile.getParent()));
        i("Device", Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " | abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
    }

    /**
     * 崩溃处理器。
     *
     * 两个必须做的细节，缺一个就抓不到现场：
     *  1. **直接同步落盘**，不走 writer.post() 那条异步队列。
     *     进程即将结束时 HandlerThread 可能还没来得及消费队列，
     *     排队里的日志连同整个进程一起消失 —— 实测就是这么丢的：
     *     用户导出日志只剩进程启动那 4 条，崩溃现场一条都没有。
     *  2. **写完再交给系统处理器**，顺序不能反。
     */
    private static void installCrashHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    String head = "未捕获异常 thread=" + thread.getName();
                    Entry entry = new Entry(System.currentTimeMillis(), ERROR,
                            "Crash", head + "（进程即将结束）| " + stackToString(ex));
                    synchronized (lock) {
                        memory.addLast(entry);
                        while (memory.size() > MEMORY_MAX) {
                            memory.removeFirst();
                        }
                    }
                    // 绕过 writer.post，直接同步写文件
                    appendToFile(entry);
                } catch (Throwable ignored) {
                    // 记日志失败也不能拦着系统处理器往下走
                }
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, ex);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 写入

    public static void d(String tag, String msg) {
        log(DEBUG, tag, msg, null);
    }

    public static void i(String tag, String msg) {
        log(INFO, tag, msg, null);
    }

    public static void w(String tag, String msg) {
        log(WARN, tag, msg, null);
    }

    public static void e(String tag, String msg) {
        log(ERROR, tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        log(ERROR, tag, msg, t);
    }

    /** 是否记录每一块 TTS 合成的回调。关着的时候 ttl 级别的流水直接丢掉，日志才不会爆炸。 */
    public static void setVerbose(boolean on) {
        verbose = on;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    /**
     * 关键步骤标记：**同步**落盘，不进异步队列。
     *
     * 用途：native 崩溃（SIGSEGV / OOM kill / ANR 被系统杀）根本不会进
     * UncaughtExceptionHandler，日志里一条 Crash 都不会有。
     * 这时唯一能定位的办法，就是在每个关键步骤上同步写一行，
     * 看日志停在哪一行 —— 停在哪就是崩在哪。
     *
     * 代价是每步一次文件写入，所以只在少量关键节点用，别当普通日志使。
     */
    public static void step(String msg) {
        if (!ready || logFile == null) {
            return;
        }
        try {
            Entry e = new Entry(System.currentTimeMillis(), WARN, "步骤", msg);
            synchronized (lock) {
                memory.addLast(e);
                while (memory.size() > MEMORY_MAX) {
                    memory.removeFirst();
                }
            }
            appendToFile(e);
        } catch (Throwable ignored) {
            // 标记写不进去绝不能影响业务
        }
    }

    /** 专给导出用：每一块的 TTS 回调。默认关，只在排查问题时打开。 */
    public static void tts(String msg) {
        if (!verbose) {
            return;
        }
        log(DEBUG, "TTS", msg, null);
    }

    public static void exportEvent(String msg) {
        log(INFO, "Export", msg, null);
    }

    private static void log(int level, String tag, String msg, Throwable t) {
        String text = msg == null ? "" : msg;
        if (t != null) {
            String stack = stackToString(t);
            if (stack.length() > 0) {
                text = text + (text.length() > 0 ? " | " : "") + stack;
            }
        }
        final Entry entry = new Entry(System.currentTimeMillis(), level, tag, text);
        synchronized (lock) {
            memory.addLast(entry);
            while (memory.size() > MEMORY_MAX) {
                memory.removeFirst();
            }
            if (!ready) {
                return;
            }
        }
        try {
            // 关键级别的日志同步落盘，不再排队。
            //
            // 原因很实在：排队的那条路走的是 HandlerThread 异步队列，
            // 进程被系统/native 崩溃带走时，队列里还没消费的内容会跟进程一起消失。
            // 实测就是这样：用户导出回来的日志只剩「进程启动」那几条，
            // 崩溃现场一条都没有 —— 整个诊断功能等于白做。
            //
            // 同步写的代价是主线程多一次文件 IO，但导出场景日志量很小
            // （每章几条、每步一条），这个代价可以接受。
            if (level >= INFO) {
                appendToFile(entry);
                return;
            }
            writer.post(new Runnable() {
                @Override
                public void run() {
                    appendToFile(entry);
                }
            });
        } catch (Exception ignored) {
            // 写不了就算了，绝不能因为记日志把业务搞崩
        }
    }

    private static void appendToFile(Entry e) {
        if (logFile == null) {
            return;
        }
        // 同步写意味着可能有多个线程同时写（主线程打点 + 后台导出线程打点），
        // 不加锁的话两行的内容会交错在一起，日志就废了。
        synchronized (fileLock) {
            try {
                if (logFile.length() > FILE_MAX) {
                    Io.deleteQuietly(backupFile);
                    logFile.renameTo(backupFile);
                }
                BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true));
                try {
                    bw.write(formatLine(e));
                    bw.newLine();
                } finally {
                    Io.close(bw);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static String formatLine(Entry e) {
        return e.timeText() + " " + e.levelName() + "/"
                + (e.tag == null ? "?" : e.tag) + ": " + (e.msg == null ? "" : e.msg);
    }

    private static String stackToString(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            String s = sw.toString();
            if (s.length() > 4000) {
                s = s.substring(0, 4000) + "…(truncated)";
            }
            return s.replace('\n', '⏎');
        } catch (Exception ignored) {
            return t.getClass().getSimpleName();
        }
    }

    /**
     * 只在关键节点调一次（导出结束、崩溃前）：等写盘线程把手上的队列排空，
     * 保证这些日志不会丢。
     */
    public static void flush(long timeoutMs) {
        if (!ready) {
            return;
        }
        final CountSignal done = new CountSignal();
        try {
            writer.post(new Runnable() {
                @Override
                public void run() {
                    done.signal();
                }
            });
        } catch (Exception e) {
            return;
        }
        done.await(timeoutMs);
    }

    private static final class CountSignal {
        private final Object monitor = new Object();
        private boolean fired;

        void signal() {
            synchronized (monitor) {
                fired = true;
                monitor.notifyAll();
            }
        }

        void await(long ms) {
            synchronized (monitor) {
                if (fired) {
                    return;
                }
                try {
                    monitor.wait(ms);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ------------------------------------------------------------------ 读取

    /** 快照。level 为最低级别（含），keyword 为空表示不过滤。 */
    public static List<Entry> snapshot(int minLevel, String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.CHINA);
        List<Entry> out = new ArrayList<Entry>();
        synchronized (lock) {
            for (int i = 0; i < memory.size(); i++) {
                Entry e = memory.get(i);
                if (e.level < minLevel) {
                    continue;
                }
                if (kw.length() > 0) {
                    String hay = ((e.tag == null ? "" : e.tag) + " " + (e.msg == null ? "" : e.msg))
                            .toLowerCase(Locale.CHINA);
                    if (!hay.contains(kw)) {
                        continue;
                    }
                }
                out.add(e);
            }
        }
        return out;
    }

    public static int count() {
        synchronized (lock) {
            return memory.size();
        }
    }

    public static void clear() {
        synchronized (lock) {
            memory.clear();
        }
        try {
            writer.post(new Runnable() {
                @Override
                public void run() {
                    Io.deleteQuietly(logFile);
                    Io.deleteQuietly(backupFile);
                }
            });
        } catch (Exception ignored) {
        }
    }

    public static String logPath() {
        return logFile == null ? "" : logFile.getAbsolutePath();
    }

    public static long fileSize() {
        long n = 0L;
        if (logFile != null && logFile.isFile()) {
            n += logFile.length();
        }
        if (backupFile != null && backupFile.isFile()) {
            n += backupFile.length();
        }
        return n;
    }

    public static String header() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统：Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("生成时间：").append(FULL.format(new Date())).append('\n');
        sb.append("日志路径：").append(logPath()).append('\n');
        return sb.toString();
    }

    /** 纯文本。 */
    public static String toText(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 听书导出 诊断日志 ===\n");
        sb.append(header());
        sb.append("条目数：").append(entries.size()).append('\n');
        sb.append("--------------------------------------------------\n");
        for (int i = 0; i < entries.size(); i++) {
            sb.append(formatLine(entries.get(i))).append('\n');
        }
        return sb.toString();
    }

    /** Markdown：按级别分色是渲染器的活，这里保证表格和代码块能直接看。 */
    public static String toMarkdown(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 听书导出 · 诊断日志\n\n");
        sb.append("```\n").append(header()).append("```\n\n");
        sb.append("共 ").append(entries.size()).append(" 条。级别：**E** 错误 / **W** 警告 / **I** 关键节点 / **D** 细节。\n\n");
        sb.append("| 时间 | 级别 | 模块 | 内容 |\n|---|---|---|---|\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            sb.append('|').append(e.timeText())
                    .append('|').append(e.levelName())
                    .append('|').append(escape(e.tag))
                    .append('|').append(escape(e.msg))
                    .append("|\n");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("|", "\\|").replace("\n", "<br>");
        if (t.length() > 300) {
            t = t.substring(0, 300) + "…";
        }
        return t;
    }

    /**
     * 生成一份完整的日志文本（Markdown 格式），不落盘，直接返回。
     *
     * 一键复制用得上：原来出问题时用户得「导出文件 → 分享 → 保存」三步才能把日志发出来，
     * 中间还要过一遍系统分享面板。有了这个，一步复制到剪贴板就够了。
     */
    public static String dump(int minLevel, String keyword) {
        return toMarkdown(snapshot(minLevel, keyword));
    }

    /** 把日志写到指定文件。调用方自己负责在后台线程跑。 */
    public static boolean writeTo(File target, boolean markdown, int minLevel, String keyword) {
        String body = markdown
                ? toMarkdown(snapshot(minLevel, keyword))
                : toText(snapshot(minLevel, keyword));
        try {
            Io.ensureParent(target);
            Io.writeString(target, body);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
