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
    }
}
