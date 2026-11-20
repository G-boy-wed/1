package dev.linjian.peek;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 掌心窗自由桌面容器：元素可用绝对 dp 坐标自由摆放，支持
 *  - 单击 → 打开功能 / 换图 / 编辑文字
 *  - 长按 → 进入编辑态（显示缩放把手；贴图另有删除按钮）
 *  - 拖动本体 → 改位置（贴图拖动时自动避让角标，不遮挡）
 *  - 拖动右下角把手 → 拖拽缩放尺寸
 *  - 位置与尺寸落 DeskLayoutStore，重启不丢。
 *
 * kind：feature（角标，不可删）/ sticker（贴图，可删可增）/ text（文字卡，不可删）。
 */
public class FreeDeskLayout extends FrameLayout {
    public static final String KIND_FEATURE = "feature";
    public static final String KIND_STICKER = "sticker";
    public static final String KIND_TEXT = "text";
    /** 陪伴展示卡：贴图形式直接展现真实状态（窗边/此刻/轨迹/倒计日/行动），非可点开角标。 */
    public static final String KIND_CARD = "card";

    public interface Actions {
        void onClick(String kind, String id);
        void onDelete(String kind, String id);
        /** 编辑态下点左上角编辑按钮：角标/旧入口弹功能菜单（改名/换图标等）。 */
        void onEdit(String kind, String id);
        /** 编辑态下点右上角移动按钮：把元素移动到指定页（附当前位置尺寸）。 */
        void onMoveToPage(String kind, String id, int page, DeskLayoutStore.Pos pos);
        void onChanged();
    }

    private final int page;
    private final float density;
    private final Actions actions;
    private final List<ItemView> items = new ArrayList<>();
    private ItemView editing;

    public FreeDeskLayout(Context c, int page, Actions actions) {
        super(c);
        this.page = page;
        this.actions = actions;
        this.density = c.getResources().getDisplayMetrics().density;
        setClipChildren(false);
        setClipToPadding(false);
    }

    public int px(float dp) { return Math.round(dp * density); }

    /** 本容器代表的页号（用于元素归属页判断）。 */
    public int getPage() { return page; }

    public void addItem(final View content, final String kind, final String id,
                        DeskLayoutStore.Pos def, boolean deletable) {
        ItemView wrap = new ItemView(getContext(), kind, id, def, deletable);
        wrap.addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(wrap, wrap.params);
        items.add(wrap);
    }

    /** 重新读取存储并应用位置/尺寸（换图、改文案后整页重建时调用）。 */
    public void refreshAll() {
        for (ItemView v : items) v.applyStored();
    }

    public void clearEditing() {
        if (editing != null) { editing.leaveEdit(); editing = null; }
    }

    private void setEditing(ItemView v) {
        if (editing != null && editing != v) editing.leaveEdit();
        editing = v;
    }

    private boolean overlapsFeature(ItemView self, int l, int t, int w, int h) {
        for (ItemView o : items) {
            if (o == self || !KIND_FEATURE.equals(o.kind)) continue;
            int ol = o.params.leftMargin, ot = o.params.topMargin;
            int ow = o.params.width, oh = o.params.height;
            if (l < ol + ow && l + w > ol && t < ot + oh && t + h > ot) return true;
        }
        return false;
    }

    private int parentW() {
        ViewGroup p = (ViewGroup) getParent();
        return p == null ? getWidth() : p.getWidth();
    }
    private int parentH() {
        ViewGroup p = (ViewGroup) getParent();
        return p == null ? getHeight() : p.getHeight();
    }

    private final class ItemView extends FrameLayout {
        final String kind, id;
        final boolean deletable;
        DeskLayoutStore.Pos def;
        LayoutParams params;
        View handle, delBtn, editBtn, moveBtn;
        int pageCount = 4;
        float startRawX, startRawY;
        int startL, startT, startW, startH;
        boolean longPressed, moved, resizing;
        final Handler handler = new Handler();
        final Runnable longPress = new Runnable() {
            @Override public void run() {
                longPressed = true;
                setEditing(ItemView.this);
                enterEdit();
            }
        };

        ItemView(Context c, String kind, String id, DeskLayoutStore.Pos def, boolean deletable) {
            super(c);
            this.kind = kind; this.id = id; this.def = def; this.deletable = deletable;
            DeskLayoutStore.Pos p = DeskLayoutStore.get(c, page, kind, id, def);
            params = new LayoutParams(px(p.w), px(p.h));
            params.leftMargin = px(p.x);
            params.topMargin = px(p.y);
            params.gravity = Gravity.TOP | Gravity.START;
            setBackgroundColor(Color.TRANSPARENT);
            buildControls();
        }

        void applyStored() {
            DeskLayoutStore.Pos p = DeskLayoutStore.get(getContext(), page, kind, id, def);
            params.width = px(p.w); params.height = px(p.h);
            params.leftMargin = px(p.x); params.topMargin = px(p.y);
            setLayoutParams(params);
        }

        void save() {
            DeskLayoutStore.set(getContext(), page, kind, id, new DeskLayoutStore.Pos(
                    params.leftMargin / density, params.topMargin / density,
                    params.width / density, params.height / density));
        }

        private void buildControls() {
            handle = new View(getContext());
            handle.setBackgroundColor(0xCC78AE90);
            LayoutParams hp = new LayoutParams(px(20), px(20), Gravity.BOTTOM | Gravity.END);
            hp.rightMargin = px(2); hp.bottomMargin = px(2);
            handle.setLayoutParams(hp);
            handle.setVisibility(GONE);
            handle.setOnTouchListener((v, e) -> onHandleTouch(e));
            addView(handle);

            // 左上角操作按钮：贴图=删除（红）；角标/旧入口/文字=编辑（蓝，弹改名换图标菜单）
            View topBtn = new View(getContext());
            topBtn.setBackgroundColor(deletable ? 0xCCE07A7A : 0xCC5B9BD5);
            LayoutParams tp = new LayoutParams(px(20), px(20), Gravity.TOP | Gravity.START);
            tp.leftMargin = px(2); tp.topMargin = px(2);
            topBtn.setLayoutParams(tp);
            topBtn.setVisibility(GONE);
            if (deletable) {
                delBtn = topBtn;
                topBtn.setOnClickListener(x -> {
                    if (actions != null) actions.onDelete(kind, id);
                    FreeDeskLayout.this.removeView(ItemView.this);
                    items.remove(ItemView.this);
                    if (editing == ItemView.this) editing = null;
                });
            } else {
                editBtn = topBtn;
                topBtn.setOnClickListener(x -> {
                    if (actions != null) actions.onEdit(kind, id);
                });
            }
            addView(topBtn);

            // 右上角移动按钮：所有元素（角标/贴图/展示卡）长按后都能跨页移动
            moveBtn = new View(getContext());
            moveBtn.setBackgroundColor(0xCC7B8FB0);
            LayoutParams mp = new LayoutParams(px(20), px(20), Gravity.TOP | Gravity.END);
            mp.rightMargin = px(2); mp.topMargin = px(2);
            moveBtn.setLayoutParams(mp);
            moveBtn.setVisibility(GONE);
            moveBtn.setOnClickListener(x -> {
                if (actions != null) actions.onMoveToPage(kind, id, page,
                        new DeskLayoutStore.Pos(params.leftMargin / density, params.topMargin / density,
                                params.width / density, params.height / density));
            });
            addView(moveBtn);
        }

        void enterEdit() {
            handle.setVisibility(VISIBLE);
            if (delBtn != null) delBtn.setVisibility(VISIBLE);
            if (editBtn != null) editBtn.setVisibility(VISIBLE);
            if (moveBtn != null) moveBtn.setVisibility(VISIBLE);
            if (actions != null) actions.onChanged();
        }
        void leaveEdit() {
            handle.setVisibility(GONE);
            if (delBtn != null) delBtn.setVisibility(GONE);
            if (editBtn != null) editBtn.setVisibility(GONE);
            if (moveBtn != null) moveBtn.setVisibility(GONE);
            longPressed = false;
        }

        private boolean onHandleTouch(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    resizing = true;
                    startRawX = x; startRawY = y;
                    startW = params.width; startH = params.height;
                    handler.removeCallbacks(longPress);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!resizing) return true;
                    int nw = startW + (int) (x - startRawX);
                    int nh = startH + (int) (y - startRawY);
                    nw = Math.max(px(58), Math.min(nw, parentW() - params.leftMargin));
                    nh = Math.max(px(58), Math.min(nh, parentH() - params.topMargin));
                    params.width = nw; params.height = nh;
                    setLayoutParams(params);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (resizing) { resizing = false; save(); }
                    return true;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = x; startRawY = y;
                    startL = params.leftMargin; startT = params.topMargin;
                    moved = false; longPressed = false;
                    handler.postDelayed(longPress, 480L);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = x - startRawX, dy = y - startRawY;
                    if (!longPressed && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) {
                        handler.removeCallbacks(longPress);
                    }
                    if (longPressed) {
                        moved = true;
                        int nl = startL + (int) dx;
                        int nt = startT + (int) dy;
                        nl = Math.max(0, Math.min(nl, parentW() - params.width));
                        nt = Math.max(0, Math.min(nt, parentH() - params.height));
                        // 贴图拖动时避让角标：若与任一角标重叠，则保持原位不进入
                        if (KIND_STICKER.equals(kind) && overlapsFeature(this, nl, nt, params.width, params.height)) {
                            return true;
                        }
                        params.leftMargin = nl; params.topMargin = nt;
                        setLayoutParams(params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    if (longPressed && moved) {
                        save();
                    } else if (!moved && !longPressed && actions != null) {
                        actions.onClick(kind, id);
                    }
                    return true;
            }
            return true;
        }
    }
}
