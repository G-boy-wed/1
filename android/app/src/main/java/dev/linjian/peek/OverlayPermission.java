package dev.linjian.peek;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * v0.4.0 弹窗/拉回权限引导：
 * 实时检测「悬浮窗(在其他应用上层显示)」与「后台弹出界面 / 电池优化白名单」，
 * 并提供一键跳转授权。用于在 Android 10+ 后台启动 Activity 被拦截时，
 * 让用户一次性把「弹窗骑脸」所需权限全部打开。
 *
 * 全部使用 framework API + 代码构建对话框，不依赖任何新增 XML 资源，编译零额外依赖。
 */
public final class OverlayPermission {
    private OverlayPermission() { }

    /** 悬浮窗权限（TYPE_APPLICATION_OVERLAY）。M 以下系统默认具备。 */
    public static boolean overlayGranted(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
            return Settings.canDrawOverlays(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否已加入电池优化白名单（国产 ROM 杀后台导致计时器暂停的关键因素之一）。 */
    public static boolean batteryOptimizationIgnored(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 后台启动 Activity 权限（Android 10+ / 国产 ROM「后台弹出界面」）。
     * 标准 AOSP 没有公开稳定的检测 API，这里用 AppOpsManager 尽力探测 OP_BACKGROUND_START_ACTIVITY，
     * 探测不到时返回 null（未知），由引导弹窗给出跳转让用户确认，绝不谎报已授权。
     * 返回：Boolean.TRUE=允许 / FALSE=被拒 / null=无法判定。
     */
    public static Boolean backgroundStartAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Boolean.TRUE;
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return null;
            int op = 10021; // OP_BACKGROUND_START_ACTIVITY (AOSP hidden constant)
            int mode;
            try {
                mode = (Integer) AppOpsManager.class
                        .getMethod("checkOpNoThrow", int.class, int.class, String.class)
                        .invoke(ops, op, android.os.Process.myUid(), ctx.getPackageName());
            } catch (Exception e) {
                return null;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return null;
        }
    }

    /** 一键跳转：悬浮窗权限设置页。 */
    public static void openOverlaySettings(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        } catch (Exception ignored) { }
    }

    /** 一键跳转：应用详情页（部分 ROM 的「后台弹出界面」权限在这里）。 */
    public static void openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) { }
    }

    /** 一键跳转：电池优化白名单（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。 */
    public static void openBatterySettings(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            }
        } catch (Exception ignored) { }
    }

    /** 汇总状态，供 UI 与状态上报展示。 */
    public static String statusSummary(Context ctx) {
        Boolean bg = backgroundStartAllowed(ctx);
        String bgText = bg == null ? "需到系统设置确认" : (bg ? "已允许" : "被限制，建议开启");
        return "悬浮窗权限：" + (overlayGranted(ctx) ? "已开启" : "未开启（弹窗将无法覆盖其他 App）")
                + "\n后台弹出界面：" + bgText
                + "\n电池优化白名单：" + (batteryOptimizationIgnored(ctx) ? "已加入" : "未加入（后台计时可能被杀）");
    }

    /**
     * 弹窗实时检测 + 一键授权引导。传入 Activity context 时展示对话框；
     * 非 Activity（如 Service）调用会安全降级为直接打开悬浮窗设置。
     */
    public static void showGuide(Context ctx) {
        String message = "为了让关心/训诫/拉回弹窗能在你刷任意 App 时直接「骑脸」显示，"
                + "并避免后台计时被系统杀掉，请确认以下权限：\n\n" + statusSummary(ctx)
                + "\n\n建议把悬浮窗、后台弹出界面、电池优化白名单全部打开。";
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(ctx);
            b.setTitle("弹窗与后台保活权限");
            b.setMessage(message);
            b.setPositiveButton("① 悬浮窗权限", (d, w) -> openOverlaySettings(ctx));
            b.setNeutralButton("② 后台弹窗/应用详情", (d, w) -> openAppDetails(ctx));
            b.setNegativeButton("③ 电池优化白名单", (d, w) -> openBatterySettings(ctx));
            b.show();
        } catch (Exception e) {
            // 非 Activity context 无法弹 Dialog，降级直接开悬浮窗设置。
            openOverlaySettings(ctx);
        }
    }
}
