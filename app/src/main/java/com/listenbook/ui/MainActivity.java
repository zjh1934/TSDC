package com.listenbook.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.listenbook.R;
import com.listenbook.export.ExportQueue;
import com.listenbook.export.ExportState;
import com.listenbook.export.ExportStore;
import com.listenbook.model.Book;
import com.listenbook.model.BookLibrary;
import com.listenbook.parse.BookParser;
import com.listenbook.util.AppLog;
import com.listenbook.util.Fingerprint;
import com.listenbook.util.Io;
import com.listenbook.util.SafeName;
import com.listenbook.util.UriHelper;
import com.listenbook.util.WavTool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 书库页。导入走 ContentResolver，绝不用 uri.getPath()。 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int MENU_QUEUE = 3;
    private static final int MENU_DIAG = 4;

    private final Handler main = new Handler(Looper.getMainLooper());
    private BookLibrary library;
    private BookAdapter adapter;
    private ListView listView;
    private TextView emptyView;
    private ProgressBar importBar;
    private volatile boolean importing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        setContentView(R.layout.activity_main);
        listView = (ListView) findViewById(R.id.list_books);
        emptyView = (TextView) findViewById(R.id.empty_view);
        importBar = (ProgressBar) findViewById(R.id.import_bar);
        TextView importBtn = (TextView) findViewById(R.id.btn_import);

        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (library != null && position < library.size()) {
                    showActions(library.get(position));
                }
            }
        });
        listView.setEmptyView(emptyView);
        maybeReportInterrupted();
    }

    /**
     * 上次导出被系统中断过，弹一句话告诉用户。
     *
     * 静默恢复是不够的：用户看到「导出自己开始了」会以为出了 bug，
     * 看到「上次是被系统杀的，已经接着跑了」才知道发生了什么。
     * 播完一次就把标记清掉，不会每次开 App 都唠叨一遍。
     */
    private void maybeReportInterrupted() {
        try {
            if (!ExportState.wasInterrupted) {
                return;
            }
            ExportState.wasInterrupted = false;
            boolean needManual = ExportState.interruptedNeedsManualStart;
            ExportState.interruptedNeedsManualStart = false;
            String title = ExportState.interruptedTitle;
            String bookId = ExportState.interruptedBook;
            StringBuilder msg = new StringBuilder();
            if (title != null && title.length() > 0) {
                msg.append("《").append(title).append("》");
                if (ExportState.interruptedDone > 0 && ExportState.interruptedTotal > 0) {
                    msg.append("上次跑到 ").append(ExportState.interruptedDone)
                            .append('/').append(ExportState.interruptedTotal).append(" 章");
                }
                msg.append("，被系统中断了。\n\n");
            } else {
                msg.append("上次的导出被系统中断了。\n\n");
            }
            msg.append("已经完成的章节都还在，接着跑会自动跳过，不会重复合成。\n\n");
            if (needManual) {
                // 这句必须放在最前面：用户看到「被中断」却发现它没自己动，
                // 第一反应是"这软件又坏了"。先把要他做什么说清楚。
                msg.append("现在它停在队列里等你启动 —— 点下面的「去接着导出」即可。\n\n");
            }
            msg.append("想彻底避免：菜单 → 后台运行设置，把电池优化和后台限制放开；")
                    .append("另外保持「悬浮进度窗」开着，就能随时看到进度。");
            AppLog.i("MainActivity", "提示用户上次导出被中断，需要手动启动=" + needManual);

            if (needManual && bookId != null && bookId.length() > 0
                    && !ExportState.running && ExportQueue.hasPending()) {
                // 有得跳就直接跳，别让用户自己在书库里翻半天
                final String bid = bookId;
                Sheet.confirm(this, "导出中断了，去接着导出", msg.toString(),
                        "去接着导出", false, new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    startActivity(new Intent(MainActivity.this, ExportActivity.class)
                                            .putExtra("bookId", bid));
                                } catch (Throwable t) {
AppLog.e("MainActivity",
                                                    "跳导出页失败", t);
                                            Toast.makeText(MainActivity.this,
                                                    "打不开导出页，已记入日志",
                                                    Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                return;
            }
            Sheet.confirm(this, "上次导出被中断了", msg.toString(),
                    getString(R.string.ok), false, null);
        } catch (Throwable t) {
            AppLog.e("MainActivity", "中断提示弹失败", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        BookParser.invalidate();
        reload();
    }

    private void reload() {
        library = new BookLibrary(this);
        adapter = new BookAdapter(this, library.all());
        listView.setAdapter(adapter);
        emptyView.setVisibility(library.size() == 0 ? View.VISIBLE : View.GONE);
        scanExportedCounts();
    }

    /**
     * 后台统计每本书已导出几章。
     * 放在这里而不是 getView 里：书多的时候统计要扫几百个 WAV，
     * 在滚动过程中做必然掉帧。
     */
    private void scanExportedCounts() {
        final List<String> ids = new ArrayList<String>();
        List<Book> all = library.all();
        for (int i = 0; i < all.size(); i++) {
            ids.add(all.get(i).id);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.Map<String, Integer> counts =
                        new java.util.HashMap<String, Integer>();
                final java.util.Map<String, Long> bytes = new java.util.HashMap<String, Long>();
                for (int i = 0; i < ids.size(); i++) {
                    String id = ids.get(i);
                    ExportStore store = new ExportStore(MainActivity.this, id);
                    store.scan(false);
                    counts.put(id, Integer.valueOf(store.completedCountLoose()));
                    bytes.put(id, Long.valueOf(store.totalBytes()));
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (adapter != null) {
                            adapter.setExportStats(counts, bytes);
                        }
                    }
                });
            }
        }, "scan-exported").start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_QUEUE, 0, R.string.menu_export_queue);
        menu.add(0, 2, 1, R.string.menu_storage);
        menu.add(0, MENU_DIAG, 2, R.string.menu_diagnostics);
        menu.add(0, 1, 3, R.string.menu_about);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == 1) {
            showAbout();
            return true;
        }
        if (id == 2) {
            showOutputDir();
            return true;
        }
        if (id == MENU_QUEUE) {
            startActivity(new Intent(this, ExportActivity.class));
            return true;
        }
        if (id == MENU_DIAG) {
            startActivity(new Intent(this, DiagnosticsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAbout() {
        Sheet.confirm(this, getString(R.string.app_name), getString(R.string.about_text),
                getString(R.string.ok), false, null);
    }

    private void showOutputDir() {
        File dir = ExportStore.baseDir(this);
        long bytes = 0L;
        int wavs = 0;
        File[] books = dir.listFiles();
        if (books != null) {
            for (int i = 0; i < books.length; i++) {
                File[] files = books[i].listFiles();
                if (files == null) {
                    continue;
                }
                for (int j = 0; j < files.length; j++) {
                    if (files[j].getName().endsWith(".wav")) {
                        wavs++;
                        bytes += files[j].length();
                    }
                }
            }
        }
        Sheet.confirm(this, "导出目录",
                dir.getAbsolutePath() + "\n\n已生成 " + wavs + " 个 WAV，占用 "
                        + WavTool.humanSize(bytes)
                        + "\n\n提示：Android 11+ 其它 App 读不到这个目录，"
                        + "需要在导出页勾选「同步到公共音乐目录」。",
                getString(R.string.ok), false, null);
    }

    private void pickFile() {
        if (importing) {
            toast("正在导入，请稍候");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "application/epub+zip", "application/octet-stream",
                "text/markdown", "application/zip"
        });
        try {
            startActivityForResult(Intent.createChooser(intent, "选择 TXT / EPUB"), REQ_PICK);
        } catch (Exception e) {
            toast("没有找到文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        doImport(uri);
    }

    private void doImport(final Uri uri) {
        importing = true;
        importBar.setVisibility(View.VISIBLE);
        AppLog.i("MainActivity", "开始导入 " + uri.toString());

        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                Book imported = null;
                File copied = null;
                try {
                    String displayName = UriHelper.displayName(MainActivity.this, uri);
                    // 扩展名不支持也照收：UriHelper 会读文件头猜是 epub 还是 txt
                    copied = UriHelper.importTo(MainActivity.this, uri,
                            BookLibrary.booksDir(MainActivity.this));
                    String format = BookParser.formatOf(copied.getName());
                    String guessTitle = SafeName.title(stripExt(displayName), 60);
                    String id = Fingerprint.bookId(displayName, copied.length(), guessTitle);

                    BookParser.Result result = BookParser.parse(copied, format, guessTitle, id);

                    Book b = new Book();
                    b.id = id;
                    b.title = result.title.length() > 0 ? result.title : guessTitle;
                    b.author = result.author;
                    b.format = result.format;
                    b.path = copied.getAbsolutePath();
                    b.addedAt = System.currentTimeMillis();
                    b.chapterCount = result.chapters.size();
                    b.charCount = result.charCount();

                    BookLibrary lib = new BookLibrary(MainActivity.this);
                    int exist = lib.indexOfId(id);
                    if (exist >= 0) {
                        Book old = lib.get(exist);
                        if (old.path != null && !old.path.equals(b.path)) {
                            Io.deleteQuietly(new File(old.path));
                        }
                        b.lastChapterIndex = old.lastChapterIndex;
                        b.lastChapterId = old.lastChapterId;
                        b.lastSentence = old.lastSentence;
                        b.fontSp = old.fontSp;
                        b.rate = old.rate;
                        b.enginePkg = old.enginePkg;
                        lib.remove(id);
                    }
                    lib.add(b);
                    imported = b;
                    AppLog.i("MainActivity", "导入成功《" + b.title + "》"
                            + b.chapterCount + " 章 / " + b.charCount + " 字");
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    AppLog.e("MainActivity", "导入失败", t);
                    if (copied != null) {
                        Io.deleteQuietly(copied);
                    }
                }
                final String err = error;
                final Book ok = imported;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        importing = false;
                        importBar.setVisibility(View.GONE);
                        if (err != null) {
                            Sheet.confirm(MainActivity.this, "导入失败", err,
                                    getString(R.string.ok), true, null);
                        } else if (ok != null) {
                            reload();
                            toast("已导入：" + ok.title + "（" + ok.chapterCount + " 章）");
                        }
                    }
                });
            }
        }, "import-book").start();
    }

    /** 点一本书：底部弹层，替代原来那个白底黑字的原生列表框。 */
    private void showActions(final Book book) {
        if (!book.fileExists()) {
            Sheet.confirm(this, book.title,
                    "书籍文件已丢失，可能被系统清理了。删除这条记录？",
                    "删除记录", true, new Runnable() {
                        @Override
                        public void run() {
                            library.remove(book.id);
                            reload();
                        }
                    });
            return;
        }
        ExportStore store = new ExportStore(this, book.id);
        store.scan(false);
        int doneCount = store.completedCountLoose();
        long usedBytes = store.totalBytes();

        List<Sheet.Row> rows = new ArrayList<Sheet.Row>();
        // v1.4：看书模块整体砍掉，这里只剩导出这一条主线。
        // 「合成 WAV」从副位提到第一位 —— 以前它排在「边看边听」下面，
        // 用户要先跳过一行才能碰到真正要做的事。
        rows.add(new Sheet.Row(getString(R.string.open_export),
                "合成 WAV 到 App 目录" + (doneCount > 0 ? "（已导出 " + doneCount + " 章）" : ""), "export"));
        rows.add(new Sheet.Row(getString(R.string.rename_book),
                book.title, "rename"));
        rows.add(new Sheet.Row(getString(R.string.clean_audio),
                doneCount > 0 ? "当前占用 " + WavTool.humanSize(usedBytes) + "，删了要重新合成"
                        : "这本书还没有导出音频", "clean"));
        rows.add(new Sheet.Row(getString(R.string.delete_book),
                "从书库移除", "delete"));

        StringBuilder sub = new StringBuilder();
        sub.append(book.format).append(" · ").append(book.chapterCount).append(" 章");
        if (book.author != null && book.author.length() > 0) {
            sub.append(" · ").append(book.author);
        }

        Sheet.list(this, book.title, sub.toString(), rows, -1, new Sheet.Callback() {
            @Override
            public void onSelected(int index, Sheet.Row row) {
                if ("export".equals(row.tag)) {
                    startActivity(new Intent(MainActivity.this, ExportActivity.class)
                            .putExtra("bookId", book.id));
                } else if ("rename".equals(row.tag)) {
                    showRename(book);
                } else if ("clean".equals(row.tag)) {
                    confirmCleanAudio(book);
                } else if ("delete".equals(row.tag)) {
                    confirmDelete(book);
                }
            }
        });
    }

    private void showRename(final Book book) {
        final EditText input = new EditText(this);
        input.setText(book.title);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setPadding(36, 24, 36, 12);
        input.setSelectAllOnFocus(true);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.rename_book)
                .setView(input)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) {
                            toast("名字不能为空");
                            return;
                        }
                        book.title = SafeName.title(name, 60);
                        library.update(book);
                        reload();
                        AppLog.i("MainActivity", "重命名 → " + book.title);
                        toast(getString(R.string.toast_renamed));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        dialog.setCanceledOnTouchOutside(true);
    }

    private void confirmCleanAudio(final Book book) {
        final ExportStore store = new ExportStore(this, book.id);
        store.scan(false);
        if (store.completedCountLoose() == 0) {
            toast("这本书还没有导出音频");
            return;
        }
        Sheet.confirm(this, getString(R.string.clean_audio),
                "将删除《" + book.title + "》已导出的 " + store.completedCountLoose() + " 个 WAV"
                        + "（" + WavTool.humanSize(store.totalBytes()) + "）。"
                        + "\n书籍本身和书库记录都保留，下次导出要重新合成。",
                "清理", true, new Runnable() {
                    @Override
                    public void run() {
                        long freed = store.clearAudio();
                        reload();
                        toast(getString(R.string.toast_audio_cleaned, WavTool.humanSize(freed)));
                        AppLog.i("MainActivity", "清理《" + book.title + "》音频 "
                                + WavTool.humanSize(freed));
                    }
                });
    }

    private void confirmDelete(final Book book) {
        Sheet.confirm(this, "删除 " + book.title,
                "删书库记录和已导入的副本，导出目录里的 WAV 保留。\n"
                        + "想连音频一起删，先点上一层的「清理已导出音频」。",
                getString(R.string.delete_book), true, new Runnable() {
                    @Override
                    public void run() {
                        library.remove(book.id);
                        reload();
                        toast(getString(R.string.toast_deleted, book.title));
                    }
                });
    }

    private static String stripExt(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
