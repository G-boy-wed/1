package dev.linjian.peek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bug#3：解锁次数统计（真实系统广播，不再只靠 UsageStats 推断）。
 *
 * 原先 unlock_count_today 常年为 0、unlock_count_reliable=false，是因为计数完全依赖
 * UsageStatsManager 的 KEYGUARD_HIDDEN / SCREEN_INTERACTIVE 事件——国产 ROM 常不给这些事件，
 * 或进程被冻结后拿不到，导致 0 次解锁被当真值上报。
 *
 * 现在由常驻前台服务 CompanionService 动态注册：
 *  - ACTION_USER_PRESENT（用户真正解锁）→ 当日计数 +1，标记 reliable=true；
 *  - ACTION_SCREEN_ON 仅记录亮屏时刻、不计数（避免与 USER_PRESENT 双计）。
 * 按自然日落到 SharedPreferences，进程被杀重启后仍能从磁盘恢复当日计数；
 * 前台服务保活（START_STICKY + startForeground）保证广播长期在线。
 */
public final class UnlockTracker {
    private static final String PREF = "peek_unlock_tracker";
    private static final String KEY_DAY = "day";
    private static final String KEY_COUNT = "count";
    private static final String KEY_LAST_AT = "last_at_ms";
    private static final String KEY_RELIABLE = "reliable";
    private static final String KEY_SCREEN_ON_AT = "screen_on_at_ms";

    private static volatile BroadcastReceiver receiver = null;
    private static volatile Context registeredCtx = null;
    private static final Object LOCK = new Object();

    private UnlockTracker() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
    }

    /** 在前台服务启动时注册；重复调用安全（幂等）。 */
    public static void register(Context ctx) {
        if (ctx == null) return;
        synchronized (LOCK) {
            if (receiver != null) return;
            final Context app = ctx.getApplicationContext();
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_USER_PRESENT.equals(action)) onUserPresent(app);
                    else if (Intent.ACTION_SCREEN_ON.equals(action)) onScreenOn(app);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            try {
                app.registerReceiver(r, filter);
                receiver = r;
                registeredCtx = app;
                DebugState.append(app, "解锁统计广播已注册（USER_PRESENT + SCREEN_ON，前台服务保活）");
            } catch (Exception e) {
                DebugState.append(app, "解锁统计广播注册失败：" + ScreenshotService.shortMsg(e));
            }
        }
    }

    public static void unregister(Context ctx) {
        synchronized (LOCK) {
            if (receiver == null) return;
            try {
                (registeredCtx != null ? registeredCtx : ctx).unregisterReceiver(receiver);
            } catch (Exception ignored) { }
            receiver = null;
            registeredCtx = null;
        }
    }

    private static void onUserPresent(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            String day = today();
            int count = p.getInt(KEY_COUNT, 0);
            if (!day.equals(p.getString(KEY_DAY, ""))) count = 0;   // 跨自然日归零
            count++;
            long now = System.currentTimeMillis();
            p.edit().putString(KEY_DAY, day).putInt(KEY_COUNT, count)
                    .putLong(KEY_LAST_AT, now).putBoolean(KEY_RELIABLE, true).commit();
            DebugState.append(ctx, "解锁广播 USER_PRESENT：今日第 " + count + " 次");
        } catch (Exception ignored) { }
    }

    private static void onScreenOn(Context ctx) {
        try {
            prefs(ctx).edit().putLong(KEY_SCREEN_ON_AT, System.currentTimeMillis()).commit();
        } catch (Exception ignored) { }
    }

    /** 供 LifeState.collect 读取：当日解锁次数、最后解锁时间、是否可靠。 */
    public static JSONObject snapshot(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            SharedPreferences p = prefs(ctx);
            String day = today();
            boolean sameDay = day.equals(p.getString(KEY_DAY, ""));
            int count = sameDay ? p.getInt(KEY_COUNT, 0) : 0;
            long last = sameDay ? p.getLong(KEY_LAST_AT, 0) : 0;
            boolean reliable = sameDay && (p.getBoolean(KEY_RELIABLE, false) || count > 0);
            out.put("count", count)
                    .put("last_unlock_at", last)
                    .put("reliable", reliable)
                    .put("source", "broadcast_user_present");
        } catch (Exception ignored) { }
        return out;
    }
}
