package dev.linjian.peek;

import android.app.Activity;
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
 * 屏幕居中弹窗：用 Activity + 半透明根布局把卡片固定在屏幕上，
 * 不需要把内容写进 MainActivity，也不依赖特定 App 的界面。
 * 文案与语气由后端下发（tone: gentle / angry）；
 * 配色与位置复用归电设置（GuidianTheme + 归电弹窗位置），归电改了这里跟着变。
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
        boolean angry = "angry".equalsIgnoreCase(tone);

        GuidianTheme theme = GuidianTheme.from(GuidianState.themeName(this));
        int accent = theme.accentFor(tone);

        TextView t = findViewById(R.id.title);
        TextView m = findViewById(R.id.message);
        Button b = findViewById(R.id.btn);
        LinearLayout card = findViewById(R.id.card);
        // root 在布局里是 FrameLayout：它没有 setGravity，位置要靠 card 的 LayoutParams.gravity 控制。
        FrameLayout root = findViewById(R.id.root);

        t.setText(title);
        m.setText(message);
        b.setText(buttonText);

        t.setTextColor(accent);
        m.setTextColor(theme.text);
        card.setBackgroundColor(theme.panel);
        b.setTextColor(theme.onPrimary);
        b.setBackgroundColor(accent);
        if (root != null) {
            root.setBackgroundColor(theme.scrim);
            // 位置跟随归电设置里的弹窗位置。
            ViewGroup.LayoutParams raw = card.getLayoutParams();
            FrameLayout.LayoutParams cardLp = raw instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) raw
                    : new FrameLayout.LayoutParams(-1, -2);
            cardLp.gravity = gravityFor(GuidianState.popupPosition(this));
            card.setLayoutParams(cardLp);
        }
        if (angry) {
            // 生气语气保留一点点压迫感：卡片描边用危险色。
            card.setBackgroundColor(blend(theme.panel, accent, 0.12f));
        }

        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
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
