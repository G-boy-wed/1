package dev.linjian.peek;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 屏幕居中弹窗的 Activity 兜底通道：锁屏、或没有悬浮窗权限时才用。
 * 正常情况下优先走 AlertCenterOverlay，直接盖在当前 App 之上。
 */
public class AlertCenterActivity extends Activity {

    @Override
    protected void onDestroy() {
        PopupMutex.onDismissed(PopupMutex.CENTER_ALERT);
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        setContentView(R.layout.activity_alert_center);
        // 弹窗互斥：悬浮窗层级高于 Activity，启动本兜底 Activity 前必须移除两个通道的
        // 悬浮弹窗，否则会出现「悬浮窗 + Activity」双重展示。
        PopupMutex.beforeShow(PopupMutex.CENTER_ALERT);

        String title = getIntent().getStringExtra("title");
        String message = getIntent().getStringExtra("message");
        String tone = getIntent().getStringExtra("tone");
        String buttonText = getIntent().getStringExtra("button_text");
        String kind = getIntent().getStringExtra("kind");
        if (title == null || title.isEmpty()) title = "掌心窗";
        if (message == null || message.isEmpty()) message = AppPrefs.userName(this) + "，看一眼这里。";
        if (buttonText == null || buttonText.isEmpty()) buttonText = "知道啦";
        if (kind == null || kind.trim().isEmpty()) kind = "陪伴消息";

        bind(findViewById(R.id.root), kind, title, message, tone, buttonText, new Runnable() {
            @Override public void run() { finish(); }
        });
    }

    /** 兼容旧调用：不带类型标识时默认「陪伴消息」。 */
    public static void bind(View root, String title, String message, String tone, String buttonText, final Runnable onDismiss) {
        bind(root, "陪伴消息", title, message, tone, buttonText, onDismiss);
    }

    /** 卡片绑定：Activity 与悬浮窗两条通道共用同一份样式与位置逻辑；kindLabel 标明这是什么弹窗。 */
    public static void bind(View root, String kindLabel, String title, String message, String tone, String buttonText, final Runnable onDismiss) {
        if (root == null) return;
        Context ctx = root.getContext();
        boolean angry = "angry".equalsIgnoreCase(tone);
        GuidianTheme theme = GuidianTheme.from(GuidianState.themeName(ctx));
        int accent = theme.accentFor(tone);

        TextView t = root.findViewById(R.id.title);
        TextView m = root.findViewById(R.id.message);
        Button b = root.findViewById(R.id.btn);
        LinearLayout card = root.findViewById(R.id.card);
        FrameLayout rootFrame = root.findViewById(R.id.root);

        // 弹窗类型徽标：插到卡片最上方，标明这是什么弹窗（陪伴消息 / 主动聊天 / 提醒…）。
        if (card != null && kindLabel != null && !kindLabel.trim().isEmpty()) {
            android.widget.TextView badge = new android.widget.TextView(ctx);
            badge.setText(kindLabel.trim());
            badge.setTextSize(10);
            badge.setTextColor(accent);
            badge.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            badge.setLetterSpacing(0.12f);
            int pad = Math.round(10 * ctx.getResources().getDisplayMetrics().density);
            badge.setPadding(pad / 2, pad / 4, pad / 2, pad / 4);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(12 * ctx.getResources().getDisplayMetrics().density);
            bg.setColor(alterAlpha(accent, .14f));
            bg.setStroke(Math.max(1, Math.round(ctx.getResources().getDisplayMetrics().density)), alterAlpha(accent, .5f));
            badge.setBackground(bg);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
            badgeLp.bottomMargin = Math.round(6 * ctx.getResources().getDisplayMetrics().density);
            card.addView(badge, 0, badgeLp);
        }

        if (t != null) { t.setText(title); t.setTextColor(accent); }
        if (m != null) { m.setText(message); m.setTextColor(theme.text); }
        if (b != null) {
            b.setText(buttonText);
            b.setTextColor(theme.onPrimary);
            b.setBackgroundColor(accent);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (onDismiss != null) onDismiss.run(); }
            });
        }
        if (card != null) {
            card.setBackgroundColor(theme.panel);
            if (angry) card.setBackgroundColor(blend(theme.panel, accent, 0.12f));
            if (rootFrame != null) {
                ViewGroup.LayoutParams raw = card.getLayoutParams();
                FrameLayout.LayoutParams cardLp = raw instanceof FrameLayout.LayoutParams
                        ? (FrameLayout.LayoutParams) raw
                        : new FrameLayout.LayoutParams(-1, -2);
                cardLp.gravity = gravityFor(GuidianState.popupPosition(ctx));
                card.setLayoutParams(cardLp);
            }
        }
        if (rootFrame != null) rootFrame.setBackgroundColor(theme.scrim);
    }

    /** 位置跟随归电设置：顶部 / 居中 / 底部。 */
    private static int gravityFor(String position) {
        if (GuidianState.POSITION_TOP.equals(position)) return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (GuidianState.POSITION_BOTTOM.equals(position)) return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        return Gravity.CENTER;
    }

    private static int blend(int from, int to, float amount) {
        float x = Math.max(0f, Math.min(1f, amount));
        return Color.argb(Math.round(Color.alpha(from) * (1f - x) + Color.alpha(to) * x),
                Math.round(Color.red(from) * (1f - x) + Color.red(to) * x),
                Math.round(Color.green(from) * (1f - x) + Color.green(to) * x),
                Math.round(Color.blue(from) * (1f - x) + Color.blue(to) * x));
    }

    /** 同色换透明度（弹窗类型徽标用）。 */
    private static int alterAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * Math.max(0f, Math.min(1f, alpha))),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
