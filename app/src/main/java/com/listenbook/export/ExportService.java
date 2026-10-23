package com.listenbook.export;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.listenbook.R;
import com.listenbook.model.Book;
import com.listenbook.model.BookLibrary;
import com.listenbook.model.Chapter;
import com.listenbook.parse.BookParser;
import com.listenbook.tts.SentenceSplitter;
import com.listenbook.tts.TtsCatalog;
import com.listenbook.ui.ExportActivity;
import com.listenbook.util.AppLog;
import com.listenbook.util.Io;
import com.listenbook.util.MediaStorePublisher;
import com.listenbook.util.SafExport;
import com.listenbook.util.Settings;
import com.listenbook.util.TextEncoding;
import com.listenbook.util.WavTool;
import com.listenbook.ui.FloatingWindow;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 模块 B：导出。串行消费 ExportQueue → synthesizeToFile() → 落 WAV。
 * 只产 WAV，绝不播放（播放是模块 A 的事，两者运行时完全隔离，各自持有独立 TextToSpeech 实例）。
 *
 * 队列：一个 Service 实例把队列里的任务全部跑完再 stopSelf。
 *       因为 TTS 引擎自己就是串行合成的，并行只会互相拖慢、还会互相删 .tmp。
 *
 * 单个 Job 参数独立：每个任务可以指定不同引擎 / 音色 / 语速 / 音调 / 语言，
 *                   任务之间会重建 TextToSpeech（切引擎必须重建，这是 Android 的硬要求）。
 *
 * 后台导出：前台服务 + 常驻通知 + PARTIAL_WAKE_LOCK，息屏/退出界面继续跑。
 * 断点续传：文件即状态 —— 每次启动重新解析书籍，扫目录跳过已完成章节，
 *          所有 .tmp 一律删除重来，事务边界 = 原子重命名。
 *
 * targetSdk=30，故意不声明 foregroundServiceType：
 * 强制类型是 targetSdk>=34 的要求，mediaProcessing 的 6 小时上限是 targetSdk>=35 的要求，
 * 我们都不受影响，声明反而在 AIDE 里编译报错。
 */
public class ExportService extends Service {

    public static final String ACTION_START = "com.listenbook.action.EXPORT_START";
    public static final String ACTION_STOP = "com.listenbook.action.EXPORT_STOP";
    public static final String ACTION_CLEAR_QUEUE = "com.listenbook.action.EXPORT_CLEAR";

    public static final String EXTRA_BOOK_ID = "bookId";
    public static final String EXTRA_INDICES = "indices";
    public static final String EXTRA_SYNC = "syncPublic";
    public static final String EXTRA_ENGINE = "enginePkg";
    public static final String EXTRA_VOICE = "voiceName";
    public static final String EXTRA_RATE = "rate";
    public static final String EXTRA_PITCH = "pitch";
    public static final String EXTRA_LANG_MODE = "langMode";
    public static final String EXTRA_LOCALE = "localeTag";
    public static final String EXTRA_CHUNK = "chunkBudget";
    public static final String EXTRA_SLACK = "timeoutSlack";
    public static final String EXTRA_ATTEMPTS = "maxAttempts";
    public static final String EXTRA_TITLE = "bookTitle";
    public static final String EXTRA_JOB_ID = "jobId";
    public static final String EXTRA_CUSTOM_TREE = "customTree";

    private static final String CHANNEL_ID = "listenbook_export";
    private static final String CHANNEL_DONE_ID = "listenbook_export_done";
    private static final int NOTIF_ID = 1001;
    private static final int NOTIF_DONE_ID = 1002;

    private Thread worker;
    private volatile boolean stopRequested;
    private volatile boolean busy;

    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile String currentUid = "";
    private volatile CountDownLatch chunkLatch;
    private volatile boolean chunkOk;
    private volatile int utteranceErrors;

    private PowerManager.WakeLock wakeLock;
    private NotificationManager nm;

    /** 悬浮窗只能在主线程 addView/removeView，工作线程碰它会抛 CalledFromWrongThread */
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 自定义输出目录（SAF tree）。每章完成后搬一份过去。 */
    private Uri customTree;
    private int copiedToTree;
    private int treeCopyFailed;

    // ---------------------------------------------------------------- 外部调用

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.init(this);
        // 先把队列和落盘连上，后面 restoreFromDisk 才有地方写
        ExportQueue.attach(this);
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            AppLog.exportEvent("收到停止请求");
            stopRequested = true;
            safeStopTts();
            // 停止是用户的明确意图：要断掉系统重启，不然点完「停止」它又自己跑起来了
            ExportQueue.clearInterrupted();
            return START_NOT_STICKY;
        }
        if (ACTION_CLEAR_QUEUE.equals(action)) {
            int n = ExportQueue.clearPending();
            AppLog.exportEvent("收到清空队列请求，移除 " + n + " 个任务");
            ExportState.queuePending = ExportQueue.pendingCount();
            updateNotificationInWorker("队列已清空", ExportState.percent());
            // 队列清空后如果没任务在跑，服务可以自己退场
            if (!busy) {
                ExportState.running = false;
                stopForegroundCompat();
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (busy && worker != null && worker.isAlive()) {
            // 已在跑，工作线程自己会去队列取下一条，这里不需要第二根线程
            AppLog.i("ExportService", "服务正忙，任务留在队列里等待");
            return START_STICKY;
        }
        // 走到这里说明不是「已在跑」，那么看看是不是被系统杀过、盘上有没跑完的任务。
        // 之前队列纯内存，进程一死就凭空消失 —— 合成长任务最怕这个。
        int restored = ExportQueue.restoreFromDisk();
        if (restored > 0) {
            AppLog.exportEvent("检测到上次遗留的 " + restored + " 个任务，自动接着跑");
        }
        if (!ExportQueue.hasPending()) {
            // 没有任何活可干。别把 START_STICKY 挂上：
            // 否则系统会在任意时刻把服务重新拉起来，空跑一次，白耗电。
            AppLog.i("ExportService", "队列是空的，服务退场（不挂STICKY）");
            stopSelf();
            return START_NOT_STICKY;
        }
        stopRequested = false;
        busy = true;
        startForeground(NOTIF_ID, buildNotification("准备中…", 0));
        // 悬浮窗和通知是两个独立出口：通知会被系统折叠/清理/被省电策略压掉，
        // 悬浮窗挂在 WindowManager 上没那套脆弱的生命周期。两者显示同样的内容，
        // 谁活下来用户都能看到进度。悬浮窗没权限或开关关着时会静默跳过。
        main.post(new Runnable() {
            @Override
            public void run() {
                FloatingWindow.get(ExportService.this).showIfAllowed();
            }
        });
        worker = new Thread(new Worker(), "listenbook-export");
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
        // START_STICKY：进程被系统回收后，系统会重新拉起本服务并传一个
        // intent == null 的 startCommand，上面 restoreFromDisk() 就会把落盘的
        // 队列读回来接着跑。这才是「杀后台也不怕」的真正保障。
        // 注意：只有在「确实有活干」时才挂 STICKY（见上面队列为空的分支）。
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        // 先标记「还有活没干完」再拆资源。反过来的话，队列已经被判成空，
        // 标记就写不进去了，下次启动会以为一切正常，用户白丢一个任务。
        ExportQueue.markInterrupted(ExportState.bookId, ExportState.bookTitle,
                ExportState.done, ExportState.total);
        safeStopTts();
        releaseWakeLock();
        shutdownTts();
        busy = false;
        AppLog.i("ExportService", "服务销毁 " + ExportQueue.describe());
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 队列循环

    private final class Worker implements Runnable {

        @Override
        public void run() {
            AppLog.i("ExportService", "工作线程启动 " + ExportQueue.describe());
            acquireWakeLock();
            try {
                while (!stopRequested) {
                    ExportJob job = ExportQueue.takeNext();
                    if (job == null) {
                        break;
                    }
                    runJob(job);
                    ExportQueue.trim();
                    ExportState.queuePending = ExportQueue.pendingCount();
                    if (stopRequested) {
                        AppLog.exportEvent("收到停止信号，队列循环退出");
                        break;
                    }
                }
                AppLog.i("ExportService", "队列跑完 " + ExportQueue.describe());
            } catch (Throwable t) {
                AppLog.e("ExportService", "工作线程异常退出", t);
            } finally {
                shutdownTts();
                releaseWakeLock();
                AppLog.flush(1500L);
                busy = false;
                ExportState.queuePending = ExportQueue.pendingCount();
                stopForegroundCompat();
                stopSelf();
            }
        }
    }

    private void runJob(ExportJob job) {
        ExportStore store = null;
        List<Chapter> chapters = null;
        Book book = null;
        String summary = "";
        boolean hasFailure = false;
        customTree = SafExport.treeUri(ExportService.this);
        copiedToTree = 0;
        treeCopyFailed = 0;
        try {
            ExportState.reset(job.bookId, job.bookTitle, job.indices.length, job.jobId);
            ExportState.setStage("读取书库…");
            updateNotification("读取书库…", 0);
            AppLog.exportEvent("===== 开始任务 #" + job.jobId + " 《" + job.bookTitle + "》 "
                    + job.indices.length + " 章 · " + job.paramSummary() + " =====");

            BookLibrary library = new BookLibrary(ExportService.this);
            book = library.findById(job.bookId);
            if (book == null) {
                summary = "书库里找不到这本书（id=" + job.bookId + "）";
                hasFailure = true;
                ExportState.setStage("失败：书库里没有这本书");
                AppLog.w("ExportService", summary);
                return;
            }
            if (!book.fileExists()) {
                summary = "书籍文件已丢失，请重新导入：" + book.path;
                hasFailure = true;
                ExportState.setStage("失败：书籍文件已丢失");
                AppLog.w("ExportService", summary);
                return;
            }
            if (job.bookTitle == null || job.bookTitle.length() == 0) {
                job.bookTitle = book.title;
            }
            ExportState.bookTitle = book.title;

            ExportState.setStage("解析章节…");
            updateNotification("解析章节…", 0);
            BookParser.Result result = BookParser.parse(book);
            chapters = result.chapters;
            AppLog.i("ExportService", "解析完成 " + chapters.size() + " 章");
            if (job.indices.length == 0) {
                summary = "没有任何章节被选中（任务里是空的）";
                hasFailure = true;
                ExportState.setStage("失败：没选章节");
                AppLog.w("ExportService", summary + " jobId=" + job.jobId);
                return;
            }

            store = new ExportStore(ExportService.this, job.bookId);
            // 传 chapters 才能认出 v1.2 的 0001_ 序号命名（断点续传靠它跳过已完成章节）
            store.prepare(chapters);
            ExportState.outputDir = store.dirPath();
            ExportState.producedBytes = store.totalBytes();

            ExportState.setStage("启动 TTS 引擎…");
            updateNotification("启动 TTS 引擎…", 0);
            if (!initTts(30000L, job)) {
                summary = "TTS 引擎启动失败（" + (job.enginePkg.length() == 0
                        ? "系统默认" : job.enginePkg) + "），"
                        + "一个字都还没合成。去系统设置里确认装了 TTS 引擎，"
                        + "或到导出页换个引擎再试。";
                hasFailure = true;
                ExportState.setStage("失败：TTS 引擎启动不了");
                AppLog.e("ExportService", summary);
                return;
            }
            ExportState.engineLabel = describeEngine();
            ExportState.voiceLabel = job.voiceName;

            int done = 0;
            int failed = 0;
            int skipped = 0;
            for (int i = 0; i < job.indices.length; i++) {
                if (stopRequested) {
                    break;
                }
                int idx = job.indices[i];
                if (idx < 0 || idx >= chapters.size()) {
                    failed++;
                    ExportState.addError("第 " + (idx + 1) + " 章：索引越界（书籍可能已改动）");
                    hasFailure = true;
                    AppLog.w("ExportService", "章索引越界 idx=" + idx + " size=" + chapters.size());
                    continue;
                }
                Chapter chapter = chapters.get(idx);
                // 断点续传：RIFF 校验通过的直接跳过
                if (store.isCompleted(chapter.id)) {
                    skipped++;
                    done++;
                    ExportState.done = done;
                    ExportState.skipped = skipped;
                    updateProgress(chapter, done, skipped, failed);
                    AppLog.i("ExportService", "跳过已完成章节：" + chapter.title);
                    continue;
                }
                updateProgress(chapter, done, skipped, failed);
                ExportState.setStage("合成第 " + (idx + 1) + "/" + job.indices.length + " 章");
                AppLog.exportEvent("开始第 " + (idx + 1) + " 章《" + chapter.title + "》 "
                        + chapter.length() + " 字");
                ExportResult r = exportChapter(store, chapter, job);
                if (r == ExportResult.OK) {
                    done++;
                } else if (r == ExportResult.STOPPED) {
                    AppLog.exportEvent("任务在第 " + (idx + 1) + " 章被停止");
                    break;
                } else {
                    failed++;
                    hasFailure = true;
                    AppLog.w("ExportService", "第 " + (idx + 1) + " 章失败："
                            + ExportState.lastError);
                }
                ExportState.done = done;
                ExportState.failed = failed;
                ExportState.skipped = skipped;
                store.writeMeta(book, chapters, describeExportEngineLabel(job));
            }

            ExportState.queuePending = ExportQueue.pendingCount();
            if (stopRequested) {
                summary = "已手动停止：完成 " + done + " 章，失败 " + failed
                        + " 章，跳过 " + skipped + " 章（已完成部分保留，下次自动续传）";
            } else {
                summary = "导出结束：完成 " + done + " 章，失败 " + failed
                        + " 章，跳过 " + skipped + " 章";
                if (failed > 0) {
                    summary = summary + "；失败明细见诊断日志";
                }
            }
            if (store != null) {
                store.writeMeta(book, chapters, describeExportEngineLabel(job));
                store.writePlaylist(book, chapters);
                ExportState.producedBytes = store.totalBytes();
                summary = summary + "\n输出目录：" + store.dirPath()
                        + "\n总占用：" + WavTool.humanSize(store.totalBytes());
                if (job.syncPublic) {
                    summary = summary + "\n已同步到公共目录 Music/"
                            + MediaStorePublisher.APP_DIR + "/" + book.title;
                }
                if (copiedToTree > 0 || treeCopyFailed > 0) {
                    summary = summary + "\n已搬运到自定义目录 " + copiedToTree + " 个"
                            + (treeCopyFailed > 0 ? "，失败 " + treeCopyFailed + " 个" : "");
                }
            }
            AppLog.exportEvent("===== 任务 #" + job.jobId + " 结束：" + summary + " =====");
        } catch (Throwable t) {
            hasFailure = true;
            summary = "导出异常：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            ExportState.addError(summary);
            AppLog.e("ExportService", "任务 #" + job.jobId + " 异常", t);
        } finally {
            // 引擎在下一个任务可能不同，这里必须放下，否则会串味
            shutdownTts();
            if (ExportState.running) {
                // runJob 走到这里说明任务已经定局
                ExportState.bookId = job.bookId == null ? "" : job.bookId;
                ExportState.elapsedMs = System.currentTimeMillis() - ExportState.startedAt;
                if (stopRequested) {
                    ExportState.stopped = true;
                }
                // 失败和完成必须分开传，否则 UI 会把「一个字都没合成」画成「已完成」
                // done 是 try 块里的局部变量，这里够不着，用 ExportState.done
                ExportState.finish(summary, hasFailure || ExportState.done == 0);
            }
            ExportQueue.finish(job, hasFailure, summary);
            ExportState.queuePending = ExportQueue.pendingCount();
            AppLog.flush(800L);
            String head = summary.length() > 60 ? summary.substring(0, 60) + "…" : summary;
            updateNotification(head, 100);
            // 完成任务单独发一条，而不是靠前台通知「留着不走」。
            // stopForeground(true) 会把前台通知整个删掉，跑到这一步通知就没了 ——
            // 用户跑到一半切去干别的，回头连「跑完了没」都不知道。
            notifyDone(hasFailure);
            hideFloating();
        }
    }

    private enum ExportResult {
        OK, FAILED, STOPPED
    }

    private ExportResult exportChapter(ExportStore store, Chapter chapter, ExportJob job) {
        List<String> chunks = SentenceSplitter.chunk(chapter.text, job.chunkBudget);
        if (chunks.isEmpty()) {
            ExportState.addError(chapter.title + "：没有可合成的文本");
            return ExportResult.FAILED;
        }
        Locale loc = resolveLocale(chapter.text, job);
        try {
            if (tts != null) {
                int lang = tts.setLanguage(loc);
                logLanguage(chapter.title, loc, lang);
            }
        } catch (Exception ignored) {
        }

        ExportState.currentChapter = chapter.title;
        ExportState.currentChapterId = chapter.id;
        ExportState.chunkTotal = chunks.size();
        long chapterStart = System.currentTimeMillis();

        List<File> parts = new ArrayList<File>();
        for (int k = 0; k < chunks.size(); k++) {
            if (stopRequested) {
                deleteAll(parts);
                return ExportResult.STOPPED;
            }
            ExportState.chunkIndex = k;
            File part = store.partFile(chapter.id, k);
            Io.deleteQuietly(part);
            if (!synthesizeChunk(chunks.get(k), part, chapter.id, k, job)) {
                deleteAll(parts);
                if (stopRequested) {
                    return ExportResult.STOPPED;
                }
                ExportState.addError(chapter.title + "：第 " + (k + 1) + "/" + chunks.size()
                        + " 块合成失败（" + chunks.get(k).length() + " 字）");
                return ExportResult.FAILED;
            }
            parts.add(part);
            updateNotification(chapter.title + "  " + (k + 1) + "/" + chunks.size(),
                    ExportState.percent());
        }

        File[] partArray = parts.toArray(new File[parts.size()]);
        File out = store.commit(partArray, chapter);
        if (out == null) {
            deleteAll(parts);
            ExportState.addError(chapter.title + "：WAV 合并或校验失败");
            return ExportResult.FAILED;
        }
        WavTool.Info info = WavTool.probe(out);
        ExportState.producedBytes += out.length();
        ExportState.producedMillis += info.millis();
        // 落盘路径必须记：用户最常见的诉求之一是「文件到底在哪」，
        // 日志里有绝对路径，诊断时可以直接判断是权限问题还是根本没写出来。
        AppLog.step("落盘: " + out.getAbsolutePath() + " (" + out.length() + " 字节)");
        AppLog.exportEvent("完成《" + chapter.title + "》 " + (info.millis() / 1000L) + " 秒音频 · "
                + WavTool.humanSize(out.length()) + " · 本章耗时 "
                + ((System.currentTimeMillis() - chapterStart) / 1000L) + " 秒");

        if (job.syncPublic) {
            String published = MediaStorePublisher.publish(
                    ExportService.this, out, ExportState.bookTitle);
            if (published == null && MediaStorePublisher.needsLegacyPermission()) {
                ExportState.addError("同步到公共目录失败：Android 9 及以下需要存储权限");
            } else {
                AppLog.step("公共目录发布: " + (published == null ? "失败" : published));
            }
        }
        // 自定义目录：SAF 搬运，跟「公共音乐目录」互不冲突，可以同时开
        if (customTree != null) {
            if (SafExport.publishFile(ExportService.this, customTree,
                    ExportState.bookTitle, out)) {
                copiedToTree++;
            } else {
                treeCopyFailed++;
                AppLog.w("ExportService", "搬运到自定义目录失败：" + out.getName());
            }
        }
        return ExportResult.OK;
    }

    private Locale resolveLocale(String text, ExportJob job) {
        if (Settings.LANG_ZH.equals(job.languageMode)) {
            return Locale.CHINESE;
        }
        if (Settings.LANG_EN.equals(job.languageMode)) {
            return Locale.ENGLISH;
        }
        if (job.localeTag != null && job.localeTag.length() > 0) {
            return TtsCatalog.localeOf(job.localeTag, Locale.CHINESE);
        }
        return TextEncoding.cjkRatio(text) >= 0.15f ? Locale.CHINESE : Locale.ENGLISH;
    }

    private void logLanguage(String title, Locale loc, int lang) {
        if (lang == TextToSpeech.LANG_MISSING_DATA) {
            AppLog.w("ExportService", title + "：引擎缺少 " + loc + " 语音数据");
        } else if (lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            AppLog.w("ExportService", title + "：引擎不支持 " + loc);
        }
    }

    /**
     * 合成一块文本到 part 文件，带重试。
     *
     * uid 里必须带上 attempt：原实现用 "e:<chapter>:<k>"，重试时两次用的是同一个 id，
     * 第一次超时的迟到 onError 会在第二次等待期间到达并通过 uid 校验，
     * 把第二次的 chunkOk 打成 false —— 第二次明明成功却判失败，整章 FAILED。
     * 表现就是「跑着跑着某章突然失败，重跑又能过」。
     */
    private boolean synthesizeChunk(String text, File part, String chapterId, int chunkIdx,
                                    ExportJob job) {
        if (tts == null || !ttsReady) {
            ExportState.addError("TTS 引擎未就绪");
            return false;
        }
        for (int attempt = 1; attempt <= job.maxAttempts; attempt++) {
            if (stopRequested) {
                return false;
            }
            ExportState.attempt = attempt;
            chunkOk = false;
            String uid = "e:" + chapterId + ":" + chunkIdx + ":" + attempt;
            currentUid = uid;
            chunkLatch = new CountDownLatch(1);

            Bundle params = new Bundle();
            // 坑 2：不传 utteranceId，onDone 永远不回调 → 断点续传的命门
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid);

            int r;
            long t0 = System.currentTimeMillis();
            try {
                // 坑 1：目标文件必须在 getExternalFilesDir 下，TTS 引擎才写得进去
                r = tts.synthesizeToFile(text, params, part, uid);
            } catch (Exception e) {
                r = TextToSpeech.ERROR;
            }
            if (r != TextToSpeech.SUCCESS) {
                ExportState.addError("synthesizeToFile 返回 ERROR（第 " + attempt + " 次）");
                AppLog.w("ExportService", chapterId + " 第 " + chunkIdx + " 块："
                        + "synthesizeToFile 返回 " + r + "（第 " + attempt + " 次）");
                Io.deleteQuietly(part);
                sleep(400L);
                continue;
            }

            boolean awaited;
            try {
                awaited = chunkLatch.await(timeoutFor(text.length(), job.timeoutSlack),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                awaited = false;
                Thread.currentThread().interrupt();
            }
            long cost = System.currentTimeMillis() - t0;

            if (awaited && chunkOk) {
                // 判定完成不能只看文件存在，用 RIFF 头校验
                if (WavTool.isValid(part)) {
                    AppLog.tts(chapterId + " #" + chunkIdx + " 成功，耗时 " + cost + "ms，"
                            + text.length() + " 字");
                    ExportState.attempt = 0;
                    return true;
                }
                ExportState.addError("回调说完成了，但 WAV 头不完整");
            } else {
                // 前人踩过的坑：不少引擎压根不回调 onDone，或者回调晚得离谱，
                // 但文件其实已经写完了。超时/报错都先回头看一眼文件，别白等一场。
                sleep(1500L);
                if (WavTool.isValid(part)) {
                    AppLog.tts(chapterId + " #" + chunkIdx + " 引擎没回调，但文件有效，接受（"
                            + cost + "ms）");
                    ExportState.attempt = 0;
                    return true;
                }
                if (!awaited) {
                    ExportState.addError("合成超时（引擎无回调，文件也没写出来），第 "
                            + attempt + " 次");
                } else {
                    ExportState.addError("引擎返回错误码，第 " + attempt + " 次");
                    utteranceErrors++;
                }
            }
            AppLog.w("ExportService", chapterId + " #" + chunkIdx + " 第 " + attempt
                    + " 次失败（" + (awaited ? "引擎报错" : "超时") + "，耗时 " + cost + "ms）");
            safeStopTts();
            Io.deleteQuietly(part);
            if (stopRequested) {
                return false;
            }
            sleep(600L);
        }
        ExportState.attempt = 0;
        return false;
    }

    /** 实时合成：按 4 字/秒估算，再乘余量 + 45 秒固定开销 */
    private static long timeoutFor(int chars, int slack) {
        long estMs = (long) chars * 250L;
        long timeout = estMs * (long) slack + 45000L;
        if (timeout < 60000L) {
            timeout = 60000L;
        }
        if (timeout > 1800000L) {
            timeout = 1800000L;
        }
        return timeout;
    }

    private void deleteAll(List<File> parts) {
        for (int i = 0; i < parts.size(); i++) {
            Io.deleteQuietly(parts.get(i));
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- TTS

    private boolean initTts(long timeoutMs, ExportJob job) {
        AppLog.setVerbose(Settings.verboseTts(this));
        // TTS 初始化是最容易「卡住不动」的一段（要等厂商引擎进程握手），
        // 同步打点能把「卡在等引擎」和「引擎起来了但合成慢」区分开。
        AppLog.step("TTS:开始初始化 引擎=" + (job.enginePkg == null || job.enginePkg.length() == 0
                ? "系统默认" : job.enginePkg) + " 超时=" + timeoutMs + "ms");
        shutdownTts();
        utteranceErrors = 0;
        final CountDownLatch latch = new CountDownLatch(1);
        TextToSpeech.OnInitListener onInit = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                AppLog.step("TTS:onInit 回调 status=" + status);
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    // 坑 3：监听器必须在 onInit 成功回调内部挂
                    tts.setOnUtteranceProgressListener(progressListener);
                    ttsReady = true;
                } else {
                    ttsReady = false;
                }
                latch.countDown();
            }
        };
        String engine = job.enginePkg == null ? "" : job.enginePkg;
        try {
            if (engine.length() > 0) {
                tts = new TextToSpeech(this, onInit, engine);
            } else {
                tts = new TextToSpeech(this, onInit);
            }
        } catch (Exception e) {
            AppLog.e("ExportService", "创建 TextToSpeech 失败，退回系统默认", e);
            try {
                tts = new TextToSpeech(this, onInit);
            } catch (Exception e2) {
                AppLog.step("TTS:创建失败（系统默认也不行）");
                return false;
            }
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!ttsReady || tts == null) {
            AppLog.step("TTS:初始化超时或失败 ready=" + ttsReady
                    + " tts=" + (tts == null ? "null" : "有"));
            return false;
        }
        applyParams(job);
        AppLog.step("TTS:就绪 引擎=" + describeEngine());
        AppLog.i("ExportService", "TTS 就绪 引擎=" + describeEngine()
                + " 音色=" + (job.voiceName.length() == 0 ? "引擎默认" : job.voiceName)
                + " 语速=" + job.rate + " 音调=" + job.pitch);
        return true;
    }

    /** 语速 / 音调 / 音色，统一在这里落地，避免散落各处。 */
    private void applyParams(ExportJob job) {
        if (tts == null) {
            return;
        }
        try {
            tts.setSpeechRate(job.rate);
        } catch (Exception e) {
            AppLog.w("ExportService", "设置语速失败：" + e.getMessage());
        }
        try {
            tts.setPitch(job.pitch);
        } catch (Exception e) {
            AppLog.w("ExportService", "设置音调失败：" + e.getMessage());
        }
        if (job.voiceName != null && job.voiceName.length() > 0) {
            applyVoice(job.voiceName);
        }
    }

    /**
     * 选音色。Voice 对象要跟引擎实例对得上，所以这里从引擎自己列的 voices 里
     * 按 name 精确匹配，而不是自己 new 一个 —— 部分引擎认后者会失败。
     */
    private void applyVoice(String voiceName) {
        if (tts == null) {
            return;
        }
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) {
                return;
            }
            for (Voice v : voices) {
                if (v != null && voiceName.equals(v.getName())) {
                    int r = tts.setVoice(v);
                    if (r == TextToSpeech.SUCCESS) {
                        AppLog.i("ExportService", "已选中音色 " + voiceName);
                    } else {
                        AppLog.w("ExportService", "setVoice 返回 " + r + "：" + voiceName);
                    }
                    return;
                }
            }
            AppLog.w("ExportService", "引擎里找不到音色 " + voiceName + "，使用默认");
        } catch (Exception e) {
            AppLog.w("ExportService", "setVoice 异常：" + e.getMessage());
        }
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            if (isCurrent(utteranceId)) {
                AppLog.tts("onStart " + utteranceId);
            }
        }

        @Override
        public void onDone(String utteranceId) {
            settle(utteranceId, true);
        }

        @Override
        public void onError(String utteranceId) {
            settle(utteranceId, false);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            settle(utteranceId, false);
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            settle(utteranceId, false);
        }
    };

    private boolean isCurrent(String utteranceId) {
        return utteranceId != null && utteranceId.equals(currentUid);
    }

    /** 坑 5：回调在 binder 线程。这里只碰 volatile 字段和 latch，不碰 UI。 */
    private void settle(String utteranceId, boolean ok) {
        if (!isCurrent(utteranceId)) {
            return; // 上一块 / 上一次重试的迟到回调
        }
        if (!ok) {
            utteranceErrors++;
        }
        chunkOk = ok;
        CountDownLatch l = chunkLatch;
        if (l != null) {
            l.countDown();
        }
    }

    private String describeEngine() {
        try {
            String e = tts == null ? null : tts.getDefaultEngine();
            return e == null ? "system" : e;
        } catch (Exception ex) {
            return "system";
        }
    }

    /** 写进 meta.json 的引擎描述：用户看得懂的优先 */
    private String describeExportEngineLabel(ExportJob job) {
        String pkg = describeEngine();
        String label = null;
        try {
            label = TtsCatalog.engineLabel(this, pkg);
        } catch (Exception ignored) {
        }
        String out = label == null ? pkg : label;
        if (job != null && job.voiceName != null && job.voiceName.length() > 0) {
            out = out + " / " + job.voiceName;
        }
        return out;
    }

    private void safeStopTts() {
        try {
            if (tts != null) {
                tts.stop();
            }
        } catch (Exception ignored) {
        }
    }

    private void shutdownTts() {
        ttsReady = false;
        try {
            if (tts != null) {
                tts.setOnUtteranceProgressListener(null);
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {
        }
        tts = null;
    }

    // ---------------------------------------------------------------- WakeLock

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "listenbook:export");
            wakeLock.setReferenceCounted(false);
            // 记录「非预期释放」。走到这里说明系统在导出中途把 WakeLock 掐了 ——
            // 这是「息屏后合成变慢/停住」最直接的信号，之前的日志完全看不到。
            //
            // 必须走纯反射、连接口类型都不在编译期出现：
            // PowerManager.OnUnheldExitHandler 在本项目 compileSdk=30 的 android.jar 里
            // 根本不导出（它是 @hide API），直接写会编译失败。
            // 同时国产 ROM 对这个回调的实现也不一致，所以失败就跳过，绝不影响导出。
            try {
                Class<?> handlerIface = Class.forName("android.os.PowerManager$OnUnheldExitHandler");
                Object handler = java.lang.reflect.Proxy.newProxyInstance(
                        ExportService.class.getClassLoader(),
                        new Class<?>[]{handlerIface},
                        new java.lang.reflect.InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, java.lang.reflect.Method m,
                                    Object[] args) {
                                AppLog.exportEvent("⚠ WakeLock 被系统提前释放，"
                                        + "息屏后合成可能变慢（书：" + ExportState.bookTitle + "）");
                                return null;
                            }
                        });
                java.lang.reflect.Method set = wakeLock.getClass().getMethod(
                        "setOnUnheldExitHandler", handlerIface);
                set.invoke(wakeLock, handler);
                AppLog.i("ExportService", "已挂上 WakeLock 异常释放回调");
            } catch (Throwable t) {
                AppLog.i("ExportService", "这台机器不支持 WakeLock 异常回调，跳过（不影响导出）");
            }
            // 最长 12 小时，超时自动释放，绝不泄漏
            wakeLock.acquire(12L * 60L * 60L * 1000L);
        } catch (Exception e) {
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    // ---------------------------------------------------------------- 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) {
            return;
        }
        try {
            // 通道建过一次就改不动了（系统会保留用户和既有设置），所以要换通道 id。
            // 原来用的是 IMPORTANCE_LOW：静默、通知栏几乎无感知，
            // 叠加 setOngoing(true) 运行中不可滑动，跑完又直接移除 ——
            // 换句话说这个通知存在了，但用户几乎不可能看见它。
            // 合成要跑几小时，这是整个 App 唯一的进度出口，不能是隐形的。
            createChannelIfAbsent(CHANNEL_ID, getString(R.string.notif_channel),
                    "导出 WAV 时显示的常驻通知", NotificationManager.IMPORTANCE_DEFAULT);
            createChannelIfAbsent(CHANNEL_DONE_ID, getString(R.string.notif_channel_done),
                    "导出完成时通知一声", NotificationManager.IMPORTANCE_DEFAULT);
        } catch (Exception ignored) {
        }
    }

    private void createChannelIfAbsent(String id, String name, String desc, int importance) {
        try {
            NotificationChannel existing = nm.getNotificationChannel(id);
            if (existing != null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(id, name, importance);
            channel.setDescription(desc);
            channel.setShowBadge(true);
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String text, int percent) {
        Intent open = new Intent(this, ExportActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // 带上 bookId：点通知直接落到这本书的导出页。不带的话只能回到
        // 「最近打开的那本书」，多本书排队时一点就点错了。
        if (ExportState.bookId != null && ExportState.bookId.length() > 0) {
            open.putExtra("bookId", ExportState.bookId);
        }
        int openFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            openFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, openFlags);

        Intent stop = new Intent(this, ExportService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, openFlags);

        String title = ExportState.bookTitle.length() > 0
                ? "导出：" + ExportState.bookTitle : "导出音频";
        int pending = ExportQueue.pendingCount();
        if (pending > 0) {
            title = title + "（队列还有 " + pending + " 个）";
        }
        // 正文拆两行：第一行总量进度，第二行「现在在干嘛 + 还要多久」。
        // 之前只有一行、章数和阶段文案混在一起，看通知的人得自己拆数字。
        // etaMs() 早就实现了却一直没人调用 —— 用户最关心的恰恰是「还要等多久」。
        String stage = text == null ? "" : text;
        StringBuilder body = new StringBuilder();
        if (ExportState.total > 0) {
            body.append(ExportState.done + "/" + ExportState.total).append(" 章　")
                    .append(percent).append('%');
        } else {
            body.append(percent).append('%');
        }
        long eta = ExportState.etaMs();
        if (eta > 0) {
            body.append("　剩 ").append(WavTool.humanDuration(eta));
        }
        if (stage.length() > 0) {
            body.append('\n').append(stage);
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
                .setContentText(body.toString())
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(busy)
                // 进度每章都变，不加这个每刷新一次「叮」一声，几百章下来能把人逼疯
                .setOnlyAlertOnce(true);
        if (percent > 0) {
            b.setProgress(100, percent, false);
        }
        if (busy) {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止", stopPi).build());
        }
        return b.build();
    }

    private void updateNotification(String text, int percent) {
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIF_ID, buildNotification(text, percent));
        } catch (Exception ignored) {
        }
    }

    /** 从非工作线程（清队列请求）刷新通知时的入口 */
    private void updateNotificationInWorker(String text, int percent) {
        updateNotification(text, percent);
    }

    private void updateProgress(Chapter chapter, int done, int skipped, int failed) {
        String text = chapter == null ? "" : chapter.title;
        if (ExportState.chunkTotal > 0) {
            text = text + "  块 " + (ExportState.chunkIndex + 1) + "/" + ExportState.chunkTotal;
        }
        ExportState.currentChapter = text;
        updateNotification(text, ExportState.percent());
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
    }

    /**
     * 摘掉悬浮窗。必须在主线程调 —— WindowManager 的 addView/removeView
     * 只允许主线程，从工作线程调会抛 CalledFromWrongThread。
     * 导出可能还会接着跑队列里的下一个任务，所以只在整体结束时才摘。
     */
    private void hideFloating() {
        if (ExportQueue.pendingCount() > 0) {
            // 队列里还有下一个任务，悬浮窗留着继续显示
            main.post(new Runnable() {
                @Override
                public void run() {
                    FloatingWindow.get(ExportService.this).refresh();
                }
            });
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                FloatingWindow.get(ExportService.this).remove();
            }
        });
    }

    /**
     * 任务收尾通知。用独立 id + 独立通道，这样它不会和运行中的前台通知互相顶掉，
     * 而且可以「常驻不自动消失」—— 用户切走去干别的，回来一眼就能看到结果。
     */
    private void notifyDone(boolean hasFailure) {
        if (nm == null) {
            return;
        }
        try {
            int done = ExportState.done;
            int failed = ExportState.failed;
            int skipped = ExportState.skipped;
            boolean stopped = ExportState.stopped;
            String title = ExportState.bookTitle.length() > 0
                    ? ExportState.bookTitle : "导出任务";

            String body;
            String bigText;
            if (stopped && !hasFailure) {
                title = "已停止 · " + title;
                body = "已完成 " + done + "/" + ExportState.total + " 章，随时可以接着跑";
                bigText = body + "\n在导出页点「开始导出」会跳过已完成的部分继续。";
            } else if (hasFailure) {
                title = "导出失败 · " + title;
                body = "已完成 " + done + " 章" + (failed > 0 ? "，失败 " + failed + " 章" : "");
                bigText = body + "\n点开看失败原因，成功的章节已经保住了。";
            } else {
                title = "导出完成 · " + title;
                body = "共 " + done + " 章"
                        + (skipped > 0 ? "（跳过已完成 " + skipped + " 章）" : "")
                        + "　用时 " + WavTool.humanDuration(ExportState.elapsedMs);
                bigText = body + "\n成品已经放到音乐/ListenBook 和下载/ListenBook。";
            }
            body = body + "\n耗时 " + WavTool.humanDuration(ExportState.elapsedMs);

            Intent open = new Intent(this, ExportActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (ExportState.bookId.length() > 0) {
                open.putExtra("bookId", ExportState.bookId);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(this, CHANNEL_DONE_ID);
            } else {
                b = new Notification.Builder(this);
            }
            b.setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(bigText))
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    // 完成通知用默认提示音，用户不看屏幕也能知道跑完了。
                    // 长时间任务只在开头响一次，中间靠 setOnlyAlertOnce 静默。
                    .setDefaults(Notification.DEFAULT_ALL);
            nm.notify(NOTIF_DONE_ID, b.build());
            AppLog.exportEvent("收尾通知已发出: " + title);
        } catch (Throwable t) {
            // 收尾通知发不出去绝不能影响已经跑完的结果
            AppLog.e("ExportService", "收尾通知失败", t);
        }
    }
}
