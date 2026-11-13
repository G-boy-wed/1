package dev.linjian.peek;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 掌心窗 全站 UI 美化规范 v2026-11-14-03 · COMPONENT 通用件。
 *
 * 16 个页面 / 弹层 / 桌面组件共用这一套构建器：chip、segmented、card、glassCard、
 * row、sheet、primaryPill、whitePill、badge、colorDot、empty、toast、文本字号工厂与动效。
 * 所有颜色实时取自 UITheme.current(ctx)，因此切主题 / 亮暗后重绘即全站生效，
 * 所有尺寸、圆角、间距、字号取自 UiTokens，页面里不再写死数值。
 */
public final class UiKit {
    private UiKit() {}

    public interface OnSelect { void onSelect(int index); }

    private static int d(float v) { return UiTokens.dpI(v); }
    private static UITheme theme(Context ctx) { return UITheme.current(ctx); }

    // ---------------------------------------------------------------- 文本

    /** NAV-02 页面小写标签：11sp 大写 + letterSpacing 2sp。 */
    public static TextView pageLabel(Context ctx, String text) {
        UITheme t = theme(ctx);
        TextView v = new TextView(ctx);
        v.setText(text.toUpperCase(Locale.ROOT));
        v.setTextSize(UiTokens.FONT_PAGE_LABEL);
        v.setLetterSpacing(UiTokens.LETTER_SPACING / UiTokens.FONT_PAGE_LABEL);
        v.setTextColor(t.subtext);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    /** NAV-02 大标题：28sp Bold，滚动折叠时页面自行改为 18sp。 */
    public static TextView pageTitle(Context ctx, String text) {
        UITheme t = theme(ctx);
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(UiTokens.FONT_PAGE_TITLE);
        v.setTextColor(t.text);
        v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return v;
    }

    /** PAGE-07 / PAGE-14 大字数字：64sp Light。 */
    public static TextView displayNumber(Context ctx, String text) {
        UITheme t = theme(ctx);
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(UiTokens.FONT_DISPLAY);
        v.setTextColor(t.text);
        v.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    public static TextView rowText(Context ctx, String text) {
        UITheme t = theme(ctx);
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(UiTokens.FONT_ROW);
        v.setTextColor(t.text);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    public static TextView value(Context ctx, String text) {
        UITheme t = theme(ctx);
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(UiTokens.FONT_VALUE);
        v.setTextColor(t.subtext);
        return v;
    }

    public static TextView note(Context ctx, String text) {
        UITheme t = theme(ctx);
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(UiTokens.FONT_NOTE);
        v.setTextColor(t.subtext);
        return v;
    }

    // ---------------------------------------------------------------- 胶囊 / 分段

    /** COMPONENT chip：高 36dp，选中白底深字，未选 #EFEFEF 灰字。 */
    public static TextView chip(Context ctx, String text, boolean selected) {
        UITheme t = theme(ctx);
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(UiTokens.FONT_VALUE);
        v.setTextColor(selected ? t.text : t.subtext);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setBackground(t.chip(selected));
        v.setHeight(d(UiTokens.CHIP_H));
        v.setPadding(d(UiTokens.SPACE_M), 0, d(UiTokens.SPACE_M), 0);
        return v;
    }

    /** PAGE-03/05/06 的胶囊分段控件：选中白底深字，整条无分割线、靠间距分块。 */
    public static LinearLayout segmented(Context ctx, String[] labels, int selected, OnSelect cb) {
        UITheme t = theme(ctx);
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(t.soft(UiTokens.CHIP_H / 2f + 2f));
        bar.setPadding(d(UiTokens.SPACE_XS), d(UiTokens.SPACE_XS), d(UiTokens.SPACE_XS), d(UiTokens.SPACE_XS));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = chip(ctx, labels[i], i == selected);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, d(UiTokens.CHIP_H), 1f);
            lp.setMargins(d(2), 0, d(2), 0);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> {
                for (int j = 0; j < bar.getChildCount(); j++) {
                    View child = bar.getChildAt(j);
                    boolean on = j == idx;
                    child.setBackground(t.chip(on));
                    if (child instanceof TextView) ((TextView) child).setTextColor(on ? t.text : t.subtext);
                }
                if (cb != null) cb.onSelect(idx);
            });
            bar.addView(c);
        }
        return bar;
    }

    // ---------------------------------------------------------------- 卡片 / 抽屉

    /** COMPONENT card：白卡 radius 24dp + 轻阴影，无分割线。 */
    public static LinearLayout card(Context ctx) {
        UITheme t = theme(ctx);
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(t.card(UiTokens.RADIUS_CARD, 0f));
        l.setPadding(d(UiTokens.SPACE_M), d(UiTokens.SPACE_M), d(UiTokens.SPACE_M), d(UiTokens.SPACE_M));
        l.setElevation(d(UiTokens.SHADOW_CARD_Y));
        return l;
    }

    /** 毛玻璃卡：白色 60% + 1dp 白色描边 + radius 24dp（PAGE-02/11/13 用）。 */
    public static LinearLayout glassCard(Context ctx, float radiusDp) {
        UITheme t = theme(ctx);
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.dark ? Color.argb(214, 46, 46, 50) : UiTokens.GLASS_CARD);
        g.setCornerRadius(UiTokens.dp(radiusDp));
        g.setStroke(d(UiTokens.STROKE_DP), t.dark ? Color.argb(70, 255, 255, 255) : UiTokens.STROKE);
        l.setBackground(g);
        l.setPadding(d(UiTokens.SPACE_L), d(UiTokens.SPACE_L), d(UiTokens.SPACE_L), d(UiTokens.SPACE_L));
        return l;
    }

    /** COMPONENT sheet：顶部 4dp 拖拽条 + radius 28dp 容器。 */
    public static LinearLayout sheet(Context ctx) {
        UITheme t = theme(ctx);
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.card);
        g.setCornerRadii(new float[]{
                UiTokens.dp(UiTokens.RADIUS_SHEET), UiTokens.dp(UiTokens.RADIUS_SHEET),
                UiTokens.dp(UiTokens.RADIUS_SHEET), UiTokens.dp(UiTokens.RADIUS_SHEET),
                0, 0, 0, 0});
        l.setBackground(g);
        l.setPadding(d(UiTokens.SPACE_L), d(UiTokens.SPACE_S), d(UiTokens.SPACE_L), d(UiTokens.SPACE_L));
        l.addView(dragHandle(ctx));
        return l;
    }

    public static View dragHandle(Context ctx) {
        UITheme t = theme(ctx);
        View v = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.line);
        g.setCornerRadius(UiTokens.dp(2));
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(36), d(4));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = d(UiTokens.SPACE_M);
        v.setLayoutParams(lp);
        return v;
    }

    /** COMPONENT row：高 64dp，无分割线，靠 12dp 间距分块。 */
    public static LinearLayout row(Context ctx, String title, String valueText) {
        UITheme t = theme(ctx);
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(d(UiTokens.ROW_H));
        TextView titleV = rowText(ctx, title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleV.setLayoutParams(tlp);
        l.addView(titleV);
        TextView val = value(ctx, valueText == null ? "" : valueText);
        val.setPadding(d(UiTokens.FONT_NOTE), 0, 0, 0);
        l.addView(val);
        TextView arrow = value(ctx, "›");
        arrow.setTextSize(UiTokens.FONT_ROW);
        arrow.setPadding(d(UiTokens.SPACE_S), 0, 0, 0);
        l.addView(arrow);
        l.setBackground(t.card(UiTokens.RADIUS_ROW, 0f));
        l.setPadding(d(UiTokens.SPACE_M), d(UiTokens.SPACE_S), d(UiTokens.SPACE_M), d(UiTokens.SPACE_S));
        return l;
    }

    // ---------------------------------------------------------------- 按钮 / 徽标

    public static Button primaryPill(Context ctx, String text) {
        UITheme t = theme(ctx);
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(UiTokens.FONT_ROW);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(t.pill(true));
        b.setMinHeight(d(48));
        b.setStateListAnimator(null);
        pressScale(b);
        return b;
    }

    public static Button whitePill(Context ctx, String text) {
        UITheme t = theme(ctx);
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(t.text);
        b.setTextSize(UiTokens.FONT_ROW);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(t.pill(false));
        b.setMinHeight(d(48));
        b.setStateListAnimator(null);
        pressScale(b);
        return b;
    }

    /** COMPONENT badge：11sp、圆角 8dp、弱红底白字。 */
    public static TextView badge(Context ctx, String text) {
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(UiTokens.FONT_PAGE_LABEL);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(UiTokens.DANGER);
        g.setCornerRadius(UiTokens.dp(8));
        v.setBackground(g);
        v.setPadding(d(UiTokens.SPACE_S), d(2), d(UiTokens.SPACE_S), d(2));
        return v;
    }

    /** COMPONENT colorDot：36dp 圆，选中 2dp 描边 + 内勾。 */
    public static FrameLayout colorDot(Context ctx, int color, boolean selected) {
        UITheme t = theme(ctx);
        FrameLayout f = new FrameLayout(ctx);
        int size = d(36);
        f.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        if (selected) dot.setStroke(d(2), t.primary);
        f.setBackground(dot);
        if (selected) {
            TextView check = new TextView(ctx);
            check.setText("✓");
            check.setTextSize(UiTokens.FONT_VALUE);
            check.setTextColor(Color.WHITE);
            check.setGravity(Gravity.CENTER);
            f.addView(check);
        }
        return f;
    }

    /** COMPONENT empty：居中圆插画 96dp + 一行说明 + 白胶囊按钮。 */
    public static LinearLayout emptyState(Context ctx, String hint, String action, Runnable onAction) {
        UITheme t = theme(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(d(UiTokens.SPACE_L), d(UiTokens.SPACE_L), d(UiTokens.SPACE_L), d(UiTokens.SPACE_L));

        View circle = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(UiTokens.blend(t.card, t.subtext, 0.18f));
        circle.setBackground(g);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(d(96), d(96));
        clp.bottomMargin = d(UiTokens.SPACE_M);
        circle.setLayoutParams(clp);
        box.addView(circle);

        TextView tv = note(ctx, hint);
        tv.setGravity(Gravity.CENTER);
        box.addView(tv);

        if (action != null) {
            Button b = whitePill(ctx, action);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = d(UiTokens.SPACE_M);
            b.setLayoutParams(blp);
            if (onAction != null) b.setOnClickListener(v -> onAction.run());
            box.addView(b);
        }
        return box;
    }

    /** COMPONENT toast：毛玻璃胶囊，距底 96dp，2s 消失。 */
    public static void toast(Context ctx, String text) {
        UITheme t = theme(ctx);
        TextView v = new TextView(ctx);
        v.setText(text);
        v.setTextSize(UiTokens.FONT_VALUE);
        v.setTextColor(t.text);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.dark ? Color.argb(226, 48, 48, 52) : UiTokens.GLASS_CARD);
        g.setCornerRadius(UiTokens.dp(UiTokens.RADIUS_ROW));
        g.setStroke(d(UiTokens.STROKE_DP), t.dark ? Color.argb(70, 255, 255, 255) : UiTokens.STROKE);
        v.setBackground(g);
        v.setPadding(d(UiTokens.SPACE_L), d(UiTokens.SPACE_S), d(UiTokens.SPACE_L), d(UiTokens.SPACE_S));
        Toast tst = new Toast(ctx);
        tst.setDuration(Toast.LENGTH_SHORT);
        tst.setGravity(Gravity.BOTTOM, 0, d(96));
        tst.setView(v);
        tst.show();
    }

    // ---------------------------------------------------------------- 动效 · MOTION

    /** 点击缩放 0.97 / 120ms，不引入弹跳、旋转、闪烁。 */
    public static void pressScale(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(UiTokens.TAP_SCALE).scaleY(UiTokens.TAP_SCALE)
                        .setDuration(UiTokens.MOTION_TAP).start();
            } else if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(UiTokens.MOTION_TAP).start();
            }
            return false; // 不吞掉点击，OnClickListener 照常触发
        });
    }

    /** 页面进入：上浮 12dp + 淡入 220ms ease-out。 */
    public static void enterAnim(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(UiTokens.dp(UiTokens.MOTION_ENTER_Y));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(UiTokens.MOTION_ENTER)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
