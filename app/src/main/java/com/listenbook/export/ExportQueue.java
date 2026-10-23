package com.listenbook.export;

import android.content.Context;

import com.listenbook.util.AppLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出任务队列。静态持有，串行执行 —— 同一时刻永远只有一个任务的章在被合成，
 * 因为 TTS 引擎本身就串行，两个一起跑只会互相拖慢，还会互相删 .tmp。
 *
 * 为什么现在要落盘（v4.3 改）：原来是纯内存的，注释写着「App 被杀时无所谓」。
 * 但这个 App 的任务动辄跑几小时，恰恰是最容易被系统回收的场景 ——
 * 进程一死，队列里排着的书凭空消失，用户毫无察觉。合成长任务和短任务
 * 对「被杀」的敏感度差了两个数量级，不能用短任务的标准对付长任务。
 *
 * 落盘方式：整条队列序列化成 JSON 存 SharedPreferences，队列一变就写一次。
 * 不用数据库是因为队列极小（每本书一行），而且恢复只需要「原样读回来」。
 *
 * 所有公开方法都 synchronized —— Service 工作线程和 UI 主线程都会碰它。
 */
public final class ExportQueue {

    public static final int ST_PENDING = 0;
    public static final int ST_RUNNING = 1;
    public static final int ST_DONE = 2;
    public static final int ST_FAILED = 3;
    public static final int ST_CANCELED = 4;

    private static final String PREF_QUEUE = "listenbook_queue";
    private static final String KEY_JOBS = "jobs";
    /** 上次进程退出时有没有任务没跑完 —— 用来判断是不是被系统中断了 */
    private static final String KEY_INTERRUPTED = "interrupted";
    private static final String KEY_INTERRUPT_BOOK = "interruptedBook";
    private static final String KEY_INTERRUPT_TITLE = "interruptedTitle";
    private static final String KEY_INTERRUPT_DONE = "interruptedDone";
    private static final String KEY_INTERRUPT_TOTAL = "interruptedTotal";
    private static final String KEY_INTERRUPT_AT = "interruptedAt";

    private static final List<ExportJob> items = new ArrayList<ExportJob>();
    private static long seq;
    /** 落盘用的 Context。Service 一启动就会调 attach 塞进来。 */
    private static Context appContext;

    private ExportQueue() {
    }

    public static synchronized void attach(Context context) {
        if (context == null) {
            return;
        }
        appContext = context.getApplicationContext();
    }

    static synchronized long currentSeq() {
        return seq;
    }

    static synchronized void restoreSeq(long v) {
        seq = Math.max(seq, v);
    }

    /** 入队。返回 jobId。调用方负责在入队后 startService 拉起服务。 */
    public static synchronized long enqueue(ExportJob job) {
        if (job == null) {
            return 0L;
        }
        seq++;
        job.jobId = seq;
        job.status = ST_PENDING;
        job.enqueuedAt = System.currentTimeMillis();
        items.add(job);
        AppLog.exportEvent("任务入队 #" + job.jobId + " 《" + job.bookTitle + "》 "
                + job.chapterCount() + " 章 · " + job.paramSummary());
        persist();
        return job.jobId;
    }

    // ---------------------------------------------------------------- 落盘

    /** 只落「没跑完的」。跑完的任务没必要存 —— 它已经出结果了。 */
    private static boolean isUnfinished(ExportJob j) {
        return j != null && j.status != ST_DONE
                && j.status != ST_FAILED && j.status != ST_CANCELED;
    }

    private static void persist() {
        if (appContext == null) {
            return;
        }
        try {
            java.util.List<ExportJob> keep = new java.util.ArrayList<ExportJob>();
            for (int i = 0; i < items.size(); i++) {
                if (isUnfinished(items.get(i))) {
                    keep.add(items.get(i));
                }
            }
            android.content.SharedPreferences sp =
                    appContext.getSharedPreferences(PREF_QUEUE, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_JOBS,
                    keep.isEmpty() ? "" : ExportJob.queueToJson(keep)).apply();
        } catch (Throwable t) {
            // 落盘失败不该挡住导出本身，最多下次恢复不了
            AppLog.e("ExportQueue", "队列落盘失败（不影响本次导出）", t);
        }
    }

    /**
     * 从落盘恢复。只在进程刚启动时调一次。
     *
     * 返回恢复出来的任务数。恢复时会打点告诉用户「上次是被中断的」——
     * 静默恢复等于没恢复：用户只会看到导出莫名其妙又开始了。
     */
    public static synchronized int restoreFromDisk() {
        if (appContext == null || items.size() > 0) {
            return 0;
        }
        try {
            android.content.SharedPreferences p =
                    appContext.getSharedPreferences(PREF_QUEUE, Context.MODE_PRIVATE);
            boolean interrupted = p.getBoolean(KEY_INTERRUPTED, false);
            String ibook = p.getString(KEY_INTERRUPT_BOOK, "");
            String ititle = p.getString(KEY_INTERRUPT_TITLE, "");
            int idone = p.getInt(KEY_INTERRUPT_DONE, 0);
            int itotal = p.getInt(KEY_INTERRUPT_TOTAL, 0);
            long iat = p.getLong(KEY_INTERRUPT_AT, 0L);

            if (interrupted) {
                StringBuilder sb = new StringBuilder();
                sb.append("⚠ 上次的导出被系统中断了");
                if (ititle.length() > 0) {
                    sb.append("（").append(ititle);
                    if (idone > 0 && itotal > 0) {
                        sb.append("，跑到 ").append(idone).append('/').append(itotal);
                    }
                    sb.append("）");
                }
                sb.append("\n已完成的章节都还在，接着跑会跳过它们，不会重复合成。");
                AppLog.exportEvent(sb.toString());
                // 标记留给 UI 弹提示；读完就清，避免下次又说同样的话
                p.edit().putBoolean(KEY_INTERRUPTED, false).apply();
                ExportState.interruptedBook = ibook;
                ExportState.interruptedTitle = ititle;
                ExportState.interruptedDone = idone;
                ExportState.interruptedTotal = itotal;
                ExportState.interruptedAt = iat;
                ExportState.wasInterrupted = true;
            }

            java.util.List<ExportJob> back = ExportJob.queueFromJson(
                    p.getString(KEY_JOBS, ""));
            if (back == null || back.isEmpty()) {
                return 0;
            }
            for (int i = 0; i < back.size(); i++) {
                // 恢复的一律置回 PENDING：它上次是在RUNNING 状态被杀死的，
                // 留着 RUNNING 会让队列认为还有人在跑，永远轮不到它。
                ExportJob j = back.get(i);
                j.status = ST_PENDING;
                items.add(j);
            }
            AppLog.exportEvent("已从落盘恢复 " + back.size() + " 个未完成任务");
            return back.size();
        } catch (Throwable t) {
            AppLog.e("ExportQueue", "队列恢复失败", t);
            return 0;
        }
    }

    /**
     * 记下「进程要没了但还有任务没跑完」。
     * onDestroy 和 Worker 收尾都会调，这样下次启动就能知道是被中断而不是正常结束。
     */
    public static synchronized void markInterrupted(String bookId, String title,
            int done, int total) {
        if (appContext == null) {
            return;
        }
        boolean hasUnfinished = false;
        for (int i = 0; i < items.size(); i++) {
            if (isUnfinished(items.get(i))) {
                hasUnfinished = true;
                break;
            }
        }
        if (!hasUnfinished) {
            // 都跑完了，不需要标记中断，也顺手把旧的清掉
            clearInterrupted();
            return;
        }
        try {
            appContext.getSharedPreferences(PREF_QUEUE, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_INTERRUPTED, true)
                    .putString(KEY_INTERRUPT_BOOK, bookId == null ? "" : bookId)
                    .putString(KEY_INTERRUPT_TITLE, title == null ? "" : title)
                    .putInt(KEY_INTERRUPT_DONE, done)
                    .putInt(KEY_INTERRUPT_TOTAL, total)
                    .putLong(KEY_INTERRUPT_AT, System.currentTimeMillis())
                    .apply();
            persist();
        } catch (Throwable t) {
            AppLog.e("ExportQueue", "标记中断失败", t);
        }
    }

    public static synchronized void clearInterrupted() {
        if (appContext == null) {
            return;
        }
        try {
            appContext.getSharedPreferences(PREF_QUEUE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_INTERRUPTED, false).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 取出下一个待跑任务并置 RUNNING。没有就返回 null。 */
    public static synchronized ExportJob takeNext() {
        for (int i = 0; i < items.size(); i++) {
            ExportJob j = items.get(i);
            if (j.status == ST_PENDING) {
                j.status = ST_RUNNING;
                return j;
            }
        }
        return null;
    }

    /**
     * 收尾一个任务。同一本书再排队的任务不受影响。
     *
     * @param failed true 表示该书至少有一章失败（不是任务崩溃）
     */
    public static synchronized void finish(ExportJob job, boolean failed, String summary) {
        if (job == null) {
            return;
        }
        job.status = failed ? ST_FAILED : ST_DONE;
        job.resultSummary = summary == null ? "" : summary;
        AppLog.exportEvent("任务收尾 #" + job.jobId + " 状态=" + job.status + " " + job.resultSummary);
        persist();
    }

    public static synchronized void cancelAt(int index) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        ExportJob j = items.get(index);
        if (j.status == ST_RUNNING) {
            return; // 正在跑的用 stop 按钮停，这里只管排队的
        }
        j.status = ST_CANCELED;
        AppLog.exportEvent("取消排队任务 #" + j.jobId + " 《" + j.bookTitle + "》");
        persist();
    }

    /** 清掉所有还没开跑的任务（正在跑的那个留着）。 */
    public static synchronized int clearPending() {
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            ExportJob j = items.get(i);
            if (j.status == ST_PENDING) {
                j.status = ST_CANCELED;
                n++;
            }
        }
        if (n > 0) {
            AppLog.exportEvent("清空待执行队列，移除 " + n + " 个任务");
        }
        persist();
        return n;
    }

    /** 清掉已结束的任务记录。 */
    public static synchronized int clearFinished() {
        int removed = 0;
        List<ExportJob> keep = new ArrayList<ExportJob>();
        for (int i = 0; i < items.size(); i++) {
            ExportJob j = items.get(i);
            if (j.status == ST_DONE || j.status == ST_FAILED || j.status == ST_CANCELED) {
                removed++;
            } else {
                keep.add(j);
            }
        }
        if (removed > 0) {
            items.clear();
            items.addAll(keep);
        }
        persist();
        return removed;
    }

    public static synchronized List<ExportJob> snapshot() {
        return new ArrayList<ExportJob>(items);
    }

    public static synchronized int pendingCount() {
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).status == ST_PENDING) {
                n++;
            }
        }
        return n;
    }

    public static synchronized int finishedCount() {
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            int s = items.get(i).status;
            if (s == ST_DONE || s == ST_FAILED || s == ST_CANCELED) {
                n++;
            }
        }
        return n;
    }

    public static synchronized int totalCount() {
        return items.size();
    }

    public static synchronized boolean hasPending() {
        return pendingCount() > 0;
    }

    /** 正在跑的任务，没有返回 null。 */
    public static synchronized ExportJob running() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).status == ST_RUNNING) {
                return items.get(i);
            }
        }
        return null;
    }

    /**
     * 队列跑空后清理：把已结束的任务挪出列表，避免列表无限制增长。
     * 只在没有任何 RUNNING / PENDING 时调用。
     */
    public static synchronized void trim() {
        if (pendingCount() > 0 || running() != null) {
            return;
        }
        clearFinished();
    }

    public static String statusText(int status) {
        switch (status) {
            case ST_PENDING:
                return "排队中";
            case ST_RUNNING:
                return "进行中";
            case ST_DONE:
                return "已完成";
            case ST_FAILED:
                return "有失败";
            case ST_CANCELED:
                return "已取消";
            default:
                return "?";
        }
    }

    /** Service 起来之后如果发现队列空（比如进程重启），用它判断要不要马上停。 */
    public static synchronized String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("队列：").append(pendingCount()).append(" 待跑 / ")
                .append(items.size()).append(" 总数");
        ExportJob r = running();
        if (r != null) {
            sb.append(" · 当前 #").append(r.jobId).append(' ').append(r.bookTitle);
        }
        return sb.toString();
    }
}
