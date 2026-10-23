package com.listenbook.player;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.listenbook.util.AppLog;
import com.listenbook.util.WavTool;

import java.io.File;

/**
 * App 内试听。零依赖 MediaPlayer 封装，单例。
 *
 * 为什么不直接跳外部播放器 / 不让用户去文件管理器翻目录：
 * 用户导出完最想做的事就是「听一下到底合出来什么味道」，让他自己找文件
 * 等于把整个功能白做。所以点一下已完成的章节就地播，底部浮出播放条。
 *
 * 几个刻意的取舍：
 *   1. 单例而不是每个 Activity 一个：MediaPlayer 占硬件解码器，开两个会互相踢。
 *   2. 播完一个文件就停，不自动连播下一章。用户没说要连播，
 *      自动往下播会导致「怎么关不掉」。
 *   3. 换文件必须 reset() 后重新 setDataSource，不能复用同一个实例直接播下一个。
 *   4. 进度用主线程 Handler 轮询而不是线程。项目零依赖，
 *      写一个后台轮询线程再 post 回来并不会比Handler.postDelayed 更省事。
 */
public final class AudioPlayer {

    public interface Listener {
        /** 播放状态变了：开始 / 暂停 / 停止 / 播完 / 出错。 */
        void onStateChanged(boolean playing, String path);

        /** 进度回调，约每 400ms 一次。pos/dur 单位毫秒，dur<=0 表示还不知道。 */
        void onProgress(long pos, long dur);
    }

    private static AudioPlayer instance;

    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaPlayer mp;
    private Listener listener;
    private String currentPath = "";
    private boolean prepared;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!prepared) {
                return;
            }
            long pos = 0L;
            long dur = 0L;
            try {
                if (mp != null) {
                    pos = mp.getCurrentPosition();
                    dur = mp.getDuration();
                }
            } catch (IllegalStateException e) {
                // 引擎刚被 release/setDataSource 撞上了，下一个 tick 会纠正
            }
            if (listener != null) {
                listener.onProgress(pos, dur);
            }
            main.postDelayed(this, 400L);
        }
    };

    private AudioPlayer() {
    }

    public static synchronized AudioPlayer get() {
        if (instance == null) {
            instance = new AudioPlayer();
        }
        return instance;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public String currentPath() {
        return currentPath;
    }

    public boolean isPlaying() {
        if (mp == null || !prepared) {
            return false;
        }
        try {
            return mp.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 点已完成的章节 = 试听。同一个文件再点一次 = 暂停/继续。 */
    public void toggle(File f) {
        if (f == null || !f.isFile()) {
            return;
        }
        String path = f.getAbsolutePath();
        if (path.equals(currentPath) && prepared) {
            if (isPlaying()) {
                pause();
            } else {
                resume();
            }
            return;
        }
        play(f);
    }

    public void play(File f) {
        if (f == null || !f.isFile()) {
            return;
        }
        stopTicker();
        releasePlayer();
        String path = f.getAbsolutePath();
        currentPath = path;
        prepared = false;
        try {
            mp = new MediaPlayer();
            mp.setDataSource(path);
            mp.prepare();
            // 播完必须回调，否则 ticker 会一直转、播放条也永远停在「正在播放」。
            // 刻意不自动连播下一章：用户没要求连播，自动往下播会关不掉。
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer p) {
                    prepared = false;
                    stopTicker();
                    if (listener != null) {
                        listener.onStateChanged(false, currentPath);
                    }
                }
            });
            mp.start();
            prepared = true;
            AppLog.i("AudioPlayer", "开始试听 " + f.getName());
        } catch (Exception e) {
            // 合成出来的 WAV 万一有半截文件，这里会炸。不能让整个界面跟着崩。
            AppLog.w("AudioPlayer", "播放失败 " + f.getName() + "：" + e.getMessage());
            releasePlayer();
            prepared = false;
            if (listener != null) {
                listener.onStateChanged(false, path);
            }
            return;
        }
        startTicker();
        if (listener != null) {
            listener.onStateChanged(true, path);
            listener.onProgress(0L, safeDuration());
        }
    }

    public void pause() {
        if (mp == null || !prepared) {
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
            }
        } catch (IllegalStateException e) {
            return;
        }
        stopTicker();
        if (listener != null) {
            listener.onStateChanged(false, currentPath);
        }
    }

    public void resume() {
        if (mp == null || !prepared) {
            return;
        }
        try {
            mp.start();
        } catch (IllegalStateException e) {
            return;
        }
        startTicker();
        if (listener != null) {
            listener.onStateChanged(true, currentPath);
            listener.onProgress(0L, safeDuration());
        }
    }

    /** 停止并收起播放条。切页面 / 退到后台都调它。 */
    public void stop() {
        boolean had = prepared || currentPath.length() > 0;
        stopTicker();
        releasePlayer();
        prepared = false;
        currentPath = "";
        if (had && listener != null) {
            listener.onStateChanged(false, "");
        }
    }

    public void seekTo(int percent) {
        if (mp == null || !prepared) {
            return;
        }
        int p = percent;
        if (p < 0) {
            p = 0;
        }
        if (p > 100) {
            p = 100;
        }
        try {
            long dur = mp.getDuration();
            if (dur > 0) {
                mp.seekTo((int) (dur * p / 100L));
            }
        } catch (IllegalStateException e) {
            return;
        }
        if (listener != null) {
            listener.onProgress(percentToPos(p), durOfCurrent());
        }
    }

    // ------------------------------------------------------------------ 内部

    private long durOfCurrent() {
        return safeDuration();
    }

    private long percentToPos(int percent) {
        long dur = safeDuration();
        return dur * percent / 100L;
    }

    private long safeDuration() {
        if (mp == null || !prepared) {
            return 0L;
        }
        try {
            int d = mp.getDuration();
            return d > 0 ? d : 0L;
        } catch (IllegalStateException e) {
            return 0L;
        }
    }

    private void startTicker() {
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 400L);
    }

    private void stopTicker() {
        main.removeCallbacks(ticker);
    }

    private void releasePlayer() {
        if (mp == null) {
            return;
        }
        try {
            mp.stop();
        } catch (IllegalStateException e) {
            // 还没 prepare 就 stop 会抛，忽略
        } catch (Exception e) {
            // ignore
        }
        try {
            mp.reset();
        } catch (Exception e) {
            // ignore
        }
        try {
            mp.release();
        } catch (Exception e) {
            // ignore
        }
        mp = null;
    }

    /** 把毫秒格式化成 m:ss / h:mm:ss，播放条上显示用。 */
    public static String clock(long millis) {
        if (millis < 0L) {
            millis = 0L;
        }
        long s = millis / 1000L;
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        if (h > 0L) {
            return h + ":" + pad(m) + ":" + pad(sec);
        }
        return m + ":" + pad(sec);
    }

    private static String pad(long v) {
        return v < 10L ? ("0" + v) : Long.toString(v);
    }

    /** 供播放条显示文件时长（不播也能拿到） */
    public static String durationOf(File f) {
        return WavTool.humanDuration(WavTool.probe(f).millis());
    }
}