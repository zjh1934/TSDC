package com.listenbook.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.listenbook.LogProvider;
import com.listenbook.R;
import com.listenbook.util.AppLog;
import com.listenbook.util.Settings;
import com.listenbook.util.WavTool;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 诊断日志页。把 AppLog 内存里那份拿出来看，并导出 txt / md 发回来。
 *
 * 出现导出失败、引擎莫名无回调这类问题时，靠 dump猜测 是没有出路的：
 * 让用户三秒钟导出一份带设备信息和完整时间线的文件，比来回问十句强。
 *
 * 顺便把「高级参数」放这儿 —— 这些是给排查问题用的旋钮，
 * 放在导出页会干扰正常使用。
 */
public class DiagnosticsActivity extends Activity {

    private static final int LOG_MAX = 600;

    private ListView listLog;
    private EditText etSearch;
    private TextView tvCount;
    private TextView tvSysinfo;
    private LogAdapter logAdapter;

    private int minLevel = AppLog.INFO;
    private String keyword = "";
    private File lastExport;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        setContentView(R.layout.activity_diagnostics);
        setTitle(R.string.title_diagnostics);

        tvSysinfo = (TextView) findViewById(R.id.tv_sysinfo);
        tvCount = (TextView) findViewById(R.id.tv_count);
        etSearch = (EditText) findViewById(R.id.et_search);
        listLog = (ListView) findViewById(R.id.list_log);

        final TextView chipAll = (TextView) findViewById(R.id.chip_all);
        final TextView chipInfo = (TextView) findViewById(R.id.chip_info);
        final TextView chipWarn = (TextView) findViewById(R.id.chip_warn);
        final TextView chipError = (TextView) findViewById(R.id.chip_error);

        View advanced = LayoutInflater.from(this).inflate(R.layout.diag_advanced, null);
        listLog.addFooterView(advanced);
        bindAdvanced(advanced);

        logAdapter = new LogAdapter();
        listLog.setAdapter(logAdapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                keyword = s == null ? "" : s.toString();
                scheduleRefresh();
            }
        });

        chipAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLevel(AppLog.DEBUG, chipAll, chipInfo, chipWarn, chipError);
            }
        });
        chipInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLevel(AppLog.INFO, chipAll, chipInfo, chipWarn, chipError);
            }
        });
        chipWarn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLevel(AppLog.WARN, chipAll, chipInfo, chipWarn, chipError);
            }
        });
        chipError.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLevel(AppLog.ERROR, chipAll, chipInfo, chipWarn, chipError);
            }
        });
        minLevel = Settings.logLevel(this);
        applyLevelChips(chipAll, chipInfo, chipWarn, chipError);

        findViewById(R.id.btn_copy_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAll();
            }
        });
        findViewById(R.id.btn_md).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(true);
            }
        });
        findViewById(R.id.btn_txt).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                export(false);
            }
        });
        findViewById(R.id.btn_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        findViewById(R.id.btn_clear_log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Sheet.confirm(DiagnosticsActivity.this, getString(R.string.diag_clear),
                        "会同时清掉文件里的历史日志，确定？", getString(R.string.diag_clear),
                        true, new Runnable() {
                            @Override
                            public void run() {
                                AppLog.clear();
                                lastExport = null;
                                refresh();
                                toast(getString(R.string.diag_cleared));
                            }
                        });
            }
        });

        listLog.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                copyLogLine(position);
            }
        });

        tvSysinfo.setText(AppLog.header() + "内存条目 " + AppLog.count()
                + " · 文件占用 " + WavTool.humanSize(AppLog.fileSize()));
        refresh();
    }

    private void setLevel(int level, TextView all, TextView info, TextView warn, TextView error) {
        minLevel = level;
        Settings.setLogLevel(this, level);
        applyLevelChips(all, info, warn, error);
        refresh();
    }

    private void applyLevelChips(TextView all, TextView info, TextView warn, TextView error) {
        all.setSelected(minLevel <= AppLog.DEBUG);
        info.setSelected(minLevel == AppLog.INFO);
        warn.setSelected(minLevel == AppLog.WARN);
        error.setSelected(minLevel == AppLog.ERROR);
        int white = 0xFFFFFFFF;
        int dark = getResources().getColor(R.color.text_main);
        all.setTextColor(all.isSelected() ? white : dark);
        info.setTextColor(info.isSelected() ? white : dark);
        warn.setTextColor(warn.isSelected() ? white : dark);
        error.setTextColor(error.isSelected() ? white : dark);
    }

    private void scheduleRefresh() {
        listLog.removeCallbacks(refresher);
        listLog.postDelayed(refresher, 200L);
    }

    private void refresh() {
        List<AppLog.Entry> all = AppLog.snapshot(minLevel, keyword);
        List<AppLog.Entry> show = new ArrayList<AppLog.Entry>();
        // 最新的在最后（配合 stackFromBottom，打开就停在最新处），但要限流
        int from = Math.max(0, all.size() - LOG_MAX);
        for (int i = from; i < all.size(); i++) {
            show.add(all.get(i));
        }
        logAdapter.setEntries(show);
        tvCount.setText(getString(R.string.diag_count, Integer.valueOf(all.size()))
                + (all.size() > LOG_MAX ? "（界面显示最后 " + LOG_MAX + " 条，导出是全部）" : ""));
        tvSysinfo.setText(AppLog.header() + "内存条目 " + AppLog.count()
                + " · 文件占用 " + WavTool.humanSize(AppLog.fileSize()));
    }

    private void copyLogLine(int position) {
        AppLog.Entry e = logAdapter.entryAt(position);
        if (e == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        String text = e.timeText() + " " + e.levelName() + "/" + e.tag + " " + e.msg;
        cm.setPrimaryClip(ClipData.newPlainText("log", text));
        toast("已复制这行日志");
    }

    private File exportDir() {
        File base = null;
        if (Build.VERSION.SDK_INT >= 19) {
            base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        }
        if (base == null) {
            base = getExternalFilesDir(null);
        }
        if (base == null) {
            base = getFilesDir();
        }
        File dir = new File(base, "diag");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 一键复制整份日志到剪贴板。
     *
     * 原来出问题的路径是「导出文件 → 分享 → 保存 → 再发出来」，
     * 中间还要过一遍系统分享面板，非常绕。这里一步到位。
     *
     * 两个坑：
     *   1. 生成 Markdown 要把几百条拼起来，放后台线程，别卡 UI
     *   2. 部分 ROM 的剪贴板对单次文本长度有上限（常见 8000 字符），
     *      超了会静默失败，用户复制完发现是空的。所以先探一下上限，
     *      超了就只保留「最关键的部分」—— 崩溃现场和最后 N 条，
     *      这正是排查问题真正需要的，中间的重复轮询日志可以丢。
     */
    private void copyAll() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String full = AppLog.dump(minLevel, keyword);
                final String text = trimForClipboard(full);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm == null) {
                            toast(getString(R.string.diag_copy_failed, "拿不到剪贴板"));
                            return;
                        }
                        try {
                            cm.setPrimaryClip(ClipData.newPlainText("listenbook-log", text));
                        } catch (Throwable t) {
                            toast(getString(R.string.diag_copy_failed, t.getMessage()));
                            return;
                        }
                        String size = WavTool.humanSize(text.getBytes().length);
                        toast(getString(R.string.diag_copied, size));
                        AppLog.i("Diagnostics", "一键复制日志 " + size);
                        if (text.length() < full.length()) {
                            Sheet.confirm(DiagnosticsActivity.this, "日志太长，只复制了关键部分",
                                    "完整日志有 " + WavTool.humanSize(full.getBytes().length)
                                            + "，剪贴板放不下。\n\n"
                                            + "已经优先保留：设备信息、崩溃现场、最后 200 条。\n"
                                            + "要完整版请点「导出 Markdown」拿文件。",
                                    getString(R.string.ok), false, null);
                        }
                    }
                });
            }
        }, "copy-log").start();
    }

    /**
     * 剪贴板放不下时，砍掉中间部分，保留最有诊断价值的两头。
     * 保守上限 8000 字符：几乎所有 ROM 都容得下，超出就砍。
     */
    private static final int CLIP_LIMIT = 8000;

    private String trimForClipboard(String full) {
        if (full == null || full.length() <= CLIP_LIMIT) {
            return full;
        }
        List<AppLog.Entry> snapshot = AppLog.snapshot(minLevel, keyword);
        if (snapshot == null || snapshot.isEmpty()) {
            return full.substring(full.length() - CLIP_LIMIT);
        }
        // 头：设备信息与崩溃现场；尾：崩之前最后做的事
        int keepTail = Math.min(snapshot.size(), 200);
        List<AppLog.Entry> tail = snapshot.subList(
                Math.max(0, snapshot.size() - keepTail), snapshot.size());
        String head = AppLog.header();
        String body = AppLog.toMarkdown(tail);
        String note = "\n\n> ⚠ 日志过长，中间部分已省略，保留最后 "
                + keepTail + " 条。完整版请导出文件。\n";
        String out = head + note + body;
        return out.length() <= CLIP_LIMIT ? out : out.substring(0, CLIP_LIMIT);
    }

    private void export(final boolean markdown) {
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA)
                .format(new Date());
        final File target = new File(exportDir(),
                "listenbook-log-" + stamp + (markdown ? ".md" : ".txt"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = AppLog.writeTo(target, markdown, minLevel, keyword);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            lastExport = target;
                            String path = target.getAbsolutePath();
                            AppLog.i("Diagnostics", "导出日志到 " + path);
                            Sheet.confirm(DiagnosticsActivity.this, "日志已导出",
                                    getString(R.string.diag_exported, path)
                                            + "\n\n要现在分享出去吗？点「分享」，也可以点「复制路径」"
                                            + "自己用文件管理器拿。",
                                    getString(R.string.diag_share), false, new Runnable() {
                                        @Override
                                        public void run() {
                                            share();
                                        }
                                    });
                            ClipboardManager cm = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("path", target.getAbsolutePath()));
                            }
                        } else {
                            toast(getString(R.string.diag_export_failed, "写文件失败"));
                        }
                    }
                });
            }
        }, "export-log").start();
    }

    private void share() {
        if (lastExport == null || !lastExport.isFile()) {
            toast("先点「导出 Markdown」或「导出 TXT」生成文件");
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse("content://" + LogProvider.AUTHORITY + "/" + lastExport.getName());
        } catch (Exception e) {
            toast(getString(R.string.diag_export_failed, e.getMessage()));
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(lastExport.getName().endsWith(".md") ? "text/markdown" : "text/plain");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.putExtra(Intent.EXTRA_SUBJECT, "听书导出 · 诊断日志");
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, getString(R.string.diag_share)));
        } catch (Exception e) {
            toast("没有可以接收文件的应用");
        }
    }

    // ------------------------------------------------------------------ 高级参数

    private void bindAdvanced(View root) {
        final TextView tvChunk = (TextView) root.findViewById(R.id.tv_chunk_value);
        final TextView tvSlack = (TextView) root.findViewById(R.id.tv_slack_value);
        final TextView tvAttempts = (TextView) root.findViewById(R.id.tv_attempts_value);
        final TextView tvVerbose = (TextView) root.findViewById(R.id.tv_verbose_value);
        final TextView tvLevel = (TextView) root.findViewById(R.id.tv_loglevel_value);

        tvChunk.setText(Settings.chunkBudget(this) + " 字/块");
        tvSlack.setText("×" + Settings.timeoutSlack(this));
        tvAttempts.setText(Settings.maxAttempts(this) + " 次");
        tvVerbose.setText(Settings.verboseTts(this) ? "开" : "关");
        tvLevel.setText(levelName(Settings.logLevel(this)));

        root.findViewById(R.id.row_chunk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int[] options = new int[]{800, 1500, 2500, 3500};
                pickNumber(getString(R.string.advanced_chunk), getString(R.string.advanced_chunk_desc),
                        options, Settings.chunkBudget(DiagnosticsActivity.this),
                        new OnPicked() {
                            @Override
                            public void onPicked(int value) {
                                Settings.setChunkBudget(DiagnosticsActivity.this, value);
                                tvChunk.setText(value + " 字/块");
                                AppLog.i("Diagnostics", "调整单块字数 → " + value);
                            }
                        });
            }
        });
        root.findViewById(R.id.row_slack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int[] options = new int[]{2, 3, 5, 8};
                pickNumber(getString(R.string.advanced_slack), getString(R.string.advanced_slack_desc),
                        options, Settings.timeoutSlack(DiagnosticsActivity.this),
                        new OnPicked() {
                            @Override
                            public void onPicked(int value) {
                                Settings.setTimeoutSlack(DiagnosticsActivity.this, value);
                                tvSlack.setText("×" + value);
                                AppLog.i("Diagnostics", "调整超时余量 → x" + value);
                            }
                        });
            }
        });
        root.findViewById(R.id.row_attempts).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int[] options = new int[]{1, 2, 3, 5};
                pickNumber(getString(R.string.advanced_attempts),
                        getString(R.string.advanced_attempts_desc),
                        options, Settings.maxAttempts(DiagnosticsActivity.this),
                        new OnPicked() {
                            @Override
                            public void onPicked(int value) {
                                Settings.setMaxAttempts(DiagnosticsActivity.this, value);
                                tvAttempts.setText(value + " 次");
                                AppLog.i("Diagnostics", "调整重试次数 → " + value);
                            }
                        });
            }
        });
        root.findViewById(R.id.row_verbose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
                rows.add(new Sheet.Row("关", "只记关键节点", "0"));
                rows.add(new Sheet.Row("开", "每一块的 TTS 回调都记，日志会变大", "1"));
                int checked = Settings.verboseTts(DiagnosticsActivity.this) ? 1 : 0;
                Sheet.list(DiagnosticsActivity.this, getString(R.string.advanced_verbose),
                        getString(R.string.advanced_verbose_desc), rows, checked,
                        new Sheet.Callback() {
                            @Override
                            public void onSelected(int index, Sheet.Row row) {
                                boolean on = "1".equals(row.tag);
                                Settings.setVerboseTts(DiagnosticsActivity.this, on);
                                AppLog.setVerbose(on);
                                tvVerbose.setText(on ? "开" : "关");
                            }
                        });
            }
        });
        root.findViewById(R.id.row_loglevel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int[] levels = new int[]{
                        AppLog.DEBUG, AppLog.INFO, AppLog.WARN, AppLog.ERROR};
                List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
                int checked = 0;
                int current = Settings.logLevel(DiagnosticsActivity.this);
                for (int i = 0; i < levels.length; i++) {
                    rows.add(new Sheet.Row(levelName(levels[i]), "", String.valueOf(levels[i])));
                    if (levels[i] == current) {
                        checked = i;
                    }
                }
                Sheet.list(DiagnosticsActivity.this, getString(R.string.advanced_loglevel),
                        null, rows, checked, new Sheet.Callback() {
                            @Override
                            public void onSelected(int index, Sheet.Row row) {
                                int level = Integer.parseInt(row.tag);
                                Settings.setLogLevel(DiagnosticsActivity.this, level);
                                tvLevel.setText(levelName(level));
                                minLevel = level;
                                refresh();
                            }
                        });
            }
        });
    }

    private interface OnPicked {
        void onPicked(int value);
    }

    private void pickNumber(String title, String desc, int[] options, int current,
                            final OnPicked callback) {
        List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            rows.add(new Sheet.Row(String.valueOf(options[i]), "", String.valueOf(options[i])));
            if (options[i] == current) {
                checked = i;
            }
        }
        Sheet.list(this, title, desc, rows, checked, new Sheet.Callback() {
            @Override
            public void onSelected(int index, Sheet.Row row) {
                callback.onPicked(Integer.parseInt(row.tag));
            }
        });
    }

    private String levelName(int level) {
        switch (level) {
            case AppLog.DEBUG:
                return "调试";
            case AppLog.WARN:
                return "警告";
            case AppLog.ERROR:
                return "错误";
            default:
                return "信息";
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private final class LogAdapter extends BaseAdapter {

        private final List<AppLog.Entry> entries = new ArrayList<AppLog.Entry>();

        void setEntries(List<AppLog.Entry> list) {
            entries.clear();
            entries.addAll(list);
            notifyDataSetChanged();
        }

        AppLog.Entry entryAt(int position) {
            return position < 0 || position >= entries.size() ? null : entries.get(position);
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(DiagnosticsActivity.this)
                        .inflate(R.layout.item_log, parent, false);
            }
            AppLog.Entry e = entries.get(position);
            TextView tv = (TextView) v.findViewById(R.id.log_line);
            tv.setText(e.timeText() + "  " + e.levelName() + "/" + e.tag + "\n" + e.msg);
            int color;
            if (e.level >= AppLog.ERROR) {
                color = getResources().getColor(R.color.danger);
            } else if (e.level >= AppLog.WARN) {
                color = getResources().getColor(R.color.accent);
            } else {
                color = getResources().getColor(R.color.text_sub);
            }
            tv.setTextColor(color);
            return v;
        }
    }
}
