package dev.linjian.peek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 调度器看门狗：AlarmManager 每 15 分钟触发一次，把巡回归电 / 训诫 / 时间感知
 * 等插件的本地调度器重新拉起（start 幂等，已在跑则无副作用）。
 * 覆盖 Doze 深睡后 HandlerThread 停摆、进程被系统回收等场景。
 */
public class SchedulerWatchdogReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            PolicyScheduler.start(context, "watchdog");
        } catch (Exception ignored) { }
        // v0.4.0：看门狗同时确保陪伴前台服务存活（国产 ROM 杀后台后可被 AlarmManager 拉回）。
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(new Intent(context, CompanionService.class));
            } else {
                context.startService(new Intent(context, CompanionService.class));
            }
        } catch (Exception ignored) { }
        // 按绝对时间戳复查门禁锁：被杀期间缺失前台切换事件时，这里补一次到点拦截，
        // 修复「倒计时暂停、到点无法自动锁定」。
        try { AppGate.recheckActiveLocks(context); } catch (Exception ignored) { }
    }
}
