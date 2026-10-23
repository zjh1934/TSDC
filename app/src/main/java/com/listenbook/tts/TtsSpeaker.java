package com.listenbook.tts;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 模块 A：听书。加载书 → 切句 → TTS.speak() → 逐句高亮 + 滚动。
 * 不产任何文件（写盘是模块 B 的事，两者运行时完全不共享）。
 *
 * 三个坑在这里一次性堵死：
 *   坑 2：speak 必须带 utteranceId，否则 onDone 永不回调
 *   坑 3：UtteranceProgressListener 必须在 onInit(SUCCESS) 回调内部挂，
 *         构造后立刻挂会被直接丢弃
 *   坑 5：回调在 binder 线程，一律 post 回主线程再动状态
 *
 * 防 ANR：滑动窗口只预排队 6 句，每句 onDone 补一句。
 * 防串味：generation 令牌，utteranceId = "s:<gen>:<index>"，
 *         快速切章时上一次的迟到回调因 gen 不匹配被丢弃。
 */
public class TtsSpeaker {

    public interface Callback {
        void onEngineReady(boolean success, String message);

        void onSentenceStart(int index);

        void onSentenceEnd(int index);

        void onFinished();

        void onError(String message);
    }

    public static final class EngineItem {
        public final String pkg;
        public final String label;

        EngineItem(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final int WINDOW = 6;
    private static final int MAX_STUCK_SKIP = 8;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile int gen;
    private Callback callback;
    private String enginePkg = "";

    private List<Sentence> sentences = Collections.emptyList();
    private int nextIndex;
    private int playIndex = -1;
    private int queued;
    private boolean active;
    private int stuckSkips;
    private volatile boolean pendingResume;
    private float rate = 1.0f;
    private float pitch = 1.0f;
    private Locale locale = Locale.CHINESE;
    /** 切引擎期间置位。此时再次切换会连着丢两个 TextToSpeech 实例，必须挡住。 */
    private volatile boolean switching;
    private final Object switchLock = new Object();

    public TtsSpeaker(Context context) {
        this.app = context.getApplicationContext();
    }

    public void init(final Callback cb, String engine) {
        this.callback = cb;
        this.enginePkg = engine == null ? "" : engine;
        TextToSpeech.OnInitListener onInit = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    // 坑 3：必须在这里挂，不能提前
                    tts.setOnUtteranceProgressListener(progressListener);
                    tts.setSpeechRate(rate);
                    tts.setPitch(pitch);
                    int lang = tts.setLanguage(locale);
                    ready = true;
                    // 语言数据缺失时引擎仍然可用（换引擎/换语言即可），按「就绪 + 警告」处理
                    postReady(true, languageMessage(lang));
                } else {
                    ready = false;
                    postReady(false, "TTS 初始化失败（status=" + status + "），请检查系统里是否装了 TTS 引擎");
                }
            }
        };
        try {
            if (enginePkg.length() > 0) {
                tts = new TextToSpeech(app, onInit, enginePkg);
            } else {
                tts = new TextToSpeech(app, onInit);
            }
        } catch (Exception e) {
            ready = false;
            // 指定引擎失败就退回系统默认
            tts = new TextToSpeech(app, onInit);
        }
    }

    private String languageMessage(int lang) {
        if (lang == TextToSpeech.LANG_MISSING_DATA) {
            return "当前引擎缺少 " + locale.getDisplayLanguage(Locale.CHINESE) + " 语音数据";
        }
        if (lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "当前引擎不支持 " + locale.getDisplayLanguage(Locale.CHINESE);
        }
        return null;
    }

    private void postReady(final boolean ok, final String msg) {
        final Callback cb = callback;
        synchronized (switchLock) {
            switching = false;
        }
        if (cb == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onEngineReady(ok, msg);
            }
        });
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            handle(utteranceId, 1);
        }

        @Override
        public void onDone(String utteranceId) {
            handle(utteranceId, 2);
        }

        @Override
        public void onError(String utteranceId) {
            handle(utteranceId, 3);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            handle(utteranceId, 3);
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            // 主动 stop 会走这里；gen 已经自增，迟到回调自然被丢弃
        }
    };

    private void handle(final String utteranceId, final int type) {
        int[] parsed = parseUid(utteranceId);
        if (parsed == null) {
            return;
        }
        final int g = parsed[0];
        final int idx = parsed[1];
        if (g != gen) {
            return; // 上一次播放的迟到回调，丢弃
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (g != gen) {
                    return;
                }
                Callback cb = callback;
                if (type == 1) {
                    playIndex = idx;
                    armWatchdog();
                    if (cb != null) {
                        cb.onSentenceStart(idx);
                    }
                } else if (type == 2) {
                    queued = Math.max(0, queued - 1);
                    playIndex = idx;
                    stuckSkips = 0;
                    if (cb != null) {
                        cb.onSentenceEnd(idx);
                    }
                    if (!active) {
                        return;
                    }
                    if (nextIndex >= sentences.size() && queued <= 0) {
                        active = false;
                        disarmWatchdog();
                        if (cb != null) {
                            cb.onFinished();
                        }
                        return;
                    }
                    pump();
                } else {
                    queued = Math.max(0, queued - 1);
                    if (cb != null) {
                        cb.onError("第 " + (idx + 1) + " 句合成失败，已跳过");
                    }
                    if (!active) {
                        return;
                    }
                    if (nextIndex >= sentences.size() && queued <= 0) {
                        active = false;
                        disarmWatchdog();
                        if (cb != null) {
                            cb.onFinished();
                        }
                        return;
                    }
                    pump();
                }
            }
        });
    }

    /** 卡死看门狗：某些引擎会既不 onDone 也不 onError */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!active || tts == null) {
                return;
            }
            if (stuckSkips >= MAX_STUCK_SKIP) {
                active = false;
                Callback cb = callback;
                if (cb != null) {
                    cb.onError("连续多句没有回调，已停止。建议换一个 TTS 引擎。");
                    cb.onFinished();
                }
                return;
            }
            stuckSkips++;
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
            queued = 0;
            if (playIndex >= 0 && nextIndex <= playIndex) {
                nextIndex = playIndex + 1;
            }
            Callback cb = callback;
            if (cb != null) {
                cb.onError("引擎无响应，跳过卡住的句子（第 " + stuckSkips + " 次）");
            }
            if (nextIndex >= sentences.size()) {
                active = false;
                if (cb != null) {
                    cb.onFinished();
                }
                return;
            }
            pump();
        }
    };

    private void armWatchdog() {
        main.removeCallbacks(watchdog);
        long ms = 30000L;
        if (playIndex >= 0 && playIndex < sentences.size()) {
            int len = sentences.get(playIndex).length();
            ms = 20000L + (long) len * 400L;
            if (ms > 180000L) {
                ms = 180000L;
            }
        }
        main.postDelayed(watchdog, ms);
    }

    private void disarmWatchdog() {
        main.removeCallbacks(watchdog);
    }

    private void pump() {
        if (!active || tts == null || !ready) {
            return;
        }
        while (queued < WINDOW && nextIndex < sentences.size()) {
            Sentence s = sentences.get(nextIndex);
            int idx = nextIndex;
            String uid = "s:" + gen + ":" + idx;
            Bundle params = new Bundle();
            // 坑 2：不传 utteranceId，onDone 永远不回调
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid);
            int r;
            try {
                r = tts.speak(s.text, TextToSpeech.QUEUE_ADD, params, uid);
            } catch (Exception e) {
                r = TextToSpeech.ERROR;
            }
            nextIndex++;
            if (r == TextToSpeech.SUCCESS) {
                queued++;
                armWatchdog();
            } else {
                Callback cb = callback;
                if (cb != null) {
                    cb.onError("speak() 返回错误，第 " + (idx + 1) + " 句已跳过");
                }
            }
        }
        if (queued == 0 && nextIndex >= sentences.size()) {
            active = false;
            disarmWatchdog();
            Callback cb = callback;
            if (cb != null) {
                cb.onFinished();
            }
        }
    }

    /** 开始朗读一章。startIndex 为章节内句子序号。 */
    public void speakChapter(List<Sentence> newSentences, int startIndex, Locale loc) {
        if (newSentences == null) {
            newSentences = Collections.emptyList();
        }
        gen++; // 旧回调全部作废
        disarmWatchdog();
        this.sentences = newSentences;
        this.nextIndex = Math.max(0, Math.min(startIndex, newSentences.size()));
        this.playIndex = -1;
        this.queued = 0;
        this.stuckSkips = 0;
        if (loc != null) {
            this.locale = loc;
            if (tts != null && ready) {
                try {
                    tts.setLanguage(loc);
                } catch (Exception ignored) {
                }
            }
        }
        if (!ready || tts == null) {
            return;
        }
        if (newSentences.isEmpty()) {
            active = false;
            return;
        }
        try {
            tts.stop();
        } catch (Exception ignored) {
        }
        queued = 0;
        active = true;
        pump();
    }

    /** 暂停：TTS 没有真暂停，停下并记住位置，resume 时从当前句重读 */
    public void pause() {
        active = false;
        disarmWatchdog();
        gen++;
        queued = 0;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
        if (playIndex >= 0) {
            nextIndex = playIndex;
        }
    }

    public void resume() {
        if (!ready || tts == null || sentences.isEmpty()) {
            return;
        }
        gen++;
        queued = 0;
        stuckSkips = 0;
        if (nextIndex >= sentences.size()) {
            nextIndex = 0;
        }
        active = true;
        pump();
    }

    public void stop() {
        active = false;
        disarmWatchdog();
        gen++;
        queued = 0;
        playIndex = -1;
        nextIndex = 0;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    public void setRate(float r) {
        this.rate = Math.max(0.5f, Math.min(3.0f, r));
        if (tts != null && ready) {
            try {
                tts.setSpeechRate(this.rate);
            } catch (Exception ignored) {
            }
        }
    }

    public float getRate() {
        return rate;
    }

    public void setPitch(float p) {
        if (p < 0.5f) {
            p = 0.5f;
        }
        if (p > 2.0f) {
            p = 2.0f;
        }
        this.pitch = Math.round(p * 10f) / 10f;
        if (tts != null && ready) {
            try {
                tts.setPitch(this.pitch);
            } catch (Exception ignored) {
            }
        }
    }

    public float getPitch() {
        return pitch;
    }

    /** 指定当选音色。名字对不上时引擎会保持默认，这里不报错。 */
    public void setVoiceByName(String name) {
        if (name == null || name.length() == 0 || tts == null || !ready) {
            return;
        }
        try {
            java.util.Set<Voice> voices = tts.getVoices();
            if (voices == null) {
                return;
            }
            java.util.Iterator<Voice> it = voices.iterator();
            while (it.hasNext()) {
                Voice v = it.next();
                if (v != null && name.equals(v.getName())) {
                    tts.setVoice(v);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public boolean isBusySwitching() {
        return switching;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isReady() {
        return ready;
    }

    public int getPlayIndex() {
        return playIndex;
    }

    public int getNextIndex() {
        return nextIndex;
    }

    public Locale getLocale() {
        return locale;
    }

    public List<EngineItem> listEngines() {
        List<EngineItem> out = new ArrayList<EngineItem>();
        if (tts == null) {
            return out;
        }
        try {
            List<TextToSpeech.EngineInfo> engines = tts.getEngines();
            if (engines != null) {
                for (int i = 0; i < engines.size(); i++) {
                    TextToSpeech.EngineInfo e = engines.get(i);
                    String label = e.label == null || e.label.length() == 0 ? e.name : e.label;
                    out.add(new EngineItem(e.name, label));
                }
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty() && tts.getDefaultEngine() != null) {
            out.add(new EngineItem(tts.getDefaultEngine(), tts.getDefaultEngine()));
        }
        return out;
    }

    public String currentEnginePkg() {
        if (tts == null) {
            return enginePkg;
        }
        try {
            String e = tts.getDefaultEngine();
            return e == null ? enginePkg : e;
        } catch (Exception ex) {
            return enginePkg;
        }
    }

    /** 切引擎必须重建 TextToSpeech 实例 */
    public boolean switchEngine(String pkg, Callback cb) {
        // 上一次切换还没回调时被再次点击 → 直接拒绝，不然旧实例没人 shutdown
        synchronized (switchLock) {
            if (switching) {
                return false;
            }
            switching = true;
        }
        int keepGen = gen;
        boolean wasActive = active;
        int keepIndex = playIndex >= 0 ? playIndex : nextIndex;
        List<Sentence> keepSentences = sentences;
        Locale keepLocale = locale;
        shutdownInternal();
        gen = keepGen + 1;
        init(cb, pkg);
        // 引擎就绪后由 UI 决定是否续播；这里只保留状态供查询
        this.sentences = keepSentences == null ? Collections.<Sentence>emptyList() : keepSentences;
        this.nextIndex = keepIndex;
        this.locale = keepLocale;
        this.active = false;
        if (wasActive) {
            pendingResume = true;
        }
        return true;
    }

    public boolean consumePendingResume() {
        boolean v = pendingResume;
        pendingResume = false;
        return v;
    }

    private void shutdownInternal() {
        active = false;
        disarmWatchdog();
        queued = 0;
        ready = false;
        if (tts != null) {
            try {
                tts.setOnUtteranceProgressListener(null);
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
    }

    public void shutdown() {
        gen++;
        shutdownInternal();
        callback = null;
        sentences = Collections.emptyList();
    }

    private static int[] parseUid(String uid) {
        if (uid == null) {
            return null;
        }
        String[] parts = uid.split(":");
        if (parts.length != 3 || !parts[0].equals("s")) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (Exception e) {
            return null;
        }
    }
}
