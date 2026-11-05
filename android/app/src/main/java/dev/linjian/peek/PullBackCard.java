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
 * 软级别（想你程度低于阈值）：两个按钮「现在就回去」「再等一会儿」，
 *          拒绝必须留下理由，理由由调用方写入日志。
 * 强级别（想你程度达到阈值，或情绪为生气/吃醋等）：只给一个按钮，点击后强制跳回聊天界面。
 *
 * 配色与位置不在这份代码里决定，统一读归电的设置（GuidianTheme + GuidianState.popupPosition），
 * 归电改主题或改位置，这里跟着变。
 */
public class PullBackCard {

    public interface Listener {
        /** 用户选择「现在就回去」。 */
        void onAccept(Context ctx, JSONObject cfg);
        /** 用户选择「再等一会儿」并给出了理由；reason 可能为空串。 */
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

    /** 情绪弹窗文案随机微调：弱情绪时随机加尾句，强情绪保持原话以显认真。 */
    private static String randomizeEmotionMessage(String tag, String base) {
        if (base == null || base.trim().isEmpty()) return base;
        if (CompanionEmotionState.isStrongTag(tag)) return base;
        String[] tails = {"", "（别装没看见我哦）", "（回一句会怎样嘛）", "（我就在这儿等你）"};
        int i = (int) ((System.currentTimeMillis() / 1000L) % tails.length);
        return base + tails[i];
    }

    /**
     * 弹窗类型标识：优先取调用方写入的 kind_label；
     * 没给时按 kind / from 推断（查岗拉回 / 分心查岗 / 情绪感知 / 主动聊天），
     * 都认不出就显示「守护提醒」。返回空串表示不显示徽标。
     */
    private static String kindLabelOf(JSONObject cfg) {
        String label = cfg == null ? "" : cfg.optString("kind_label", "").trim();
        if (!label.isEmpty()) return label;
        String kind = (cfg == null ? "" : cfg.optString("kind", "") + " " + cfg.optString("from", "")).trim();
        if (kind.isEmpty()) return "守护提醒";
        if (kind.contains("distraction")) return "分心查岗";
        if (kind.contains("checkin") || kind.contains("force_back")) return "查岗拉回";
        if (kind.contains("emotion")) return "情绪感知";
        if (kind.contains("initiative")) return "主动聊天";
        if (kind.contains("reminder")) return "提醒";
        return "守护提醒";
    }

    /** 构建卡片视图；调用方负责把它放到屏幕的合适位置。 */
    public static View build(final Context ctx, final JSONObject cfg, final Listener listener) {
        // 弹窗配色：设置页若单独覆盖了就用覆盖值，否则跟随归电主题。
        String themeName = GuidianState.themeName(ctx);
        String overrideTheme = PolicyStore.themeOverride(ctx);
        if (overrideTheme != null && !overrideTheme.trim().isEmpty()) themeName = GuidianTheme.normalize(overrideTheme);
        final GuidianTheme theme = GuidianTheme.from(themeName);
        final boolean strong = isStrongLevel(cfg);
        final int accent = theme.accentFor(cfg.optString("tone", "gentle"));
        String companion = AppPrefs.companionName(ctx);
        // 情绪判定弹窗必须标明情绪：标签由底层 EmotionPerception 依据用户状态算出，
        // 弹窗只负责展示，不再临时伪造随机标签，保证与底层判定完全一致。
        String tag = cfg.optString("emotion_tag", "");
        if (tag.trim().isEmpty() || CompanionEmotionState.TAG_CALM.equals(tag)) {
            tag = CompanionEmotionState.popupEmotionTag(ctx);
        }
        int miss = cfg.optInt("miss_percent", 0);
        int intensity = cfg.optInt("intensity", 0);
        String message = cfg.optString("message", "");
        if (message.trim().isEmpty()) message = CompanionEmotionState.message(ctx);
        message = randomizeEmotionMessage(tag, message);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 22), dp(ctx, 20), dp(ctx, 22), dp(ctx, 18));
        card.setBackground(rounded(theme.panel, 26, theme.line, 1));
        card.setElevation(dp(ctx, 10));

        // 弹窗类型徽标：每个弹窗发出必须标明是什么弹窗（查岗拉回 / 分心查岗 / 情绪感知 /
        // 主动聊天 / 提醒…）。优先读调用方给的 kind_label，否则按 from/kind 推断。
        String kindLabel = kindLabelOf(cfg);
        if (!kindLabel.isEmpty()) {
            TextView kindBadge = text(ctx, kindLabel, 10, accent, true);
            kindBadge.setLetterSpacing(.12f);
            kindBadge.setGravity(Gravity.CENTER);
            kindBadge.setBackground(rounded(withAlpha(accent, .14f), 12, withAlpha(accent, .5f), 1));
            kindBadge.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
            LinearLayout.LayoutParams kindLp = new LinearLayout.LayoutParams(-2, -2);
            card.addView(kindBadge, kindLp);
            TextView spacer = new TextView(ctx);
            spacer.setIncludeFontPadding(false);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(1, dp(ctx, 8));
            card.addView(spacer, spacerLp);
        }

        TextView eyebrow = text(ctx, "来自 " + companion + " 的情绪", 10, theme.subtext, false);
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

        // 想你程度进度条
        View bar = progressBar(ctx, theme, miss);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(ctx, 6));
        barLp.topMargin = dp(ctx, 10);
        card.addView(bar, barLp);

        TextView messageText = text(ctx, message, 14, theme.text, false);
        messageText.setLineSpacing(dp(ctx, 4), 1f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(-1, -2);
        msgLp.topMargin = dp(ctx, 12);
        card.addView(messageText, msgLp);

        String meta = "情绪强度 " + intensity + " / 100";
        if (strong) meta = meta + " · 情绪达到拉回阈值，只能回到软件";
        TextView metaText = text(ctx, meta, 10, theme.subtext, false);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
        metaLp.topMargin = dp(ctx, 8);
        card.addView(metaText, metaLp);

        // 拒绝理由抽屉（只有软级别才会出现；强级别没有拒绝入口，抽屉整个不挂载）
        final LinearLayout reasonDrawer = strong ? null : buildReasonDrawer(ctx, theme, cfg, listener);
        if (reasonDrawer != null) {
            // 关键：抽屉必须真的挂进视图树，否则点「再等一会儿」什么都不会发生。
            // 它默认 GONE，点按钮时展开在按钮行下方。
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

        // 按钮文案可由调用方定制（例如情绪自动弹窗用「同意，回去陪她 / 拒绝，再等会儿」）。
        String acceptLabel = cfg.optString("accept_label", "");
        if (acceptLabel.trim().isEmpty()) acceptLabel = "回到软件";
        String declineLabel = cfg.optString("decline_label", "");
        if (declineLabel.trim().isEmpty()) declineLabel = "拒绝并填写理由";
        TextView accept = button(ctx, acceptLabel, true, strong ? theme.danger : accent);
        TextView later = strong ? null : button(ctx, declineLabel, false, theme.primary);

        if (strong) {
            // 强级别：只有一个按钮，没有拒绝入口，点下去强制跳回聊天界面。
            LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(-1, -1);
            actions.addView(accept, acceptLp);
        } else {
            LinearLayout.LayoutParams laterLp = new LinearLayout.LayoutParams(0, -1, 1f);
            laterLp.rightMargin = dp(ctx, 10);
            actions.addView(later, laterLp);
            LinearLayout.LayoutParams acceptLp = new LinearLayout.LayoutParams(0, -1, 1f);
            actions.addView(accept, acceptLp);
        }

        accept.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (listener != null) listener.onAccept(ctx, cfg); }
        });
        if (!strong) {
            later.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (reasonDrawer != null) reasonDrawer.setVisibility(View.VISIBLE); }
            });
        }

        // 卡片外层容器：软级别允许点外面关闭，强级别不允许（必须表态）
        FrameLayout wrapper = new FrameLayout(ctx);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2);
        cardLp.leftMargin = dp(ctx, 18);
        cardLp.rightMargin = dp(ctx, 18);
        wrapper.addView(card, cardLp);
        if (!strong) {
            wrapper.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (listener != null) listener.onDismiss(ctx, cfg); }
            });
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { /* 吃掉点击，避免误触关闭 */ }
            });
        }
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
        tv.setEnabled(true);
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
