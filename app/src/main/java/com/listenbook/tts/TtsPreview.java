package com.listenbook.tts;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.listenbook.R;
import com.listenbook.util.AppLog;
import com.listenbook.util.Settings;

import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 「试听效果」：用当前设置下的引擎/音色/语言/语速/音调，播一段固定样文。
 *
 * 为什么自己持有一个 TextToSpeech 而不复用 TtsSpeaker：
 *   TtsSpeaker 是为「整章连续朗读」设计的滑动窗口播放器，会预排队 6 句；
 *   试听只要一句，而且必须与导出彻底隔离。合成导出跑的是另一个进程里的
 *   引擎实例，如果试听共用同一个实例，stop() 会连带打断正在导出的那一块。
 *
 * 与导出的冲突处理（ExportActivity 侧）：导出中直接禁用按钮，不做「排队等导出结束」。
 *   理由：TTS 引擎是系统级共享资源，synthesizeToFile 期间引擎被长时间独占，
 *   此时另起一个实例去 speak，轻则两个音频叠在一起，重则引擎直接崩或整条导出卡死。
 *   禁用 + 明确提示是唯一不会把用户已有进度搞坏的方案。
 *
 * 三个坑：
 *   坑 1：不传 utteranceId，onDone 永远不回调，按钮会永远卡在「试听中」
 *   坑 2：进度监听必须在 onInit(SUCCESS) 内部挂，提前挂会被丢弃
 *   坑 3：引擎可能既不 onDone 也不 onError，所以另加一道主线程看门狗
 */
public final class TtsPreview {

    /** 状态变化回调，全在主线程。 */
    public interface Listener {
        /** playing=true 表示正在播，UI 该把按钮切成「停止试听」。 */
        void onStateChanged(boolean playing, String message);
    }

    /**
     * 样文。刻意混了标点和数字：这两样是听书最容易出问题的部分
     * （数字读法、停顿位置），只读一句纯汉字的诗听不出真实效果。
     */
    public static final String SAMPLE =
            "这是听书导出的试听样音，第 3 章。你现在听到的语速和语调，"
                    + "就是导出成音频后的效果。";

    private static final String UID = "preview";

    /** 引擎初始化最多等这么久，再不回调就判定这个引擎废了 */
    private static final long INIT_TIMEOUT_MS = 15000L;
    /** 播完的最长容忍时间。样文 40 字，按最慢 0.5x 也就十几秒，30 秒足够宽松 */
    private static final long SPEAK_TIMEOUT_MS = 30000L;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private TextToSpeech tts;
    /** binder 线程写、主线程读，必须 volatile，否则看门狗可能读到过期的 false */
    private volatile boolean playing;
    /** gen 令牌：每次 start/stop 自增，迟到的回调因不匹配被丢弃。binder 线程会读，必须 volatile */
    private volatile int gen;
    private boolean voiceMissing;

    public TtsPreview(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isPlaying() {
        return playing;
    }

    public String sampleText() {
        return SAMPLE;
    }

    /** 开始试听。会先把上一次的实例拆干净，避免残留实例抢占引擎。 */
    public void start(final String enginePkg, final String voiceName, final Locale locale,
                      final float rate, final float pitch) {
        gen++;
        final int myGen = gen;
        playing = false;
        voiceMissing = false;
        shutdownTts();
        post(myGen, getMessage(R.string.preview_loading), true);

        // 坑 2：OnInitListener 里挂进度监听
        TextToSpeech.OnInitListener onInit = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (myGen != gen) {
                    return;
                }
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    postFail(myGen, app.getString(R.string.preview_engine_dead));
                    return;
                }
                try {
                    tts.setOnUtteranceProgressListener(progressListener);
                    tts.setSpeechRate(rate);
                    tts.setPitch(pitch);
                    if (locale != null) {
                        int r = tts.setLanguage(locale);
                        if (r == TextToSpeech.LANG_MISSING_DATA
                                || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            // 语言装不上不直接失败：部分引擎仍能用默认语言念，
                            // 直接报错会把「换个引擎就好」的情况说成完全不可用。
                            AppLog.w("TtsPreview", "语言不可用，code=" + r);
                        }
                    }
                    applyVoice(voiceName);
                } catch (Exception e) {
                    AppLog.w("TtsPreview", "参数下发失败：" + e.getMessage());
                }
                speak(myGen);
            }
        };

        armWatchdog(myGen, INIT_TIMEOUT_MS);
        try {
            if (enginePkg != null && enginePkg.length() > 0) {
                tts = new TextToSpeech(app, onInit, enginePkg);
            } else {
                tts = new TextToSpeech(app, onInit);
            }
        } catch (Exception e) {
            AppLog.e("TtsPreview", "创建 TextToSpeech 失败", e);
            postFail(myGen, app.getString(R.string.preview_engine_dead));
        }
    }

    private void applyVoice(String name) {
        if (name == null || name.length() == 0 || tts == null) {
            return;
        }
        try {
            // 坑：setVoice 找不到同名音色时会静默保持默认，用户以为已经换过了。
            // 这里自己遍历比对一次，对不上就记下来，UI 才有办法说实话。
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                Iterator<Voice> it = voices.iterator();
                while (it.hasNext()) {
                    Voice v = it.next();
                    if (v != null && name.equals(v.getName())) {
                        tts.setVoice(v);
                        return;
                    }
                }
            }
            voiceMissing = true;
        } catch (Exception e) {
            voiceMissing = true;
        }
    }

    private void speak(int myGen) {
        if (myGen != gen || tts == null) {
            return;
        }
        Bundle params = new Bundle();
        // 坑 1：不传 utteranceId，onDone 不会回调
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UID);
        int r;
        try {
            r = tts.speak(SAMPLE, TextToSpeech.QUEUE_FLUSH, params, UID);
        } catch (Exception e) {
            postFail(myGen, app.getString(R.string.preview_engine_dead));
            return;
        }
        if (r != TextToSpeech.SUCCESS) {
            postFail(myGen, app.getString(R.string.preview_engine_dead));
            return;
        }
        playing = true;
        armWatchdog(myGen, SPEAK_TIMEOUT_MS);
        post(myGen, getMessage(R.string.preview_playing), true);
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
        }

        @Override
        public void onDone(String utteranceId) {
            if (UID.equals(utteranceId)) {
                finish(gen, false);
            }
        }

        @Override
        public void onError(String utteranceId) {
            if (UID.equals(utteranceId)) {
                fail(gen);
            }
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            if (UID.equals(utteranceId)) {
                fail(gen);
            }
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            // 主动 stop() 会走这里，stop() 内部已经收尾了，这里什么都不用做
        }
    };

    /** 停止并释放。按用户预期算「正常结束」，不给报错文案。 */
    public void stop() {
        gen++;
        finish(gen, true);
    }

    public void release() {
        gen++;
        playing = false;
        main.removeCallbacksAndMessages(null);
        shutdownTts();
    }

    private void finish(int myGen, boolean byUser) {
        if (myGen != gen) {
            return;
        }
        main.removeCallbacksAndMessages(null);
        playing = false;
        shutdownTts();
        String msg;
        if (byUser) {
            msg = app.getString(R.string.preview_stopped);
        } else if (voiceMissing) {
            msg = app.getString(R.string.preview_voice_missing);
        } else {
            msg = app.getString(R.string.preview_done);
        }
        // onDone/onError 是 binder 线程发出来的，UI 只能碰主线程，必须再 post 一次
        post(myGen, msg, false);
    }

    private void fail(int myGen) {
        postFail(myGen, app.getString(R.string.preview_engine_dead));
    }

    private void postFail(final int myGen, final String msg) {
        if (myGen != gen) {
            return;
        }
        main.removeCallbacksAndMessages(null);
        playing = false;
        shutdownTts();
        post(myGen, msg, false);
    }

    // ------------------------------------------------------------------ 看门狗

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (playing) {
                postFail(gen, app.getString(R.string.preview_speak_timeout));
            } else {
                postFail(gen, app.getString(R.string.preview_engine_dead));
            }
        }
    };

    private void armWatchdog(int myGen, long ms) {
        if (myGen != gen) {
            return;
        }
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, ms);
    }

    // ------------------------------------------------------------------ 杂项

    private void post(final int myGen, final String msg, final boolean isPlaying) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (myGen != gen) {
                    return;
                }
                dispatch(myGen, isPlaying, msg);
            }
        });
    }

    private void dispatch(int myGen, boolean isPlaying, String msg) {
        if (myGen != gen || listener == null) {
            return;
        }
        playing = isPlaying;
        listener.onStateChanged(isPlaying, msg);
    }

    private String getMessage(int resId) {
        return app.getString(resId);
    }

    private void shutdownTts() {
        if (tts == null) {
            return;
        }
        try {
            tts.setOnUtteranceProgressListener(null);
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {
        }
        tts = null;
    }

    /** 从设置里读一份当前参数出来试听。 */
    public void startFromSettings(Context context) {
        start(Settings.enginePkg(context), Settings.voiceName(context),
                previewLocale(context), Settings.rate(context), Settings.pitch(context));
    }

    /**
     * 试听用固定语言而不是「跟随正文」：样文是中文，
     * 让引擎按中文念才有意义，跟随正文在这个场景下没有可跟随的对象。
     */
    public static Locale previewLocale(Context context) {
        String mode = Settings.languageMode(context);
        if (Settings.LANG_EN.equals(mode)) {
            return Locale.ENGLISH;
        }
        String tag = Settings.localeTag(context);
        return TtsCatalog.localeOf(tag, Locale.CHINESE);
    }
}