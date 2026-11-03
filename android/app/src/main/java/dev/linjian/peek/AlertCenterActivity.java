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

        String title = getIntent().getStringExtra("title");
        String message = getIntent().getStringExtra("message");
        String tone = getIntent().getStringExtra("tone");
        String buttonText = getIntent().getStringExtra("button_text");
        if (title == null || title.isEmpty()) title = "掌心窗";
        if (message == null || message.isEmpty()) message = AppPrefs.userName(this) + "，看一眼这里。";
        if (buttonText == null || buttonText.isEmpty()) buttonText = "知道啦";

        bind(findViewById(R.id.root), title, message, tone, buttonText, new Runnable() {
            @Override public void run() { finish(); }
        });
    }

    /** 卡片绑定：Activity 与悬浮窗两条通道共用同一份样式与位置逻辑。 */
    public static void bind(View root, String title, String message, String tone, String buttonText, final Runnable onDismiss) {
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
}
