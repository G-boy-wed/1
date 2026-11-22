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

    /**
     * 天气出门弹窗：抓当前地区气象，弹出带陪伴口吻的穿衣/带伞卡片。
     * [FIX] 弹窗显示的「地址」必须是用户设置的地区名称（如 家/学校）或实时天气解析出的
     * 精确地址（geo_display/resolved_city），而不是城市名占位或「当前地区」。
     */
    public static void showWeatherAlert(Context ctx, String city, String district) {
        JSONObject current = WeatherState.currentLocation(ctx);
        String areaName = current.optString("name", "");
        // [WEATHER-FIX] 读取已保存的 GPS 坐标，走坐标级精确查询（与卡片链路一致），
        // 不再按地名降级——这是"弹窗查不出最正确天气"的主因。
        double lat = current == null ? 0 : current.optDouble("latitude", 0);
        double lon = current == null ? 0 : current.optDouble("longitude", 0);
        JSONObject live = WeatherLive.ensureFresh(ctx, city, district, lat, lon);
        if (live == null) live = WeatherLive.cached(ctx, city, district);
        // 精确地址优先级：① uapis 返回的省·市·区县(geo_display) ② 省+市(admin1+admin2) ③ 地区别名 ④ 城市名。
        // 保证弹窗标注的是「正确的区县」，从而与区县级温度、穿衣提醒一一对应。
        String precise = "";
        if (live != null) {
            precise = firstNonEmpty(
                    live.optString("geo_display", ""),
                    joinProvinceCity(live.optString("province", live.optString("admin1", "")), live.optString("resolved_city", "")),
                    live.optString("resolved_city", ""));
        }
        // [FIX] 只填了地区别名（如「家」）而没有任何可用天气时，不弹「当前地区」空卡片——
        // 没有真实城市/区县/坐标可按，弹卡片只会展示占位文案，属于无效打扰。
        if ((live == null || !live.optBoolean("ok")) && city.isEmpty() && district.isEmpty()) {
            DebugState.append(ctx, "天气弹窗跳过：地区未填城市/区县且无可用天气，避免空卡片");
            return;
        }
        String displayAddr = firstNonEmpty(precise, areaName, city);
        String advice = (live != null && live.optBoolean("ok"))
                ? WeatherLive.advice(live, displayAddr)
                : ("出门建议：" + displayAddr + "的实时天气暂时没查到，先按体感穿衣。");
        String title = "天气 · " + (displayAddr.isEmpty() ? "当前地区" : displayAddr);
        CompanionService.showCenterAlert(ctx, title, advice, "gentle", "收到啦", "天气出门", false);
    }

    /** 省 + 市拼接，供没有 geo_display 时的地址兜底。 */
    private static String joinProvinceCity(String province, String city) {
        province = province == null ? "" : province.trim();
        city = city == null ? "" : city.trim();
        if (province.isEmpty()) return city;
        if (city.isEmpty()) return province;
        if (city.startsWith(province)) return city;
        return province + "·" + city;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    /** 守护日历大事件弹窗：纪念日/生日/生理期倒数。 */
    public static void showCalendarBanner(Context ctx, JSONObject item) {
        if (item == null) return;
        String title = item.optString("title", "守护日子");
        // [FIX] CalendarState.toJson() 写入的键是 days_left（下划线），旧代码读驼峰 daysLeft 恒为 -1，
        // 导致「就是今天/还有 N 天」永远不显示。保留 daysLeft 兜底以兼容其它来源。
        int days = item.has("days_left") ? item.optInt("days_left", -1) : item.optInt("daysLeft", -1);
        String sub = days == 0 ? "就是今天" : (days > 0 ? ("还有 " + days + " 天") : "");
        String text = item.optString("banner_text", item.optString("text", ""));
        CompanionService.showCenterAlert(ctx, title + (sub.isEmpty() ? "" : " · " + sub), text, "gentle", "我记着呢", "守护日历", false);
    }

    /** 训诫强制弹窗（不可滑掉 + 震动）。[DISC-NO-TIER] 无档位/语气标签，强判断走情绪强度≥80。 */
    public static void showDiscipline(Context ctx, String text) {
        boolean strong = PolicyStore.disciplineStrongNow(ctx);
        String msg = (text == null || text.trim().isEmpty())
                ? DisciplineController.resolveDisciplineText(ctx, null) : text;
        String title = "掌心窗管束";
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
            // [FIX] HOUR_OF_DAY 取值 0~23，原 hour>23 恒假导致 23 点整仍弹窗；改为 >=23 让 23 点起静默。
            if (hour < 6 || hour >= 23) return; // 仅 06:00~22:59 触发

            // 1) 天气出门弹窗：每天首次
            if (AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_RULE_WEATHER, true)
                    && !PolicyStore.dailyAlertShown(ctx, PolicyStore.KEY_WEATHER_ALERT_DATE)) {
                JSONObject cur = WeatherState.collect(ctx).optJSONObject("current");
                String city = cur == null ? "" : cur.optString("city", "");
                String district = cur == null ? "" : cur.optString("district", "");
                String areaName = cur == null ? "" : cur.optString("name", "");
                // 地区别名 / 城市 / 区县任一存在即可弹，确保只设了区县也能出天气弹窗。
                boolean hasArea = (city != null && !city.isEmpty())
                        || (areaName != null && !areaName.isEmpty())
                        || (district != null && !district.isEmpty());
                if (hasArea) {
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
