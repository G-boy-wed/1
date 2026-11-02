package dev.linjian.peek;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 屏幕居中弹窗：用 Activity + 半透明根布局把卡片固定在屏幕正中央，
 * 不需要把内容写进 MainActivity，也不依赖特定 App 的界面。
 * 文案与语气由后端下发（tone: gentle / angry）。
 */
public class AlertCenterActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_alert_center);

        String title = getIntent().getStringExtra("title");
        String message = getIntent().getStringExtra("message");
        String tone = getIntent().getStringExtra("tone");
        String buttonText = getIntent().getStringExtra("button_text");
        if (title == null || title.isEmpty()) title = "掌心窗";
        if (message == null || message.isEmpty()) message = "宝宝，看一眼这里。";
        if (buttonText == null || buttonText.isEmpty()) buttonText = "知道啦";
        boolean angry = "angry".equalsIgnoreCase(tone);

        TextView t = findViewById(R.id.title);
        TextView m = findViewById(R.id.message);
        Button b = findViewById(R.id.btn);
        LinearLayout card = findViewById(R.id.card);
        t.setText(title);
        m.setText(message);
        b.setText(buttonText);

        if (angry) {
            t.setTextColor(0xFFC62828);
            card.setBackgroundColor(0xFFFFF3F3);
            b.setBackgroundColor(0xFFC62828);
        } else {
            t.setTextColor(0xFF5B6CFF);
            card.setBackgroundColor(0xFFFFFFFF);
            b.setBackgroundColor(0xFF5B6CFF);
        }

        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
    }
}
