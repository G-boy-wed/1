package dev.linjian.peek;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

/**
 * 居中弹窗的悬浮窗通道：真正「盖在你当前所在 App 之上」的一层。
 * 不再依赖后台启动 Activity 的权限，所以不用切回掌心窗才看得到。
 * 配色与位置复用归电设置，和 PullBackOverlay 是同一套规则。
 */
public class AlertCenterOverlay {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WindowManager windowManager;
    private static View current;

    private AlertCenterOverlay() { }

    public static boolean canShow(Context ctx) {
        try {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    /** 显示居中弹窗；返回 false 表示没能用悬浮窗展示（调用方应改用 Activity）。 */
    public static boolean show(Context ctx, String title, String message, String tone, String buttonText) {
        final Context appCtx = ctx.getApplicationContext();
        try {
            if (!canShow(appCtx)) return false;
            final WindowManager wm = manager(appCtx);
            if (wm == null) return false;

            final View root = LayoutInflater.from(appCtx).inflate(R.layout.activity_alert_center, null);
            if (root == null) return false;

            String t = (title == null || title.trim().isEmpty()) ? "掌心窗" : title;
            String m = (message == null || message.trim().isEmpty()) ? AppPrefs.userName(appCtx) + "，看一眼这里。" : message;
            String b = (buttonText == null || buttonText.trim().isEmpty()) ? "知道啦" : buttonText;

            AlertCenterActivity.bind(root, t, m, tone, b, new Runnable() {
                @Override public void run() { dismiss(); }
            });

            final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;

            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        View old = current;
                        current = null;
                        if (old != null) {
                            try { wm.removeView(old); } catch (Exception ignored) { }
                        }
                        wm.addView(root, params);
                        current = root;
                        DebugState.append(appCtx, "居中弹窗已悬浮显示");
                    } catch (Exception e) {
                        DebugState.append(appCtx, "居中悬浮窗失败：" + ScreenshotService.shortMsg(e));
                    }
                }
            });
            return true;
        } catch (Exception e) {
            DebugState.append(appCtx, "居中悬浮窗异常：" + ScreenshotService.shortMsg(e));
            return false;
        }
    }

    public static void dismiss() {
        try {
            final WindowManager wm = windowManager;
            final View view = current;
            current = null;
            if (wm == null || view == null) return;
            main.post(new Runnable() {
                @Override public void run() {
                    try { wm.removeView(view); } catch (Exception ignored) { }
                }
            });
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
}
