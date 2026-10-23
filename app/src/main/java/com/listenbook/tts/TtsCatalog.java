package com.listenbook.tts;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import com.listenbook.util.AppLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TTS 引擎 / 音色的花名册。纯查询，不持有长期实例。
 *
 * 为什么单独抽出来：
 *   导出页要在「还没开始导出」的时候就能列出引擎和音色列表。
 *   如果为此常驻一个 TextToSpeech，会跟听书页的实例抢资源，
 *   所以这里用「一次性探针」——查完立刻 shutdown，用完即走。
 *
 * 枚举引擎走 PackageManager.queryIntentServices(ACTION_TTS_SERVICE)，
 * 不需要任何 TTS 实例；系统默认引擎读 Settings.Secure.TTS_DEFAULT_SYNTH，
 * 也不需要实例。只有列音色必须实例化。
 */
public final class TtsCatalog {

    public static final class Engine {
        public final String pkg;
        public final String label;
        public final boolean systemDefault;

        Engine(String pkg, String label, boolean systemDefault) {
            this.pkg = pkg;
            this.label = label;
            this.systemDefault = systemDefault;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class VoiceItem {
        public final String name;
        public final Locale locale;
        public final int quality;
        public final boolean needsNetwork;

        VoiceItem(String name, Locale locale, int quality, boolean needsNetwork) {
            this.name = name;
            this.locale = locale;
            this.quality = quality;
            this.needsNetwork = needsNetwork;
        }

        public String label() {
            StringBuilder sb = new StringBuilder();
            sb.append(locale == null ? "?" : locale.getDisplayName(Locale.CHINA));
            sb.append(" · ");
            sb.append(shortName());
            if (needsNetwork) {
                sb.append(" · 需联网");
            }
            return sb.toString();
        }

        /** 引擎给的 name 常常是 zh-CN-language，尾部一截才是人话 */
        public String shortName() {
            String s = name == null ? "" : name;
            int i = s.lastIndexOf('-');
            if (i > 0 && i < s.length() - 1) {
                String tail = s.substring(i + 1);
                if (tail.length() >= 2) {
                    return tail;
                }
            }
            return s;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private TtsCatalog() {
    }

    /** 系统当前默认引擎包名。读 Settings.Secure，不需要 TTS 实例。 */
    public static String systemDefaultEngine(Context context) {
        try {
            String e = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);
            return e == null ? "" : e;
        } catch (Exception ex) {
            return "";
        }
    }

    /** 枚举已安装的 TTS 引擎。系统默认排第一。 */
    public static List<Engine> engines(Context context) {
        List<Engine> out = new ArrayList<Engine>();
        String def = systemDefaultEngine(context);
        PackageManager pm = context.getPackageManager();
        Intent probe = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
        List<ResolveInfo> services = null;
        try {
            // 用 0 而不是 MATCH_DEFAULT_ONLY：有些引擎的 intent-filter 没写 DEFAULT 类别
            services = pm.queryIntentServices(probe, 0);
        } catch (Exception e) {
            AppLog.w("TtsCatalog", "枚举 TTS 引擎失败：" + e.getMessage());
        }
        if (services != null) {
            for (int i = 0; i < services.size(); i++) {
                ResolveInfo ri = services.get(i);
                ServiceInfo si = ri.serviceInfo;
                if (si == null || si.packageName == null) {
                    continue;
                }
                CharSequence labelCs = null;
                try {
                    labelCs = ri.loadLabel(pm);
                } catch (Exception ignored) {
                }
                String label = labelCs == null ? "" : labelCs.toString();
                if (label.length() == 0) {
                    label = si.packageName;
                }
                out.add(new Engine(si.packageName, label, si.packageName.equals(def)));
            }
        }
        if (out.isEmpty() && def.length() > 0) {
            out.add(new Engine(def, def, true));
        }
        Collections.sort(out, new Comparator<Engine>() {
            @Override
            public int compare(Engine a, Engine b) {
                if (a.systemDefault != b.systemDefault) {
                    return a.systemDefault ? -1 : 1;
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        AppLog.i("TtsCatalog", "枚举到 " + out.size() + " 个引擎，默认=" + def);
        return out;
    }

    /**
     * 异步列音色。内部开一个有 Looper 的后台线程 —— TextToSpeech 的回调要 Looper，
     * 不能直接在裸 Thread 里 new，否则「Can't create handler inside thread」。
     *
     * @param callback 在主线程回调；失败时拿到空列表
     */
    public static void probeVoices(final Context context, final String enginePkg,
                                   final long timeoutMs, final VoiceCallback callback) {
        final android.os.HandlerThread thread =
                new android.os.HandlerThread("tts-voice-probe");
        thread.start();
        final android.os.Handler worker = new android.os.Handler(thread.getLooper());
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        worker.post(new Runnable() {
            @Override
            public void run() {
                List<VoiceItem> items = new ArrayList<VoiceItem>();
                try {
                    items = probeVoicesBlocking(context, enginePkg, timeoutMs);
                } catch (Throwable t) {
                    AppLog.w("TtsCatalog", "音色探针异常：" + t.getMessage());
                }
                final List<VoiceItem> result = items;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onVoices(result);
                        }
                    }
                });
                thread.quitSafely();
            }
        });
    }

    public interface VoiceCallback {
        void onVoices(List<VoiceItem> items);
    }

    /** 引擎的显示名，查不到就返回包名。 */
    public static String engineLabel(Context context, String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return "系统默认";
        }
        List<Engine> all = engines(context);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).pkg.equals(pkg)) {
                return all.get(i).label;
            }
        }
        return pkg;
    }

    /**
     * 列音色。必须在后台线程调用（内部会创建并销毁一个 TextToSpeech）。
     *
     * @return 排序后的音色列表；失败或引擎不支持 getVoices() 时返回空表
     */
    public static List<VoiceItem> probeVoicesBlocking(Context context, String enginePkg, long timeoutMs) {
        final List<VoiceItem> out = new ArrayList<VoiceItem>();
        final CountDownLatch readyLatch = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        final TextToSpeech[] holder = new TextToSpeech[1];

        TextToSpeech.OnInitListener listener = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ok[0] = status == TextToSpeech.SUCCESS && holder[0] != null;
                readyLatch.countDown();
            }
        };
        TextToSpeech probe;
        try {
            if (enginePkg != null && enginePkg.length() > 0) {
                probe = new TextToSpeech(context.getApplicationContext(), listener, enginePkg);
            } else {
                probe = new TextToSpeech(context.getApplicationContext(), listener);
            }
        } catch (Exception e) {
            AppLog.w("TtsCatalog", "音色探针创建失败：" + e.getMessage());
            return out;
        }
        holder[0] = probe;
        try {
            readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (ok[0]) {
            try {
                java.util.Set<Voice> voices = probe.getVoices();
                if (voices != null) {
                    for (Voice v : voices) {
                        if (v == null || v.getName() == null) {
                            continue;
                        }
                        Locale loc = v.getLocale();
                        boolean net = false;
                        int quality = 0;
                        try {
                            quality = v.getQuality();
                            net = v.getFeatures() != null
                                    && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS);
                        } catch (Exception ignored) {
                        }
                        out.add(new VoiceItem(v.getName(), loc, quality, net));
                    }
                }
            } catch (Exception e) {
                AppLog.w("TtsCatalog", "getVoices() 失败：" + e.getMessage());
            }
        } else {
            AppLog.w("TtsCatalog", "音色探针初始化失败");
        }

        try {
            probe.setOnUtteranceProgressListener(null);
            probe.stop();
            probe.shutdown();
        } catch (Exception ignored) {
        }

        Collections.sort(out, new Comparator<VoiceItem>() {
            @Override
            public int compare(VoiceItem a, VoiceItem b) {
                String la = a.locale == null ? "" : a.locale.toLanguageTag();
                String lb = b.locale == null ? "" : b.locale.toLanguageTag();
                // 中文排在前面：我们的用户主要听中文书
                boolean az = la.startsWith("zh");
                boolean bz = lb.startsWith("zh");
                if (az != bz) {
                    return az ? -1 : 1;
                }
                boolean an = la.startsWith("en");
                boolean bn = lb.startsWith("en");
                if (an != bn) {
                    return an ? -1 : 1;
                }
                if (a.quality != b.quality) {
                    return b.quality - a.quality;
                }
                return la.compareToIgnoreCase(lb);
            }
        });
        AppLog.i("TtsCatalog", "引擎 " + (enginePkg == null ? "default" : enginePkg)
                + " 提供 " + out.size() + " 个音色");
        return out;
    }

    /** Build.VERSION.SDK_INT >= 21 时 Locale.toLanguageTag() 可用，这里做一层兜底。 */
    public static String localeTag(Locale loc) {
        if (loc == null) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= 21) {
            return loc.toLanguageTag();
        }
        return loc.getLanguage() + "-" + loc.getCountry();
    }

    public static Locale localeOf(String tag, Locale fallback) {
        if (tag == null || tag.length() == 0) {
            return fallback == null ? Locale.CHINESE : fallback;
        }
        try {
            return Locale.forLanguageTag(tag);
        } catch (Exception e) {
            return fallback == null ? Locale.CHINESE : fallback;
        }
    }
}
