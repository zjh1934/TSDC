package com.listenbook.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.listenbook.R;
import com.listenbook.export.ExportState;
import com.listenbook.util.AppLog;
import com.listenbook.util.Settings;
import com.listenbook.util.WavTool;

/**
 * 悬浮进度窗。
 *
 * 为什么必须有它：合成一本书要跑几小时，用户不可能守着这个页面 ——
 * 他要息屏、切走去干别的事。这时候他唯一能知道自己这本书跑到哪儿的，
 * 就是桌面/其他 App 上面这个一直在的东西。
 * 通知是它的孪生兄弟（两者显示相同内容），但通知会被系统折叠、会被清理、
 * 在部分国产 ROM 上还可能被「智能省电」直接压掉。悬浮窗挂在 WindowManager 上，
 * 没有那套容易被干预的生命周期 —— 所以两者都要有。
 *
 * 三个必须踩准的坑：
 *
 *   1. TYPE_APPLICATION_OVERLAY 需要用户手动授权 SYSTEM_ALERT_WINDOW。
 *      未授权时 addView 会抛 SecurityException —— 这里必须兜住，并且给用户
 *      一条能点得动的路（跳设置页），而不是什么都不做或者崩掉。
 *
 *   2. 窗口参数必须在 addView 之后改。国产 ROM 会忽略未 attach 时的 attributes，
 *      表现就是「设了 TYPE_APPLICATION_OVERLAY 结果还是盖不住别的 App」。
 *
 *   3. 拖动要按手指偏移量算，不能直接 setX/setY 到按下位置，
 *      否则手指一按住窗口就瞬移到手指原点，视觉上是「窗口跳到角上」。
 */
public final class FloatingWindow {

    /** 拖动判定：超过这个像素数才算拖动，否则算点击 */
    private static final int TOUCH_SLOP = 6;

    private static FloatingWindow instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private View root;
    private TextView tvBook;
    private TextView tvProgress;
    private TextView tvStage;
    private ProgressBar bar;

    /** 刷新节流：每 700ms 一次。进度本来按章跳，刷太勤只会烧电。 */
    private static final long REFRESH_MS = 700L;
    private boolean ticking;

    /** 拖动状态 */
    private int startX, startY;
    private float touchX, touchY;
    private boolean moved;

    private FloatingWindow(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public static synchronized FloatingWindow get(Context c) {
        if (instance == null) {
            instance = new FloatingWindow(c);
        }
        return instance;
    }

    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            return android.provider.Settings.canDrawOverlays(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 拉起设置页授权。国产 ROM 有时不给这个 Intent，得逐级退化。 */
    public static void requestPermission(Context c) {
        String pkg = c.getPackageName();
        Intent[] tries = new Intent[]{
                new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + pkg)),
                new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + pkg)),
        };
        for (int i = 0; i < tries.length; i++) {
            try {
                if (!(c instanceof android.app.Activity)) {
                    tries[i].addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                c.startActivity(tries[i]);
                return;
            } catch (Throwable ignored) {
                // 继续试下一个
            }
        }
        AppLog.w("Floating", "这台机器没有可以授权悬浮窗的设置页");
    }

    /** 导出开始时调。开关关着或没权限就静默跳过 —— 悬浮窗是增强，不该挡住正事。 */
    public void showIfAllowed() {
        if (!Settings.floatingWindow(ctx)) {
            return;
        }
        if (!hasPermission(ctx)) {
            AppLog.i("Floating", "没有悬浮窗权限，跳过（导出照常进行）");
            return;
        }
        show();
    }

    @SuppressLint("ClickableViewAccessibility")
    public void show() {
        if (root != null) {
            refresh();
            return;
        }
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            root = LayoutInflater.from(ctx).inflate(R.layout.view_floating, null);
            tvBook = (TextView) root.findViewById(R.id.fw_book);
            tvProgress = (TextView) root.findViewById(R.id.fw_progress);
            tvStage = (TextView) root.findViewById(R.id.fw_stage);
            bar = (ProgressBar) root.findViewById(R.id.fw_bar);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            // 放在右上角往下一点，不挡状态栏也不挡刘海
            int x = px(ctx, 8);
            int y = px(ctx, 56);
            if (haveDensity()) {
                lp.x = x;
                lp.y = y;
            }

            wm.addView(root, lp);
            // 参数必须在 addView 之后再修正一次：国产 ROM 只认 attach 之后的值
            // 二次写入：部分国产 ROM 只在 attach 之后才认真读这些参数
            try {
                WindowManager.LayoutParams after = new WindowManager.LayoutParams();
                after.copyFrom(lp);
                after.x = x;
                after.y = y;
                wm.updateViewLayout(root, after);
            } catch (Throwable ignored) {
            }

            root.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = lp.x;
                            startY = lp.y;
                            touchX = event.getRawX();
                            touchY = event.getRawY();
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dx = event.getRawX() - touchX;
                            float dy = event.getRawY() - touchY;
                            if (Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP) {
                                moved = true;
                            }
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            if (wm != null && root != null) {
                                try {
                                    wm.updateViewLayout(root, lp);
                                } catch (Throwable ignored) {
                                }
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                            if (!moved) {
                                openExport();
                            }
                            return true;
                        default:
                            return true;
                    }
                }
            });

            refresh();
            startTicking();
            AppLog.i("Floating", "悬浮进度窗已显示");
        } catch (Throwable t) {
            // 悬浮窗挂了绝不能影响导出本身
            AppLog.e("Floating", "悬浮窗显示失败（导出不受影响）", t);
            remove();
        }
    }

    private void openExport() {
        try {
            Intent i = new Intent(ctx, ExportActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (ExportState.bookId != null && ExportState.bookId.length() > 0) {
                i.putExtra("bookId", ExportState.bookId);
            }
            ctx.startActivity(i);
        } catch (Throwable t) {
            AppLog.e("Floating", "点悬浮窗跳转失败", t);
        }
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= 26) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** minSdk 21 上部分老机型没有 displayMetrics，坐标设置要降级 */
    private boolean haveDensity() {
        return ctx.getResources() != null
                && ctx.getResources().getDisplayMetrics() != null;
    }

    private int px(Context c, int dp) {
        try {
            return (int) (dp * c.getResources().getDisplayMetrics().density);
        } catch (Throwable t) {
            return dp;
        }
    }

    private void startTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
    }

    /** 按 ExportState 刷新显示。导出结束会自动摘掉自己。 */
    public void refresh() {
        if (root == null) {
            return;
        }
        try {
            boolean running = ExportState.running;
            tvBook.setText(ExportState.bookTitle.length() > 0
                    ? ExportState.bookTitle : "导出音频");
            int pct = ExportState.percent();
            StringBuilder sb = new StringBuilder();
            sb.append(ExportState.done).append('/').append(ExportState.total)
                    .append(" 章 ").append(pct).append('%');
            long eta = ExportState.etaMs();
            if (eta > 0) {
                sb.append("  剩 ").append(WavTool.humanDuration(eta));
            }
            tvProgress.setText(sb.toString());

            String stage = ExportState.stage.length() > 0
                    ? ExportState.stage : ExportState.currentChapter;
            tvStage.setText(stage);
            bar.setProgress(pct);

            if (!running) {
                // 跑完就自己消失，别在屏幕上留一个僵尸窗
                stopTicking();
                remove();
                return;
            }
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            }, REFRESH_MS);
        } catch (Throwable t) {
            stopTicking();
            remove();
            AppLog.e("Floating", "刷新失败，已撤掉悬浮窗", t);
        }
    }

    public void remove() {
        stopTicking();
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {
            }
        }
        root = null;
        wm = null;
        tvBook = null;
        tvProgress = null;
        tvStage = null;
        bar = null;
    }

    private void stopTicking() {
        ticking = false;
        main.removeCallbacksAndMessages(null);
    }
}