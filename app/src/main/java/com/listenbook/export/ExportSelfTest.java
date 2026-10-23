package com.listenbook.export;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.listenbook.tts.TtsCatalog;
import com.listenbook.util.AppLog;
import com.listenbook.util.Io;
import com.listenbook.util.MediaStorePublisher;
import com.listenbook.util.Settings;
import com.listenbook.util.WavTool;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 导出自检：用一句话真刀真枪合成一个 WAV，把每一步的结果摊开给人看。
 *
 * 为什么需要它：synthesizeToFile 的失败方式太多了 ——
 *   引擎没装 / 语音数据缺失 / 引擎不回调 / 返回 SUCCESS 却不落文件 /
 *   文件写出来了但躺在 Android/data 里文件管理器看不见。
 * 这些失败在界面上长得都差不多（「点一下就完成，文件夹空的」），
 * 光看 UI 根本分不清是哪种。自检就是把它们一次性全抖出来。
 *
 * 跑在自带 Looper 的 HandlerThread 上：TextToSpeech 的回调要 Looper，
 * 裸 Thread 里 new 会直接抛「Can't create handler inside thread」。
 */
public final class ExportSelfTest {

    private static final String SAMPLE = "听书导出自检，一二三四五六七八九十。";
    private static final String UID = "selftest";
    private static final String DIR_NAME = "_selftest";

    public interface Callback {
        void onDone(String report);
    }

    private ExportSelfTest() {
    }

    /**
     * 自检是否正在跑。
     *
     * 存在的理由：自检和试听都要独占系统 TTS 引擎，两者同时跑会互相打断。
     * 用一个静态标志让 UI 能把试听按钮锁住，而不是等用户点了才发现没声音。
     */
    private static volatile boolean busy;

    public static boolean isBusy() {
        return busy;
    }

    public static void run(final Context context, final Callback callback) {
        busy = true;
        final HandlerThread thread = new HandlerThread("tts-selftest");
        thread.start();
        final Handler worker = new Handler(thread.getLooper());
        final Handler main = new Handler(Looper.getMainLooper());
        worker.post(new Runnable() {
            @Override
            public void run() {
                String report;
                try {
                    report = doTest(context.getApplicationContext());
                } catch (Throwable t) {
                    // 自检绝不能把异常带崩整个页面：这里它只是给人看的信息
                    AppLog.e("SelfTest", "自检过程抛异常", t);
                    report = "自检过程中抛出异常：\n" + t;
                }
                final String text = report;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        thread.quit();
                        busy = false;
                        if (callback != null) {
                            callback.onDone(text);
                        }
                    }
                });
            }
        });
    }

    private static String doTest(Context context) {
        StringBuilder sb = new StringBuilder();
        boolean fatal = false;

        // ---------- 1. 引擎清点 ----------
        sb.append("【一、TTS 引擎】\n");
        List<TtsCatalog.Engine> engines = TtsCatalog.engines(context);
        String enginePkg = Settings.enginePkg(context);
        String def = TtsCatalog.systemDefaultEngine(context);
        if (engines.isEmpty()) {
            sb.append("系统里一个 TTS 引擎都没有。装一个再导出。\n");
            return sb.append("\n结论：导出不可用 —— 缺引擎。").toString();
        }
        sb.append("已装 ").append(engines.size()).append(" 个引擎\n");
        for (int i = 0; i < engines.size(); i++) {
            TtsCatalog.Engine e = engines.get(i);
            sb.append("  · ").append(e.label)
                    .append(e.systemDefault ? "（系统默认）" : "").append('\n');
        }
        String using = enginePkg == null || enginePkg.length() == 0 ? def : enginePkg;
        sb.append("本次用：").append(TtsCatalog.engineLabel(context, using)).append('\n');

        // ---------- 2. 初始化 ----------
        final CountDownLatch initLatch = new CountDownLatch(1);
        final int[] initStatus = new int[]{-1};
        TextToSpeech tts = null;
        long t0 = System.currentTimeMillis();
        try {
            TextToSpeech.OnInitListener onInit = new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    initStatus[0] = status;
                    initLatch.countDown();
                }
            };
            if (enginePkg != null && enginePkg.length() > 0) {
                tts = new TextToSpeech(context, onInit, enginePkg);
            } else {
                tts = new TextToSpeech(context, onInit);
            }
        } catch (Exception e) {
            AppLog.e("SelfTest", "创建 TextToSpeech 就抛异常", e);
            sb.append("\n创建 TTS 实例失败：").append(e.getMessage()).append('\n');
            return sb.append("\n结论：导出不可用 —— 引擎起不来。").toString();
        }

        boolean inited;
        try {
            inited = initLatch.await(20L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            inited = false;
            Thread.currentThread().interrupt();
        }
        long initCost = System.currentTimeMillis() - t0;
        sb.append('\n').append("【二、引擎初始化】\n");
        if (!inited) {
            sb.append("等了 20 秒都没等到 onInit 回调，引擎八成是废的。\n");
            shutdown(tts);
            return sb.append("\n结论：导出不可用 —— 引擎无响应。").toString();
        }
        if (initStatus[0] != TextToSpeech.SUCCESS) {
            sb.append("onInit 返回 ").append(initStatus[0]).append("（非 SUCCESS）。\n");
            shutdown(tts);
            return sb.append("\n结论：导出不可用 —— 引擎初始化失败。").toString();
        }
        sb.append("成功，耗时 ").append(initCost).append("ms\n");

        // ---------- 3. 中文语音数据 ----------
        sb.append('\n').append("【三、中文语音】\n");
        int lang;
        try {
            lang = tts.setLanguage(Locale.CHINESE);
        } catch (Exception e) {
            lang = TextToSpeech.LANG_MISSING_DATA;
        }
        sb.append("setLanguage(中文) = ").append(langText(lang)).append('\n');
        if (lang == TextToSpeech.LANG_MISSING_DATA) {
            sb.append("引擎没装中文语音包，去系统设置里下载语音数据。\n");
            fatal = true;
        }
        float rate = Settings.rate(context);
        float pitch = Settings.pitch(context);
        try {
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
        } catch (Exception ignored) {
        }
        sb.append("语速 ").append(rate).append(" · 音调 ").append(pitch).append('\n');

        // ---------- 4. 真合成一次 ----------
        sb.append('\n').append("【四、合成测试】\n");
        sb.append("文本：").append(SAMPLE).append("（").append(SAMPLE.length()).append(" 字）\n");

        final CountDownLatch doneLatch = new CountDownLatch(1);
        final int[] outcome = new int[]{0};  // 0=未回调 1=onDone -1=onError
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    if (UID.equals(utteranceId)) {
                        outcome[0] = 1;
                        doneLatch.countDown();
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    if (UID.equals(utteranceId)) {
                        outcome[0] = -1;
                        doneLatch.countDown();
                    }
                }

                @Override
                public void onStop(String utteranceId, boolean interrupted) {
                    if (UID.equals(utteranceId)) {
                        outcome[0] = -1;
                        doneLatch.countDown();
                    }
                }
            });
        } catch (Exception e) {
            sb.append("挂不上进度监听：").append(e.getMessage()).append('\n');
        }

        File dir = new File(ExportStore.baseDir(context), DIR_NAME);
        dir.mkdirs();
        File out = new File(dir, "selftest.wav");
        Io.deleteQuietly(out);

        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UID);
        int call = TextToSpeech.ERROR;
        try {
            call = tts.synthesizeToFile(SAMPLE, params, out, UID);
        } catch (Exception e) {
            AppLog.e("SelfTest", "synthesizeToFile 抛异常", e);
        }
        sb.append("synthesizeToFile 返回：").append(call == TextToSpeech.SUCCESS
                ? "SUCCESS" : "ERROR(" + call + ")").append('\n');
        if (call != TextToSpeech.SUCCESS) {
            sb.append("引擎直接拒绝合成。换一个引擎试试。\n");
            shutdown(tts);
            return sb.append("\n结论：导出不可用 —— 引擎拒绝合成。").toString();
        }

        boolean back;
        try {
            back = doneLatch.await(60L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            back = false;
            Thread.currentThread().interrupt();
        }
        sb.append("完成回调：").append(!back ? "60 秒没等到（引擎不回调，但文件可能还是写出来的）"
                : (outcome[0] == 1 ? "收到 onDone" : "收到 onError")).append('\n');

        sb.append('\n').append("【五、产出文件】\n");
        sb.append("路径：").append(out.getAbsolutePath()).append('\n');
        if (!out.exists()) {
            sb.append("文件根本不存在 —— 引擎返回了 SUCCESS 却没落文件，这是它的锅。\n");
            shutdown(tts);
            return sb.append("\n结论：导出不可用 —— 引擎不落文件，换引擎。").toString();
        }
        boolean valid = WavTool.isValid(out);
        WavTool.Info info = WavTool.probe(out);
        sb.append("大小：").append(WavTool.humanSize(out.length()))
                .append(" · 时长：").append(WavTool.humanDuration(info.millis())).append('\n');
        sb.append("WAV 头：").append(valid ? "合法" : "不完整（文件是坏的）").append('\n');
        if (!valid || out.length() < 1024L) {
            sb.append("文件太小或头不对，这次合成等于没成。\n");
            fatal = true;
        }

        // ---------- 5. 能不能被看见 ----------
        sb.append('\n').append("【六、文件在哪】\n");
        sb.append("App 专属目录：").append(ExportStore.baseDir(context).getAbsolutePath())
                .append("\n  （Android 11+ 很多文件管理器看不到这里，不代表没导出）\n");
        String published = null;
        try {
            published = MediaStorePublisher.publish(context, out, "自检");
        } catch (Exception e) {
            AppLog.w("SelfTest", "发布公共目录异常：" + e.getMessage());
        }
        sb.append("公共音乐目录：").append(MediaStorePublisher.publicDirHint("自检")).append('\n');
        sb.append("发布到公共目录：").append(published != null ? "成功" : "失败").append('\n');
        if (published == null) {
            sb.append("  → 去文件管理器找「音乐/Music」里的 ListenBook 目录时用这个路径。\n");
        }

        Io.deleteQuietly(out);

        // ---------- 6. 参数能力实测（必须在 shutdown 之前，复用同一个实例省一次引擎冷启动） ----------
        sb.append('\n').append("【七、语速 / 语调 参数能力实测】\n");
        sb.append(paramProbe(context, tts, dir));
        shutdown(tts);

        sb.append('\n').append("【结论】\n");
        if (fatal) {
            sb.append("有问题：合成出来的文件不对劲，按上面的红字处理。\n");
        } else {
            sb.append("正常：TTS 能合成、文件能落盘。导出该出得来。\n");
            sb.append("如果正式导出仍然「秒完成且没文件」，把诊断日志导出给我。\n");
        }
        return sb.toString();
    }

    /**
     * 语速 / 语调到底有没有真的生效 —— 用产物时长说话，而不是看 API 有没有返回成功。
     *
     * 判定思路：
     *   setSpeechRate() / setPitch() 返回 void，调用成功只说明「请求送到了」，
     *   完全不代表引擎照办了。所以同一段文本在多组参数下各合成一次，
     *   读 WAV 头算真实时长：
     *     · 语速 0.6x vs 2.0x：理论上时长比接近 3 倍。差异 >= 2 倍才算真的支持，
     *       1.3~2 倍算「部分支持」（引擎多半是分段变速或有下限），
     *       小于 1.3 倍基本可以判定引擎忽略了它。
     *     · 音调：正常引擎只改基频不改语速，时长应当基本一致（允许 ±15% 抖动）。
     *       时长变了不一定是 bug，有些引擎会把音调实现在重采样上。所以音调这一栏
     *       只判定「调用被接受 + 音频正常产出」，不给「有效/无效」的武断结论。
     */
    private static String paramProbe(Context context, TextToSpeech tts, File dir) {
        StringBuilder sb = new StringBuilder();
        // 固定文本：必须是同一段，否则时长差异分不清是参数造成的还是文本造成的
        final String text = SAMPLE + "语速 1.0 倍，语调 1.0。";
        float origRate = 1.0f;
        float origPitch = 1.0f;
        try {
            origRate = Settings.rate(context);
            origPitch = Settings.pitch(context);
        } catch (Exception ignored) {
        }

        float[] rates = new float[]{0.6f, 1.0f, 2.0f};
        float[] pitches = new float[]{0.6f, 1.0f, 1.8f};

        sb.append("同一段文本（").append(text.length()).append(" 字）在不同参数下各合成一次，"
                + "比对产物的音频时长。\n");
        sb.append("你当前界面上的设置：语速 ").append(origRate)
                .append(" · 语调 ").append(origPitch).append("（下面几组是固定的对照组，跟当前设置无关）\n");

        // ---- 语速组 ----
        long[] rateMs = new long[3];
        boolean[] rateOk = new boolean[3];
        sb.append('\n').append("① 语速（语调固定 1.0）\n");
        sb.append("  语速参数 | 合成 | 产物大小 | 音频时长 | 相对 1.0x\n");
        for (int i = 0; i < rates.length; i++) {
            float r = rates[i];
            rateOk[i] = trySetRate(tts, r);
            long[] ms = synthOnce(tts, text, dir, "rate_" + i + ".wav");
            rateMs[i] = ms[0];
            sb.append(String.format(Locale.US, "  %8.1fx | %s | %9s | %8s | %s\n",
                    Float.valueOf(r),
                    rateOk[i] ? "已接受" : "引擎拒绝",
                    ms[1] > 0 ? WavTool.humanSize(ms[1]) : "无",
                    ms[0] > 0 ? WavTool.humanDuration(ms[0]) : "失败",
                    ratioText(rateMs, i)));
        }

        // ---- 音调组 ----
        long[] pitchMs = new long[3];
        boolean[] pitchOk = new boolean[3];
        trySetRate(tts, 1.0f);
        sb.append('\n').append("② 语调（语速固定 1.0）\n");
        sb.append("  语调参数 | 合成 | 产物大小 | 音频时长 | 相对 1.0x\n");
        for (int i = 0; i < pitches.length; i++) {
            float p = pitches[i];
            pitchOk[i] = trySetPitch(tts, p);
            long[] ms = synthOnce(tts, text, dir, "pitch_" + i + ".wav");
            pitchMs[i] = ms[0];
            sb.append(String.format(Locale.US, "  %8.1f | %s | %9s | %8s | %s\n",
                    Float.valueOf(p),
                    pitchOk[i] ? "已接受" : "引擎拒绝",
                    ms[1] > 0 ? WavTool.humanSize(ms[1]) : "无",
                    ms[0] > 0 ? WavTool.humanDuration(ms[0]) : "失败",
                    ratioText(pitchMs, i)));
        }

        // 收尾：把参数还原成用户自己的设置。这里绝不能留下对照组留下的值，
        // 否则自检跑完导出会用一个用户从没选过的语速合成整本书。
        trySetRate(tts, origRate);
        trySetPitch(tts, origPitch);

        // ---- 结论 ----
        sb.append('\n').append("实测结论：\n");
        boolean allRateOk = rateMs[0] > 0 && rateMs[1] > 0 && rateMs[2] > 0;
        if (!allRateOk) {
            sb.append("· 语速：对照组里有 ")
                    .append(failedCount(rateMs))
                    .append(" 次没合成出来，这一项测不了。引擎多半不稳定或语音数据不全。\n");
        } else {
            double ratio = (double) rateMs[0] / (double) rateMs[2];
            sb.append(String.format(Locale.US,
                    "· 语速：0.6x 念了 %s，2.0x 念了 %s，相差 %.2f 倍。%s\n",
                    WavTool.humanDuration(rateMs[0]), WavTool.humanDuration(rateMs[2]),
                    Double.valueOf(ratio), rateVerdict(ratio)));
        }
        boolean allPitchOk = pitchMs[0] > 0 && pitchMs[1] > 0 && pitchMs[2] > 0;
        if (!allPitchOk) {
            sb.append("· 语调：对照组里有 ").append(failedCount(pitchMs))
                    .append(" 次没合成出来，这一项测不了。\n");
        } else {
            boolean callsOk = true;
            for (int i = 0; i < pitchOk.length; i++) {
                if (!pitchOk[i]) {
                    callsOk = false;
                }
            }
            sb.append("· 语调：三档都调用成功且音频正常产出。");
            sb.append("时长分别为 ")
                    .append(WavTool.humanDuration(pitchMs[0])).append(" / ")
                    .append(WavTool.humanDuration(pitchMs[1])).append(" / ")
                    .append(WavTool.humanDuration(pitchMs[2]))
                    .append("。语调正常只改音高不改语速，时长接近属正常；");
            if (!callsOk) {
                sb.append("但有引擎直接拒绝了参数调用，界面上的这项设置对它无效。");
            }
            sb.append("请以你实际听到的高低为准。\n");
        }
        sb.append("\n对照组文件已删除，别在「音乐/Music」里找它们。\n");
        return sb.toString();
    }

    private static String rateVerdict(double ratio) {
        if (ratio >= 2.0d) {
            return "这个引擎确实支持调速，界面上的语速设置有效。";
        }
        if (ratio >= 1.3d) {
            return "这个引擎只部分支持调速（多半是分段变速或设了下限），"
                    + "界面上的语速设置有效但不完全线性。";
        }
        return "这个引擎基本忽略了语速参数 —— 界面上的语速设置对它无效，别指望它。";
    }

    private static String ratioText(long[] ms, int i) {
        if (ms[i] <= 0 || ms.length < 2 || ms[1] <= 0) {
            return "—";
        }
        return String.format(Locale.US, "%.2fx", Double.valueOf((double) ms[i] / (double) ms[1]));
    }

    private static int failedCount(long[] ms) {
        int n = 0;
        for (int i = 0; i < ms.length; i++) {
            if (ms[i] <= 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 合成一次并读回时长。
     *
     * @return {时长ms, 文件字节数}；失败时都是 0
     */
    private static long[] synthOnce(TextToSpeech tts, String text, File dir, String name) {
        long[] out = new long[]{0L, 0L};
        final CountDownLatch latch = new CountDownLatch(1);
        final String uid = "probe:" + name;
        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    if (uid.equals(utteranceId)) {
                        latch.countDown();
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    if (uid.equals(utteranceId)) {
                        latch.countDown();
                    }
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    if (uid.equals(utteranceId)) {
                        latch.countDown();
                    }
                }

                @Override
                public void onStop(String utteranceId, boolean interrupted) {
                    if (uid.equals(utteranceId)) {
                        latch.countDown();
                    }
                }
            });
        } catch (Exception e) {
            AppLog.w("SelfTest", "参数实测挂监听失败：" + e.getMessage());
        }

        File f = new File(dir, name);
        Io.deleteQuietly(f);
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid);
        int call;
        try {
            call = tts.synthesizeToFile(text, params, f, uid);
        } catch (Exception e) {
            call = TextToSpeech.ERROR;
        }
        if (call == TextToSpeech.SUCCESS) {
            try {
                // 不等回调也继续：有些引擎根本不回调但文件照样写好了，
                // 早判失败会把「文件其实在」的情况误报成引擎不可用。
                latch.await(60L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (f.isFile() && f.length() > 0L) {
            out[0] = WavTool.probe(f).millis();
            out[1] = f.length();
        }
        Io.deleteQuietly(f);
        return out;
    }

    private static boolean trySetRate(TextToSpeech tts, float r) {
        try {
            tts.setSpeechRate(r);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean trySetPitch(TextToSpeech tts, float p) {
        try {
            tts.setPitch(p);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String langText(int lang) {
        if (lang == TextToSpeech.LANG_MISSING_DATA) {
            return "缺少语音数据";
        }
        if (lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "引擎不支持";
        }
        if (lang == TextToSpeech.LANG_AVAILABLE) {
            return "可用";
        }
        if (lang == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
            return "可用（含国家变体）";
        }
        if (lang == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
            return "可用（含方言变体）";
        }
        return "返回 " + lang;
    }

    private static void shutdown(TextToSpeech tts) {
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {
        }
    }
}
