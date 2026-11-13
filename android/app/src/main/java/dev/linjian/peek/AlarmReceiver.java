package dev.linjian.peek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/**
 * BE-01：由 AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP) 触发的闹钟接收器。
 * 不再委托系统时钟 App（易因无对应 App 而静默失败），改为本进程自行弹出
 * 最高优先级悬浮横幅 + 振动 + 提示音，保证「到点一定响」。
 */
public class AlarmReceiver extends BroadcastReceiver {
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String CHANNEL_ID = "peek_alarm_channel";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            String msg = intent == null ? null : intent.getStringExtra(EXTRA_MESSAGE);
            if (msg == null || msg.isEmpty()) msg = "掌心窗闹钟";
            DebugState.append(ctx, "闹钟触发：" + msg);
            fireAlarm(ctx, msg);
        } catch (Exception e) {
            DebugState.append(ctx, "闹钟触发异常：" + ScreenshotService.shortMsg(e));
        }
    }

    private static void fireAlarm(Context ctx, String msg) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "掌心窗闹钟", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("本地闹钟：到点强制提醒，覆盖在其他 App 之上。");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.enableVibration(true);
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(ch);
        }
        Intent full = new Intent(ctx, ReminderActivity.class);
        full.putExtra("title", "⏰ 闹钟");
        full.putExtra("message", msg);
        full.putExtra("from_full_screen_intent", true);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent fullPi = PendingIntent.getActivity(ctx, 990001, full, piFlags);
        PendingIntent tap = PendingIntent.getActivity(ctx, 990002, full, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        b.setContentTitle("⏰ 闹钟")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(tap)
                .setFullScreenIntent(fullPi, true)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setSound(sound)
                .setDefaults(Notification.DEFAULT_VIBRATE)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        nm.notify(990000, b.build());
    }
}
