package com.listenbook.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 导出进度状态。全部 volatile，Service 工作线程写、Activity 主线程轮询读。
 * 不用广播接收器：runtime receiver 在 API 33+ 要声明 exported 标志，
 * 而我们 targetSdk=30 / AIDE API 30，轮询 500ms 更省事也更稳。
 *
 * 一次 os 级时间点上面向的是「当前正在跑的这一个任务」，队列整体信息从
 * ExportQueue 拿，两边职责不重叠。
 */
public final class ExportState {

    private ExportState() {
    }

    public static volatile boolean running;
    public static volatile boolean finished;
    public static volatile boolean stopped;

    /**
     * 这一轮是不是以失败收场。
     *
     * 之前没有这个字段，runJob 里「书找不到 / 文件丢失 / TTS 启动失败 / 解析异常」
     * 全都走 finally → finish()，而 UI 一律画成「已完成」。
     * 用户看到的就是：点一下导出 → 秒变已完成 → 文件夹里一个文件都没有，
     * 以为压根没执行合成。失败必须跟完成长得不一样，否则等于没报错。
     */
    public static volatile boolean failedRun;

    /** 当前处在哪个阶段。UI 直接显示，卡在哪一步一眼看见。 */
    public static volatile String stage = "";

    /** 当前任务的队列 id。UI 用它判断「看到的还是不是我这本书的结果」。 */
    public static volatile long jobId;
    /** finish 时记下属于哪本书，避免跨书读到上一本的完成提示（幽灵完成）。 */
    public static volatile String finishedFor = "";

    public static volatile String bookId = "";
    public static volatile String bookTitle = "";
    public static volatile int total;
    public static volatile int done;
    public static volatile int failed;
    public static volatile int skipped;

    public static volatile String currentChapter = "";
    public static volatile String currentChapterId = "";
    public static volatile int chunkIndex;
    public static volatile int chunkTotal;
    /** 当前块正在第几次重试。UI 会显示「重试 2/3」。 */
    public static volatile int attempt;

    public static volatile long startedAt;
    public static volatile long elapsedMs;
    public static volatile long producedBytes;
    public static volatile long producedMillis;

    public static volatile String lastError = "";
    public static volatile String summary = "";
    public static volatile String engineLabel = "";
    public static volatile String voiceLabel = "";
    public static volatile String outputDir = "";

    /** 队列里还排着几个任务。每次派生任务都会重算。 */
    public static volatile int queuePending;

    // ---------------- v4.3：被系统中断的痕迹 ----------------
    //
    // 进程被系统回收时 ExportState 全是空的，UI 无从知道发生过什么。
    // 这几个字段由 ExportQueue.restoreFromDisk() 填，UI 读一次就该清掉。

    /** 上一次进程退出时是否还有任务没跑完 */
    public static volatile boolean wasInterrupted;
    public static volatile String interruptedBook = "";
    public static volatile String interruptedTitle = "";
    public static volatile int interruptedDone;
    public static volatile int interruptedTotal;
    public static volatile long interruptedAt;

    /**
     * 上次的中断任务有没有自动续上。
     *
     * false = 服务已经自己跑起来了，用户的书在后台继续合成，不用管。
     * true= Android 8+ 后台启动限制拦住了自动拉起，任务躺在队列里等用户点一下。
     *
     * 这两种情况的区别对用户很大：前者不用打扰，后者必须告诉他「它停在那儿了，
     * 你点一下开始导出」，否则他就以为软件卡死了。MainActivity 按这个决定提示怎么写。
     */
    public static volatile boolean interruptedNeedsManualStart;

    /** 本轮失败章节的完整流水。汇总时比 lastError 有用得多。 */
    public static final List<String> errors =
            Collections.synchronizedList(new ArrayList<String>());

    public static void reset(String bookIdValue, String title, int totalChapters) {
        reset(bookIdValue, title, totalChapters, 0L);
    }

    public static void reset(String bookIdValue, String title, int totalChapters, long jobIdValue) {
        running = true;
        finished = false;
        stopped = false;
        failedRun = false;
        stage = "准备中";
        finishedFor = "";
        jobId = jobIdValue;
        bookId = bookIdValue == null ? "" : bookIdValue;
        bookTitle = title == null ? "" : title;
        total = totalChapters;
        done = 0;
        failed = 0;
        skipped = 0;
        currentChapter = "";
        currentChapterId = "";
        chunkIndex = 0;
        chunkTotal = 0;
        attempt = 0;
        startedAt = System.currentTimeMillis();
        elapsedMs = 0L;
        producedBytes = 0L;
        producedMillis = 0L;
        lastError = "";
        summary = "";
        queuePending = ExportQueue.pendingCount();
        errors.clear();
    }

    public static void addError(String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        lastError = text;
        if (errors.size() < 100) {
            errors.add(text);
        }
    }

    /** 最近 n 条失败，最新的在前。 */
    public static List<String> recentErrors(int max) {
        List<String> all = new ArrayList<String>(errors);
        List<String> out = new ArrayList<String>();
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    public static void finish(String summaryText) {
        finish(summaryText, false);
    }

    public static void finish(String summaryText, boolean isFailed) {
        running = false;
        finished = true;
        failedRun = isFailed;
        finishedFor = bookId;
        elapsedMs = System.currentTimeMillis() - startedAt;
        summary = summaryText == null ? "" : summaryText;
        currentChapter = "";
        currentChapterId = "";
        attempt = 0;
        if (!isFailed) {
            stage = "已完成";
        }
    }

    /** 推进阶段。Service 每做一步就调，UI 看到的就是实时流水。 */
    public static void setStage(String text) {
        if (text != null) {
            stage = text;
        }
    }

    /** 这一轮结果是不是这本书的。防止 A 书跑完，切到 B 书看到 A 的完成提示。 */
    public static boolean finishedFor(String bookIdValue) {
        return finished && finishedFor != null && finishedFor.equals(bookIdValue);
    }

    public static int percent() {
        if (total <= 0) {
            return 0;
        }
        int v = (int) ((done + failed) * 100L / (long) total);
        return Math.max(0, Math.min(100, v));
    }

    /** 已用时 / 已完成章数 → 估算剩余时间（毫秒） */
    public static long etaMs() {
        int finishedCount = done + failed;
        if (finishedCount <= 0 || total <= finishedCount) {
            return 0L;
        }
        long used = System.currentTimeMillis() - startedAt;
        long per = used / finishedCount;
        return per * (long) (total - finishedCount);
    }
}
