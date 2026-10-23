package com.listenbook.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.TextView;

import com.listenbook.R;
import com.listenbook.export.ExportStore;
import com.listenbook.model.Chapter;
import com.listenbook.util.WavTool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出页的章节列表。
 *
 * v1.2 的变化：
 *   1. 多了筛选（全部 / 未导出 / 已导出）。筛选只影响「显示哪些行」，
 *      不影响勾选状态—— 用户筛掉几行之后，原来勾的还在，
 *      否则一筛选就要把选择重新算一遍，非常容易误操作。
 *   2. 已导出的章节行内直接给一个「试听」按钮，点已完成的章节 = 直接播，
 *      不用跳外部播放器、不用去文件管理器翻目录。
 *   3. 副行文案分两种：已导出显示真实文件大小/时长，未导出只显示预计时长。
 *      之前两种状态都往副行塞一堆数字，用户根本分不清哪个是结果哪个是估算。
 */
public class ChapterAdapter extends BaseAdapter {

    /** 筛选：全部 */
    public static final int FILTER_ALL = 0;
    /** 筛选：只看未导出 */
    public static final int FILTER_PENDING = 1;
    /** 筛选：只看已导出 */
    public static final int FILTER_DONE = 2;

    /** 行内「试听」按钮的回调，由 Activity 接到 AudioPlayer 上 */
    public interface OnPlayClick {
        void onPlay(Chapter chapter, File file);
    }

    private final LayoutInflater inflater;
    private final List<Chapter> chapters = new ArrayList<Chapter>();
    private final List<Boolean> checked = new ArrayList<Boolean>();
    /** visible[i] = chapters.get(visiblePos) 对应的下标，null 表示不过滤 */
    private final List<Integer> visible = new ArrayList<Integer>();
    private ExportStore store;
    private String exportingId = "";
    private int filter = FILTER_ALL;
    private OnPlayClick onPlay;

    public ChapterAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnPlayClick(OnPlayClick l) {
        this.onPlay = l;
    }

    public void setData(List<Chapter> data, ExportStore exportStore) {
        chapters.clear();
        checked.clear();
        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                chapters.add(data.get(i));
                checked.add(Boolean.FALSE);
            }
        }
        this.store = exportStore;
        rebuildVisible();
        notifyDataSetChanged();
    }

    public void setStore(ExportStore exportStore) {
        this.store = exportStore;
        notifyDataSetChanged();
    }

    public void setExportingId(String chapterId) {
        this.exportingId = chapterId == null ? "" : chapterId;
    }

    public void setFilter(int filterValue) {
        this.filter = filterValue;
        rebuildVisible();
        notifyDataSetChanged();
    }

    /**
     * store 内容变了（又合成完一章、删了成品）之后重建可见列表。
     * 只 notifyDataSetChanged 是不够的 —— 筛选「未导出」时，
     * 刚合成完的那一章会继续留在列表里显示「已导出」，必须重算可见性。
     */
    public void refreshFilter() {
        rebuildVisible();
        notifyDataSetChanged();
    }

    public int filter() {
        return filter;
    }

    private void rebuildVisible() {
        visible.clear();
        for (int i = 0; i < chapters.size(); i++) {
            if (matchesFilter(i)) {
                visible.add(Integer.valueOf(i));
            }
        }
    }

    private boolean matchesFilter(int index) {
        if (filter == FILTER_ALL) {
            return true;
        }
        boolean done = isCompletedAt(index);
        return filter == FILTER_DONE ? done : !done;
    }

    /** 当前筛选下可见的章节总数 */
    public int visibleCount() {
        return visible.size();
    }

    /** 把可见位置映射回 chapters 的真实下标 */
    private int realIndex(int position) {
        if (position < 0 || position >= visible.size()) {
            return -1;
        }
        return visible.get(position).intValue();
    }

    @Override
    public int getCount() {
        return visible.size();
    }

    @Override
    public Object getItem(int position) {
        int r = realIndex(position);
        return r < 0 ? null : chapters.get(r);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    /** 可见位置对应的真实章节下标。勾选/批量操作都要用它。 */
    public int realIndexOf(int position) {
        return realIndex(position);
    }

    public Chapter chapterAt(int position) {
        int r = realIndex(position);
        return r < 0 ? null : chapters.get(r);
    }

    public boolean isChecked(int position) {
        int r = realIndex(position);
        if (r < 0) {
            return false;
        }
        return checked.get(r).booleanValue();
    }

    public void toggle(int position) {
        int r = realIndex(position);
        if (r < 0) {
            return;
        }
        checked.set(r, Boolean.valueOf(!checked.get(r).booleanValue()));
        notifyDataSetChanged();
    }

    public void selectAll(boolean value) {
        for (int i = 0; i < checked.size(); i++) {
            checked.set(i, Boolean.valueOf(value));
        }
        notifyDataSetChanged();
    }

    public void invert() {
        for (int i = 0; i < checked.size(); i++) {
            checked.set(i, Boolean.valueOf(!checked.get(i).booleanValue()));
        }
        notifyDataSetChanged();
    }

    /**
     * 恢复持久化的选区（传入的是**真实下标**，不是可见位置）。
     * 越界的下标直接丢弃——书可能被重新导入过，下标对不上很正常。
     */
    public int restoreSelection(int[] realIndices) {
        if (realIndices == null || realIndices.length == 0) {
            return 0;
        }
        // 先全清，避免上次选了 1~100、这次只恢复 5~20 时残留旧的勾
        for (int i = 0; i < checked.size(); i++) {
            checked.set(i, Boolean.FALSE);
        }
        int n = 0;
        for (int i = 0; i < realIndices.length; i++) {
            int r = realIndices[i];
            if (r >= 0 && r < checked.size()) {
                checked.set(r, Boolean.TRUE);
                n++;
            }
        }
        notifyDataSetChanged();
        return n;
    }

    /** 只勾还没导出的 */
    public int selectPending() {
        int n = 0;
        for (int i = 0; i < chapters.size(); i++) {
            boolean pending = !isCompletedAt(i);
            checked.set(i, Boolean.valueOf(pending));
            if (pending) {
                n++;
            }
        }
        notifyDataSetChanged();
        return n;
    }

    /** 只勾已经导出的（想重跑某一章时用它） */
    public int selectCompleted() {
        int n = 0;
        for (int i = 0; i < chapters.size(); i++) {
            boolean done = isCompletedAt(i);
            checked.set(i, Boolean.valueOf(done));
            if (done) {
                n++;
            }
        }
        notifyDataSetChanged();
        return n;
    }

    /** 从可见位置往后全选（长按菜单里「从这里开始往后选」用） */
    public int selectFrom(int position) {
        int r = realIndex(position);
        if (r < 0) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < checked.size(); i++) {
            boolean on = i >= r;
            checked.set(i, Boolean.valueOf(on));
            if (on) {
                n++;
            }
        }
        notifyDataSetChanged();
        return n;
    }

    /** 只勾某一章（长按菜单里「只导出这一章」用） */
    public void selectOnly(int position) {
        int r = realIndex(position);
        if (r < 0) {
            return;
        }
        for (int i = 0; i < checked.size(); i++) {
            checked.set(i, Boolean.valueOf(i == r));
        }
        notifyDataSetChanged();
    }

    private boolean isCompletedAt(int index) {
        if (store == null || index < 0 || index >= chapters.size()) {
            return false;
        }
        return store.isCompleted(chapters.get(index).id);
    }

    /** 可见位置上这一章是否已导出 */
    public boolean isCompleted(int position) {
        int r = realIndex(position);
        if (r < 0 || r >= chapters.size()) {
            return false;
        }
        return isCompletedAt(r);
    }

    /** 可见位置上这一章的成品文件，没有则null */
    public File completedFileAt(int position) {
        int r = realIndex(position);
        if (store == null || r < 0 || r >= chapters.size()) {
            return null;
        }
        return store.completedFile(chapters.get(r).id);
    }

    public int checkedCount() {
        int n = 0;
        for (int i = 0; i < checked.size(); i++) {
            if (checked.get(i).booleanValue()) {
                n++;
            }
        }
        return n;
    }

    /** 勾选部分的章节下标（真实下标，交给 Service 用） */
    public int[] checkedIndices() {
        int[] tmp = new int[chapters.size()];
        int n = 0;
        for (int i = 0; i < checked.size(); i++) {
            if (checked.get(i).booleanValue()) {
                tmp[n++] = i;
            }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** 勾选部分的总字数与估算时长 */
    public long checkedChars() {
        long n = 0L;
        for (int i = 0; i < checked.size(); i++) {
            if (checked.get(i).booleanValue()) {
                n += chapters.get(i).length();
            }
        }
        return n;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        Holder h;
        if (v == null) {
            v = inflater.inflate(R.layout.item_chapter, parent, false);
            h = new Holder();
            h.chk = (CheckBox) v.findViewById(R.id.chk);
            h.title = (TextView) v.findViewById(R.id.ch_title);
            h.sub = (TextView) v.findViewById(R.id.ch_sub);
            h.state = (TextView) v.findViewById(R.id.ch_state);
            h.play = (TextView) v.findViewById(R.id.ch_play);
            v.setTag(h);
        } else {
            h = (Holder) v.getTag();
        }
        final int real = realIndex(position);
        // 这里必须同时校验 real 对 chapters 和 checked 都越界。
        // realIndex() 只按 visible.size() 判界，而 visible 是「过滤后的可见列表」，
        // checked/chapters 是「全量列表」—— 两者长度不等。
        // refreshFilter() 在后台回帖里重建 visible 时，ListView 可能正在拿旧 position 布局，
        // 于是 real 合法但 checked.get(real) 越界，直接 IndexOutOfBounds 闪退。
        if (real < 0 || real >= checked.size() || real >= chapters.size()) {
            return v;
        }
        final Chapter c = chapters.get(real);
        h.chk.setChecked(checked.get(real).booleanValue());
        h.title.setText((real + 1) + ". " + c.title);

        boolean done = isCompletedAt(real);
        // 必须 final：AIDE 以 source 1.7 编译，匿名内部类只能捕获显式 final 的局部变量
        final File file = done && store != null ? store.completedFile(c.id) : null;
        long sec = c.estimateSeconds();
        if (done && file != null) {
            // 已导出：只报真实结果，不掺估算值
            WavTool.Info info = WavTool.probe(file);
            h.sub.setText(WavTool.humanSize(file.length()) + " · "
                    + WavTool.humanDuration(info.millis()));
        } else {
            h.sub.setText(c.length() + " 字 · 约 " + WavTool.humanDuration(sec * 1000L));
        }

        if (c.id.equals(exportingId)) {
            h.state.setText("合成中");
            h.state.setTextColor(0xFFC9782F);
        } else if (done) {
            h.state.setText("已导出");
            h.state.setTextColor(0xFF2F6F4E);
        } else {
            h.state.setText("未导出");
            h.state.setTextColor(0xFF9AA0A6);
        }

        if (done && file != null && onPlay != null) {
            h.play.setVisibility(View.VISIBLE);
            h.play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onPlay.onPlay(c, file);
                }
            });
        } else {
            h.play.setVisibility(View.GONE);
            h.play.setOnClickListener(null);
        }
        return v;
    }

    private static final class Holder {
        CheckBox chk;
        TextView title;
        TextView sub;
        TextView state;
        TextView play;
    }
}