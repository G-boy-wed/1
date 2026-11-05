package dev.linjian.peek;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 归电的沉浸式来电页；只调整展示，不改变归电判断与记录逻辑。 */
public class GuidianActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout reasonDrawer;
    private GuidianTheme theme;
    private TextView callerName;
    private TextView callState;
    private TextView rejectButton;
    private TextView acceptButton;
    private WaveLineView waveLine;
    private View avatarBox;
    private boolean connected;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        theme = GuidianTheme.from(GuidianState.themeName(this));
        applySystemBars();
        buildUi(getIntent() == null ? "" : getIntent().getStringExtra("prompt"));
    }

    private void applySystemBars() {
        getWindow().setNavigationBarColor(theme.background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            // Bug 4.1：允许内容布局延伸到导航栏之下，再用底部内边距避让，避免全面屏手势导航下
            // 底部「× 稍后 / ⌁ 接通」按钮与理由抽屉被系统导航条遮挡。
            flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (theme.dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void buildUi(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) prompt = GuidianState.pickPrompt(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(theme.background);
        setContentView(root);

        addDecor(R.drawable.decor_guidian_rose, Gravity.TOP | Gravity.RIGHT,
                dp(164), dp(220), -dp(12), dp(8), theme.roseAlpha);
        addDecor(R.drawable.decor_guidian_butterfly, Gravity.BOTTOM | Gravity.LEFT,
                dp(126), dp(190), -dp(20), dp(76), theme.butterflyAlpha);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(30), dp(34), dp(30), dp(24) + navBarHeight());
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));

        String companion = AppPrefs.companionName(this);
        TextView eyebrow = text("来自 " + companion, 9, theme.primary, true);
        eyebrow.setLetterSpacing(.18f);
        eyebrow.setGravity(Gravity.CENTER);
        body.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(22)));

        callerName = text(companion, 28, theme.text, true);
        callerName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = dp(9);
        body.addView(callerName, nameLp);

        callState = text("正在找你", 9, theme.subtext, false);
        callState.setGravity(Gravity.CENTER);
        callState.setLetterSpacing(.08f);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, -2);
        stateLp.topMargin = dp(4);
        body.addView(callState, stateLp);

        FrameLayout callVisual = new FrameLayout(this);
        LinearLayout.LayoutParams visualLp = new LinearLayout.LayoutParams(-1, dp(142));
        visualLp.topMargin = dp(12);
        body.addView(callVisual, visualLp);

        avatarBox = createAvatar();
        FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(dp(102), dp(102), Gravity.CENTER);
        callVisual.addView(avatarBox, avatarLp);

        AlphaAnimation breath = new AlphaAnimation(.82f, 1f);
        breath.setDuration(1500);
        breath.setRepeatMode(Animation.REVERSE);
        breath.setRepeatCount(Animation.INFINITE);
        avatarBox.startAnimation(breath);

        TextView message = text("“" + prompt.trim() + "”", 14, theme.text, false);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(-1, -2);
        messageLp.topMargin = dp(5);
        body.addView(message, messageLp);

        TextView quiet = text("不用急着回答，听见就好。", 9, theme.subtext, false);
        quiet.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams quietLp = new LinearLayout.LayoutParams(-1, -2);
        quietLp.topMargin = dp(9);
        body.addView(quiet, quietLp);

        waveLine = new WaveLineView(theme);
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(dp(132), dp(40));
        waveLp.topMargin = dp(12);
        body.addView(waveLine, waveLp);

        // 归电内容位置跟随归电设置里的“弹窗位置”，拉回弹窗与居中弹窗读的是同一份配置。
        String position = GuidianState.popupPosition(this);
        float bottomSpacerWeight = .58f;
        if (GuidianState.POSITION_TOP.equals(position)) bottomSpacerWeight = .04f;
        else if (GuidianState.POSITION_BOTTOM.equals(position)) bottomSpacerWeight = .92f;

        body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, bottomSpacerWeight));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        body.addView(actions, new LinearLayout.LayoutParams(-1, dp(72)));

        LinearLayout rejectAction = compactAction("×", "稍后", false);
        rejectButton = (TextView) rejectAction.getChildAt(0);
        LinearLayout acceptAction = compactAction("⌁", "接通", true);
        acceptButton = (TextView) acceptAction.getChildAt(0);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(dp(58), dp(72));
        left.rightMargin = dp(13);
        actions.addView(rejectAction, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(dp(58), dp(72));
        right.leftMargin = dp(13);
        actions.addView(acceptAction, right);

        TextView returnHint = text("接通后回到 " + GuidianState.targetLabel(this), 8, withAlpha(theme.subtext, .72f), false);
        returnHint.setGravity(Gravity.CENTER);
        returnHint.setLetterSpacing(.06f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(8);
        body.addView(returnHint, hintLp);

        // Keep the controls away from the screen edge while preserving calm space above them.
        body.addView(new View(this), new LinearLayout.LayoutParams(1, dp(90)));

        rejectButton.setOnClickListener(v -> showReasonDrawer());
        acceptButton.setOnClickListener(v -> acceptCall());
        rejectAction.setOnClickListener(v -> showReasonDrawer());
        acceptAction.setOnClickListener(v -> acceptCall());
    }

    private LinearLayout compactAction(String icon, String label, boolean primary) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView circle = text(icon, primary ? 20 : 23, primary ? theme.onPrimary : theme.primary, false);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(circle(primary ? theme.primary : theme.panel,
                primary ? withAlpha(theme.primary, .92f) : theme.line, dp(1)));
        circle.setClickable(true);
        circle.setFocusable(true);
        group.addView(circle, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView caption = text(label, 9, theme.subtext, false);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(-1, -2);
        captionLp.topMargin = dp(5);
        group.addView(caption, captionLp);
        return group;
    }

    private View createAvatar() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.TRANSPARENT);
        frame.setPadding(dp(1), dp(1), dp(1), dp(1));

        String uri = GuidianState.prefs(this).getString(GuidianState.KEY_AVATAR_URI, "");
        if (uri != null && !uri.trim().isEmpty()) {
            SoftAvatarView image = new SoftAvatarView(this);
            image.setCircle(true);
            image.setColors(theme.panel, withAlpha(theme.line, .62f), theme.online);
            image.setImageUri(Uri.parse(uri));
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else {
            SoftAvatarView initials = new SoftAvatarView(this);
            initials.setCircle(true);
            initials.setColors(theme.panel, withAlpha(theme.line, .62f), theme.online);
            initials.setFallbackPaddingDp(0);
            Bitmap fallback = Bitmap.createBitmap(dp(88), dp(88), Bitmap.Config.ARGB_8888);
            Canvas avatarCanvas = new Canvas(fallback);
            Paint avatarText = new Paint(Paint.ANTI_ALIAS_FLAG);
            avatarText.setColor(theme.text);
            avatarText.setTextSize(dp(22));
            avatarText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            avatarText.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = avatarText.getFontMetrics();
            avatarCanvas.drawText(AppPrefs.companionName(this), fallback.getWidth() / 2f,
                    fallback.getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f, avatarText);
            initials.setFallbackBitmap(fallback);
            frame.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        }

        View dot = new View(this);
        dot.setBackground(circle(theme.online, theme.background, dp(1)));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(11), dp(11), Gravity.RIGHT | Gravity.BOTTOM);
        dotLp.rightMargin = dp(3);
        dotLp.bottomMargin = dp(3);
        frame.addView(dot, dotLp);
        return frame;
    }

    private void addDecor(int drawable, int gravity, int width, int height, int horizontalMargin, int verticalMargin, float alpha) {
        ImageView art = new ImageView(this);
        art.setImageResource(drawable);
        art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        art.setColorFilter(theme.decor, PorterDuff.Mode.SRC_IN);
        art.setAlpha(alpha);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, gravity);
        if ((gravity & Gravity.RIGHT) == Gravity.RIGHT) lp.rightMargin = horizontalMargin; else lp.leftMargin = horizontalMargin;
        if ((gravity & Gravity.TOP) == Gravity.TOP) lp.topMargin = verticalMargin; else lp.bottomMargin = verticalMargin;
        root.addView(art, lp);
    }

    private void acceptCall() {
        if (connected) return;
        connected = true;
        rejectButton.setEnabled(false);
        acceptButton.setEnabled(false);
        if (rejectButton.getParent() instanceof View) ((View) rejectButton.getParent()).setEnabled(false);
        if (acceptButton.getParent() instanceof View) ((View) acceptButton.getParent()).setEnabled(false);
        callState.setText("已接通 · 正在回到 " + GuidianState.targetLabel(this));
        callState.setTextColor(theme.primary);
        callerName.setText("已接通");
        acceptButton.setText("✓");
        waveLine.setConnected(true);
        avatarBox.clearAnimation();
        avatarBox.animate().scaleX(1.05f).scaleY(1.05f).setDuration(180).start();
        GuidianState.markReturned(this, "guidian_accept");
        final android.content.Context appCtx = getApplicationContext();
        final String target = GuidianState.targetPackage(this);
        handler.postDelayed(() -> {
            finish();
            handler.postDelayed(() -> {
                String result = target == null || target.trim().isEmpty() ? "package_empty" : CompanionService.openPackageResult(appCtx, target.trim());
                DebugState.append(appCtx, "归电接通打开目标：" + result + "；target=" + (target == null ? "" : target));
                if (!result.startsWith("opened_")) {
                    Toast.makeText(appCtx, "归电目标打开失败：" + result, Toast.LENGTH_SHORT).show();
                }
            }, 260L);
        }, 220L);
    }

    private void showReasonDrawer() {
        if (connected) return;
        if (reasonDrawer != null) {
            reasonDrawer.setVisibility(View.VISIBLE);
            return;
        }

        View scrim = new View(this);
        scrim.setBackgroundColor(theme.scrim);
        scrim.setOnClickListener(v -> hideReasonDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setTag("guidian_scrim");

        reasonDrawer = new LinearLayout(this);
        reasonDrawer.setOrientation(LinearLayout.VERTICAL);
        reasonDrawer.setPadding(dp(20), dp(20), dp(20), dp(22));
        reasonDrawer.setBackground(rounded(theme.panel, 30, theme.line, 1));
        reasonDrawer.setElevation(dp(8));
        FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        drawerLp.leftMargin = dp(14);
        drawerLp.rightMargin = dp(14);
        drawerLp.bottomMargin = dp(14) + navBarHeight();
        root.addView(reasonDrawer, drawerLp);

        TextView title = text("晚一点，也没关系。", 19, theme.text, true);
        reasonDrawer.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text("留一句话给" + AppPrefs.companionName(this), 10, theme.subtext, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(5);
        reasonDrawer.addView(hint, hintLp);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.topMargin = dp(10);
        reasonDrawer.addView(grid, gridLp);

        String[] reasons = GuidianState.quickReasons(this);
        LinearLayout row = null;
        int column = 0;
        for (String raw : reasons) {
            String reason = raw == null ? "" : raw.trim();
            if (reason.isEmpty()) continue;
            if (row == null || column == 2) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(40));
                rowLp.topMargin = dp(7);
                grid.addView(row, rowLp);
                column = 0;
            }
            TextView choice = text(reason, 11, theme.text, false);
            choice.setGravity(Gravity.CENTER);
            choice.setBackground(rounded(theme.soft, 19, theme.line, 1));
            LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(0, dp(38), 1);
            if (column == 0) choiceLp.rightMargin = dp(4); else choiceLp.leftMargin = dp(4);
            row.addView(choice, choiceLp);
            choice.setOnClickListener(v -> submitReason(reason));
            column++;
        }

        EditText custom = new EditText(this);
        custom.setHint("自己写一句");
        custom.setTextColor(theme.text);
        custom.setHintTextColor(withAlpha(theme.subtext, .72f));
        custom.setSingleLine(true);
        custom.setTextSize(11);
        custom.setBackground(rounded(theme.soft, 20, theme.line, 1));
        custom.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams customLp = new LinearLayout.LayoutParams(-1, dp(44));
        customLp.topMargin = dp(12);
        reasonDrawer.addView(custom, customLp);

        TextView send = action("告诉" + AppPrefs.companionName(this), true);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(-1, dp(46));
        sendLp.topMargin = dp(10);
        reasonDrawer.addView(send, sendLp);
        send.setOnClickListener(v -> submitReason(custom.getText().toString().trim().isEmpty()
                ? "晚点找你" : custom.getText().toString().trim()));

        reasonDrawer.setTranslationY(dp(380));
        reasonDrawer.animate().translationY(0).setDuration(260).start();
    }

    private void hideReasonDrawer() {
        if (reasonDrawer == null) return;
        View scrim = root.findViewWithTag("guidian_scrim");
        root.removeView(reasonDrawer);
        if (scrim != null) root.removeView(scrim);
        reasonDrawer = null;
    }

    private void submitReason(String reason) {
        GuidianState.reject(this, reason);
        Toast.makeText(this, "好，" + AppPrefs.companionName(this) + "晚点再来", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override public void onBackPressed() {
        if (reasonDrawer != null) hideReasonDrawer();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView action(String label, boolean primary) {
        TextView button = text(label, 13, primary ? theme.onPrimary : theme.text, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(primary ? theme.primary : theme.panel, 27,
                primary ? theme.primary : theme.line, 1));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        tv.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return tv;
    }

    private GradientDrawable rounded(int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    private GradientDrawable circle(int color, int stroke, int strokePx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        if (strokePx > 0) drawable.setStroke(strokePx, stroke);
        return drawable;
    }

    private int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    /** Bug 4.1：取系统导航栏高度，用于底部内容避让手势导航条。 */
    private int navBarHeight() {
        int resId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resId > 0) {
            try { return getResources().getDimensionPixelSize(resId); } catch (Exception ignored) { }
        }
        return 0;
    }

    private class WaveLineView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator breath;
        private boolean isConnected;
        private float pulse;

        WaveLineView(GuidianTheme palette) {
            super(GuidianActivity.this);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeWidth(dp(1.45f));
            line.setColor(palette.wave);
            setAlpha(.96f);
            breath = ValueAnimator.ofFloat(0f, 1f, 0f);
            breath.setDuration(1800L);
            breath.setRepeatCount(ValueAnimator.INFINITE);
            breath.addUpdateListener(animation -> {
                pulse = (float) animation.getAnimatedValue();
                invalidate();
            });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!breath.isStarted()) breath.start();
        }

        @Override protected void onDetachedFromWindow() {
            breath.cancel();
            super.onDetachedFromWindow();
        }

        void setConnected(boolean value) {
            isConnected = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float mid = h / 2f;
            float[] shape = {0f, -.10f, .18f, -.28f, .45f, -.34f, .76f, -.24f, .52f,
                    -.18f, .30f, -.62f, .22f, -.42f, .58f, -.20f, .36f, -.12f, 0f};
            float amplitude = dp(12.5f) * (isConnected ? .38f : (.94f + pulse * .10f));
            Path path = new Path();
            float inset = dp(2);
            for (int i = 0; i < shape.length; i++) {
                float x = inset + (w - inset * 2) * i / (shape.length - 1f);
                float y = mid + shape[i] * amplitude;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            canvas.drawPath(path, line);
        }
    }
}
