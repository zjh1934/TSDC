package com.listenbook.export;

import android.content.Context;
import android.content.Intent;

import com.listenbook.util.AppLog;
import com.listenbook.util.Settings;

/** 一次导出任务的完整描述。UI 构造它，交给 ExportService 执行。 */
public class ExportJob {

    /** 队列里的稳定 id。递增序号，不是 bookId —— 同一本书可以排两次。 */
    public long jobId;

    // ---------------- 目标 ----------------
    public String bookId = "";
    public String bookTitle = "";
    public int[] indices = new int[0];
    public boolean syncPublic;

    // ---------------- 合成参数 ----------------
    /** 空 = 系统默认引擎 */
    public String enginePkg = "";
    /** 自定义输出目录的 SAF tree Uri 字符串；空表示只留在 App 专属目录 */
    public String customTree = "";
    /** 空 = 引擎自选 */
    public String voiceName = "";
    public float rate = 1.0f;
    public float pitch = 1.0f;
    /** Settings.LANG_AUTO / LANG_ZH / LANG_EN */
    public String languageMode = Settings.LANG_AUTO;
    /** languageMode = auto 时被忽略 */
    public String localeTag = "";
    /** 单块字数预算 */
    public int chunkBudget = 3500;
    /** 超时余量倍数 */
    public int timeoutSlack = 3;
    /** 单块最大重试次数 */
    public int maxAttempts = 2;

    // ---------------- 状态 ----------------
    public int status = ExportQueue.ST_PENDING;
    public long enqueuedAt;
    public String resultSummary = "";

    public ExportJob() {
    }

    public ExportJob(String bookId, String bookTitle, int[] indices, boolean syncPublic) {
        this.bookId = bookId;
        this.bookTitle = bookTitle == null ? "" : bookTitle;
        this.indices = indices == null ? new int[0] : indices;
        this.syncPublic = syncPublic;
    }

    public int chapterCount() {
        return indices == null ? 0 : indices.length;
    }

    /** 从全局偏好里把合成参数灌进来，UI 不必逐字段赋值。 */
    public ExportJob applyDefaults(Context context) {
        this.enginePkg = Settings.enginePkg(context);
        this.voiceName = Settings.voiceName(context);
        String tree = Settings.customTreeUri(context);
        this.customTree = tree == null ? "" : tree;
        this.rate = Settings.rate(context);
        this.pitch = Settings.pitch(context);
        this.languageMode = Settings.languageMode(context);
        this.localeTag = Settings.localeTag(context);
        this.chunkBudget = Settings.chunkBudget(context);
        this.timeoutSlack = Settings.timeoutSlack(context);
        this.maxAttempts = Settings.maxAttempts(context);
        return this;
    }

    public String paramSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("语速 ").append(String.format(java.util.Locale.CHINA, "%.1fx",
                Float.valueOf(rate)));
        sb.append(" · 音调 ").append(String.format(java.util.Locale.CHINA, "%.1f",
                Float.valueOf(pitch)));
        sb.append(" · ").append(languageText());
        if (voiceName != null && voiceName.length() > 0) {
            sb.append(" · 音色 ").append(voiceName);
        }
        return sb.toString();
    }

    public String languageText() {
        if (Settings.LANG_ZH.equals(languageMode)) {
            return "中文";
        }
        if (Settings.LANG_EN.equals(languageMode)) {
            return "英文";
        }
        return "自动识别";
    }

    public Intent toIntent(Context context) {
        Intent i = new Intent(context, ExportService.class);
        i.setAction(ExportService.ACTION_START);
        i.putExtra(ExportService.EXTRA_BOOK_ID, bookId);
        i.putExtra(ExportService.EXTRA_INDICES, indices);
        i.putExtra(ExportService.EXTRA_SYNC, syncPublic);
        i.putExtra(ExportService.EXTRA_ENGINE, enginePkg);
        i.putExtra(ExportService.EXTRA_VOICE, voiceName);
        i.putExtra(ExportService.EXTRA_RATE, rate);
        i.putExtra(ExportService.EXTRA_PITCH, pitch);
        i.putExtra(ExportService.EXTRA_LANG_MODE, languageMode);
        i.putExtra(ExportService.EXTRA_LOCALE, localeTag);
        i.putExtra(ExportService.EXTRA_CHUNK, chunkBudget);
        i.putExtra(ExportService.EXTRA_SLACK, timeoutSlack);
        i.putExtra(ExportService.EXTRA_ATTEMPTS, maxAttempts);
        i.putExtra(ExportService.EXTRA_TITLE, bookTitle);
        i.putExtra(ExportService.EXTRA_JOB_ID, jobId);
        i.putExtra(ExportService.EXTRA_CUSTOM_TREE, customTree);
        return i;
    }

    public static ExportJob fromIntent(Intent intent) {
        ExportJob j = new ExportJob();
        j.bookId = intent.getStringExtra(ExportService.EXTRA_BOOK_ID);
        j.bookTitle = intent.getStringExtra(ExportService.EXTRA_TITLE);
        int[] idx = intent.getIntArrayExtra(ExportService.EXTRA_INDICES);
        j.indices = idx == null ? new int[0] : idx;
        j.syncPublic = intent.getBooleanExtra(ExportService.EXTRA_SYNC, false);
        String engine = intent.getStringExtra(ExportService.EXTRA_ENGINE);
        j.enginePkg = engine == null ? "" : engine;
        String voice = intent.getStringExtra(ExportService.EXTRA_VOICE);
        j.voiceName = voice == null ? "" : voice;
        j.rate = intent.getFloatExtra(ExportService.EXTRA_RATE, 1.0f);
        j.pitch = intent.getFloatExtra(ExportService.EXTRA_PITCH, 1.0f);
        String mode = intent.getStringExtra(ExportService.EXTRA_LANG_MODE);
        j.languageMode = mode == null ? Settings.LANG_AUTO : mode;
        String loc = intent.getStringExtra(ExportService.EXTRA_LOCALE);
        j.localeTag = loc == null ? "" : loc;
        j.chunkBudget = intent.getIntExtra(ExportService.EXTRA_CHUNK, 3500);
        j.timeoutSlack = intent.getIntExtra(ExportService.EXTRA_SLACK, 3);
        j.maxAttempts = intent.getIntExtra(ExportService.EXTRA_ATTEMPTS, 2);
        j.jobId = intent.getLongExtra(ExportService.EXTRA_JOB_ID, 0L);
        String tree = intent.getStringExtra(ExportService.EXTRA_CUSTOM_TREE);
        j.customTree = tree == null ? "" : tree;
        return j;
    }

    /** 拉起服务。服务忙时它只会取队列里已有的任务，不会重复入队。 */
    public static void start(Context context, ExportJob job) {
        Intent i = job.toIntent(context);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            AppLog.e("ExportJob", "启动导出服务失败", e);
        }
    }

    public static void requestStop(Context context) {
        Intent i = new Intent(context, ExportService.class);
        i.setAction(ExportService.ACTION_STOP);
        try {
            context.startService(i);
        } catch (Exception ignored) {
            // 服务没在跑，忽略
        }
    }

    public static void requestClearQueue(Context context) {
        Intent i = new Intent(context, ExportService.class);
        i.setAction(ExportService.ACTION_CLEAR_QUEUE);
        try {
            context.startService(i);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 持久化
    //
    // 为什么需要：队列原来只在内存里，注释写着「App 被杀时无所谓」。
    // 但这个 App 的任务动辄跑几小时，正是最容易被系统回收的场景 ——
    // 一旦进程死了，队列里排着的书就凭空消失，用户毫无察觉，
    // 回来只看到「停了」，不知道是被系统杀的。
    //
    // 不新建数据库：直接把 Intent 的 extra 打成一条 JSON 存 SharedPreferences。
    // 复用已有的 toIntent/fromIntent，不为持久化再写一遍字段映射。

    private static final org.json.JSONObject toJson(ExportJob j) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("jobId", j.jobId);
        o.put("bookId", j.bookId);
        o.put("bookTitle", j.bookTitle);
        o.put("syncPublic", j.syncPublic);
        o.put("enginePkg", j.enginePkg);
        o.put("customTree", j.customTree);
        o.put("voiceName", j.voiceName);
        o.put("rate", Double.valueOf(j.rate));
        o.put("pitch", Double.valueOf(j.pitch));
        o.put("languageMode", j.languageMode);
        o.put("localeTag", j.localeTag);
        o.put("chunkBudget", j.chunkBudget);
        o.put("timeoutSlack", j.timeoutSlack);
        o.put("maxAttempts", j.maxAttempts);
        o.put("status", j.status);
        o.put("enqueuedAt", j.enqueuedAt);
        o.put("resultSummary", j.resultSummary);
        org.json.JSONArray a = new org.json.JSONArray();
        int[] idx = j.indices;
        if (idx != null) {
            for (int k = 0; k < idx.length; k++) {
                a.put(idx[k]);
            }
        }
        o.put("indices", a);
        return o;
    }

    private static ExportJob fromJson(org.json.JSONObject o) {
        ExportJob j = new ExportJob();
        j.jobId = o.optLong("jobId", 0L);
        j.bookId = o.optString("bookId", "");
        j.bookTitle = o.optString("bookTitle", "");
        j.syncPublic = o.optBoolean("syncPublic", false);
        j.enginePkg = o.optString("enginePkg", "");
        j.customTree = o.optString("customTree", "");
        j.voiceName = o.optString("voiceName", "");
        j.rate = (float) o.optDouble("rate", 1.0d);
        j.pitch = (float) o.optDouble("pitch", 1.0d);
        j.languageMode = o.optString("languageMode", Settings.LANG_AUTO);
        j.localeTag = o.optString("localeTag", "");
        j.chunkBudget = o.optInt("chunkBudget", 3500);
        j.timeoutSlack = o.optInt("timeoutSlack", 3);
        j.maxAttempts = o.optInt("maxAttempts", 2);
        j.status = o.optInt("status", ExportQueue.ST_PENDING);
        j.enqueuedAt = o.optLong("enqueuedAt", 0L);
        j.resultSummary = o.optString("resultSummary", "");
        org.json.JSONArray a = o.optJSONArray("indices");
        int[] idx = new int[a == null ? 0 : a.length()];
        for (int k = 0; k < idx.length; k++) {
            idx[k] = a.optInt(k);
        }
        j.indices = idx;
        return j;
    }

    /** 打进队列时调：把整个队列写成 JSON 文本返回，交给调用方落盘 */
    public static String queueToJson(java.util.List<ExportJob> list) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int i = 0; i < list.size(); i++) {
                arr.put(toJson(list.get(i)));
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("seq", Long.valueOf(seqForSave()));
            root.put("jobs", arr);
            return root.toString();
        } catch (Exception e) {
            AppLog.e("ExportJob", "队列序列化失败", e);
            return null;
        }
    }

    private static long seqForSave() {
        return ExportQueue.currentSeq();
    }

    /** 从落盘文本读回队列。读不出来就返回 null，调用方按「没有历史队列」处理。 */
    public static java.util.List<ExportJob> queueFromJson(String raw) {
        if (raw == null || raw.length() == 0) {
            return null;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(raw);
            org.json.JSONArray arr = root.optJSONArray("jobs");
            if (arr == null) {
                return null;
            }
            java.util.List<ExportJob> out = new java.util.ArrayList<ExportJob>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(fromJson(o));
                }
            }
            ExportQueue.restoreSeq(root.optLong("seq", 0L));
            AppLog.i("ExportJob", "从落盘读回 " + out.size() + " 个任务");
            return out;
        } catch (Exception e) {
            // 落盘数据被截断或格式变了都属可能，不能因此让 App 起不来
            AppLog.e("ExportJob", "队列反序列化失败，按空队列处理", e);
            return null;
        }
    }
}
