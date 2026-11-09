package dev.linjian.peek;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONObject;

/**
 * 锁屏场景下的拉回弹窗：系统会挡住悬浮窗，所以锁屏时改用透明 Activity 承载同一张卡片。
 * 卡片内容、配色与位置都和悬浮窗通道完全一致。
 */
public class PullBackActivity extends Activity {

    public static final String EXTRA_CFG = "pull_back_cfg";

    private JSONObject cfg;
    private boolean handled;

    @Override
    protected void onDestroy() {
        PopupMutex.onDismissed(PopupMutex.PULL_BACK);
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

        cfg = new JSONObject();
        try {
            String raw = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_CFG);
            if (raw != null && !raw.trim().isEmpty()) cfg = new JSONObject(raw);
        } catch (Exception ignored) { }

        // 弹窗互斥：悬浮窗层级高于 Activity，启动本兜底 Activity 前必须移除两个通道的
        // 悬浮弹窗，否则会出现「悬浮窗 + Activity」双重展示。
        PopupMutex.beforeShow(PopupMutex.PULL_BACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x00000000);
        setContentView(root);

        View card = PullBackCard.build(this, cfg, new PullBackCard.Listener() {
            @Override public void onAccept(Context c, JSONObject config) {
                handled = true;
                PullBackAction.accept(c, config);
                finish();
            }
            @Override public void onDecline(Context c, JSONObject config, String reason) {
                handled = true;
                PullBackAction.decline(c, config, reason);
                finish();
            }
            @Override public void onDismiss(Context c, JSONObject config) {
                handled = true;
                PullBackAction.dismiss(c, config);
                finish();
            }
        });

        // 弹窗位置：设置页若单独覆盖了就用覆盖值，否则跟随归电。
        String position = GuidianState.POSITION_CENTER;
        String overridePos = PolicyStore.positionOverride(this);
        if (overridePos != null && !overridePos.trim().isEmpty()) position = GuidianState.normalizePosition(overridePos);
        else position = GuidianState.popupPosition(this);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, gravityFor(position));
        int margin = (int) (18 * getResources().getDisplayMetrics().density + .5f);
        if (GuidianState.POSITION_TOP.equals(position)) lp.topMargin = margin;
        if (GuidianState.POSITION_BOTTOM.equals(position)) lp.bottomMargin = margin;
        root.addView(card, lp);
    }

    @Override public void onBackPressed() {
        // 强级别（达到阈值 / 强情绪）：只能「回到软件」，返回键不表态、不关闭，避免绕开。
        if (PullBackCard.isStrongLevel(cfg)) return;
        if (!handled) {
            PullBackAction.dismiss(this, cfg);
            handled = true;
        }
        super.onBackPressed();
    }

    private int gravityFor(String position) {
        if (GuidianState.POSITION_TOP.equals(position)) return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (GuidianState.POSITION_BOTTOM.equals(position)) return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        return Gravity.CENTER;
    }

    /** 是否正处在锁屏状态。 */
    public static boolean isLocked(Context ctx) {
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    /** 拉回弹窗统一入口：锁屏走 Activity，其余情况优先悬浮窗，失败再退回 Activity。 */
    public static void show(Context ctx, JSONObject cfg) {
        try {
            if (isLocked(ctx)) { startActivity(ctx, cfg); return; }
            if (PullBackOverlay.canShow(ctx) && PullBackOverlay.show(ctx, cfg)) return;
            // v0.4.0 双保险：悬浮窗不可用时，后台 startActivity 会被系统拦截。
            // 额外发 fullScreenIntent 最高紧急级横幅通知，保证任意 App 上层都能「骑脸」拉回。
            try {
                String title = cfg == null ? "掌心窗" : cfg.optString("title", "该回来陪我了。");
                String message = cfg == null ? "" : cfg.optString("message", "想你了，回窗边看一眼。");
                CompanionService.showReminderNotification(ctx, title, message);
                DebugState.append(ctx, "拉回弹窗补发 fullScreenIntent 横幅通知（后台 Activity 拦截兜底）");
            } catch (Exception ignored) { }
            startActivity(ctx, cfg);
        } catch (Exception e) {
            DebugState.append(ctx, "拉回弹窗显示异常：" + ScreenshotService.shortMsg(e));
        }
    }

    private static void startActivity(Context ctx, JSONObject cfg) {
        try {
            Intent i = new Intent(ctx, PullBackActivity.class);
            i.putExtra(EXTRA_CFG, cfg == null ? "{}" : cfg.toString());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            DebugState.append(ctx, "拉回弹窗已用 Activity 显示");
        } catch (Exception e) {
            DebugState.append(ctx, "拉回弹窗 Activity 启动失败：" + ScreenshotService.shortMsg(e));
        }
    }
}
