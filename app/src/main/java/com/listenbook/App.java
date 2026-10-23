package com.listenbook;

import android.app.Application;
import android.content.Intent;

import com.listenbook.export.ExportQueue;
import com.listenbook.export.ExportService;
import com.listenbook.export.ExportState;
import com.listenbook.model.Book;
import com.listenbook.model.BookLibrary;
import com.listenbook.util.AppLog;
import com.listenbook.util.CrashTrace;

import java.util.List;

/**
 * 全局入口。做两件事：把诊断日志系统架起来、把被系统中断的导出任务捞回来。
 *
 * 为什么不放在 MainActivity：Service 可能比任何 Activity 先启动
 * （比如系统重启后恢复前台服务、或从通知直接进导出页），
 * 放在 Application 里能保证任何路径进 App 都有日志。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        AppLog.i("App", "进程启动");
        // 先把「上一次是怎么死的」捞出来 —— 用户点开 App 时最想看的就是这条。
        // 放在最前面，这样即使后面任何一步崩了，死因也已经落盘。
        CrashTrace.collect(this);
        recoverInterruptedExport();
        reportLibrary();
    }

    /**
     * 上次的导出被系统杀掉了吗？捞回来接着跑。
     *
     * 这是 v4.3 最核心的稳定性改动。以前队列纯内存，进程一死排着的书就消失，
     * 用户回来只看到「停了」，根本不知道是被系统杀的。
     *
     * 为什么必须在这里做（而不是只靠 START_STICKY）：
     * START_STICKY 只在系统愿意重启服务时生效，国产 ROM 常常不重启。
     * 但「用户自己点开 App」这条路径是百分百可靠的 —— 在这里补一刀，
     * 用户回来就能接着跑，不用记得点任何按钮。
     */
    private void recoverInterruptedExport() {
        try {
            ExportQueue.attach(this);
            int n = ExportQueue.restoreFromDisk();
            if (n > 0) {
                AppLog.exportEvent("启动时发现 " + n + " 个中断任务，自动拉起服务续跑");
                try {
                    Intent i = new Intent(this, ExportService.class);
                    i.setAction(ExportService.ACTION_START);
                    startService(i);
                } catch (Throwable t) {
                    // Android 8+ 后台 startService 会被拦（IllegalStateException），
                    // 这时不能崩也不能静默 —— 队列还在，用户下次点导出页会看到
                    ExportState.interruptedNeedsManualStart = true;
                    AppLog.w("App", "无法自动拉起服务（Android 后台启动限制），"
                            + "队列已保留，你可以在导出页手动点「开始导出」接着跑");
                }
            }
            if (ExportState.wasInterrupted) {
                AppLog.exportEvent("上次中断详情：《" + ExportState.interruptedTitle
                        + "》 " + ExportState.interruptedDone + "/" + ExportState.interruptedTotal);
            }
        } catch (Throwable t) {
            AppLog.e("App", "恢复中断任务失败", t);
        }
    }

    private void reportLibrary() {
        try {
            BookLibrary lib = new BookLibrary(this);
            List<Book> all = lib.all();
            AppLog.i("App", "书库载入 " + all.size() + " 本");
            for (int i = 0; i < all.size(); i++) {
                Book b = all.get(i);
                if (!b.fileExists()) {
                    AppLog.w("App", "《" + b.title + "》正本文件缺失 path=" + b.path);
                }
            }
        } catch (Exception e) {
            AppLog.w("App", "书库自检失败：" + e.getMessage());
        }
    }
}
