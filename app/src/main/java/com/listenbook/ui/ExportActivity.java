package com.listenbook.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.listenbook.LogProvider;
import com.listenbook.R;
import com.listenbook.export.ExportJob;
import com.listenbook.export.ExportQueue;
import com.listenbook.export.ExportSelfTest;
import com.listenbook.export.ExportState;
import com.listenbook.export.ExportStore;
import com.listenbook.model.Book;
import com.listenbook.model.BookLibrary;
import com.listenbook.model.Chapter;
import com.listenbook.parse.BookParser;
import com.listenbook.player.AudioPlayer;
import com.listenbook.tts.TtsCatalog;
import com.listenbook.tts.TtsPreview;
import com.listenbook.util.AppLog;
import com.listenbook.util.MediaStorePublisher;
import com.listenbook.util.SafExport;
import com.listenbook.util.Settings;
import com.listenbook.util.WavTool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块 B 的控制台，两种形态：
 *   1. 带 bookId 进来 → 单书导出页
 *   2. 不带 bookId → 任务队列中心
 *
 * v1.2 重构的出发点（用户原话：「界面臃肿，分不清哪些有用哪些没用」）：
 *
 *   整个页面只回答四个问题，其余全部收走：
 *     1. 现在在干什么？      → 顶部常驻状态卡：徽章 + 状态 + 当前章 + 进度 + 三格统计
 *     2. 要导出哪几章？      → 「章节」标签页
 *     3. 合成出来的东西能听吗？ → 「成品」标签页 + 底部播放条（点一下就地试听）
 *     4. 文件到底存哪了？    → 成品页的「文件在哪」卡片
 *
 *   被移出主屏的东西：4 个筛选按钮（收进 Sheet）、引擎/音色/语速（折叠成一行摘要）、
 *   总字数/总时长/预计大小三连数字（并成书头下一行灰字）。
 *
 * 合成本身不归这个页面管，关掉它导出照跑；这里只观察 ExportState 并画界面。
 */
public class ExportActivity extends Activity {

    private static final int REQ_STORAGE = 2001;

    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- 任务中心 ----
    private View panelBook;
    private View panelCenter;
    private ProgressBar centerProgress;
    private TextView tvCenterStatus;
    private TextView tvCenterDetail;
    private ListView listQueue;
    private QueueAdapter queueAdapter;

    // ---- 顶栏（L1） ----
    private TextView tvBook;
    private TextView tvBookMeta;
    private TextView btnBack;
    private TextView btnMore;
    private View dotQueue;

    // ---- 状态条（L1.5，常驻一行） ----
    private TextView badgeStatus;
    private TextView tvStatusLine;
    private TextView tvPercent;
    private TextView btnFailBadge;
    private ProgressBar progressLine;

    // ---- 完整状态卡（已降级为 Sheet 里的内容，按需绑定） ----
    private View sheetStatus;

    // ---- 标签页与筛选 ----
    private TextView tabChapters;
    private TextView tabOutputs;
    private View filterChapterBar;
    private View filterOutputBar;
    private TextView filterChPending;
    private TextView filterChDone;
    private ListView listView;
    private ChapterAdapter adapter;

    // ---- 成品页 ----
    private ListView listOutputs;
    private OutputAdapter outputAdapter;
    private View outputEmpty;
    private TextView tvOutputEmptyTitle;
    private TextView tvOutputEmptySub;

    // ---- 续传提示 ----
    private TextView tvResumeHint;

    // ---- 播放条（固定底部，可收起） ----
    private View playbar;
    private View playBarExpanded;
    private View playBarCollapsed;
    private TextView btnPlayToggle;
    private TextView btnPlayToggleMini;
    private TextView tvPlayTitle;
    private TextView tvPlayTitleMini;
    private TextView tvPlaySub;
    private SeekBar playSeek;

    // ---- 合成设置（已搬进 Sheet，打开时才绑定） ----
    private TextView tvSettingBrief;
    private TextView tvEngine;
    private TextView tvVoice;
    private TextView tvRate;
    private TextView tvPitch;
    private SeekBar barRate;
    private SeekBar barPitch;
    private TextView chipAuto;
    private TextView chipZh;
    private TextView chipEn;
    private TextView tvCustomDir;
    private CheckBox chkSync;
    private TextView btnPreview;
    private TextView tvPreviewStatus;
    private CheckBox chkFloating;
    private TextView tvFloatingHint;
    /** syncFloatingUi 期间为 true，用来把程序性 setChecked 与用户点击区分开 */
    private boolean syncingFloating;
    /** 试听实例。设置弹层可以反复开关，必须挂在 Activity 上而不是弹层上 */
    private TtsPreview preview;
    /** 上次渲染时「引擎是否被占」的快照，用来躲开 500ms 轮询里的重复 setText */
    private boolean lastPreviewBusy;

    private String bookId;
    private Book book;
    private List<Chapter> chapters = new ArrayList<Chapter>();
    private ExportStore store;
    private boolean loaded;
    private boolean hasBook;
    private int lastDone = -1;
    private volatile boolean scanning;
    /** scanning 期间又来请求：扫完补跑一次，别让刷新被吞掉 */
    private volatile boolean rescanAgain;
    /** 当前标签页：0 = 章节，1 = 成品 */
    private int tab = 0;
    /** 播放条正在展示的文件绝对路径，空串表示播放条收起 */
    private String playingPath = "";

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            try {
                renderState();
            } catch (Throwable t) {
                // 轮询每 500ms 跑一次，一次崩了如果不管就会连着崩几百次，
                // 把日志刷爆，真正的原因反而被埋掉。
                AppLog.step("ExportActivity:轮询崩了 " + t.getClass().getName()
                        + " " + t.getMessage());
                stopPolling();
            }
        }
    };

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        AppLog.step("ExportActivity.onCreate:begin");
        try {
            onCreateInner(savedInstanceState);
        } catch (Throwable t) {
            // 兜底：这一页崩掉就是「点合成音频立刻闪退」。
            // 无论什么原因，都不能让它变成「闪退后什么线索都没有」——
            // 把现场同步写进日志，再给用户一句看得懂的话。
            AppLog.step("ExportActivity.onCreate:崩了 " + t.getClass().getName()
                    + " " + t.getMessage());
            AppLog.e("ExportActivity", "onCreate 异常", t);
            try {
                Toast.makeText(this, "导出页初始化失败，已记入日志",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            finish();
        }
    }

    private void onCreateInner(Bundle savedInstanceState) {
        setContentView(R.layout.activity_export);
        AppLog.step("ExportActivity.onCreate:setContentView-ok");

        panelBook = findViewById(R.id.panel_book);
        panelCenter = findViewById(R.id.panel_center);

        bookId = getIntent() == null ? null : getIntent().getStringExtra("bookId");
        hasBook = bookId != null && bookId.length() > 0;
        AppLog.i("ExportActivity", "打开导出页 bookId=" + (hasBook ? bookId : "<无>"));

        AppLog.step("ExportActivity.onCreate:hasBook=" + hasBook);
        if (hasBook) {
            panelCenter.setVisibility(View.GONE);
            bindBookViews();
            AppLog.step("ExportActivity.onCreate:bindBookViews-ok");
            setStatus(R.drawable.bg_badge_idle, getString(R.string.st_loading), "");
            loadAsync();
        } else {
            panelBook.setVisibility(View.GONE);
            panelCenter.setVisibility(View.VISIBLE);
            setTitle(R.string.queue_title);
            bindCenterViews();
        }
    }

    private void bindCenterViews() {
        tvCenterStatus = (TextView) findViewById(R.id.tv_center_status);
        tvCenterDetail = (TextView) findViewById(R.id.tv_center_detail);
        centerProgress = (ProgressBar) findViewById(R.id.center_progress);
        listQueue = (ListView) findViewById(R.id.list_queue);
        queueAdapter = new QueueAdapter();
        listQueue.setAdapter(queueAdapter);
        listQueue.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ExportJob job = queueAdapter.jobAt(position);
                if (job == null) {
                    return;
                }
                showJobActions(job, position);
            }
        });
        findViewById(R.id.btn_center_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ExportState.running) {
                    toast("当前没有正在跑的任务");
                    return;
                }
                ExportJob.requestStop(ExportActivity.this);
                toast(getString(R.string.toast_stopping));
            }
        });
        findViewById(R.id.btn_center_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearQueue();
            }
        });
        findViewById(R.id.btn_center_empty).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_center_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hasBook && book != null) {
                    panelCenter.setVisibility(View.GONE);
                    panelBook.setVisibility(View.VISIBLE);
                    renderState();
                } else {
                    finish();
                }
            }
        });
    }

    private void bindBookViews() {
        tvBook = (TextView) findViewById(R.id.tv_book);
        tvBookMeta = (TextView) findViewById(R.id.tv_book_meta);
        btnBack = (TextView) findViewById(R.id.btn_back);
        btnMore = (TextView) findViewById(R.id.btn_more);
        dotQueue = findViewById(R.id.dot_queue);

        // 常驻状态条
        badgeStatus = (TextView) findViewById(R.id.badge_status);
        tvStatusLine = (TextView) findViewById(R.id.tv_status_line);
        tvPercent = (TextView) findViewById(R.id.tv_percent);
        btnFailBadge = (TextView) findViewById(R.id.btn_fail_badge);
        progressLine = (ProgressBar) findViewById(R.id.progress_line);

        tabChapters = (TextView) findViewById(R.id.tab_chapters);
        tabOutputs = (TextView) findViewById(R.id.tab_outputs);
        filterChapterBar = findViewById(R.id.filter_chapter_bar);
        filterOutputBar = findViewById(R.id.filter_output_bar);
        filterChPending = (TextView) findViewById(R.id.filter_ch_pending);
        filterChDone = (TextView) findViewById(R.id.filter_ch_done);
        listView = (ListView) findViewById(R.id.list_chapters);
        listOutputs = (ListView) findViewById(R.id.list_outputs);
        outputEmpty = findViewById(R.id.output_empty);
        tvOutputEmptyTitle = (TextView) findViewById(R.id.tv_output_empty_title);
        tvOutputEmptySub = (TextView) findViewById(R.id.tv_output_empty_sub);
        tvResumeHint = (TextView) findViewById(R.id.tv_resume_hint);

        playbar = findViewById(R.id.playback_bar);
        playBarExpanded = findViewById(R.id.play_bar_expanded);
        playBarCollapsed = findViewById(R.id.play_bar_collapsed);
        btnPlayToggle = (TextView) findViewById(R.id.btn_play_toggle);
        btnPlayToggleMini = (TextView) findViewById(R.id.btn_play_toggle_mini);
        tvPlayTitle = (TextView) findViewById(R.id.tv_play_title);
        tvPlayTitleMini = (TextView) findViewById(R.id.tv_play_title_mini);
        tvPlaySub = (TextView) findViewById(R.id.tv_play_sub);
        playSeek = (SeekBar) findViewById(R.id.play_seek);

        adapter = new ChapterAdapter(this);
        adapter.setOnPlayClick(new ChapterAdapter.OnPlayClick() {
            @Override
            public void onPlay(Chapter chapter, File file) {
                playOutput(file);
            }
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (ExportState.running) {
                    toast(getString(R.string.toast_exporting_locked));
                    return;
                }
                adapter.toggle(position);
                updateSelection();
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showChapterActions(position);
                return true;
            }
        });

        outputAdapter = new OutputAdapter();
        listOutputs.setAdapter(outputAdapter);
        listOutputs.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File f = outputAdapter.fileAt(position);
                if (f != null) {
                    playOutput(f);
                }
            }
        });
        listOutputs.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                ExportStore.Output o = outputAdapter.outputAt(position);
                if (o != null) {
                    showOutputActions(o);
                }
                return true;
            }
        });

        bindTabsAndFilters();
        bindStatusActions();
        bindPlayback();
        bindTopBar();

        syncFilterChips();
        setTab(0);
    }

    // ---------------------------------------------------------------- 标签页

    private void bindTabsAndFilters() {
        tabChapters.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTab(0);
            }
        });
        tabOutputs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTab(1);
            }
        });
        filterChPending.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adapter.setFilter(ChapterAdapter.FILTER_PENDING);
                syncFilterChips();
            }
        });
        filterChDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adapter.setFilter(ChapterAdapter.FILTER_DONE);
                syncFilterChips();
            }
        });
        // 原来平铺的 4 个筛选按钮收进这个菜单，窄屏不再折成两行
        findViewById(R.id.btn_select_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSelectMenu();
            }
        });
        findViewById(R.id.btn_output_info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWhereFiles();
            }
        });
        findViewById(R.id.btn_output_clean).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmCleanAllOutputs();
            }
        });
    }

    private void bindStatusActions() {
        findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ExportState.running) {
                    toast("当前没有正在跑的导出任务");
                    return;
                }
                ExportJob.requestStop(ExportActivity.this);
                toast(getString(R.string.toast_stopping));
            }
        });
    }

    // ---------------------------------------------------------------- 顶栏与菜单

    /**
     * 低频入口全部收进右上角 ⋮。
     * 这是这版改动的核心：设置21 个元素、队列 5 个元素原来平铺在正文里，
     * 跟章节列表抢同一块屏幕，页面被塞满又每一块都不好用。
     */
    private void bindTopBar() {
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        if (btnMore != null) {
            btnMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMoreMenu();
                }
            });
        }
        if (dotQueue != null) {
            dotQueue.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMoreMenu();
                }
            });
        }
        final View statusBar = findViewById(R.id.status_bar);
        if (statusBar != null) {
            statusBar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showStatusSheet();
                }
            });
        }
        TextView expand = (TextView) findViewById(R.id.btn_status_expand);
        if (expand != null) {
            expand.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showStatusSheet();
                }
            });
        }
        if (btnFailBadge != null) {
            btnFailBadge.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFailureReport();
                }
            });
        }
    }

    private void showMoreMenu() {
        View root = LayoutInflater.from(this).inflate(R.layout.sheet_menu, null);
        TextView title = (TextView) root.findViewById(R.id.sheet_title);
        TextView sub = (TextView) root.findViewById(R.id.sheet_sub);
        if (book != null) {
            title.setText(book.title);
        }
        if (tvSettingBrief != null && tvSettingBrief.getText() != null) {
            sub.setText(tvSettingBrief.getText());
            sub.setVisibility(View.VISIBLE);
        } else {
            sub.setText(settingBriefText());
            sub.setVisibility(View.VISIBLE);
        }

        final TextView queueHint = (TextView) root.findViewById(R.id.tv_queue_hint);
        int pending = ExportQueue.pendingCount();
        queueHint.setText(pending > 0 ? getString(R.string.queue_dot_fmt,
                Integer.valueOf(pending)) : "");

        // 注意：row_failure / row_clean 在布局里是 LinearLayout（整行可点），
        // 不是 TextView。这里强转成 TextView 会抛 ClassCastException，
        // 表现就是「点右上角 ⋮ 菜单直接崩」。它们只用来控制显隐，View 就够。
        final View failRow = root.findViewById(R.id.row_failure);
        final View cleanRow = root.findViewById(R.id.row_clean);
        boolean anyFailed = ExportState.failed > 0 || ExportState.failedRun;
        failRow.setVisibility(anyFailed ? View.VISIBLE : View.GONE);
        int doneCount = store == null ? 0 : store.completedCount();
        cleanRow.setVisibility(doneCount > 0 ? View.VISIBLE : View.GONE);

        final Dialog dialog = Sheet.showCard(this, root);

        row(root, R.id.row_settings, new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                showSettingsSheet();
            }
        });
        row(root, R.id.row_where, new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                showWhereFiles();
            }
        });
        row(root, R.id.row_queue, new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                openQueuePage();
            }
        });
        // 防杀后台引导页：紧挨「合成队列」。两者是同一件事的两面 ——
        // 队列回答「正在合成什么」，这一项回答「别让系统中途掐断它」。
        final TextView keepAliveHint = (TextView) root.findViewById(R.id.tv_keepalive_hint);
        if (keepAliveHint != null) {
            // 有没设过白名单一眼可见，用户不用点进去才知道自己有没有设过
            int ok = 0;
            int total = 0;
            if (Build.VERSION.SDK_INT >= 23) {
                total++;
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                boolean ignoring = pm != null
                        && pm.isIgnoringBatteryOptimizations(getPackageName());
                keepAliveHint.setText(ignoring ? getString(R.string.ka_state_on)
                        : getString(R.string.ka_state_off));
                if (ignoring) {
                    ok++;
                }
            } else {
                keepAliveHint.setText(R.string.ka_state_na);
            }
            AppLog.i("ExportActivity", "后台保护就绪 " + ok + "/" + total);
        }
        row(root, R.id.row_keepalive, new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                openKeepAlive();
            }
        });
        row(root, R.id.row_failure, new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                showFailureReport();
            }
        });
        row(root, R.id.row_selftest, new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                runSelfTest();
            }
        });
        row(root, R.id.row_clean, new Runnable() {
            @Override
            public void run() {
                dialog.dismiss();
                confirmCleanAllOutputs();
            }
        });
        TextView cancel = (TextView) root.findViewById(R.id.sheet_cancel);
        if (cancel != null) {
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }
    }

    private void row(View root, int id, final Runnable action) {
        View v = root.findViewById(id);
        if (v == null) {
            return;
        }
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                action.run();
            }
        });
    }

    /** 完整状态卡：原来常驻 180dp，现在按需弹出 */
    private void showStatusSheet() {
        View root = LayoutInflater.from(this).inflate(R.layout.sheet_status, null);
        sheetStatus = root;
        // 绑定完立刻用当前状态填一遍，避免弹出瞬间是空白的
        renderState();
        final Dialog dialog = Sheet.showCard(this, root);
        TextView done = (TextView) root.findViewById(R.id.sheet_done);
        if (done != null) {
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }
        TextView fail = (TextView) root.findViewById(R.id.btn_view_failure);
        if (fail != null) {
            fail.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    showFailureReport();
                }
            });
        }
    }

    /** 合成设置整块搬进 Sheet，绑定推迟到真正打开时 */
    private void showSettingsSheet() {
        final View root = LayoutInflater.from(this).inflate(R.layout.sheet_settings, null);
        final Dialog dialog = Sheet.showCard(this, root);
        bindSettings(root);
        // 关掉弹层就停试听：用户是在「设置」这个语境下按的试听，
        // 弹层没了还一直外放会变成莫名其妙的噪音，而且它占着系统 TTS 引擎。
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                stopPreview();
                btnPreview = null;
                tvPreviewStatus = null;
            }
        });

        TextView done = (TextView) root.findViewById(R.id.sheet_done);
        if (done != null) {
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }
    }

    /**
     * 打开合成队列页。
     *
     * 注意这里的判断条件：之前写的是 tvCenterStatus != null，
     * 但那个字段只在 bindCenterViews() 里赋值，而有书时onCreate 走的是 bindBookViews()，
     * 于是它恒为 null —— 面板切过去了、内容却是空白的，点哪都没反应。
     * 判据必须落在 panelCenter 上，它一定有值。
     */
    private void openQueuePage() {
        panelCenter.setVisibility(View.VISIBLE);
        panelBook.setVisibility(View.GONE);
        if (tvCenterStatus == null) {
            bindCenterViews();
        }
        renderCenter();
    }

    /**
     * 打开防杀后台引导页。用 startActivity 而不是 Sheet：
     * 那一页要让用户跳到系统设置去改权限再跳回来刷新状态，
     * 放在 Dialog 里人一走 dialog 就没了，状态永远停在跳之前那一份。
     */
    private void openKeepAlive() {
        try {
            startActivity(new Intent(this, KeepAliveActivity.class));
        } catch (Throwable t) {
            AppLog.e("ExportActivity", "打不开后台运行设置页", t);
            toast("后台运行设置页打不开，已记入日志");
        }
    }

    /**
     * 悬浮窗开关的唯一写入点。
     *
     * 点方框（CheckBox 自己翻）和点整行（代码里 toggle()）都会走到这里，
     * 所以存储、权限申请、提示都只写一份，不会出现「一半生效」的怪状态。
     */
    private void onFloatingChanged(boolean on) {
        if (syncingFloating) {
            return;
        }
        Settings.setFloatingWindow(this, on);
        if (on && !FloatingWindow.hasPermission(this)) {
            // 没权限就当场去要。开关本身先记住用户的选择，
            // 等授权完下一个任务就自动生效，不用他再回来开一次。
            FloatingWindow.requestPermission(this);
            toast(getString(R.string.floating_ask_permission));
        } else {
            toast(getString(on ? R.string.floating_toast_on
                    : R.string.floating_toast_off));
        }
        if (!on) {
            // 关掉要立刻生效，不能等下次导出
            FloatingWindow.get(this).remove();
        }
        syncFloatingUi();
    }

    private void syncFloatingUi() {
        if (chkFloating == null) {
            return;
        }
        boolean granted = FloatingWindow.hasPermission(this);
        // 关键：这是「程序把 UI 拉回真实值」，不是用户操作。
        // CheckBox.setChecked 在值相同时不触发监听，但保险起见还是挡一下 ——
        // 万一某台 ROM 对 setChecked 的行为不一致，就会陷入
        // onFloatingChanged → syncFloatingUi → setChecked → onFloatingChanged 的死循环。
        syncingFloating = true;
        try {
            chkFloating.setChecked(Settings.floatingWindow(this));
        } finally {
            syncingFloating = false;
        }
        if (tvFloatingHint != null) {
            tvFloatingHint.setText(getString(granted
                    ? R.string.floating_granted : R.string.floating_need_permission));
            // 不用 ContextCompat.getColor（那是 androidx），compileSdk 30 上
            // Resources.getColor(int) 虽被标记过时但行为一致
            tvFloatingHint.setTextColor(getResources().getColor(granted
                    ? R.color.ok_text : R.color.warn_text));
        }
    }

    private void bindPlayback() {
        btnPlayToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File f = new File(playingPath);
                if (f.isFile()) {
                    AudioPlayer.get().toggle(f);
                }
            }
        });
        findViewById(R.id.btn_play_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
            }
        });
        playSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progressValue, boolean fromUser) {
                if (fromUser) {
                    // playSeek 是 0~1000，换算成百分比给播放器
                    AudioPlayer.get().seekTo(progressValue / 10);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        if (btnPlayToggleMini != null) {
            btnPlayToggleMini.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    File f = new File(playingPath);
                    if (f.isFile()) {
                        AudioPlayer.get().toggle(f);
                    }
                }
            });
        }
        findViewById(R.id.btn_play_fold).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPlaybarFolded(true);
            }
        });
        findViewById(R.id.btn_play_expand).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPlaybarFolded(false);
            }
        });
        AudioPlayer.get().setListener(new AudioPlayer.Listener() {
            @Override
            public void onStateChanged(boolean playing, String path) {
                String glyph = playing ? "❚❚" : "▶";
                btnPlayToggle.setText(glyph);
                if (btnPlayToggleMini != null) {
                    btnPlayToggleMini.setText(glyph);
                }
                if (path != null && path.length() > 0 && !path.equals(playingPath)) {
                    playingPath = path;
                    bindPlayBarTitle();
                }
                if (outputAdapter != null) {
                    outputAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onProgress(long pos, long dur) {
                if (dur <= 0L) {
                    return;
                }
                playSeek.setProgress((int) (pos * 1000L / dur));
                tvPlaySub.setText(getString(R.string.playbar_position,
                        AudioPlayer.clock(pos), AudioPlayer.clock(dur)));
            }
        });
    }

    private void setTab(int which) {
        tab = which;
        boolean chapters = which == 0;
        listView.setVisibility(chapters ? View.VISIBLE : View.GONE);
        filterChapterBar.setVisibility(chapters ? View.VISIBLE : View.GONE);
        listOutputs.setVisibility(chapters ? View.GONE : View.VISIBLE);
        filterOutputBar.setVisibility(chapters ? View.GONE : View.VISIBLE);
        tabChapters.setBackgroundResource(chapters ? R.drawable.bg_tab_on : R.drawable.bg_tab_off);
        tabOutputs.setBackgroundResource(chapters ? R.drawable.bg_tab_off : R.drawable.bg_tab_on);
        int on = getResources().getColor(R.color.primary);
        int off = getResources().getColor(R.color.text_sub);
        tabChapters.setTextColor(chapters ? on : off);
        tabOutputs.setTextColor(chapters ? off : on);
        if (chapters) {
            outputEmpty.setVisibility(View.GONE);
            listOutputs.setVisibility(View.GONE);
        } else {
            refreshOutputs();
        }
    }

    private void syncFilterChips() {
        int f = adapter.filter();
        filterChPending.setSelected(f == ChapterAdapter.FILTER_PENDING);
        filterChDone.setSelected(f == ChapterAdapter.FILTER_DONE);

        int on = getResources().getColor(R.color.primary);
        int off = getResources().getColor(R.color.text_sub);
        filterChPending.setTextColor(f == ChapterAdapter.FILTER_PENDING ? on : off);
        filterChDone.setTextColor(f == ChapterAdapter.FILTER_DONE ? on : off);
    }

    // ---------------------------------------------------------------- 状态卡

    /**
     * 徽章 + 主文案 + 副文案永远一起改。
     * 分成几个方法分开set 的话，早晚会漏出「绿勾 + 失败文案」这种自相矛盾的组合，
     * 而状态显示错一次，用户就不敢信这个页面了。
     */
    private void setStatus(int badgeRes, String title, String sub) {
        badgeStatus.setBackgroundResource(badgeRes);
        badgeStatus.setText(badgeTextFor(badgeRes));
        // 常驻只有状态条那一行：有副文案就用它（合成中显示「正在合成哪一章」最有用）
        tvStatusLine.setText(sub != null && sub.length() > 0 ? sub : title);
        if (sheetStatus != null) {
            TextView t = (TextView) sheetStatus.findViewById(R.id.tv_status);
            if (t != null) {
                t.setText(title);
            }
            TextView badge2 = (TextView) sheetStatus.findViewById(R.id.badge_status);
            if (badge2 != null) {
                badge2.setBackgroundResource(badgeRes);
                badge2.setText(badgeTextFor(badgeRes));
            }
            TextView pct = (TextView) sheetStatus.findViewById(R.id.tv_percent);
            if (pct != null) {
                pct.setText(tvPercent == null ? "" : tvPercent.getText());
            }
            TextView cur = (TextView) sheetStatus.findViewById(R.id.tv_current);
            if (cur != null) {
                if (sub == null || sub.length() == 0) {
                    cur.setVisibility(View.GONE);
                } else {
                    cur.setText(sub);
                    cur.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    /**
     * 进度 / 三格统计 / 失败入口现在都住在 Sheet 里，
     * Sheet 没打开时这些引用是 null，下面每个写入都必须容忍 null，
     * 否则「点开状态卡之前先崩一次」这种低级错误根本没法上真机。
     */
    private void applyProgress(int pct) {
        if (progressLine != null) {
            progressLine.setProgress(pct);
        }
        if (sheetStatus != null) {
            ProgressBar big = (ProgressBar) sheetStatus.findViewById(R.id.progress);
            if (big != null) {
                big.setProgress(pct);
            }
        }
    }

    private void setPercent(String text) {
        if (tvPercent != null) {
            tvPercent.setText(text == null ? "" : text);
        }
        if (sheetStatus != null) {
            TextView pct = (TextView) sheetStatus.findViewById(R.id.tv_percent);
            if (pct != null) {
                pct.setText(text == null ? "" : text);
            }
        }
    }

    private void setStat(int done, int failed, String eta) {
        if (sheetStatus == null) {
            return;
        }
        TextView d = (TextView) sheetStatus.findViewById(R.id.stat_done);
        TextView f = (TextView) sheetStatus.findViewById(R.id.stat_failed);
        TextView e = (TextView) sheetStatus.findViewById(R.id.stat_eta);
        if (d != null) {
            d.setText(String.valueOf(done));
        }
        if (f != null) {
            f.setText(String.valueOf(failed));
        }
        if (e != null) {
            e.setText(eta == null ? "" : eta);
        }
    }

    /** 合成中已完成数要显示成「3/12」这种带总数的形式，跟其它态的纯数字不一样 */
    private void setStatDone(String text) {
        if (sheetStatus == null) {
            return;
        }
        TextView d = (TextView) sheetStatus.findViewById(R.id.stat_done);
        if (d != null) {
            d.setText(text == null ? "" : text);
        }
    }

    private void setFailEntry(boolean show) {
        if (sheetStatus != null) {
            TextView v = (TextView) sheetStatus.findViewById(R.id.btn_view_failure);
            if (v != null) {
                v.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        }
        // 状态条上的 !角标：失败时一眼可见，不用点开 Sheet 才知道
        if (btnFailBadge != null) {
            btnFailBadge.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void setSelectionText(String text) {
        if (sheetStatus != null) {
            TextView v = (TextView) sheetStatus.findViewById(R.id.tv_selection);
            if (v != null) {
                v.setText(text == null ? "" : text);
            }
        }
    }

    private static String badgeTextFor(int badgeRes) {
        if (badgeRes == R.drawable.bg_badge_running) {
            return "▶";
        }
        if (badgeRes == R.drawable.bg_badge_done) {
            return "✓";
        }
        if (badgeRes == R.drawable.bg_badge_partial) {
            return "!";
        }
        if (badgeRes == R.drawable.bg_badge_fail) {
            return "✕";
        }
        return "○";
    }

    private void refreshTabs() {
        int total = loaded ? chapters.size() : 0;
        int outs = outputAdapter == null ? 0 : outputAdapter.count();
        tabChapters.setText(getString(R.string.tab_chapters_fmt, Integer.valueOf(total)));
        tabOutputs.setText(getString(R.string.tab_outputs_fmt, Integer.valueOf(outs)));
    }

    private void showFailureReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("阶段：")
                .append(ExportState.stage.length() == 0 ? "—" : ExportState.stage)
                .append("\n\n");
        sb.append(ExportState.summary);
        List<String> errs = ExportState.recentErrors(12);
        if (errs.size() > 0) {
            sb.append("\n\n失败明细：");
            for (int i = 0; i < errs.size(); i++) {
                sb.append("\n· ").append(errs.get(i));
            }
        }
        sb.append("\n\n如果一个文件都没出来，去合成设置里跑一次「导出自检」，"
                + "它会真合成一句话，逐项告诉你是哪一环断了。");
        showReport("导出没有完成", sb.toString());
    }

    /** 一键自检：真合成一句话，把「引擎 / 初始化 / 中文语音 / 落文件 / 文件在哪」逐项摊开 */
    private void runSelfTest() {
        // 自检自己要 new TextToSpeech，同样得先把试听让出去
        stopPreview();
        // 自检会跑 7 次真实合成（含参数对照组），耗时比之前长得多；
        // 导出期间跑它等于两个任务抢同一个引擎，导出会被拖到超时
        if (ExportState.running) {
            toast(getString(R.string.toast_selftest_blocked_export));
            return;
        }
        toast(getString(R.string.toast_selftest_running));
        ExportSelfTest.run(this, new ExportSelfTest.Callback() {
            @Override
            public void onDone(String report) {
                showReport("导出自检结果", report);
            }
        });
    }

    /** 长报告要能滚、能复制，Sheet 装不下，直接用系统对话框 + ScrollView */
    private void showReport(String title, final String text) {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(getResources().getColor(R.color.text_main));
        tv.setPadding(40, 28, 40, 28);
        tv.setLineSpacing(4f, 1.1f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        scroll.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("复制结果", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("report", text));
                            toast("已复制");
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------------------------------------------------------- 播放

    private void playOutput(File f) {
        if (f == null || !f.isFile()) {
            return;
        }
        playingPath = f.getAbsolutePath();
        AudioPlayer.get().play(f);
        playbar.setVisibility(View.VISIBLE);
        bindPlayBarTitle();
        if (outputAdapter != null) {
            outputAdapter.notifyDataSetChanged();
        }
    }

    private void stopPlayback() {
        AudioPlayer.get().stop();
        playingPath = "";
        playbar.setVisibility(View.GONE);
        playSeek.setProgress(0);
        if (outputAdapter != null) {
            outputAdapter.notifyDataSetChanged();
        }
    }

    /** 播放条收起：只留播放键 + 曲名，占位从 ~64dp 降到 ~34dp */
    private void setPlaybarFolded(boolean folded) {
        if (playBarExpanded == null || playBarCollapsed == null) {
            return;
        }
        playBarExpanded.setVisibility(folded ? View.GONE : View.VISIBLE);
        playBarCollapsed.setVisibility(folded ? View.VISIBLE : View.GONE);
    }

    private void bindPlayBarTitle() {
        File f = new File(playingPath);
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String pretty = dot > 0 ? name.substring(0, dot) : name;
        tvPlayTitle.setText(pretty);
        if (tvPlayTitleMini != null) {
            tvPlayTitleMini.setText(pretty);
        }
        if (f.isFile()) {
            tvPlaySub.setText(getString(R.string.output_wav,
                    AudioPlayer.durationOf(f)));
        }
    }

    // ---------------------------------------------------------------- 成品

    private void refreshOutputs() {
        if (outputAdapter == null) {
            return;
        }
        List<ExportStore.Output> list = store == null
                ? new ArrayList<ExportStore.Output>() : store.outputs(chapters);
        outputAdapter.setData(list);
        refreshTabs();
    }

    private void showOutputActions(final ExportStore.Output o) {
        List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
        rows.add(new Sheet.Row(getString(R.string.output_play), "在 App 里直接听", "play"));
        rows.add(new Sheet.Row(getString(R.string.output_action_share),
                "发给别的 App 去播", "share"));
        rows.add(new Sheet.Row(getString(R.string.output_action_delete),
                "释放空间，下次要重新合成", "delete"));
        rows.add(new Sheet.Row(getString(R.string.output_action_info),
                describeFile(o.file), "info"));
        Sheet.list(this, o.chapter.title, describeFile(o.file), rows, -1,
                new Sheet.Callback() {
                    @Override
                    public void onSelected(int index, Sheet.Row row) {
                        if ("play".equals(row.tag)) {
                            playOutput(o.file);
                        } else if ("share".equals(row.tag)) {
                            shareFile(o.file);
                        } else if ("delete".equals(row.tag)) {
                            confirmDeleteOutput(o);
                        } else if ("info".equals(row.tag)) {
                            Sheet.confirm(ExportActivity.this,
                                    getString(R.string.output_action_info),
                                    describeFile(o.file) + "\n\n公共目录里还有一份同名副本，"
                                            + "删掉这里不会删掉公共目录那份。",
                                    getString(R.string.ok), false, null);
                        }
                    }
                });
    }

    private String describeFile(File f) {
        if (f == null || !f.isFile()) {
            return "文件不存在";
        }
        WavTool.Info info = WavTool.probe(f);
        return WavTool.humanSize(f.length()) + " · "
                + WavTool.humanDuration(info.millis()) + " · " + info.describe();
    }

    private void shareFile(File f) {
        Uri uri = LogProvider.uriFor(this, f);
        if (uri == null) {
            toast("分享失败，文件读不到了");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("audio/x-wav");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.output_action_share)));
        } catch (Exception e) {
            AppLog.w("ExportActivity", "分享失败：" + e.getMessage());
            toast("分享失败，这个系统里没有能接收音频的应用");
        }
    }

    private void confirmDeleteOutput(final ExportStore.Output o) {
        Sheet.confirm(this, "删除这个成品？",
                "《" + o.chapter.title + "》\n" + describeFile(o.file)
                        + "\n\n删除后这一章会退回「未导出」，下次要重新合成。"
                        + "公共目录里的副本不受影响。",
                "删除", true, new Runnable() {
                    @Override
                    public void run() {
                        if (store == null) {
                            return;
                        }
                        store.deleteChapter(o.chapter.id);
                        if (playingPath.equals(o.file.getAbsolutePath())) {
                            stopPlayback();
                        }
                        refreshStoreAsync(null);
                        toast(getString(R.string.output_deleted, o.file.getName()));
                    }
                });
    }

    private void confirmCleanAllOutputs() {
        if (store == null) {
            return;
        }
        int n = store.completedCount();
        if (n == 0) {
            toast("这本书还没有成品");
            return;
        }
        Sheet.confirm(this, getString(R.string.output_clean_all),
                "将删除《" + (book == null ? "" : book.title) + "》已导出的 " + n
                        + " 个 WAV（" + WavTool.humanSize(store.totalBytes())
                        + "）。书籍本身保留，下次导出要重新合成。",
                "清空", true, new Runnable() {
                    @Override
                    public void run() {
                        if (store == null) {
                            return;
                        }
                        long freed = store.clearAudio();
                        stopPlayback();
                        refreshStoreAsync(null);
                        toast(getString(R.string.output_deleted_all,
                                WavTool.humanSize(freed)));
                    }
                });
    }

    /** 「文件在哪」：三个去向 + 各自可见性 */
    private void showWhereFiles() {
        View root = LayoutInflater.from(this).inflate(R.layout.sheet_where_files, null);
        String title = book == null ? "" : book.title;

        ((TextView) root.findViewById(R.id.tv_where_music)).setText(
                getString(R.string.where_public_music)
                        + "\n" + MediaStorePublisher.relPath("音乐", title)
                        + "\n" + getString(R.string.where_public_music_sub));
        ((TextView) root.findViewById(R.id.tv_where_download)).setText(
                getString(R.string.where_public_download)
                        + "\n" + MediaStorePublisher.relPath("下载", title)
                        + "\n" + getString(R.string.where_public_download_sub));
        ((TextView) root.findViewById(R.id.tv_where_internal)).setText(
                getString(R.string.where_internal)
                        + "\n" + (store == null ? "—" : store.dirPath())
                        + "\n" + getString(R.string.where_internal_sub));

        String custom = Settings.customTreeName(this);
        ((TextView) root.findViewById(R.id.tv_where_custom)).setText(
                getString(R.string.where_custom) + "\n"
                        + (custom.length() == 0
                        ? getString(R.string.where_custom_none) : custom)
                        + "\n导出完成后自动搬过去一份");

        root.findViewById(R.id.btn_copy_public_path).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("path",
                                    MediaStorePublisher.publicDirHint(
                                            book == null ? "" : book.title)));
                            toast("已复制");
                        }
                    }
                });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        Sheet.showCard(this, scroll);
    }

    // ---------------------------------------------------------------- 章节操作

    /** 章节长按：单章重跑 / 试听 / 单章清音 */
    private void showChapterActions(final int position) {
        final Chapter c = adapter.chapterAt(position);
        if (c == null) {
            return;
        }
        final int real = adapter.realIndexOf(position);
        final boolean done = adapter.isCompleted(position);
        List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
        rows.add(new Sheet.Row(done ? "重跑这一章" : "只导出这一章",
                done ? "会覆盖原有的音频文件" : "把其他勾选清掉，只留这一章", "only"));
        if (done) {
            rows.add(new Sheet.Row(getString(R.string.output_play), "在 App 里直接听", "play"));
            rows.add(new Sheet.Row("删除这一章已导出的音频",
                    "释放空间，下次要重新合成", "delete"));
        }
        rows.add(new Sheet.Row("从这里开始往后选",
                "第 " + (real + 1) + " 章及之后全部勾上", "from"));
        Sheet.list(this, (real + 1) + ". " + c.title,
                c.length() + " 字 · 约 "
                        + WavTool.humanDuration(c.estimateSeconds() * 1000L)
                        + (done ? " · 已导出" : " · 未导出"),
                rows, -1, new Sheet.Callback() {
                    @Override
                    public void onSelected(int index, Sheet.Row row) {
                        if ("only".equals(row.tag)) {
                            adapter.selectOnly(position);
                            updateSelection();
                            toast("已只选中这一章，点底部按钮开始");
                        } else if ("delete".equals(row.tag) && store != null) {
                            store.deleteChapter(c.id);
                            refreshStoreAsync(null);
                            toast("已删除这一章的音频");
                        } else if ("play".equals(row.tag)) {
                            File f = adapter.completedFileAt(position);
                            if (f != null) {
                                playOutput(f);
                            }
                        } else if ("from".equals(row.tag)) {
                            adapter.selectFrom(position);
                            updateSelection();
                            toast("已选中第 " + (real + 1) + " 章及之后");
                        }
                    }
                });
    }

    /** 批量勾选菜单：全选 / 反选 / 仅未完成 / 仅已完成 / 清空 */
    private void showSelectMenu() {
        if (ExportState.running) {
            toast(getString(R.string.toast_exporting_locked));
            return;
        }
        List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
        rows.add(new Sheet.Row(getString(R.string.select_all),
                "共 " + chapters.size() + " 章", "all"));
        rows.add(new Sheet.Row(getString(R.string.invert_sel), "把勾选反过来", "invert"));
        rows.add(new Sheet.Row(getString(R.string.only_pending),
                "只勾还没导出的章节", "pending"));
        rows.add(new Sheet.Row(getString(R.string.only_done),
                "只勾已导出的（重跑会覆盖）", "done"));
        rows.add(new Sheet.Row("清空勾选", "一章都不选", "none"));
        Sheet.list(this, getString(R.string.filter_chapter),
                "当前已勾 " + adapter.checkedCount() + " 章", rows, -1,
                new Sheet.Callback() {
                    @Override
                    public void onSelected(int index, Sheet.Row row) {
                        if ("all".equals(row.tag)) {
                            adapter.selectAll(true);
                        } else if ("invert".equals(row.tag)) {
                            adapter.invert();
                        } else if ("pending".equals(row.tag)) {
                            toast("已选中 " + adapter.selectPending() + " 个未导出章节");
                        } else if ("done".equals(row.tag)) {
                            toast("已选中 " + adapter.selectCompleted() + " 个已完成章节");
                        } else if ("none".equals(row.tag)) {
                            adapter.selectAll(false);
                        }
                        updateSelection();
                    }
                });
    }

    /** 自定义输出目录 */
    private void showCustomDirPicker() {
        List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
        rows.add(new Sheet.Row(getString(R.string.custom_dir_pick),
                getString(R.string.custom_dir_pick_desc), "pick"));
        String current = Settings.customTreeName(this);
        if (current.length() > 0) {
            rows.add(new Sheet.Row(getString(R.string.custom_dir_clear), current, "clear"));
        }
        rows.add(new Sheet.Row(getString(R.string.custom_dir_why),
                getString(R.string.custom_dir_why_desc), "why"));
        if (Settings.hasCustomTree(ExportActivity.this) && store != null
                && store.completedCount() > 0) {
            rows.add(new Sheet.Row(getString(R.string.custom_dir_copy_now),
                    "把已导出的 " + store.completedCount() + " 个 WAV 立刻搬过去", "copy"));
        }
        Sheet.list(this, getString(R.string.setting_customdir),
                current.length() > 0 ? "当前目录：" + current : "还没设置", rows, -1,
                new Sheet.Callback() {
                    @Override
                    public void onSelected(int index, Sheet.Row row) {
                        if ("pick".equals(row.tag)) {
                            SafExport.requestTree(ExportActivity.this);
                        } else if ("clear".equals(row.tag)) {
                            SafExport.clear(ExportActivity.this);
                            syncSettingsToUi();
                            toast("已取消自定义目录，成品只留在 App 专属目录");
                        } else if ("why".equals(row.tag)) {
                            Sheet.confirm(ExportActivity.this,
                                    getString(R.string.custom_dir_why),
                                    getString(R.string.custom_dir_why_desc),
                                    getString(R.string.ok), false, null);
                        } else if ("copy".equals(row.tag)) {
                            copyAllToTree();
                        }
                    }
                });
    }

    /** 把这本书已导出的音频一次性搬进自定义目录。耗时长，后台跑。 */
    private void copyAllToTree() {
        if (store == null || book == null) {
            return;
        }
        final Uri tree = SafExport.treeUri(this);
        if (tree == null) {
            toast("先选一个目录");
            return;
        }
        final List<File> files = store.wavFiles();
        if (files.isEmpty()) {
            toast("还没有导出完成的文件");
            return;
        }
        toast("搬运中，别关页面…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = SafExport.publishAll(ExportActivity.this, tree,
                        book.title, files);
                postSafe(new Runnable() {
                    @Override
                    public void run() {
                        renderState();
                        toast("已搬运 " + n + "/" + files.size() + " 个文件");
                        AppLog.i("ExportActivity",
                                "批量搬运到自定义目录 " + n + "/" + files.size());
                    }
                });
            }
        }, "saf-copy").start();
    }

    // ---------------------------------------------------------------- 合成设置

    private void bindSettings(final View root) {
        tvSettingBrief = (TextView) root.findViewById(R.id.tv_setting_brief);
        tvEngine = (TextView) root.findViewById(R.id.tv_engine);
        tvVoice = (TextView) root.findViewById(R.id.tv_voice);
        tvRate = (TextView) root.findViewById(R.id.tv_rate);
        tvPitch = (TextView) root.findViewById(R.id.tv_pitch);
        barRate = (SeekBar) root.findViewById(R.id.bar_rate);
        barPitch = (SeekBar) root.findViewById(R.id.bar_pitch);
        chipAuto = (TextView) root.findViewById(R.id.chip_lang_auto);
        chipZh = (TextView) root.findViewById(R.id.chip_lang_zh);
        chipEn = (TextView) root.findViewById(R.id.chip_lang_en);
        tvCustomDir = (TextView) root.findViewById(R.id.tv_customdir);
        chkSync = (CheckBox) root.findViewById(R.id.chk_sync);
        btnPreview = (TextView) root.findViewById(R.id.btn_preview);
        tvPreviewStatus = (TextView) root.findViewById(R.id.tv_preview_status);
        chkFloating = (CheckBox) root.findViewById(R.id.chk_floating);
        tvFloatingHint = (TextView) root.findViewById(R.id.tv_floating_hint);
        syncFloatingUi();
        if (btnPreview != null) {
            btnPreview.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePreview();
                }
            });
            // 弹层可能被系统回收重建，重新绑定时要把按钮状态拉回真实值
            refreshPreviewUi(preview != null && preview.isPlaying(), null);
        }

        root.findViewById(R.id.row_floating).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 不能在这里直接改存储：点方框走的是 CheckBox 自己的 onCheckedChanged，
                // 点行空白走的是这里，两条路必须汇到同一个地方，否则会各改一半。
                // toggle() 会触发 CheckBox 的 onCheckedChanged，写入逻辑只写一份。
                if (chkFloating != null) {
                    chkFloating.toggle();
                }
            }
        });
        if (chkFloating != null) {
            chkFloating.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                            onFloatingChanged(on);
                        }
                    });
        }

        root.findViewById(R.id.row_engine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEnginePicker();
            }
        });
        root.findViewById(R.id.row_voice).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showVoicePicker();
            }
        });
        root.findViewById(R.id.btn_rate_dec).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRate(Settings.rate(ExportActivity.this) - 0.1f);
            }
        });
        root.findViewById(R.id.btn_rate_inc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRate(Settings.rate(ExportActivity.this) + 0.1f);
            }
        });
        root.findViewById(R.id.btn_pitch_dec).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPitch(Settings.pitch(ExportActivity.this) - 0.1f);
            }
        });
        root.findViewById(R.id.btn_pitch_inc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPitch(Settings.pitch(ExportActivity.this) + 0.1f);
            }
        });
        barRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int v, boolean fromUser) {
                if (fromUser) {
                    setRate((v + 5) / 10f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        barPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int v, boolean fromUser) {
                if (fromUser) {
                    setPitch((v + 5) / 10f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        chipAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLanguage(Settings.LANG_AUTO);
            }
        });
        chipZh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLanguage(Settings.LANG_ZH);
            }
        });
        chipEn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setLanguage(Settings.LANG_EN);
            }
        });
        chkSync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Settings.setSyncPublic(ExportActivity.this, isChecked);
            }
        });
        root.findViewById(R.id.row_customdir).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCustomDirPicker();
            }
        });
        root.findViewById(R.id.row_sync).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chkSync.toggle();
            }
        });
        root.findViewById(R.id.row_selftest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSelfTest();
            }
        });

        syncSettingsToUi();
    }

    // ---------------------------------------------------------------- 试听

    /**
     * 试听开关。
     *
     * 与导出的冲突用「禁用」处理，不用「排队等导出结束」：
     * TTS 引擎是系统级共享资源，导出时 synthesizeToFile 会长时间独占引擎，
     * 此时另起实例去 speak，两个音频会叠在一起，引擎还可能直接崩或把导出卡死。
     * 排队更糟 —— 用户点了试听却要等几个小时。这里直接拒绝并说明原因。
     */
    private void togglePreview() {
        if (preview != null && preview.isPlaying()) {
            preview.stop();
            return;
        }
        if (ExportState.running) {
            toast(getString(R.string.toast_preview_blocked_export));
            return;
        }
        if (ExportSelfTest.isBusy()) {
            toast(getString(R.string.toast_preview_blocked_selftest));
            return;
        }
        if (preview == null) {
            preview = new TtsPreview(this, new TtsPreview.Listener() {
                @Override
                public void onStateChanged(boolean playing, String message) {
                    refreshPreviewUi(playing, message);
                }
            });
        }
        // 参数一律现读 Settings：用户可能调完语速直接点试听，中间没有提交动作
        preview.startFromSettings(this);
    }

    /**
     * 刷新试听按钮与状态文字。
     *
     * @param message 为 null 表示不覆盖状态文字（保留导出/自检占用时的解释）
     */
    private void refreshPreviewUi(boolean playing, String message) {
        if (btnPreview == null) {
            return;
        }
        if (playing) {
            btnPreview.setText(R.string.preview_stop);
            btnPreview.setAlpha(1f);
            btnPreview.setEnabled(true);
        } else {
            btnPreview.setText(R.string.preview_start);
            // 禁用而不是隐藏：用户要能看见这个功能存在，才知道去哪找原因
            boolean busy = ExportState.running || ExportSelfTest.isBusy();
            btnPreview.setEnabled(!busy);
            btnPreview.setAlpha(busy ? 0.4f : 1f);
        }
        if (tvPreviewStatus == null) {
            return;
        }
        if (message != null && message.length() > 0) {
            tvPreviewStatus.setText(message);
        } else if (ExportState.running) {
            tvPreviewStatus.setText(R.string.preview_blocked_export);
        } else if (ExportSelfTest.isBusy()) {
            tvPreviewStatus.setText(R.string.preview_blocked_selftest);
        } else {
            tvPreviewStatus.setText(R.string.preview_idle_hint);
        }
    }

    /** 只停播，不释放实例：用户可能马上再点一次，重建引擎要等好几秒 */
    private void stopPreview() {
        if (preview != null && preview.isPlaying()) {
            preview.stop();
        }
    }

    private void releasePreview() {
        if (preview != null) {
            preview.release();
            preview = null;
        }
    }

    /** 导出开始/结束时要把试听按钮一起锁上，光靠点击时拦不够（用户看不到为什么灰的） */
    private void syncPreviewLock() {
        if (btnPreview == null) {
            return;
        }
        // renderState 是 500ms 一次的轮询，setText 每次都刷会白白让布局重排，
        // 所以只在「能不能用」真的翻转时才动 UI
        boolean busy = ExportState.running || ExportSelfTest.isBusy();
        if (busy == lastPreviewBusy) {
            return;
        }
        lastPreviewBusy = busy;
        refreshPreviewUi(preview != null && preview.isPlaying(), null);
    }

    private void syncSettingsToUi() {
        if (tvEngine == null || tvVoice == null) {
            return;
        }
        String enginePkg = Settings.enginePkg(this);
        tvEngine.setText(enginePkg.length() == 0
                ? getString(R.string.engine_system_default)
                : TtsCatalog.engineLabel(this, enginePkg));
        String voice = Settings.voiceName(this);
        tvVoice.setText(voice.length() == 0
                ? getString(R.string.voice_engine_default) : voice);
        setRate(Settings.rate(this));
        setPitch(Settings.pitch(this));
        setLanguage(Settings.languageMode(this));
        if (chkSync != null) {
            chkSync.setChecked(Settings.syncPublic(this)
                    && !MediaStorePublisher.needsLegacyPermission());
        }
        if (tvCustomDir != null) {
            String name = Settings.customTreeName(this);
            tvCustomDir.setText(name.length() == 0
                    ? getString(R.string.custom_dir_none) : name);
        }
    }

    private void setRate(float r) {
        float v = Settings.clampRate(r);
        Settings.setRate(this, v);
        if (tvRate != null) {
            tvRate.setText(String.format(java.util.Locale.CHINA, "%.1fx", Float.valueOf(v)));
            barRate.setProgress(clamp(Math.round(v * 10f) - 5, 0, 25));
        }
        updateSettingBrief();
    }

    private void setPitch(float p0) {
        float v = Settings.clampPitch(p0);
        Settings.setPitch(this, v);
        if (tvPitch != null) {
            tvPitch.setText(String.format(java.util.Locale.CHINA, "%.1f", Float.valueOf(v)));
            barPitch.setProgress(clamp(Math.round(v * 10f) - 5, 0, 15));
        }
        updateSettingBrief();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void setLanguage(String mode) {
        Settings.setLanguageMode(this, mode);
        applyChip(chipAuto, Settings.LANG_AUTO.equals(mode));
        applyChip(chipZh, Settings.LANG_ZH.equals(mode));
        applyChip(chipEn, Settings.LANG_EN.equals(mode));
        updateSettingBrief();
    }

    private void applyChip(TextView chip, boolean on) {
        if (chip == null) {
            return;
        }
        chip.setSelected(on);
        chip.setTextColor(on ? 0xFFFFFFFF : getResources().getColor(R.color.text_main));
    }

    /** 设置摘要：不依赖任何弹层视图，随时能算出来给顶栏副标题/⋮ 菜单用 */
    private String settingBriefText() {
        String engine = Settings.enginePkg(this);
        String engineText = engine.length() == 0
                ? getString(R.string.engine_system_default)
                : TtsCatalog.engineLabel(this, engine);
        StringBuilder sb = new StringBuilder();
        sb.append(engineText).append(" · ")
                .append(String.format(java.util.Locale.CHINA, "%.1fx",
                        Float.valueOf(Settings.rate(this))));
        return sb.toString();
    }

    private void updateSettingBrief() {
        if (tvSettingBrief != null) {
            tvSettingBrief.setText(settingBriefText());
        }
        // 设置弹层没打开时，顶栏副标题就是唯一能看见设置的地方
        refreshMetaLine();
    }

    private void showEnginePicker() {
        final List<TtsCatalog.Engine> engines = TtsCatalog.engines(this);
        if (engines.isEmpty()) {
            toast(getString(R.string.toast_no_engine));
            return;
        }
        List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
        int checked = 0;
        String current = Settings.enginePkg(this);
        for (int i = 0; i < engines.size(); i++) {
            TtsCatalog.Engine e = engines.get(i);
            String sub = e.pkg + (e.systemDefault ? "（系统默认）" : "");
            rows.add(new Sheet.Row(e.label, sub, e.pkg));
            if (e.pkg.equals(current)) {
                checked = i;
            }
        }
        Sheet.list(this, getString(R.string.setting_engine),
                "App 不自带发音人，能选哪些音色取决于你装的引擎", rows, checked,
                new Sheet.Callback() {
                    @Override
                    public void onSelected(int index, Sheet.Row row) {
                        Settings.setEnginePkg(ExportActivity.this, engines.get(index).pkg);
                        // 换了引擎，旧音色名多半不存在了，直接重置
                        Settings.setVoiceName(ExportActivity.this, "");
                        syncSettingsToUi();
                        toast("已选引擎：" + engines.get(index).label);
                    }
                });
    }

    private void showVoicePicker() {
        final String pkg = Settings.enginePkg(this);
        toast("正在向引擎查询可用音色…");
        TtsCatalog.probeVoices(this, pkg, 15000L, new TtsCatalog.VoiceCallback() {
            @Override
            public void onVoices(final List<TtsCatalog.VoiceItem> items) {
                if (items.isEmpty()) {
                    toast("这个引擎没有列出可选音色，保持引擎默认即可");
                    return;
                }
                List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
                rows.add(new Sheet.Row(getString(R.string.voice_engine_default),
                        "交给引擎自己挑", ""));
                int checked = 0;
                String current = Settings.voiceName(ExportActivity.this);
                for (int i = 0; i < items.size(); i++) {
                    TtsCatalog.VoiceItem v = items.get(i);
                    if (v.name != null && v.name.equals(current)) {
                        checked = i + 1;
                    }
                }
                for (int i = 0; i < items.size(); i++) {
                    TtsCatalog.VoiceItem v = items.get(i);
                    rows.add(new Sheet.Row(v.label(), v.name, v.name));
                }
                Sheet.list(ExportActivity.this, getString(R.string.setting_voice),
                        "共 " + items.size() + " 个音色", rows, checked,
                        new Sheet.Callback() {
                            @Override
                            public void onSelected(int index, Sheet.Row row) {
                                Settings.setVoiceName(ExportActivity.this,
                                        row.tag == null ? "" : row.tag);
                                syncSettingsToUi();
                            }
                        });
            }
        });
    }

    // ---------------------------------------------------------------- 加载

    private void loadAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                final List<Chapter> parsed = new ArrayList<Chapter>();
                try {
                    BookLibrary lib = new BookLibrary(ExportActivity.this);
                    Book b = lib.findById(bookId);
                    if (b == null) {
                        throw new IllegalStateException("书库里找不到这本书");
                    }
                    book = b;
                    if (!b.fileExists()) {
                        throw new IllegalStateException("书籍文件已丢失，请重新导入");
                    }
                    BookParser.Result r = BookParser.parse(b);
                    parsed.addAll(r.chapters);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName()
                            : t.getMessage();
                }
                final String err = error;
                postSafe(new Runnable() {
                    @Override
                    public void run() {
                        if (err != null) {
                            setStatus(R.drawable.bg_badge_fail, "无法导出", err);
                            AppLog.e("ExportActivity", "解析失败：" + err);
                            Sheet.confirm(ExportActivity.this, "无法导出", err,
                                    getString(R.string.ok), false, new Runnable() {
                                        @Override
                                        public void run() {
                                            finish();
                                        }
                                    });
                            return;
                        }
                        chapters = parsed;
                        store = new ExportStore(ExportActivity.this, bookId);
                        // 必须传 chapters：0001_ 这样的序号前缀要靠它映射回 chapterId，
                        // 否则续传会把已导出的章节当成没导出的重跑一遍
                        store.scan(false, chapters);
                        adapter.setData(chapters, store);
                        loaded = true;
                        setTitle(book.title);
                        tvBook.setText(book.title);
                        // 优先恢复上次勾选的章节（停止后回来能接着跑）；
                        // 没有存过才退回到「全选未导出」这个老默认值
                        int restored = restoreSelection();
                        if (restored == 0) {
                            adapter.selectPending();
                        }
                        AppLog.step("loadAsync:parse-ok");
                        refreshOverview();
                        AppLog.step("loadAsync:refreshOverview-ok");
                        refreshOutputs();
                        AppLog.step("loadAsync:refreshOutputs-ok");
                        renderState();
                        AppLog.step("loadAsync:done");
                    }
                });
            }
        }, "parse-for-export").start();
    }

    /**
     * 后台重扫目录并回到主线程刷新。400 章的书对着 400 个 WAV 做 RIFF校验，
     *  放主线程会明显卡顿，所以这里多绕一层。
     *
     * scanning 期间到来的请求不会被丢：置 rescanAgain，扫完补跑一次。
     * 早期版本直接 return，结果导出高峰期每完成一章就调一次，
     * 大部分请求被静默吞掉，成品列表会停在旧状态不刷新。
     */
    private void refreshStoreAsync(final Runnable after) {
        if (store == null) {
            return;
        }
        if (scanning) {
            rescanAgain = true;
            return;
        }
        scanning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (store) {
                    store.scan(false, chapters);
                }
                postSafe(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (adapter != null) {
                            // 筛选依赖「是否已导出」，store 变了可见列表也得重算
                            adapter.refreshFilter();
                        }
                        if (outputAdapter != null) {
                            outputAdapter.setData(store == null
                                    ? new ArrayList<ExportStore.Output>()
                                    : store.outputs(chapters));
                        }
                        if (loaded) {
                            refreshOverview();
                        }
                        refreshTabs();
                        if (after != null) {
                            after.run();
                        }
                        if (rescanAgain) {
                            rescanAgain = false;
                            refreshStoreAsync(null);
                        }
                    }
                });
            }
        }, "rescan-export").start();
    }

    /** 书头那一行灰字：格式 · 章数 · 全书时长与占用，不再堆三行大数字 */
    private void refreshOverview() {
        if (!loaded || book == null) {
            return;
        }
        long totalSec = 0L;
        for (int i = 0; i < chapters.size(); i++) {
            totalSec += chapters.get(i).estimateSeconds();
        }
        int doneCount = store == null ? 0 : store.completedCount();
        long doneBytes = store == null ? 0L : store.totalBytes();

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.book_meta_fmt, book.format,
                Integer.valueOf(chapters.size())));
        sb.append(" · ").append(getString(R.string.book_progress_fmt,
                Integer.valueOf(doneCount), Integer.valueOf(chapters.size())));
        sb.append('\n').append(getString(R.string.book_estimate_fmt,
                WavTool.humanDuration(totalSec * 1000L),
                WavTool.humanSize(totalSec * 44100L)));
        if (doneBytes > 0L) {
            sb.append(" · 已占用 ").append(WavTool.humanSize(doneBytes));
        }
        tvBookMeta.setText(sb.toString());
        updateSelection();
        refreshQueueCard();
    }

    /** 「已选几章 / 预计产出多大」 + 开始按钮文案 */
    private void updateSelection() {
        if (!loaded) {
            return;
        }
        int n = adapter.checkedCount();
        long chars = adapter.checkedChars();
        boolean busy = ExportState.running;
        TextView start = (TextView) findViewById(R.id.btn_start);
        // 勾选立刻落盘：停止后重新进来能接着跑，不用再手动勾一遍
        saveSelection();

        if (busy) {
            start.setText(getString(R.string.enqueue_export));
        } else if (n == 0) {
            // 一章没勾但还有没导出的章节时，主动给出「继续」的路，
            // 而不是让用户对着空列表发呆
            int pending = resumePendingCount();
            if (pending > 0) {
                start.setBackgroundResource(R.drawable.bg_btn_outline);
                start.setText(getString(R.string.resume_all_fmt,
                        Integer.valueOf(pending)));
            } else {
                start.setBackgroundResource(R.drawable.bg_btn_primary);
                start.setText(getString(R.string.start_export));
            }
        } else if (resumePendingOnly(n) && store != null && store.completedCount() > 0) {
            // 勾的全是还没导出的，且这本书已经有成品 —— 这就是在续传
            start.setBackgroundResource(R.drawable.bg_btn_outline);
            start.setText(getString(R.string.resume_fmt, Integer.valueOf(n)));
        } else {
            start.setBackgroundResource(R.drawable.bg_btn_primary);
            start.setText("开始导出（" + n + " 章）");
        }
        setSelectionText(getString(R.string.sel_detail_fmt, Integer.valueOf(n),
                Long.valueOf(chars),
                WavTool.humanSize((long) (chars / 5.0d * 44100.0d)))
                + (busy ? "（导出进行中）" : ""));
        refreshResumeHint();
        refreshTabs();
        refreshMetaLine();
    }

    /** 上次落盘的选区，用来判断勾选是否真的变了 */
    private String lastSavedSelection = "";

    /**
     * 选区落盘。
     *
     * 关键点：只在勾选**真的变了**时才写。updateSelection() 每 500ms 被轮询调一次，
     * 无条件写的话就是每秒两次把几百个下标序列化进 SharedPreferences，
     * 导出高峰期还会跟TTS 抢 IO —— 合成变慢的隐形元凶之一。
     */
    private void saveSelection() {
        if (!loaded || bookId == null) {
            return;
        }
        try {
            int[] idx = adapter.checkedIndices();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < idx.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(idx[i]);
            }
            String key = sb.toString();
            if (key.equals(lastSavedSelection)) {
                return;
            }
            lastSavedSelection = key;
            Settings.saveSelection(this, bookId, idx);
        } catch (Throwable ignored) {
            // 存不进去不该影响导出本身
        }
    }

    /** 进页面时恢复上次的勾选 —— 断点续传能被发现的关键一步 */
    private int restoreSelection() {
        if (!loaded || bookId == null) {
            return 0;
        }
        int[] saved = Settings.loadSelection(this, bookId);
        if (saved == null || saved.length == 0) {
            return 0;
        }
        int n = adapter.restoreSelection(saved);
        if (n > 0) {
            // 记下这次恢复的值，省得紧接着 updateSelection 又把同样的东西写一遍
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < saved.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(saved[i]);
            }
            lastSavedSelection = sb.toString();
            AppLog.i("ExportActivity", "恢复上次选区 " + n + " 章 bookId=" + bookId);
        }
        return n;
    }

    /** 这本书还有多少章没导出 */
    private int resumePendingCount() {
        if (store == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < chapters.size(); i++) {
            if (!store.isCompleted(chapters.get(i).id)) {
                n++;
            }
        }
        return n;
    }

    /** 勾选的 n 章里，是不是一章已导出的都没有（= 纯续传） */
    private boolean resumePendingOnly(int n) {
        if (store == null || n <= 0) {
            return false;
        }
        int[] idx = adapter.checkedIndices();
        for (int i = 0; i < idx.length; i++) {
            if (idx[i] >= 0 && idx[i] < chapters.size()
                    && store.isCompleted(chapters.get(idx[i]).id)) {
                return false;
            }
        }
        return true;
    }

    /** 停止后给一行直白的说明，代替原来那张塞在正文中间的队列卡 */
    private void refreshResumeHint() {
        if (tvResumeHint == null) {
            return;
        }
        if (ExportState.running || !loaded) {
            tvResumeHint.setVisibility(View.GONE);
            return;
        }
        int pending = resumePendingCount();
        int doneCount = store == null ? 0 : store.completedCount();
        if (doneCount > 0 && pending > 0) {
            tvResumeHint.setText(getString(R.string.resume_hint_fmt,
                    Integer.valueOf(ExportState.done > 0 ? ExportState.done : doneCount),
                    Integer.valueOf(doneCount),
                    Integer.valueOf(Math.max(pending, adapter.checkedCount()))));
            tvResumeHint.setVisibility(View.VISIBLE);
        } else {
            tvResumeHint.setVisibility(View.GONE);
        }
    }

    /** 顶栏副标题：一行说完「多少章 / 已合成多少」 */
    private void refreshMetaLine() {
        if (tvBookMeta == null || !loaded) {
            return;
        }
        int doneCount = store == null ? 0 : store.completedCountLoose();
        tvBookMeta.setText(getString(R.string.meta_fmt,
                Integer.valueOf(chapters.size()), Integer.valueOf(doneCount)));
    }

    private void refreshQueueDot() {
        if (dotQueue == null) {
            return;
        }
        int pending = ExportQueue.pendingCount();
        dotQueue.setVisibility(pending > 0 || ExportState.running
                ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- 队列卡片

    /** 队列概览卡已删除：信息量就一个「排队几个」，改用顶栏小圆点 + ⋮ 里的提示 */
    private void refreshQueueCard() {
        refreshQueueDot();
        updateSettingBrief();
    }

    private void confirmClearQueue() {
        Sheet.confirm(this, getString(R.string.clear_queue),
                "正在跑的这一本不受影响，只清掉后面排着的 "
                        + ExportQueue.pendingCount() + " 个任务。",
                getString(R.string.clear_queue), true, new Runnable() {
                    @Override
                    public void run() {
                        ExportJob.requestClearQueue(ExportActivity.this);
                        refreshQueueCard();
                        if (hasBook && listView != null) {
                            renderState();
                        }
                    }
                });
    }

    private void showJobActions(final ExportJob job, final int position) {
        List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
        if (job.status == ExportQueue.ST_RUNNING && hasBookId(job.bookId)) {
            rows.add(new Sheet.Row("打开这本书的导出页", job.bookTitle, "open"));
            rows.add(new Sheet.Row("停止当前任务", "当前这一块合成完就停", "stop"));
        } else if (job.status == ExportQueue.ST_PENDING) {
            rows.add(new Sheet.Row("取消这个排队任务", job.bookTitle, "cancel"));
        }
        rows.add(new Sheet.Row("查看结果摘要", job.resultSummary.length() == 0
                ? "任务还没跑完" : job.resultSummary, "info"));
        if (rows.isEmpty()) {
            rows.add(new Sheet.Row("这个任务没有可执行的操作", "", "none"));
        }
        Sheet.list(this, ExportQueue.statusText(job.status) + " · " + job.bookTitle,
                job.paramSummary(), rows, -1, new Sheet.Callback() {
                    @Override
                    public void onSelected(int index, Sheet.Row row) {
                        if ("stop".equals(row.tag)) {
                            ExportJob.requestStop(ExportActivity.this);
                            toast(getString(R.string.toast_stopping));
                        } else if ("cancel".equals(row.tag)) {
                            ExportQueue.cancelAt(position);
                            queueAdapter.notifyDataSetChanged();
                            refreshQueueCard();
                        } else if ("open".equals(row.tag)) {
                            startActivity(new Intent(ExportActivity.this,
                                    ExportActivity.class).putExtra("bookId", job.bookId));
                        }
                    }
                });
    }

    private boolean hasBookId(String id) {
        return id != null && id.length() > 0;
    }

    // ---------------------------------------------------------------- 启动导出

    private void startExport() {
        // 每一步都要留痕。以前这里三条 return 路径全都不打日志，
        // 结果就是「点了开始导出但没反应」时，日志里一片空白，根本看不出卡在哪。
        AppLog.step("startExport:点击 loaded=" + loaded);
        if (!loaded) {
            AppLog.step("startExport:还没解析完，直接返回");
            toast("还在解析，请稍候");
            return;
        }
        final int[] indices = adapter.checkedIndices();
        AppLog.step("startExport:勾选章节数=" + indices.length);
        if (indices.length == 0) {
            int pending = resumePendingCount();
            AppLog.step("startExport:没勾选，可继续的章节数=" + pending);
            if (pending > 0) {
                // 这就是「停止了能不能继续」的答案：一键勾上所有没导出的章节接着跑
                adapter.selectPending();
                updateSelection();
                toast(getString(R.string.resume_all_fmt, Integer.valueOf(pending))
                        + "，已帮你全选，再点一次开始");
            } else {
                toast("一章都没选，而且没有可继续的章节了");
            }
            return;
        }
        // 设置已经搬进弹层，用户可能压根没打开过，所以这里读 Settings 而不是问那个 CheckBox
        boolean sync = Settings.syncPublic(this) && !MediaStorePublisher.needsLegacyPermission();
        AppLog.step("startExport:准备提交 章节数=" + indices.length + " 同步到公共目录=" + sync);
        // checkSelfPermission / requestPermissions 是 API 23 才有的，低版本直接跳过
        if (sync && MediaStorePublisher.needsLegacyPermission()
                && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLog.step("startExport:缺存储权限，先申请");
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
            toast("授权后请再点一次「开始导出」");
            return;
        }
        final ExportJob job = new ExportJob(bookId, book.title, indices, sync)
                .applyDefaults(this);
        // 导出马上要独占引擎，试听必须先让位，否则两个实例同时挂在引擎上
        stopPreview();
        if (ExportState.running && bookId.equals(ExportState.bookId)) {
            // 同本书已经在跑：允许再排一轮（比如临时又勾了几章），但先问清楚
            AppLog.step("startExport:同书已在跑，询问是否排队");
            Sheet.confirm(this, "再排一个任务？",
                    "《" + book.title + "》已经在导出中，再加一个任务会排到它后面跑。",
                    getString(R.string.enqueue_export), false, new Runnable() {
                        @Override
                        public void run() {
                            enqueueJob(job);
                        }
                    });
            return;
        }
        confirmAndRun(job);
    }

    private void confirmAndRun(final ExportJob job) {
        long chars = 0L;
        for (int i = 0; i < job.indices.length; i++) {
            Chapter c = chapters.get(job.indices[i]);
            if (c != null) {
                chars += c.length();
            }
        }
        String est = WavTool.humanDuration((long) (chars / 5.0d * 1000.0d));
        String engineName = TtsCatalog.engineLabel(this, job.enginePkg);
        int pending = ExportQueue.pendingCount();
        String msg = "预计要跑 " + est + "（合成是实时的）。\n"
                + "已完成的章节会自动跳过。\n"
                + "息屏和退出本页面都不会中断，建议插电挂机。\n\n"
                + "引擎：" + engineName + "\n"
                + "参数：" + job.paramSummary();
        if (job.syncPublic) {
            msg += "\n成品会同时出现在「音乐/ListenBook」和「下载/ListenBook」";
        }
        if (pending > 0) {
            msg += "\n\n队列里还有 " + pending + " 个任务，这个排到最末。";
        }
        Sheet.confirm(this, "导出 " + job.indices.length + " 章", msg,
                ExportState.running ? getString(R.string.enqueue_export)
                        : getString(R.string.ok), false, new Runnable() {
                    @Override
                    public void run() {
                        enqueueJob(job);
                    }
                });
    }

    private void enqueueJob(ExportJob job) {
        long id = ExportQueue.enqueue(job);
        AppLog.step("enqueueJob:任务已入队 #" + id + " 章节数=" + job.indices.length
                + " 队列长度=" + ExportQueue.pendingCount());
        ExportJob.start(this, job);
        if (ExportState.running) {
            toast(getString(R.string.toast_enqueued,
                    Integer.valueOf(ExportQueue.pendingCount() + 1)));
        } else {
            toast(getString(R.string.toast_started));
        }
        AppLog.i("ExportActivity", "提交任务 #" + id + " 给 Service");
        refreshQueueCard();
        startPolling();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SafExport.REQ_TREE && resultCode == RESULT_OK) {
            String name = SafExport.handleResult(this, data);
            if (name != null) {
                syncSettingsToUi();
                toast("自定义目录已设置：" + name);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0
                    && grantResults[0]
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("已授权，请再点一次「开始导出」");
            } else {
                Settings.setSyncPublic(ExportActivity.this, false);
                if (chkSync != null) {
                    chkSync.setChecked(false);
                }
                toast("未授权，改为只导出到 App 专属目录");
            }
        }
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 渲染总入口。
     *
     * 为什么要包 try-catch：onResume 会立刻调它，而onResume 早于 loadAsync 的
     * 后台解析完成；500ms 轮询也会调它。任何一个子方法在「视图还没绑完 / 弹层刚关掉」
     * 的时序窗口里抛 NPE，都会让整个页面一闪而过 —— 而用户完全不知道发生了什么。
     * 导出这种长任务绝不能因为一次渲染异常就整个崩掉。
     */
    private void renderState() {
        try {
            syncPreviewLock();
            doRenderState();
        } catch (Throwable t) {
            AppLog.e("ExportActivity", "渲染异常（已忽略，页面继续可用）", t);
        }
    }

    private void doRenderState() {
        if (!hasBook) {
            renderCenter();
            return;
        }
        // 记一下当前处在哪个分支：出问题时光看日志就知道是「已完成态」还是「空闲态」，
        // 不用再对着代码猜渲染逻辑走到哪了。
        AppLog.step("renderState:running=" + ExportState.running
                + " finishedFor=" + (ExportState.finishedFor.length() > 0)
                + " hasBook=" + hasBook);
        if (ExportState.running) {
            renderRunning();
            startPolling();
            return;
        }
        stopPolling();
        if (ExportState.finishedFor(bookId)) {
            renderFinished();
            // 只清 finishedFor：finished 是全局单标志，任务中心页还要用它，
            // 在这里清掉会让「任务中心先开着 → 再进导出页」看不到完成提示。
            ExportState.finishedFor = "";
            adapter.setExportingId("");
            refreshStoreAsync(null);
        } else if (loaded) {
            renderIdle();
        }
        refreshQueueCard();
    }

    /**
     * 空闲态。区分「从没导过」和「跑完了但一集都没产出」：
     * 后者必须显式画成失败，绝不能再变回一句「已完成」——
     * 用户投诉过「点合成马上就好了，回头文件夹什么都没有」。
     */
    private void renderIdle() {
        int doneCount = store == null ? 0 : store.completedCount();
        int pendingCount = 0;
        for (int i = 0; i < chapters.size(); i++) {
            if (store == null || !store.isCompleted(chapters.get(i).id)) {
                pendingCount++;
            }
        }
        setPercent("");
        setFailEntry(false);
        setStat(doneCount, 0, getString(R.string.stat_eta_unknown));

        // v4.3：队列恢复了但服务没被拉起来时（Android 8+ 后台启动限制会拦），
        // 任务就静静躺在队列里什么都不做。旧版这里只画「已有 N 章成品」，
        // 用户看到的是"书导到一半就停了，而且它自己不会动"—— 这正是
        // 「软件不稳定」最伤人的那种表现：连为什么不动都不告诉你。
        // 所以这里必须显式画出来：还有多少个任务在排队等你点开始。
        int stuck = countQueuedForBook();
        if (stuck > 0) {
            setStatus(R.drawable.bg_badge_partial, getString(R.string.st_queued),
                    stuck + " 个任务还在队列里等你开始导出（已完成 " + doneCount
                            + " 章，继续跑会跳过它们）");
        } else if (doneCount > 0) {
            setStatus(R.drawable.bg_badge_done, getString(R.string.st_done),
                    "已有 " + doneCount + " 章成品，未导出的 " + pendingCount + " 章");
        } else {
            setStatus(R.drawable.bg_badge_idle, getString(R.string.st_idle),
                    chapters.size() + " 章，一章都还没导出");
        }
        applyProgress(doneCount > 0 ? 100 : 0);
        updateSelection();
    }

    /**
     * 队列里属于这本书、还没跑完的任务数。
     *
     * 用快照而不是 pendingCount()：用户可能同时排了别的书，
     * 这里要回答的是「这本书还有多少在等」，不是「全局排了几个」。
     */
    private int countQueuedForBook() {
        if (!hasBook || !hasBookId(bookId)) {
            return 0;
        }
        try {
            List<ExportJob> all = ExportQueue.snapshot();
            if (all == null) {
                return 0;
            }
            int n = 0;
            for (int i = 0; i < all.size(); i++) {
                ExportJob j = all.get(i);
                if (j == null || !bookId.equals(j.bookId)) {
                    continue;
                }
                if (j.status == ExportQueue.ST_PENDING
                        || j.status == ExportQueue.ST_RUNNING) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void renderFinished() {
        boolean bad = ExportState.failedRun || ExportState.done == 0;
        setPercent("");
        applyProgress(ExportState.percent());
        setStat(ExportState.done, ExportState.failed,
                getString(R.string.stat_eta_unknown));

        if (ExportState.stopped) {
            setStatus(R.drawable.bg_badge_idle, getString(R.string.st_stopped),
                    "已完成 " + ExportState.done + " 章，随时可以接着跑");
            setFailEntry(false);
        } else if (bad) {
            setStatus(R.drawable.bg_badge_fail, getString(R.string.st_fail),
                    "卡在：" + (ExportState.stage.length() == 0
                    ? "未知步骤" : ExportState.stage));
            setFailEntry(true);
        } else if (ExportState.failed > 0) {
            setStatus(R.drawable.bg_badge_partial, getString(R.string.st_partial),
                    ExportState.done + " 章成功 · " + ExportState.failed + " 章失败");
            setFailEntry(true);
        } else {
            setStatus(R.drawable.bg_badge_done, getString(R.string.st_done),
                    ExportState.done + " 章全部合成完毕");
            setFailEntry(false);
        }
        // 合成完了就把「成品」页亮出来：用户最想看的就是合成出来什么
        if (ExportState.done > 0 && loaded) {
            refreshOutputs();
            if (tab == 0 && !bad) {
                setTab(1);
            }
        }
        updateSelection();
    }

    private void renderRunning() {
        boolean mine = ExportState.bookId.equals(bookId);
        long eta = ExportState.etaMs();
        setStat(ExportState.done, ExportState.failed,
                eta > 0L ? WavTool.humanDuration(eta)
                        : getString(R.string.stat_eta_unknown));
        setFailEntry(ExportState.failed > 0);
        setPercent(ExportState.percent() + "%");
        applyProgress(ExportState.percent());

        if (!mine) {
            setStatus(R.drawable.bg_badge_running, getString(R.string.st_other_book),
                    "《" + ExportState.bookTitle + "》 " + ExportState.done + "/"
                            + ExportState.total + " 章");
            return;
        }

        StringBuilder cur = new StringBuilder();
        if (ExportState.stage.length() > 0) {
            cur.append(ExportState.stage);
        }
        if (ExportState.currentChapter.length() > 0) {
            if (cur.length() > 0) {
                cur.append(" · ");
            }
            cur.append("《").append(ExportState.currentChapter).append("》");
        }
        if (ExportState.chunkTotal > 0) {
            cur.append("（块 ").append(ExportState.chunkIndex + 1).append("/")
                    .append(ExportState.chunkTotal);
            if (ExportState.attempt > 1) {
                cur.append(" · 重试 ").append(ExportState.attempt);
            }
            cur.append("）");
        }
        setStatus(R.drawable.bg_badge_running, getString(R.string.st_running), cur.toString());
        setStatDone(ExportState.done + "/" + ExportState.total);
        adapter.setExportingId(currentExportingId());
        updateSelection();
        if (ExportState.done != lastDone) {
            lastDone = ExportState.done;
            refreshStoreAsync(null);
        }
    }

    private void renderCenter() {
        queueAdapter.setJobs(ExportQueue.snapshot());
        if (ExportState.running) {
            tvCenterStatus.setText("正在导出《" + ExportState.bookTitle + "》  "
                    + ExportState.percent() + "%");
            centerProgress.setProgress(ExportState.percent());
            StringBuilder sb = new StringBuilder();
            if (ExportState.currentChapter.length() > 0) {
                sb.append("当前：").append(ExportState.currentChapter).append('\n');
            }
            sb.append("完成 ").append(ExportState.done)
                    .append(" / 失败 ").append(ExportState.failed)
                    .append(" / 跳过 ").append(ExportState.skipped)
                    .append(" / 共 ").append(ExportState.total).append(" 章\n");
            sb.append("已产出 ").append(WavTool.humanDuration(ExportState.producedMillis))
                    .append(" / ").append(WavTool.humanSize(ExportState.producedBytes))
                    .append('\n');
            long eta = ExportState.etaMs();
            if (eta > 0L) {
                sb.append("预计剩余 ").append(WavTool.humanDuration(eta)).append('\n');
            }
            if (ExportState.engineLabel.length() > 0) {
                sb.append("引擎：").append(ExportState.engineLabel).append('\n');
            }
            sb.append("队列还剩 ").append(ExportQueue.pendingCount()).append(" 个任务");
            tvCenterDetail.setText(sb.toString());
            startPolling();
            return;
        }
        stopPolling();
        centerProgress.setProgress(0);
        if (ExportState.finished) {
            boolean bad = ExportState.failedRun || ExportState.done == 0;
            tvCenterStatus.setText(ExportState.stopped ? getString(R.string.st_stopped)
                    : (bad ? getString(R.string.st_fail) : "全部导出完成"));
            tvCenterDetail.setText(bad
                    ? ExportState.summary + "\n\n卡在哪一步：" + ExportState.stage
                    : ExportState.summary);
            ExportState.finished = false;
            return;
        }
        int total = ExportQueue.totalCount();
        if (total == 0) {
            tvCenterStatus.setText("队列是空的。回书架选一本书，点「导出音频」就能开始。");
            tvCenterDetail.setText("");
        } else {
            tvCenterStatus.setText("没有正在跑的任务。共 " + total + " 条记录，"
                    + "已完成 " + ExportQueue.finishedCount() + " 条。");
            tvCenterDetail.setText("");
        }
    }

    private String currentExportingId() {
        return ExportState.currentChapterId == null ? "" : ExportState.currentChapterId;
    }

    private void startPolling() {
        main.removeCallbacks(poller);
        main.postDelayed(poller, 500L);
    }

    private void stopPolling() {
        main.removeCallbacks(poller);
    }

    /**
     * 安全回帖：后台线程干完活回主线程刷新 UI 时统一走这里。
     *
     * 为什么必须包一层：
     *   后台回帖里最容易踩空的是「页面已经关了但引用还在」——
     *   比如解析线程刚回来，Activity 已经被用户划掉，
     *   这时 refreshOverview() 里访问 tvBookMeta 就是空指针，
     *   表现是「点了一下导出，界面闪一下就没了」，完全看不出是哪一步崩的。
     *
     *   兜住它：记日志、提示、然后安静地结束这一轮刷新。
     */
    private void postSafe(final Runnable r) {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    AppLog.step("ExportActivity:后台回帖崩了 "
                            + t.getClass().getName() + " " + t.getMessage());
                    AppLog.e("ExportActivity", "后台回帖异常", t);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.step("ExportActivity.onResume:begin");
        try {
            onResumeInner();
        } catch (Throwable t) {
            // onResume 早于 loadAsync 的解析回调，此刻章节列表、store 都可能还是空的，
            // 这里最容易踩到空指针。崩了也别闪退 —— 记日志 + 提示。
            AppLog.step("ExportActivity.onResume:崩了 " + t.getClass().getName()
                    + " " + t.getMessage());
            AppLog.e("ExportActivity", "onResume 异常", t);
        }
    }

    private void onResumeInner() {
        if (ExportState.running) {
            startPolling();
        } else {
            renderState();
        }
        if (hasBook && loaded && store != null) {
            refreshStoreAsync(null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
        // 试听跟着页面一起停：导出页关掉后还响，会让人以为出了 bug
        if (playbar != null) {
            stopPlayback();
        }
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        AudioPlayer.get().setListener(null);
        // 不 release 的话 TextToSpeech 实例会一直挂在引擎上，导出随后开始会抢资源
        releasePreview();
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 成品适配器

    /** 成品列表。行内直接给试听，⋮ 给分享 / 删除 / 信息。 */
    private final class OutputAdapter extends BaseAdapter {

        private final List<ExportStore.Output> items = new ArrayList<ExportStore.Output>();
        private final LayoutInflater inflater = LayoutInflater.from(ExportActivity.this);

        void setData(List<ExportStore.Output> list) {
            items.clear();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    items.add(list.get(i));
                }
            }
            notifyDataSetChanged();
            boolean empty = items.isEmpty();
            if (listOutputs != null) {
                listOutputs.setVisibility(empty ? View.GONE : View.VISIBLE);
            }
            if (outputEmpty != null) {
                outputEmpty.setVisibility(empty && tab == 1 ? View.VISIBLE : View.GONE);
            }
        }

        int count() {
            return items.size();
        }

        ExportStore.Output outputAt(int position) {
            return position < 0 || position >= items.size() ? null : items.get(position);
        }

        File fileAt(int position) {
            ExportStore.Output o = outputAt(position);
            return o == null ? null : o.file;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            TextView seq;
            TextView title;
            TextView meta;
            TextView play;
            TextView more;
            if (v == null) {
                v = inflater.inflate(R.layout.item_output, parent, false);
                seq = (TextView) v.findViewById(R.id.out_seq);
                title = (TextView) v.findViewById(R.id.out_title);
                meta = (TextView) v.findViewById(R.id.out_meta);
                play = (TextView) v.findViewById(R.id.out_play);
                more = (TextView) v.findViewById(R.id.out_more);
            } else {
                seq = (TextView) v.findViewById(R.id.out_seq);
                title = (TextView) v.findViewById(R.id.out_title);
                meta = (TextView) v.findViewById(R.id.out_meta);
                play = (TextView) v.findViewById(R.id.out_play);
                more = (TextView) v.findViewById(R.id.out_more);
            }
            final ExportStore.Output o = items.get(position);
            seq.setText(String.format(java.util.Locale.US, "%02d",
                    Integer.valueOf(o.chapter.index + 1)));
            title.setText(o.chapter.title);
            WavTool.Info info = WavTool.probe(o.file);
            meta.setText(getString(R.string.output_meta,
                    WavTool.humanSize(o.file.length()),
                    WavTool.humanDuration(info.millis())));
            boolean playing = playingPath.equals(o.file.getAbsolutePath())
                    && AudioPlayer.get().isPlaying();
            play.setText(playing ? "❚❚" : getString(R.string.output_play));
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playOutput(o.file);
                }
            });
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showOutputActions(o);
                }
            });
            return v;
        }
    }

    // ---------------------------------------------------------------- 队列适配器

    private final class QueueAdapter extends BaseAdapter {

        private final List<ExportJob> jobs = new ArrayList<ExportJob>();

        void setJobs(List<ExportJob> list) {
            jobs.clear();
            jobs.addAll(list);
            notifyDataSetChanged();
        }

        ExportJob jobAt(int position) {
            return position < 0 || position >= jobs.size() ? null : jobs.get(position);
        }

        @Override
        public int getCount() {
            return jobs.size();
        }

        @Override
        public Object getItem(int position) {
            return jobs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return jobs.get(position).jobId;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2,
                        parent, false);
            }
            ExportJob job = jobs.get(position);
            TextView t1 = (TextView) v.findViewById(android.R.id.text1);
            TextView t2 = (TextView) v.findViewById(android.R.id.text2);
            t1.setText("#" + job.jobId + "  " + job.bookTitle + "  ·  "
                    + ExportQueue.statusText(job.status));
            t2.setText(job.chapterCount() + " 章 · " + job.paramSummary());
            return v;
        }
    }
}