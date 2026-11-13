package dev.linjian.peek;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 通知使用权开启后，同步媒体通知给 MediaState。 */
public class MediaNotificationService extends NotificationListenerService {
    // BE-11：区分「设置里已授权」与「监听服务真的已连接/绑定」。
    // 国产 ROM 常见 Settings.Secure 里已开启、但服务未回调 onListenerConnected 的半死状态，
    // 此时 getActiveSessions 抛 SecurityException，available 恒为 false。
    private static volatile boolean connected = false;

    public static boolean isListenerConnected() { return connected; }

    /** 权限页「重新连接」诊断按钮：请求系统重新绑定本监听服务（API 24+）。 */
    public static void requestRebind(Context ctx) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationListenerService.requestRebind(new ComponentName(ctx, MediaNotificationService.class));
            }
            MediaState.updateFromActiveNotifications(ctx, null);
        } catch (Exception e) {
            DebugState.append(ctx, "媒体监听重新连接失败：" + ScreenshotService.shortMsg(e));
        }
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        refreshMediaState("媒体通知监听已连接");
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        try { DebugState.append(this, "媒体通知监听已断开"); } catch (Exception ignored) { }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        connected = true;
        refreshMediaState(null);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        refreshMediaState(null);
    }

    private void refreshMediaState(String log) {
        try {
            MediaState.updateFromActiveNotifications(this, getActiveNotifications());
            if (log != null) DebugState.append(this, log);
        } catch (Exception e) {
            DebugState.append(this, "媒体状态刷新失败：" + ScreenshotService.shortMsg(e));
        }
    }
}
