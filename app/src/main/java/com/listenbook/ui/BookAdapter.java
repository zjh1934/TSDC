package com.listenbook.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.listenbook.R;
import com.listenbook.model.Book;
import com.listenbook.util.WavTool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书库卡片列表。用 ListView + BaseAdapter，不碰 androidx 的 RecyclerView。
 *
 * 每本书「已导出几章」需要扫目录才能知道，而 List 滚动时会疯狂调 getView，
 * 所以扫描结果由 Activity 在后台算好一次性灌进来（setExportStats），
 * 这里只做查表，getView 里绝不碰磁盘。
 */
public class BookAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Book> books;
    private Map<String, Integer> doneCount = new HashMap<String, Integer>();
    private Map<String, Long> doneBytes = new HashMap<String, Long>();

    public BookAdapter(Context context, List<Book> books) {
        this.inflater = LayoutInflater.from(context);
        this.books = books;
    }

    /** 后台统计完调用。key 是 bookId。 */
    public void setExportStats(Map<String, Integer> counts, Map<String, Long> bytes) {
        this.doneCount = counts == null ? new HashMap<String, Integer>() : counts;
        this.doneBytes = bytes == null ? new HashMap<String, Long>() : bytes;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return books == null ? 0 : books.size();
    }

    @Override
    public Object getItem(int position) {
        return books.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        Holder h;
        if (v == null) {
            v = inflater.inflate(R.layout.item_book, parent, false);
            h = new Holder();
            h.cover = (TextView) v.findViewById(R.id.cover);
            h.title = (TextView) v.findViewById(R.id.book_title);
            h.meta = (TextView) v.findViewById(R.id.book_meta);
            h.progress = (TextView) v.findViewById(R.id.book_progress);
            v.setTag(h);
        } else {
            h = (Holder) v.getTag();
        }
        Book b = books.get(position);
        String t = b.title == null || b.title.length() == 0 ? "未命名" : b.title;
        h.title.setText(t);
        h.cover.setText(String.valueOf(t.charAt(0)));
        StringBuilder meta = new StringBuilder();
        meta.append(b.format).append(" · ").append(b.chapterCount).append(" 章");
        if (b.charCount > 0) {
            meta.append(" · ");
            if (b.charCount >= 10000) {
                meta.append(String.format(java.util.Locale.CHINA, "%.1f", b.charCount / 10000.0d))
                        .append(" 万字");
            } else {
                meta.append(b.charCount).append(" 字");
            }
        }
        if (b.author != null && b.author.length() > 0) {
            meta.append(" · ").append(b.author);
        }
        h.meta.setText(meta.toString());

        if (!b.fileExists()) {
            h.progress.setText("文件已丢失，需重新导入");
            h.progress.setTextColor(0xFFB23A3A);
        } else {
            StringBuilder line = new StringBuilder();
            Integer exported = doneCount.get(b.id);
            if (exported != null && exported.intValue() > 0) {
                Long size = doneBytes.get(b.id);
                line.append("已导出 ").append(exported.intValue()).append(" 章")
                        .append("（").append(WavTool.humanSize(size == null ? 0L : size.longValue()))
                        .append("）");
            }
            if (line.length() == 0) {
                line.append("导入于 ").append(formatDate(b.addedAt));
                h.progress.setTextColor(0xFF7A8089);
            } else {
                h.progress.setTextColor(0xFF2F6F4E);
            }
            h.progress.setText(line.toString());
        }
        return v;
    }

    private static String formatDate(long millis) {
        if (millis <= 0L) {
            return "—";
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd", java.util.Locale.CHINA);
            return f.format(new java.util.Date(millis));
        } catch (Exception e) {
            return "—";
        }
    }

    private static final class Holder {
        TextView cover;
        TextView title;
        TextView meta;
        TextView progress;
    }
}
