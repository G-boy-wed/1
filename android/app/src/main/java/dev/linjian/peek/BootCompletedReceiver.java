package dev.linjian.peek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 应用更新自启：重启后自动拉起陪伴前台服务与插件调度器，
 * 保证巡回查岗、训诫、时间感知、门禁等守护能力不因重启而失效。
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? "" : intent.getAction();
            if (action == null) return;
            boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
            boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
            if (!boot && !updated) return;
            PolicyScheduler.start(context, "boot");
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(new Intent(context, CompanionService.class));
            } else {
                context.startService(new Intent(context, CompanionService.class));
            }
            DebugState.append(context, boot ? "开机自启：守护服务与调度器已拉起" : "应用更新后自启：守护服务与调度器已拉起");
        } catch (Exception e) {
            DebugState.append(context, "开机自启失败：" + ScreenshotService.shortMsg(e));
        }
    }
}
