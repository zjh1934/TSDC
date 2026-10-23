package com.listenbook.util;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 崩溃现场采集。
 *
 * 为什么需要它：
 *   AppLog 里的 UncaughtExceptionHandler 只抓得到 Java 层异常。
 *   但真机上「点一下就闪退」最常见的两类死法它都抓不到：
 *     1. native 崩溃（SIGSEGV / SIGABRT）—— 发生在渲染器、TTS 引擎进程、厂商库里
 *     2. 进程被系统直接杀掉（OOM killer、LMK）—— 连 Java 异常都没有
 *   这两种情况下 AppLog 里一条记录都不会多，日志停在崩溃前最后一条，
 *   用户看到的报告就是「什么都没记录」—— 正是之前诊断日志最大的问题。
 *
 * Android 11(API 30) 起系统会替我们把死因记下来：
 *   ActivityManager.getHistoricalProcessExitReasons() 能拿到最近的进程退出记录，
 *   ApplicationExitInfo 里带着 REASON_CRASH_NATIVE / REASON_CRASH / REASON_LOW_MEMORY，
 *   还有 getTraceInputStream() —— 那是 tombstone，里面有 native 崩溃的完整栈。
 *
 * 用法：在 App.onCreate 里调 collect()，它会把上一次的退出原因写进日志。
 * 本次运行的日志才是「活」的，上一次的退出记录才是「死因」，
 * 两者拼在一起才能看清「点哪里 → 崩在哪」。
 */
public final class CrashTrace {

    private static final int MAX_TRACE_LINES = 80;

    private CrashTrace() {
    }

    /**
     * 采集上一次进程退出的死因。必须在 App.onCreate 里调。
     * 任何内部异常都吞掉 —— 诊断功能绝不能自己把 App 搞崩。
     */
    public static void collect(Context context) {
        if (context == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT < 30) {
                AppLog.step("CrashTrace:系统低于 API 30，拿不到系统退出记录（"
                        + Build.VERSION.SDK_INT + "）");
                return;
            }
            ActivityManager am = (ActivityManager) context.getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 5);
            if (list == null || list.isEmpty()) {
                AppLog.step("CrashTrace:没有历史退出记录");
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                ApplicationExitInfo info = list.get(i);
                if (info == null) {
                    continue;
                }
                // 只关心「上一次」的记录，也就是列表里第一个（按时间倒序）
                if (i > 0) {
                    break;
                }
                report(info);
            }
        } catch (Throwable t) {
            AppLog.step("CrashTrace:采集失败 " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }

    private static void report(ApplicationExitInfo info) {
        int reason = info.getReason();
        String desc = reasonName(reason);
        AppLog.step("=== 上一次进程退出 ===");
        AppLog.step("原因: " + desc + " (reason=" + reason + ")");
        AppLog.step("时间: " + formatTime(info.getTimestamp()));
        AppLog.step("重要性: " + info.getImportance()
                + " 前台=" + isForeground(info.getImportance()));
        if (info.getDescription() != null && info.getDescription().length() > 0) {
            AppLog.step("系统描述: " + info.getDescription());
        }
        try {
            java.io.InputStream in = info.getTraceInputStream();
            if (in != null) {
                AppLog.step("--- tombstone 片段 ---");
                readTrace(in);
            }
        } catch (java.io.IOException ignored) {
            AppLog.step("tombstone 读不出来（系统没给）");
        }
    }

    /** 读 tombstone。只取前面若干行 —— 完整 native 栈动辄上百 KB，写进日志会淹没其它信息。 */
    private static void readTrace(InputStream in) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            int n = 0;
            while (n < MAX_TRACE_LINES && (line = br.readLine()) != null) {
                n++;
                // tombstone 里全是符号名和地址，保留有信息量的行
                if (line.trim().length() == 0) {
                    continue;
                }
                AppLog.step("T " + line.trim());
            }
            if (n >= MAX_TRACE_LINES) {
                AppLog.step("T ...(tombstone 截断)");
            }
        } catch (Throwable ignored) {
            // 读不出来就算了
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 是否前台进程。
     *
     * 注意：ApplicationExitInfo.IMPORTANCE_FOREGROUND 常量是 API 34 才加的，
     * 而本项目 compileSdk 只有 30，直接引用会编译失败。
     * 这里用数值 100（= IMPORTANCE_FOREGROUND）自己判断，兼容所有 API 30+ 设备。
     */
    private static boolean isForeground(int importance) {
        return importance == 100;
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH:
                return "Java 异常崩溃";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "native 崩溃 (SIGSEGV/SIGABRT)";
            case ApplicationExitInfo.REASON_ANR:
                return "ANR 卡死";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "内存不足被杀";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "资源占用过多被杀";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "用户主动退出";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "被划掉/后台回收";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "被信号杀死";
            default:
                return "其它原因";
        }
    }

    private static String formatTime(long millis) {
        try {
            return new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                    java.util.Locale.CHINA).format(new java.util.Date(millis));
        } catch (Throwable t) {
            return String.valueOf(millis);
        }
    }
}
