package dev.linjian.peek;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 拉回弹窗的悬浮窗通道：真正的「盖在其他 App 之上」的一层，
 * 位置跟随归电设置里的弹窗位置（顶部 / 居中 / 底部）。
 * 锁屏状态下系统会挡住悬浮窗，此时由 PullBackActivity 接手，两边显示的是同一张卡片。
 */
public class PullBackOverlay {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WindowManager windowManager;
    private static View current;

    private PullBackOverlay() { }

    public static boolean canShow(Context ctx) {
        try {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    /** 显示拉回弹窗；返回 false 表示没能用悬浮窗展示（调用方应改用 Activity）。 */
    public static boolean show(final Context ctx, final JSONObject cfg) {
        final Context appCtx = ctx.getApplicationContext();
        try {
            if (!canShow(appCtx)) return false;
            final WindowManager wm = manager(appCtx);
            if (wm == null) return false;
            final View card = PullBackCard.build(appCtx, cfg, new PullBackCard.Listener() {
                @Override public void onAccept(Context c, JSONObject config) {
                    PullBackAction.accept(c, config);
                    dismiss();
                }
                @Override public void onDecline(Context c, JSONObject config, String reason) {
                    PullBackAction.decline(c, config, reason);
                    dismiss();
                }
                @Override public void onDismiss(Context c, JSONObject config) {
                    PullBackAction.dismiss(c, config);
                    PullBackOverlay.dismiss();
                }
            });

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = gravityFor(GuidianState.popupPosition(appCtx));
            params.x = 0;
            params.y = 0;

            return addSynchronously(wm, card, params, appCtx);
        } catch (Exception e) {
            DebugState.append(ctx, "拉回悬浮窗异常：" + ScreenshotService.shortMsg(e));
            return false;
        }
    }

    private static boolean addSynchronously(final WindowManager wm, final View view, final WindowManager.LayoutParams params, final Context ctx) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return doAdd(wm, view, params, ctx);
        }
        final boolean[] ok = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                ok[0] = doAdd(wm, view, params, ctx);
                latch.countDown();
            }
        });
        try { latch.await(800, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        return ok[0];
    }

    private static boolean doAdd(WindowManager wm, View view, WindowManager.LayoutParams params, Context ctx) {
        try {
            dismiss();
            wm.addView(view, params);
            current = view;
            DebugState.append(ctx, "拉回弹窗已悬浮显示");
            return true;
        } catch (Exception e) {
            DebugState.append(ctx, "拉回悬浮窗失败：" + ScreenshotService.shortMsg(e));
            return false;
        }
    }

    public static void dismiss() {
        try {
            final WindowManager wm = windowManager;
            final View view = current;
            current = null;
            if (wm == null || view == null) return;
            // Bug 1.2 修复：主线程上同步移除旧视图，避免旧弹窗尚未移除时又叠加新弹窗，
            // 造成软/强级别按钮在同帧共存、点击穿透。doAdd 在主线调用 dismiss 后即 addView，
            // 因此必须同步移除才能消除重叠窗口。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                try { wm.removeViewImmediate(view); } catch (Exception ignored) { }
            } else {
                main.post(new Runnable() {
                    @Override public void run() {
                        try { wm.removeView(view); } catch (Exception ignored) { }
                    }
                });
            }
        } catch (Exception ignored) { }
    }

    private static WindowManager manager(Context ctx) {
        if (windowManager == null) {
            try { windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE); } catch (Exception ignored) { }
        }
        return windowManager;
    }

    private static int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** 位置复用归电设置：顶部 / 居中 / 底部。 */
    private static int gravityFor(String position) {
        if (GuidianState.POSITION_TOP.equals(position)) return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (GuidianState.POSITION_BOTTOM.equals(position)) return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        return Gravity.CENTER;
    }
}
