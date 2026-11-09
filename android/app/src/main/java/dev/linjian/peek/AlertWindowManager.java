package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * 多维感知弹窗矩阵统一视图控制器（对应 PRD 模块三）。
 *
 * 把「日常提醒 / 天气出门 / 守护日历 / 训诫 / 情绪拉回」五类弹窗统一收口到
 * CompanionService.showCenterAlert —— 共用一套「盖在当前 App 之上、标注弹窗类型、随语气变色」的逻辑，
 * 避免各模块各写一套弹窗导致样式/层级不一致。
 *
 * 另提供 onTick()：调度器每轮或解锁时调用，负责
 *  - 天气出门弹窗（每天首次触发一次）；
 *  - 守护日历大事件弹窗（纪念日/生日/生理期倒数，每天首屏一次）。
 */
public final class AlertWindowManager {

    private AlertWindowManager() { }

    /** 日常提醒：喝水 / 久坐 / 低电量 / 用药生理期。 */
    public static void showDailyReminder(Context ctx, String type, String title, String message) {
        CompanionService.showCenterAlert(ctx, title, message, "gentle", "知道啦", dailyKind(type), false);
    }

    /** 天气出门弹窗：抓当前经纬度气象，弹出带祁晏口吻的穿衣/带伞卡片。 */
    public static void showWeatherAlert(Context ctx, String city, String district) {
        JSONObject live = WeatherLive.cached(ctx, city, district);
        String advice = (live != null && live.optBoolean("ok"))
                ? WeatherLive.advice(live, (city == null || city.isEmpty()) ? "当前地区" : city)
                : "出门建议：实时天气没查到，先按体感穿衣。";
        String title = (city == null || city.isEmpty()) ? "天气提醒" : ("天气 · " + city);
        CompanionService.showCenterAlert(ctx, title, advice, "gentle", "收到啦", "天气出门", false);
    }

    /** 守护日历大事件弹窗：纪念日/生日/生理期倒数。 */
    public static void showCalendarBanner(Context ctx, JSONObject item) {
        if (item == null) return;
        String title = item.optString("title", "守护日子");
        int days = item.optInt("daysLeft", -1);
        String sub = days == 0 ? "就是今天" : (days > 0 ? ("还有 " + days + " 天") : "");
        String text = item.optString("banner_text", item.optString("text", ""));
        CompanionService.showCenterAlert(ctx, title + (sub.isEmpty() ? "" : " · " + sub), text, "gentle", "我记着呢", "守护日历", false);
    }

    /** 训诫强制弹窗（不可滑掉 + 震动），文案与语气标签统一由 DisciplineController 加工。 */
    public static void showDiscipline(Context ctx, String text) {
        boolean strictMode = "strict".equals(PolicyStore.disciplineMode(ctx));
        boolean strong = strictMode || PolicyStore.disciplineLevel(ctx) >= 2;
        String msg = (text == null || text.trim().isEmpty())
                ? DisciplineController.resolveDisciplineText(ctx, null) : text;
        String title = DisciplineController.toneBadge(ctx) + " 掌心窗管束";
        CompanionService.showCenterAlert(ctx, title, msg, strong ? "angry" : "gentle", "我错了", "训诫管束", true);
    }

    /** 情绪驱动的强行拉回：软/强级别交给现有 PullBackActivity 统一渲染。 */
    public static void showForceBack(Context ctx, JSONObject cfg) {
        if (cfg == null) return;
        PullBackActivity.show(ctx, cfg);
    }

    /**
     * 调度器每轮 / 解锁时调用：每天各触发一次天气出门与日历大事件弹窗。
     * 用本地日期去重，避免重复骚扰；上课/安静时段不弹（守护页静默）。
     */
    public static void onTick(Context ctx) {
        try {
            if (ClassScheduleState.isInClassNow(ctx) || PolicyStore.isQuietNow(ctx)) return;
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (hour < 6 || hour > 23) return; // 仅白天/清晨触发

            // 1) 天气出门弹窗：每天首次
            if (AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_RULE_WEATHER, true)
                    && !PolicyStore.dailyAlertShown(ctx, PolicyStore.KEY_WEATHER_ALERT_DATE)) {
                JSONObject cur = WeatherState.collect(ctx).optJSONObject("current");
                String city = cur == null ? "" : cur.optString("city", "");
                String district = cur == null ? "" : cur.optString("district", "");
                if (city != null && !city.isEmpty()) {
                    showWeatherAlert(ctx, city, district);
                    PolicyStore.markDailyAlertShown(ctx, PolicyStore.KEY_WEATHER_ALERT_DATE);
                }
            }

            // 2) 守护日历大事件弹窗：每天首次一条
            if (CalendarState.enabled(ctx)
                    && !PolicyStore.dailyAlertShown(ctx, PolicyStore.KEY_CAL_BANNER_DATE)) {
                JSONArray due = CalendarState.dueReminders(ctx);
                if (due != null && due.length() > 0) {
                    showCalendarBanner(ctx, due.optJSONObject(0));
                    PolicyStore.markDailyAlertShown(ctx, PolicyStore.KEY_CAL_BANNER_DATE);
                }
            }
        } catch (Exception e) {
            DebugState.append(ctx, "弹窗矩阵 onTick 异常：" + ScreenshotService.shortMsg(e));
        }
    }

    private static String dailyKind(String type) {
        if ("battery".equals(type)) return "日常提醒·低电量";
        if ("water".equals(type)) return "日常提醒·喝水";
        if ("sit".equals(type)) return "日常提醒·久坐";
        if ("cycle".equals(type)) return "日常提醒·生理期";
        if ("medicine".equals(type)) return "日常提醒·用药";
        return "日常提醒";
    }
}
