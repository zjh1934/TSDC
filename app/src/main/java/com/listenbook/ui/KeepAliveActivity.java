package com.listenbook.ui;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.listenbook.R;
import com.listenbook.util.AppLog;

/**
 * 后台运行设置页 —— 专门对付国产 ROM（用户机型：小米 24117RK2CC / MIUI·HyperOS）
 * 的激进后台管控。
 *
 * 为什么必须单独做一页：
 *   合成一本 300 章的书要跑几小时，过程中用户一定会息屏、切走。
 *   而这些系统会把「息屏 + 长时间无交互」判定成「用户在用别的 App」，
 *   直接把本进程连前台服务一起冻结或杀掉 —— 表现是「导出到一半莫名其妙停了」。
 *   这不是 bug，是系统策略；App 这边唯一能做的就是把相关开关一个个指给用户看。
 *
 * 判状态的坑：
 *   1. isIgnoringBatteryOptimizations 只有 API 23+ 才有，21/22 上必须整段跳过，
 *      否则 NoSuchMethodError —— minSdk 21 意味着这两台机器是真的存在。
 *   2. canDrawOverlays 是 API 23+，同理。
 *   3. NotificationManager.areNotificationsEnabled() 是 API 24+，
 *      不能用 androidx 的 NotificationManagerCompat（项目零第三方依赖）。
 *   4. 厂商自启动管理（MIUI 的「省电策略 → 自启动」）根本没有公开 API 可查，
 *      硬猜只会显示错的结论。老老实实写「需手动确认」并把详情页甩给用户。
 *
 * 跳转的坑：
 *   国产 ROM 改路径 / 砍设置页是必然会遇到的（MIUI 尤其爱这么干），
 *   所以每一次 startActivity 都必须 try-catch ActivityNotFoundException，
 *   并且带一条 fallback Intent，不能让用户点一下按钮就闪退。
 */
public class KeepAliveActivity extends Activity {

    private TextView tvBattery;
    private TextView tvAutorun;
    private TextView tvOverlay;
    private TextView tvNotify;
    private TextView tvSummary;

    private boolean batteryOn;
    private boolean overlayOn;
    private boolean notifyOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            AppLog.init(this);
            setContentView(R.layout.activity_keepalive);

            tvBattery = (TextView) findViewById(R.id.tv_battery_state);
            tvAutorun = (TextView) findViewById(R.id.tv_autorun_state);
            tvOverlay = (TextView) findViewById(R.id.tv_overlay_state);
            tvNotify = (TextView) findViewById(R.id.tv_notify_state);
            tvSummary = (TextView) findViewById(R.id.ka_summary);

            findViewById(R.id.ka_back).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });

            // 整行可点，不必精确点到右侧那个小按钮
            bindRow(R.id.row_battery, R.id.btn_battery_go, ACTION_BATTERY);
            bindRow(R.id.row_autorun, R.id.btn_autorun_go, ACTION_AUTORUN);
            bindRow(R.id.row_overlay, R.id.btn_overlay_go, ACTION_OVERLAY);
            bindRow(R.id.row_notify, R.id.btn_notify_go, ACTION_NOTIFY);
        } catch (Throwable t) {
            // 这一页崩了等于用户连设置入口都打不开，比不做还糟，所以同样兜住
            AppLog.e("KeepAliveActivity", "初始化失败", t);
            try {
                Toast.makeText(this, "这个页面初始化失败", Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            finish();
        }
    }

    /**
     * 用户跳到系统设置改完再回来，必须重新判定一遍。
     * 只在 onCreate 判一次的话，用户改完看到还是「未开启」，会以为按钮没用。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (tvBattery == null) {
            return;
        }
        refresh();
    }

    // ---------------------------------------------------------------- 状态判定

    private void refresh() {
        int ready = 0;
        int pending = 0;

        // ---- 1. 电池优化白名单（API 23+ 才有这个 API，21/22 直接判「无需设置」）----
        if (Build.VERSION.SDK_INT >= 23) {
            batteryOn = isIgnoringBatteryOptimizations();
            paint(tvBattery, batteryOn);
            // 行按钮在低于 23 上没意义，点过去只会白跳一次
            setGoVisible(R.id.btn_battery_go, true);
            if (batteryOn) {
                ready++;
            } else {
                pending++;
            }
        } else {
            batteryOn = false;
            paintNotApplicable(tvBattery);
            setGoVisible(R.id.btn_battery_go, false);
        }

        // ---- 2. 厂商自启动：查不到，老实说「需手动确认」----
        // 这一项恒为待确认，不计入「已就绪」，否则汇总会骗用户说全好了
        tvAutorun.setText(R.string.ka_state_manual);
        tvAutorun.setTextColor(color(R.color.accent));
        setGoVisible(R.id.btn_autorun_go, true);
        pending++;

        // ---- 3. 悬浮窗 / 允许后台弹出界面（API 23+）----
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                overlayOn = Settings.canDrawOverlays(this);
            } catch (Throwable t) {
                // 极少数 ROM 在 canDrawOverlays 上抛异常，按「未开启」处理而不是崩
                AppLog.w("KeepAliveActivity", "canDrawOverlays 异常：" + t.getMessage());
                overlayOn = false;
            }
            paint(tvOverlay, overlayOn);
            setGoVisible(R.id.btn_overlay_go, true);
            if (overlayOn) {
                ready++;
            } else {
                pending++;
            }
        } else {
            // API 21/22 没有悬浮窗权限这回事
            overlayOn = false;
            paintNotApplicable(tvOverlay);
            setGoVisible(R.id.btn_overlay_go, false);
        }

        // ---- 4. 通知是否允许（API 24+；23 及以下只有「通知开关」在应用详情页里）----
        if (Build.VERSION.SDK_INT >= 24) {
            notifyOn = areNotificationsEnabled();
            paint(tvNotify, notifyOn);
            setGoVisible(R.id.btn_notify_go, true);
            if (notifyOn) {
                ready++;
            } else {
                pending++;
            }
        } else {
            notifyOn = false;
            paintNotApplicable(tvNotify);
            setGoVisible(R.id.btn_notify_go, false);
        }

        if (pending == 0) {
            tvSummary.setText(R.string.ka_summary_all_ok);
            tvSummary.setTextColor(color(R.color.primary));
        } else {
            tvSummary.setText(getString(R.string.ka_summary_fmt,
                    Integer.valueOf(ready), Integer.valueOf(pending)));
            tvSummary.setTextColor(color(R.color.accent));
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return false;
            }
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            AppLog.w("KeepAliveActivity", "查电池白名单失败：" + t.getMessage());
            return false;
        }
    }

    private boolean areNotificationsEnabled() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable t) {
            AppLog.w("KeepAliveActivity", "查通知开关失败：" + t.getMessage());
            return false;
        }
    }

    /** 三种状态：已开启（绿）/ 未开启（橙）/ 本机无需设置（灰） */
    private void paint(TextView tv, boolean on) {
        tv.setText(on ? R.string.ka_state_on : R.string.ka_state_off);
        tv.setTextColor(color(on ? R.color.primary : R.color.accent));
    }

    private void paintNotApplicable(TextView tv) {
        tv.setText(R.string.ka_state_na);
        tv.setTextColor(color(R.color.text_sub));
    }

    private int color(int id) {
        return getResources().getColor(id);
    }

    private void setGoVisible(int btnId, boolean visible) {
        View btn = findViewById(btnId);
        if (btn != null) {
            // 降级时「隐藏」而不是「禁用」：禁用的按钮在国产 ROM 上还会留个灰影子，
            // 用户会反复去点它。隐藏更干净。
            btn.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    // ---------------------------------------------------------------- 跳转

    private static final int ACTION_BATTERY = 1;
    private static final int ACTION_AUTORUN = 2;
    private static final int ACTION_OVERLAY = 3;
    private static final int ACTION_NOTIFY = 4;

    private void bindRow(int rowId, int btnId, final int action) {
        View.OnClickListener l = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jump(action);
            }
        };
        View row = findViewById(rowId);
        if (row != null) {
            row.setOnClickListener(l);
        }
        View btn = findViewById(btnId);
        if (btn != null) {
            btn.setOnClickListener(l);
        }
    }

    private void jump(int action) {
        try {
            if (action == ACTION_BATTERY) {
                jumpBattery();
            } else if (action == ACTION_AUTORUN) {
                jumpAppDetails();
            } else if (action == ACTION_OVERLAY) {
                jumpOverlay();
            } else if (action == ACTION_NOTIFY) {
                jumpNotification();
            }
        } catch (ActivityNotFoundException e) {
            // 国产 ROM 砍设置页 —— 这是必然会遇到的情况，不是异常情况
            AppLog.w("KeepAliveActivity", "设置页被系统干掉 action=" + action
                    + " " + e.getMessage());
            onJumpFailed();
        } catch (Throwable t) {
            // SecurityException：某些 ROM 要求权限才能跳（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            // 在个别厂商 ROM 上会直接抛这个）；同样不能让用户看着闪退
            AppLog.e("KeepAliveActivity", "跳设置页失败 action=" + action, t);
            onJumpFailed();
        }
    }

    /**
     * 电池白名单。首选带 package 的直连 intent —— 它会直接弹「是否允许」对话框，
     * 用户点一下就完事，不用自己在列表里找。
     * 退路是纯列表页：部分 ROM（尤其是改了包名策略的 MIUI）会让带 package 的
     * intent 匹配不到任何 Activity 而抛 ActivityNotFoundException。
     */
    private void jumpBattery() {
        if (Build.VERSION.SDK_INT < 23) {
            onJumpFailed();
            return;
        }
        Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        direct.setData(Uri.parse("package:" + getPackageName()));
        if (tryStart(direct)) {
            return;
        }
        if (!tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            onJumpFailed();
        }
    }

    /**
     * 厂商后台管理查不到 API，只能把用户送到应用详情页自己找。
     * MIUI 上详情页里才有「省电策略 → 自启动 / 后台弹窗」这一层。
     */
    private void jumpAppDetails() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        if (!tryStart(i)) {
            onJumpFailed();
        }
    }

    private void jumpOverlay() {
        if (Build.VERSION.SDK_INT < 23) {
            onJumpFailed();
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        i.setData(Uri.parse("package:" + getPackageName()));
        // 有的 ROM 只认不带 data 的裸 action（显示所有应用列表），所以两条都试
        if (!tryStart(i) && !tryStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) {
            onJumpFailed();
        }
    }

    private void jumpNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            // 26+ 有专属的通知设置页，能直接落到本 App 的开关上
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (tryStart(i)) {
                return;
            }
        }
        // 24/25 没有这个 action；厂商 ROM 也可能没实现，统一退到应用详情页
        jumpAppDetails();
    }

    /**
     * 所有跳转的唯一出口：先查再跳，最后兜底。
     * 查得到 → startActivity；查不到 → 让上层给一句人话提示，绝不抛出去。
     */
    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            AppLog.w("KeepAliveActivity", "intent 无对应 Activity: " + i.getAction()
                    + " / " + e.getMessage());
            return false;
        } catch (SecurityException e) {
            AppLog.w("KeepAliveActivity", "intent 被系统拒绝: " + i.getAction()
                    + " / " + e.getMessage());
            return false;
        }
    }

    private void onJumpFailed() {
        Toast.makeText(this, R.string.ka_jump_failed, Toast.LENGTH_LONG).show();
    }
}