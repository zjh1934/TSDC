package com.listenbook.export;

import android.content.Context;
import android.os.Build;

import com.listenbook.model.Book;
import com.listenbook.model.Chapter;
import com.listenbook.util.Io;
import com.listenbook.util.SafeName;
import com.listenbook.util.WavTool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件即状态。不靠 Service 存活，不靠数据库。
 *
 * 目录结构：
 *   getExternalFilesDir(null)/export/&lt;bookId&gt;/
 *     meta.json
 *     0001_第一章.wav已v1.2 完成，4 位序号开头
 *     e50d5943_第一章.wav     v1.1 完成，内容指纹开头（仍被识别，只是不再产生）
 *     ef56gh78.p0.tmp         正在合成
 *
 * 为什么放 getExternalFilesDir 而不是 getFilesDir：
 *   TTS 引擎是独立 APK，拿不到我们私有目录的写权限（EACCES），
 *   但它能写 App 的外部专属目录。这是坑 1 的对策。
 */
public class ExportStore {

    private final Context context;
    private final String bookId;
    private final File dir;
    private final Map<String, File> completedCache = new HashMap<String, File>();
    /** 校验通过、但没能对应到章节的成品数（没传 chapters 时全是这类）。 */
    private int looseCount;

    public ExportStore(Context context, String bookId) {
        this.context = context.getApplicationContext();
        this.bookId = bookId == null ? "unknown" : bookId;
        this.dir = new File(baseDir(this.context), this.bookId);
    }

    /** 导出根目录。TTS 引擎必须能写，所以优先外部专属目录。 */
    public static File baseDir(Context context) {
        File ext = context.getExternalFilesDir(null);
        if (ext != null && (ext.isDirectory() || ext.mkdirs())) {
            File root = new File(ext, "export");
            if (root.isDirectory() || root.mkdirs()) {
                return root;
            }
        }
        // 极端情况（无外部存储）退回私有目录，此时 TTS 大概率写失败，会明确报错
        File root = new File(context.getFilesDir(), "export");
        root.mkdirs();
        return root;
    }

    public File dir() {
        return dir;
    }

    public String dirPath() {
        return dir.getAbsolutePath();
    }

    /** 建目录 + 清掉所有半截文件。绝不信任 .tmp。只有 Service 启动任务时能调。
     *  chapters 用于把 0001_ 这样的序号前缀映射回 chapterId，从而认出 v1.2 命名的成品。 */
    public void prepare(List<Chapter> chapters) {
        scan(true, chapters);
    }

    /** 不带章节表的准备：只认旧命名，仅用于章节还没解析出来的极端情况。 */
    public void prepare() {
        scan(true, null);
    }

    /**
     * 扫描目录建完成表。
     * deleteTmp=false 是只读扫描（UI 用）——导出进行中时 UI 绝不能删 .tmp，
     * 否则会把正在合成的分块删掉。
     *
     * synchronized：UI 会把它丢到后台线程跑（一本 400 章的书对着 400 个 WAV
     * 做 RIFF 校验，主线程做要卡几百毫秒），而主线程同时在读 completedCount()。
     */
    public synchronized void scan(boolean deleteTmp) {
        scan(deleteTmp, null);
    }

    /**
     * 扫描目录建完成表。
     *
     * v1.2 的命名从「内容指纹」改成了「4 位序号」：
     *   旧  e50d5943_丝袜淫女 第01章.wav     → 前缀是 chapterId
     *   新  0001_丝袜淫女 第01章.wav          → 前缀是序号
     * 两种都要认，而且 completedCache 一律以 chapterId 为 key，
     * 这样 isCompleted() / completedFile() 的调用方完全不用知道文件叫什么，
     * 老用户已经导出的文件也不用重导。
     *
     * 序号→chapterId 的映射靠 chapters 列表（Chapter.index + 1 即文件名前缀）。
     * chapters 传 null 时只能认旧命名—— 那条路径只用于「这本书还没解析出来」的场合。
     */
    public synchronized void scan(boolean deleteTmp, List<Chapter> chapters) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        completedCache.clear();
        looseCount = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String name = f.getName();
            if (name.endsWith(".tmp")) {
                if (deleteTmp) {
                    Io.deleteQuietly(f);
                }
                continue;
            }
            if (!name.endsWith(".wav")) {
                continue;
            }
            // 判定完成不能只看文件存在，必须过 RIFF 校验
            if (!WavTool.isValid(f)) {
                if (deleteTmp) {
                    Io.deleteQuietly(f);
                }
                continue;
            }
            int us = name.indexOf('_');
            String prefix = us > 0 ? name.substring(0, us) : name.substring(0, name.length() - 4);
            String chapterId = chapterIdOfPrefix(prefix, chapters);
            if (chapterId != null) {
                completedCache.put(chapterId, f);
            } else {
                // 没章节表 / 序号对不上：文件本身是好的，只是认不出属于哪一章。
                // 计入 looseCount，书架上的「已导出 N 章」才不会被少算。
                looseCount++;
            }
        }
    }

    /**
     * 文件名前缀 → chapterId。
     * 纯数字前缀按序号解释（v1.2 新命名），否则当成 chapterId（v1.1 旧命名）。
     * 认不出来就返回 null：这个文件不进完成表，章节会被当成「未导出」重新合成，
     * 而不是错认成别的章——错认比不认危险得多。
     */
    private static String chapterIdOfPrefix(String prefix, List<Chapter> chapters) {
        if (prefix == null || prefix.length() == 0) {
            return null;
        }
        // 先按旧命名解释：chapterId 是 sha256 前 8 位 hex，理论上存在「恰好全是数字」
        // 的情况（概率约 2%）。若直接按序号认领，会把别的章节的成品错认到这一章上，
        // 错认比不认危险得多 —— 会导致「已导出」却还能被覆盖重跑。所以先查 id 精确匹配。
        if (chapters != null) {
            for (int i = 0; i < chapters.size(); i++) {
                if (prefix.equals(chapters.get(i).id)) {
                    return prefix;
                }
            }
        }
        boolean numeric = true;
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c < '0' || c > '9') {
                numeric = false;
                break;
            }
        }
        if (numeric) {
            if (chapters == null) {
                return null;
            }
            int seq;
            try {
                seq = Integer.parseInt(prefix);
            } catch (NumberFormatException e) {
                return null;
            }
            for (int i = 0; i < chapters.size(); i++) {
                Chapter c = chapters.get(i);
                if (c.index + 1 == seq) {
                    return c.id;
                }
            }
            return null;
        }
        return prefix;
    }

    /** UI 展示用：某章已导出文件的大小描述，未完成返回 null */
    public synchronized String completedLabel(String chapterId) {
        File f = completedCache.get(chapterId);
        if (f == null) {
            return null;
        }
        return sizeLabel(f);
    }

    /** 单独对一个文件取大小/时长描述，不要求它在完成表里（给列表尽头用） */
    public static String sizeLabel(File f) {
        if (f == null || !f.isFile()) {
            return "";
        }
        WavTool.Info info = WavTool.probe(f);
        return WavTool.humanSize(f.length()) + " / " + WavTool.humanDuration(info.millis());
    }

    public synchronized boolean isCompleted(String chapterId) {
        return completedCache.containsKey(chapterId);
    }

    public synchronized File completedFile(String chapterId) {
        return completedCache.get(chapterId);
    }

    public synchronized List<String> completedIds() {
        return new ArrayList<String>(completedCache.keySet());
    }

    public synchronized int completedCount() {
        return completedCache.size();
    }

    /**
     * 不依赖章节表的成品数：completedCache 里的 + 认不出归属的。
     *
     * 书架列表只想要「这本书导出了多少、占了多少空间」，不该为了这个把整本书
     * 重新解析一遍（几百章的解析要好几秒）。这个方法就是给那种场景用的。
     * 但注意：它只能用来显示数字，做「这一章到底导出了没」的判断必须用
     * isCompleted(chapterId) ——那个需要 chapters 才能把序号映射回章节。
     */
    public synchronized int completedCountLoose() {
        return completedCache.size() + looseCount;
    }

    /** 清掉这本书导出的全部音频与播放列表。返回释放的字节数。 */
    public synchronized long clearAudio() {
        long freed = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                String name = f.getName();
                if (name.endsWith(".wav") || name.endsWith(".tmp")) {
                    freed += f.length();
                    Io.deleteQuietly(f);
                }
            }
        }
        completedCache.clear();
        looseCount = 0;
        return freed;
    }

    /** 删掉某一章单独导出的成品，章节会退回「未导出」状态。 */
    public synchronized boolean deleteChapter(String chapterId) {
        File f = completedCache.get(chapterId);
        if (f == null) {
            return false;
        }
        boolean ok = Io.deleteQuietly(f);
        completedCache.remove(chapterId);
        if (ok) {
            // 删掉一个成品，认不出归属的那批里如果正好少了它，计数要跟着降
            if (looseCount > 0) {
                looseCount--;
            }
        }
        return ok;
    }

    /**
     * 成品文件名：4 位序号 + 标题。
     *
     * 为什么不用内容指纹当前缀：指纹是 sha256，算出来的东西对用户毫无意义，
     * 而且按文件名排序会得到完全乱的顺序。改成 0001_ 开头之后，
     * 文件管理器里按名称排序 = 阅读顺序，一眼能看出是第几章。
     */
    public File finalFile(Chapter c) {
        return new File(dir, fileNameFor(c));
    }

    public static String fileNameFor(Chapter c) {
        return String.format(java.util.Locale.US, "%04d_%s.wav",
                Integer.valueOf(c.index + 1), SafeName.file(c.title, 40));
    }

    public File partFile(String chapterId, int index) {
        return new File(dir, chapterId + ".p" + index + ".tmp");
    }

    public File mergeTmp(String chapterId) {
        return new File(dir, chapterId + ".merge.tmp");
    }

    /** 分块合并 → .tmp → 原子重命名成 .wav。事务边界就是这次 rename。 */
    public synchronized File commit(File[] parts, Chapter chapter) {
        File finalFile = finalFile(chapter);
        File tmp;
        if (parts.length == 1) {
            tmp = parts[0];
        } else {
            tmp = mergeTmp(chapter.id);
            Io.deleteQuietly(tmp);
            if (!WavTool.merge(parts, tmp)) {
                Io.deleteQuietly(tmp);
                return null;
            }
        }
        if (!WavTool.isValid(tmp)) {
            Io.deleteQuietly(tmp);
            return null;
        }
        if (finalFile.exists()) {
            Io.deleteQuietly(finalFile);
        }
        Io.ensureParent(finalFile);
        boolean ok = tmp.renameTo(finalFile);
        if (!ok) {
            // 跨文件系统等情况，退化为复制
            try {
                Io.copyFile(tmp, finalFile);
                ok = WavTool.isValid(finalFile);
                Io.deleteQuietly(tmp);
            } catch (Exception e) {
                ok = false;
            }
        }
        if (parts.length > 1) {
            for (int i = 0; i < parts.length; i++) {
                Io.deleteQuietly(parts[i]);
            }
            Io.deleteQuietly(mergeTmp(chapter.id));
        }
        if (ok && WavTool.isValid(finalFile)) {
            completedCache.put(chapter.id, finalFile);
            return finalFile;
        }
        Io.deleteQuietly(finalFile);
        return null;
    }

    /** 一条成品：章节 + 落盘文件。给「成品」列表用。 */
    public static final class Output {
        public final Chapter chapter;
        public final File file;

        Output(Chapter chapter, File file) {
            this.chapter = chapter;
            this.file = file;
        }
    }

    /**
     * 成品列表，按章节顺序返回。没被 scan() 认领的文件（旧命名的孤儿、
     * 或序号对不上当前解析结果）不列出来——它们没法和章节对应，
     * 界面上显示一个点不开的条目只会让用户更困惑。
     */
    public synchronized List<Output> outputs(List<Chapter> chapters) {
        List<Output> out = new ArrayList<Output>();
        if (chapters == null) {
            return out;
        }
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            File f = completedCache.get(c.id);
            if (f != null && f.isFile()) {
                out.add(new Output(c, f));
            }
        }
        return out;
    }

    /** 当前目录下所有成品 WAV，按章节文件名排序。给「整体搬运到自定义目录」用。 */
    public synchronized List<File> wavFiles() {
        List<File> out = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && files[i].getName().endsWith(".wav")) {
                out.add(files[i]);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    public long totalBytes() {
        File[] files = dir.listFiles();
        long n = 0L;
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isFile()) {
                    n += files[i].length();
                }
            }
        }
        return n;
    }

    public void writeMeta(Book book, List<Chapter> chapters, String engine) {
        try {
            JSONObject o = new JSONObject();
            o.put("bookId", book.id);
            o.put("title", book.title);
            o.put("author", book.author == null ? "" : book.author);
            o.put("format", book.format);
            o.put("engine", engine == null ? "" : engine);
            o.put("updatedAt", System.currentTimeMillis());
            o.put("androidSdk", Build.VERSION.SDK_INT);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < chapters.size(); i++) {
                Chapter c = chapters.get(i);
                JSONObject co = new JSONObject();
                co.put("index", i);
                co.put("id", c.id);
                co.put("title", c.title);
                co.put("chars", c.length());
                File f = completedCache.get(c.id);
                co.put("done", f != null);
                if (f != null) {
                    co.put("file", f.getName());
                    co.put("bytes", f.length());
                    WavTool.Info info = WavTool.probe(f);
                    co.put("millis", info.millis());
                    co.put("wav", info.describe());
                }
                arr.put(co);
            }
            o.put("chapters", arr);
            Io.writeString(new File(dir, "meta.json.tmp"), o.toString());
            File meta = new File(dir, "meta.json");
            if (meta.exists()) {
                meta.delete();
            }
            new File(dir, "meta.json.tmp").renameTo(meta);
        } catch (Exception ignored) {
        }
    }

    /** 顺序播放列表，扔给 VLC / Poweramp 就能按章节顺序连播 */
    public void writePlaylist(Book book, List<Chapter> chapters) {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            File f = completedCache.get(c.id);
            if (f == null || !f.isFile()) {
                continue;
            }
            WavTool.Info info = WavTool.probe(f);
            sb.append("#EXTINF:").append(info.millis() / 1000L).append(',')
                    .append(c.title.replace(",", " ")).append('\n');
            sb.append(f.getAbsolutePath()).append('\n');
        }
        try {
            Io.writeString(new File(dir, SafeName.file(book.title, 40) + ".m3u"), sb.toString());
        } catch (Exception ignored) {
        }
    }
}
