package com.listenbook.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.listenbook.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部弹层。零第三方依赖复刻 Material BottomSheet —— 项目里没有 androidx，
 * 而系统 AlertDialog.setItems 是冷冰冰的白底黑字，跟这套暖色系完全割裂。
 *
 * 实现要点：
 *   1. Dialog + Theme 透明背景 + Gravity.BOTTOM，比搞 WindowManager 悬浮窗稳
 *   2. ListView 在 wrap_content 下只会显示一行，所以 Measure 完首行后手算高度，
 *      上限取屏幕的 60%，长目录也能滚
 *   3. 点外部自动关闭，跟系统弹窗手感一致
 */
public final class Sheet {

    public static final class Row {
        public final String title;
        public final String sub;
        /** 附带数据，回调里自己取 */
        public final String tag;

        public Row(String title) {
            this(title, null, null);
        }

        public Row(String title, String sub) {
            this(title, sub, null);
        }

        public Row(String title, String sub, String tag) {
            this.title = title == null ? "" : title;
            this.sub = sub == null ? "" : sub;
            this.tag = tag;
        }

        public boolean hasSub() {
            return sub != null && sub.length() > 0;
        }
    }

    public interface Callback {
        void onSelected(int index, Row row);
    }

    private Sheet() {
    }

    /** 单选列表。checked < 0 表示纯菜单（不打勾）。 */
    public static Dialog list(Context context, String title, String sub,
                              List<Row> rows, int checked, final Callback callback) {
        final Dialog dialog = create(context);
        View root = inflate(context);
        dialog.setContentView(root);
        bindText(root, title, sub);

        final ListAdapter adapter = new ListAdapter(context, rows, checked);
        ListView list = (ListView) root.findViewById(R.id.sheet_list);
        list.setAdapter(adapter);
        limitHeight(context, list, rows.size());
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                dialog.dismiss();
                if (callback != null) {
                    callback.onSelected(position, rows.get(position));
                }
            }
        });
        bindCancel(root, dialog);
        show(dialog);
        return dialog;
    }

    /**
     * 确认框。也做成底部卡片，跟列表弹层手感一致。
     *
     * @param danger true 时主按钮用红色
     */
    public static Dialog confirm(Context context, String title, String message,
                                 String okText, boolean danger, final Runnable onOk) {
        final Dialog dialog = create(context);
        View root = LayoutInflater.from(context).inflate(R.layout.sheet_confirm, null);
        dialog.setContentView(root);

        TextView tvTitle = (TextView) root.findViewById(R.id.sheet_title);
        TextView tvMsg = (TextView) root.findViewById(R.id.sheet_message);
        TextView tvOk = (TextView) root.findViewById(R.id.sheet_ok);
        tvTitle.setText(title == null ? "" : title);
        tvMsg.setText(message == null ? "" : message);
        tvOk.setText(okText == null ? "确定" : okText);
        if (danger) {
            tvOk.setBackgroundResource(R.drawable.bg_btn_danger);
        }
        tvOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (onOk != null) {
                    onOk.run();
                }
            }
        });
        View cancelView = root.findViewById(R.id.sheet_cancel2);
        if (cancelView != null) {
            cancelView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }
        show(dialog);
        return dialog;
    }

    /**
     * 任意自定义内容的底部卡片。给「文件在哪」这种需要自定义布局的场景用。
     *
     * 窗口参数同样必须 show() 之后再设（原因见下面 show() 的注释），
     * 这条真机踩坑规则对所有走这个方法的弹层都成立，别在这里图省事挪回去。
     */
    public static Dialog showCard(Context context, View content) {
        Dialog dialog = create(context);
        dialog.setContentView(content);
        show(dialog);
        // 自定义卡片里往往有 ScrollView，但 ScrollView 没有 maxHeight 属性，
        // 内容一多（设置卡片有 22 个控件）就顶出屏幕。
        // capHeight 必须放在 show() 之后 —— 窗口没 attach 时设 attributes 会被 ROM 忽略，
        // 这正是下面 show() 里记的那条真机踩坑规则，别为了「看起来顺序更顺」挪回去。
        capHeight(context, dialog);
        return dialog;
    }

    /** 弹层高度封在屏幕 70%，超出部分由内部 ScrollView 滚。必须在 show() 之后调用。 */
    private static void capHeight(Context context, Dialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        int maxHeight = context.getResources().getDisplayMetrics().heightPixels * 7 / 10;
        WindowManager.LayoutParams attrs = w.getAttributes();
        if (attrs.height <= 0 || attrs.height > maxHeight) {
            attrs.height = maxHeight;
            w.setAttributes(attrs);
        }
    }

    // ------------------------------------------------------------------ 内部

    private static Dialog create(Context context) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setWindowAnimations(R.style.SheetAnim);
        }
        return dialog;
    }

    /**
     * 真机踩坑：setLayout / setGravity / setDimAmount 在 show() 之前调用，
     * 窗口还没 attach，不少 ROM（含部分国产深度定制）会直接忽略，
     * 窗口宽度退化成按内容包裹，整列被挤成一字一行竖排。
     * 必须 show() 之后再拿 attributes 改写，这一步别挪回 create()。
     */
    private static void show(Dialog dialog) {
        dialog.show();
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        WindowManager.LayoutParams attrs = w.getAttributes();
        attrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
        attrs.gravity = Gravity.BOTTOM;
        attrs.dimAmount = 0.32f;
        attrs.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        w.setAttributes(attrs);
    }

    private static View inflate(Context context) {
        return LayoutInflater.from(context).inflate(R.layout.sheet_list, null);
    }

    private static void bindText(View root, String title, String sub) {
        TextView tvTitle = (TextView) root.findViewById(R.id.sheet_title);
        TextView tvSub = (TextView) root.findViewById(R.id.sheet_sub);
        tvTitle.setText(title == null ? "" : title);
        if (sub != null && sub.length() > 0) {
            tvSub.setText(sub);
            tvSub.setVisibility(View.VISIBLE);
        }
    }

    private static void bindCancel(View root, final Dialog dialog) {
        View cancel = root.findViewById(R.id.sheet_cancel);
        if (cancel == null) {
            return;
        }
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
    }

    /**
     * ListView 在 wrap_content 下只肯显示一行。这里量一下行高，按行数算总高，
     * 再跟屏幕高度的 60% 取小值，保证长列表能滚动、短列表不拖一大块空白。
     */
    private static void limitHeight(Context context, ListView list, int count) {
        if (count <= 0) {
            return;
        }
        View probe = LayoutInflater.from(context).inflate(R.layout.item_sheet_row, list, false);
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        probe.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST), spec);
        int rowHeight = probe.getMeasuredHeight();
        if (rowHeight <= 0) {
            rowHeight = 56;
        }
        int maxHeight = context.getResources().getDisplayMetrics().heightPixels * 6 / 10;
        int want = rowHeight * count + 24;
        int height = Math.min(want, maxHeight);
        ViewGroup.LayoutParams lp = list.getLayoutParams();
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        } else {
            lp.height = height;
        }
        list.setLayoutParams(lp);
    }

    private static final class ListAdapter extends BaseAdapter {

        private final LayoutInflater inflater;
        private final List<Row> rows;
        private final int checked;

        ListAdapter(Context context, List<Row> rows, int checked) {
            this.inflater = LayoutInflater.from(context);
            this.rows = rows == null ? new ArrayList<Row>() : rows;
            this.checked = checked;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = inflater.inflate(R.layout.item_sheet_row, parent, false);
            }
            Row r = rows.get(position);
            TextView titleView = (TextView) v.findViewById(R.id.row_title);
            TextView subView = (TextView) v.findViewById(R.id.row_sub);
            TextView markView = (TextView) v.findViewById(R.id.row_mark);
            titleView.setText(r.title);
            if (r.hasSub()) {
                subView.setText(r.sub);
                subView.setVisibility(View.VISIBLE);
            } else {
                subView.setVisibility(View.GONE);
            }
            markView.setVisibility(checked == position ? View.VISIBLE : View.GONE);
            if (checked == position) {
                markView.setText("✓");
            }
            return v;
        }
    }
}
