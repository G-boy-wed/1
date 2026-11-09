package dev.linjian.peek;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 同屏画中画小挂件（Companion Floating Widget）。
 *
 * 伴看期间常驻一个不遮挡画面的微型半透明挂件：祁晏头像 + 呼吸动效 + 当前在看的视频文案。
 *  - 仅捕获自身区域的触摸，外部触摸通过 NOT_TOUCH_MODAL 透传到底层 App，不挡操作。
 *  - 点击挂件 = 快捷查岗：把手机切回掌心窗陪伴页（聊天界面）。
 *  - 后端可下发 send_watch_whisper，在挂件上弹出一句陪伴/吐槽气泡。
 *  - vivo(OriginOS) 锁后台：挂件由前台服务 MediaSyncService 持有，配合 SYSTEM_ALERT_WINDOW 不被回收。
 */
public class CompanionFloatingWidget {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WindowManager windowManager;
    private static View root;
    private static SoftAvatarView avatar;
    private static TextView statusText;
    private static TextView whisperText;
    private static View breathingDot;
    private static android.animation.ValueAnimator breath;

    private CompanionFloatingWidget() { }

    public static boolean canShow(Context ctx) {
        try {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    /** 进入伴看：显示挂件并填入当前在看的视频上下文。 */
    public static void show(final Context ctx, final JSONObject media) {
        if (!canShow(ctx)) return;
        main.post(new Runnable() {
            @Override public void run() { doShow(ctx.getApplicationContext(), media); }
        });
    }

    /** 伴看中：更新挂件上的当前视频信息。 */
    public static void update(final Context ctx, final JSONObject media) {
        main.post(new Runnable() {
            @Override public void run() {
                if (root != null) bind(media);
            }
        });
    }

    /** 后端下发：在挂件上弹一句陪伴/吐槽气泡。 */
    public static void showWhisper(final String text) {
        main.post(new Runnable() {
            @Override public void run() { showWhisperInternal(text); }
        });
    }

    /** 退出伴看：移除挂件。 */
    public static void hide(final Context ctx) {
        main.post(new Runnable() {
            @Override public void run() { doHide(); }
        });
    }

    public static boolean isShown() { return root != null; }

    private static void doShow(Context ctx, JSONObject media) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        if (root != null) doHide();
        root = buildView(ctx);
        bind(media);
        windowManager = wm;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                0x00000018 /* PixelFormat.TRANSLUCENT */);
        p.gravity = Gravity.BOTTOM | Gravity.END;
        p.x = dp(ctx, 18);
        p.y = dp(ctx, 140);
        try {
            wm.addView(root, p);
            startBreath();
        } catch (Exception e) {
            DebugState.append(ctx, "伴看挂件显示失败：" + ScreenshotService.shortMsg(e));
            root = null;
        }
    }

    private static void doHide() {
        stopBreath();
        final WindowManager wm = windowManager;
        final View view = root;
        root = null;
        windowManager = null;
        if (wm == null || view == null) return;
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) wm.removeViewImmediate(view);
            else main.post(new Runnable() { @Override public void run() { try { wm.removeView(view); } catch (Exception ignored) { } } });
        } catch (Exception ignored) { }
    }

    private static void bind(JSONObject media) {
        if (statusText == null || media == null) return;
        try {
            String app = media.optString("app", "");
            String title = media.optString("title", "");
            String author = media.optString("author", "");
            String state = media.optString("state", "");
            int pos = media.optInt("position_percent", 0);
            StringBuilder sb = new StringBuilder();
            if (!app.isEmpty()) sb.append(app).append(" · ");
            if (!title.isEmpty()) sb.append(title);
            else sb.append("在观看");
            if (!author.isEmpty()) sb.append(" @").append(author);
            if ("paused".equals(state)) sb.append(" 〔已暂停〕");
            else if (pos > 0) sb.append(" ").append(pos).append("%");
            statusText.setText(sb.length() > 0 ? sb.toString() : "在陪你看");
        } catch (Exception ignored) { }
    }

    private static void showWhisperInternal(String text) {
        if (whisperText == null) return;
        String t = (text == null || text.trim().isEmpty()) ? "我在陪你看呢~" : text.trim();
        whisperText.setText(t);
        whisperText.setAlpha(0f);
        whisperText.setVisibility(View.VISIBLE);
        whisperText.animate().alpha(1f).setDuration(200).start();
        whisperText.removeCallbacks(hideWhisper);
        whisperText.postDelayed(hideWhisper, 4200L);
    }

    private static final Runnable hideWhisper = new Runnable() {
        @Override public void run() {
            if (whisperText == null) return;
            whisperText.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
                @Override public void run() { whisperText.setVisibility(View.GONE); }
            }).start();
        }
    };

    private static View buildView(Context ctx) {
        int pad = dp(ctx, 10);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC1B1B2A"));
        bg.setCornerRadius(dp(ctx, 18));
        bg.setStroke(dp(ctx, 1), Color.parseColor("#FF6E8FB0"));
        container.setBackground(bg);

        // 头像（祁晏）：用应用图标作兜底，外圈呼吸点表示「在陪你看」
        int avatarSize = dp(ctx, 52);
        LinearLayout avatarWrap = new LinearLayout(ctx);
        avatarWrap.setOrientation(LinearLayout.VERTICAL);
        avatarWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        avatar = new SoftAvatarView(ctx);
        avatar.setCircle(true);
        avatar.setShowDot(true);
        try {
            avatar.setFallback(ctx.getResources().getDrawable(android.R.drawable.ic_menu_myplaces));
        } catch (Exception ignored) { }
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatar.setLayoutParams(ap);
        avatarWrap.addView(avatar);

        breathingDot = new View(ctx);
        int dotSize = dp(ctx, 8);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(dotSize, dotSize);
        dp2.topMargin = dp(ctx, 4);
        breathingDot.setLayoutParams(dp2);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#FF78AE90"));
        breathingDot.setBackground(dotBg);
        avatarWrap.addView(breathingDot);

        statusText = new TextView(ctx);
        statusText.setTextColor(Color.parseColor("#FFF2F5F8"));
        statusText.setTextSize(11f);
        statusText.setMaxWidth(dp(ctx, 168));
        statusText.setGravity(Gravity.CENTER);
        statusText.setSingleLine(false);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(ctx, 6);
        statusText.setLayoutParams(stp);

        whisperText = new TextView(ctx);
        whisperText.setTextColor(Color.parseColor("#FFFFFFFF"));
        whisperText.setTextSize(12f);
        whisperText.setMaxWidth(dp(ctx, 188));
        whisperText.setGravity(Gravity.CENTER);
        whisperText.setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4));
        GradientDrawable wbg = new GradientDrawable();
        wbg.setColor(Color.parseColor("#E6C45B7C"));
        wbg.setCornerRadius(dp(ctx, 12));
        whisperText.setBackground(wbg);
        whisperText.setVisibility(View.GONE);
        LinearLayout.LayoutParams wtp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wtp.topMargin = dp(ctx, 6);
        whisperText.setLayoutParams(wtp);

        container.addView(avatarWrap);
        container.addView(statusText);
        container.addView(whisperText);

        container.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    // 快捷查岗：点一下就把手机切回掌心窗陪伴页（聊天界面）
                    CompanionService.bringAppToFront(v.getContext());
                    showWhisper("我来啦，看我一下嘛~");
                } catch (Exception ignored) { }
            }
        });
        return container;
    }

    private static void startBreath() {
        if (breathingDot == null) return;
        stopBreath();
        try {
            breath = android.animation.ValueAnimator.ofFloat(0.35f, 1f);
            breath.setDuration(900L);
            breath.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            breath.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            breath.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                    if (breathingDot == null) return;
                    float v = (Float) animation.getAnimatedValue();
                    breathingDot.setAlpha(v);
                    breathingDot.setScaleX(0.7f + 0.3f * v);
                    breathingDot.setScaleY(0.7f + 0.3f * v);
                }
            });
            breath.start();
        } catch (Exception ignored) { }
    }

    private static void stopBreath() {
        if (breath != null) {
            try { breath.cancel(); } catch (Exception ignored) { }
            breath = null;
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        return WindowManager.LayoutParams.TYPE_PHONE;
    }
}
