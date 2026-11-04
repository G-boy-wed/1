package dev.linjian.peek;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 分级拉回弹窗的卡片内容。
 *
 * 分级逻辑（对应需求：未达阈值给选择，达到阈值只能回来）：
 *  - 软级别（想她百分比低于阈值，且非强情绪）：两个选项——「回到软件」与「拒绝并填写理由」，
 *            拒绝必须留一句理由，理由写入日志、调试日志并随活动事件上报。
 *  - 强级别（想她百分比达到阈值，或情绪为生气/吃醋/担心等强情绪）：只给一个按钮「回到软件」，
 *            点击后直接跳回目标 App（聊天界面），不给拒绝入口（达到阈值只能回来）。
 *
 * 弹窗顶部明确展示「情绪标签 + 想她百分比」，情绪标签由 CompanionEmotionState.popupEmotionTag
 * 决定（尊重真实情绪，平静时按想念/爱恋/担心随机点缀，体现随机触发）。
 *
 * 配色与位置不在这份代码里决定，统一读归电的设置（GuidianTheme + GuidianState.popupPosition），
 * 归电改主题或改位置，这里跟着变。
 */
public class PullBackCard {

    public interface Listener {
        /** 用户选择「回到软件」。 */
        void onAccept(Context ctx, JSONObject cfg);
        /** 用户选择「拒绝并填写理由」并给出了理由；reason 可能为空串。 */
        void onDecline(Context ctx, JSONObject cfg, String reason);
        /** 用户直接关闭（返回键 / 点外面），没有表态。 */
        void onDismiss(Context ctx, JSONObject cfg);
    }

    private PullBackCard() { }

    public static boolean isStrongLevel(JSONObject cfg) {
        if (cfg == null) return false;
        if ("strong".equalsIgnoreCase(cfg.optString("level", "soft"))) return true;
        return cfg.optBoolean("strong", false);
    }

    /** 构建卡片视图；调用方负责把它放到屏幕的合适位置（悬浮窗或锁屏 Activity）。 */
    public static View build(final Context ctx, final JSONObject cfg, final Listener listener) {
        // 弹窗配色：设置页若单独覆盖了就用覆盖值，否则跟随归电主题。
        String themeName = GuidianState.themeName(ctx);
        String overrideTheme = PolicyStore.themeOverride(ctx);
        if (overrideTheme != null && !overrideTheme.trim().isEmpty()) themeName = GuidianTheme.normalize(overrideTheme);
        final GuidianTheme theme = GuidianTheme.from(themeName);
        final boolean strong = isStrongLevel(cfg);
        final int accent = theme.accentFor(cfg.optString("tone", "gentle"));
        String companion = AppPrefs.companionName(ctx);
        // 情绪标签：尊重真实情绪，平静时随机点缀（想念/爱恋/担心），体现「随机触发」。
        String tag = CompanionEmotionState.popupEmotionTag(ctx);
        int miss = cfg.optInt("miss_percent", CompanionEmotionState.effectiveMissPercent(ctx));
        int intensity = cfg.optInt("intensity", CompanionEmotionState.effectiveIntensity(ctx));
        String message = cfg.optString("message", "");
        if (message.trim().isEmpty()) message = CompanionEmotionState.message(ctx);
        message = randomizeEmotionMessage(tag, message);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 22), dp(ctx, 20), dp(ctx, 22), dp(ctx, 18));
        card.setBackground(rounded(theme.panel, 26, theme.line, 1));
        card.setElevation(dp(ctx, 10));

        TextView eyebrow = text(ctx, "此刻的情绪", 10, theme.subtext, false);
        card.addView(eyebrow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tagRow = new LinearLayout(ctx);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tagText = text(ctx, tag, 24, accent, true);
        tagRow.addView(tagText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView percentText = text(ctx, "想你 " + miss + "%", 13, theme.primary, true);
        tagRow.addView(percentText, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-1, -2);
        tagLp.topMargin = dp(ctx, 6);
        card.addView(tagRow, tagLp);

        // 想她百分比进度条
        View bar = progressBar(ctx, theme, miss);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(ctx, 6));
        barLp.topMargin = dp(ctx, 10);
        card.addView(bar, barLp);

        TextView messageText = text(ctx, message, 14, theme.text, false);
        messageText.setLineSpacing(dp(ctx, 4), 1f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(-1, -2);
        msgLp.topMargin = dp(ctx, 12);
        card.addView(messageText, msgLp);

        if (strong) {
            // 强级别：达到阈值（或强情绪），只能回到软件，不给拒绝入口。
            TextView meta = text(ctx, "情绪已经达到拉回阈值，该回 " + companion + " 这里了。", 10, theme.subtext, false);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
            metaLp.topMargin = dp(ctx, 8);
            card.addView(meta, metaLp);

            TextView goBack = button(ctx, "回到软件", true, theme.danger);
            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(ctx, 46));
            actionsLp.topMargin = dp(ctx, 16);
            goBack.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
            actions.addView(goBack);
            card.addView(actions, actionsLp);

            goBack.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (listener != null) listener.onAccept(ctx, cfg); }
            });
        } else {
            // 软级别：两个选项——回到软件 / 拒绝并填写理由。
            TextView meta = text(ctx, "可以回到软件，也可以拒绝并留一句理由。", 10, theme.subtext, false);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
            metaLp.topMargin = dp(ctx, 8);
            card.addView(meta, metaLp);

            // 拒绝理由抽屉（软级别才有）：默认 GONE，点「拒绝并填写理由」展开。
            final LinearLayout reasonDrawer = buildReasonDrawer(ctx, theme, cfg, listener);
            if (reasonDrawer != null) {
                LinearLayout.LayoutParams drawerLp = new LinearLayout.LayoutParams(-1, -2);
                drawerLp.topMargin = dp(ctx, 12);
                card.addView(reasonDrawer, drawerLp);
            }

            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(ctx, 46));
            actionsLp.topMargin = dp(ctx, 16);
            card.addView(actions, actionsLp);

            TextView goBack = button(ctx, "回到软件", true, accent);
            TextView refuse = button(ctx, "拒绝并填写理由", false, theme.primary);
            LinearLayout.LayoutParams goBackLp = new LinearLayout.LayoutParams(0, -1, 1f);
            goBackLp.rightMargin = dp(ctx, 10);
            actions.addView(goBack, goBackLp);
            actions.addView(refuse, new LinearLayout.LayoutParams(0, -1, 1f));

            goBack.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (listener != null) listener.onAccept(ctx, cfg); }
            });
            refuse.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (reasonDrawer != null) reasonDrawer.setVisibility(View.VISIBLE); }
            });
        }

        // 卡片外层容器：仅软级别允许点外面关闭（视为「忽略」，不强制表态）；强级别必须点「回到软件」。
        FrameLayout wrapper = new FrameLayout(ctx);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2);
        cardLp.leftMargin = dp(ctx, 18);
        cardLp.rightMargin = dp(ctx, 18);
        wrapper.addView(card, cardLp);
        if (!strong) {
            wrapper.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (listener != null) listener.onDismiss(ctx, cfg); }
            });
        }
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { /* 吃掉点击，避免误触关闭 */ }
        });
        return wrapper;
    }

    private static LinearLayout buildReasonDrawer(final Context ctx, final GuidianTheme theme, final JSONObject cfg,
                                                  final Listener listener) {
        LinearLayout drawer = new LinearLayout(ctx);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16));
        drawer.setBackground(rounded(theme.soft, 20, theme.line, 1));
        drawer.setVisibility(View.GONE);

        TextView title = text(ctx, "晚一点也可以，给我留一句。", 15, theme.text, true);
        drawer.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text(ctx, "理由会写进日志，陪伴对象能看到。", 10, theme.subtext, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(ctx, 4);
        drawer.addView(hint, hintLp);

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.topMargin = dp(ctx, 8);
        drawer.addView(grid, gridLp);

        final EditText input = new EditText(ctx);
        input.setHint("自己写一句");
        input.setTextColor(theme.text);
        input.setHintTextColor(withAlpha(theme.subtext, .72f));
        input.setSingleLine(true);
        input.setTextSize(12);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(rounded(theme.panel, 18, theme.line, 1));
        input.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, dp(ctx, 42));
        inputLp.topMargin = dp(ctx, 10);
        drawer.addView(input, inputLp);

        final TextView send = button(ctx, "记下来", true, theme.primary);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(-1, dp(ctx, 42));
        sendLp.topMargin = dp(ctx, 8);
        drawer.addView(send, sendLp);

        String[] reasons = GuidianState.quickReasons(ctx);
        LinearLayout row = null;
        int column = 0;
        for (String raw : reasons) {
            final String reason = raw == null ? "" : raw.trim();
            if (reason.isEmpty()) continue;
            if (row == null || column == 2) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(ctx, 36));
                rowLp.topMargin = dp(ctx, 8);
                grid.addView(row, rowLp);
                column = 0;
            }
            TextView choice = text(ctx, reason, 11, theme.text, false);
            choice.setGravity(Gravity.CENTER);
            choice.setBackground(rounded(theme.panel, 18, theme.line, 1));
            LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(0, dp(ctx, 34), 1);
            if (column == 0) choiceLp.rightMargin = dp(ctx, 4); else choiceLp.leftMargin = dp(ctx, 4);
            row.addView(choice, choiceLp);
            choice.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (listener != null) listener.onDecline(ctx, cfg, reason); }
            });
            column++;
        }

        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String reason = "";
                try { reason = input.getText() == null ? "" : input.getText().toString().trim(); } catch (Exception ignored) { }
                if (reason.isEmpty()) reason = "晚点找你";
                if (listener != null) listener.onDecline(ctx, cfg, reason);
            }
        });

        return drawer;
    }

    /** 情绪弹窗文案随机微调：保证每次弹出文案有变化，但强情绪保持原话以显认真。 */
    private static String randomizeEmotionMessage(String tag, String base) {
        if (base == null || base.trim().isEmpty()) return base;
        if (CompanionEmotionState.isStrongTag(tag)) return base; // 强情绪不加尾巴
        String[] tails = {"", "（别装没看见我哦）", "（回一句会怎样嘛）", "（我就在这儿等你）"};
        int i = (int) ((System.currentTimeMillis() / 1000L) % tails.length);
        return base + tails[i];
    }

    private static View progressBar(Context ctx, GuidianTheme theme, int percent) {
        FrameLayout box = new FrameLayout(ctx);
        GradientDrawable track = new GradientDrawable();
        track.setColor(withAlpha(theme.line, .55f));
        track.setCornerRadius(dp(ctx, 3));
        box.setBackground(track);
        View fill = new View(ctx);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(theme.primary);
        bar.setCornerRadius(dp(ctx, 3));
        fill.setBackground(bar);
        int safe = Math.max(0, Math.min(100, percent));
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(0, -1);
        box.addView(fill, fillLp);
        // 进度条宽度在测量后再按比例设置。
        box.setTag(Integer.valueOf(safe));
        box.post(new Runnable() {
            @Override public void run() {
                try {
                    View fill = (View) box.getChildAt(0);
                    int total = box.getWidth();
                    int value = box.getTag() == null ? 0 : ((Integer) box.getTag()).intValue();
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fill.getLayoutParams();
                    lp.width = Math.max(dp(ctx, 3), total * value / 100);
                    fill.setLayoutParams(lp);
                } catch (Exception ignored) { }
            }
        });
        return box;
    }

    private static TextView button(Context ctx, String label, boolean primary, int accent) {
        TextView tv = text(ctx, label, 13, primary ? Color.WHITE : accent, true);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(rounded(primary ? accent : withAlpha(accent, .12f), 23, primary ? accent : withAlpha(accent, .45f), 1));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private static TextView text(Context ctx, String value, float sp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(value == null ? "" : value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        tv.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return tv;
    }

    private static GradientDrawable rounded(int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp);
        if (strokeDp > 0) d.setStroke(strokeDp, stroke);
        return d;
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int dp(Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + .5f);
    }
}
