package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * 独立课表插件（对应 PRD P1.5）。
 *
 *  - 支持录入与导入：星期、节次、具体时段、科目、教室地点及课间休息；
 *  - 具备到点自动判定上课状态；
 *  - 上课期间静默守护（免打扰）：调度器在上课时不主动打扰；
 *  - 课前 / 下课提醒联动。
 *
 * 数据只存本机，支持手机端与 MCP 双向修改实时生效。
 */
public final class ClassScheduleState {

    private static final String PREFS = "class_schedule_store";
    private static final String KEY_ENTRIES = "entries_v1";
    private static final String KEY_ENABLED = "class_schedule_enabled";
    private static final long PRE_CLASS_MS = 5 * 60_000L;   // 课前 5 分钟提醒
    private static final long POST_CLASS_MS = 5 * 60_000L;  // 下课 5 分钟提醒

    private ClassScheduleState() { }

    private static SharedPreferences sp(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ============ 课表作息总开关（守护页卡片可实际操作）============
    // 关闭后：不再判定上课、上下课提醒停发、上课免打扰随之失效；课表数据保留，重新打开即恢复。
    public static boolean enabled(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_ENABLED, true); } catch (Exception e) { return true; }
    }

    public static void setEnabled(Context ctx, boolean v) {
        try {
            sp(ctx).edit().putBoolean(KEY_ENABLED, v).commit();
            DebugState.append(ctx, v ? "课表作息已开启：上课判定与上下课提醒恢复" : "课表作息已关闭：上课判定与上下课提醒停发");
        } catch (Exception ignored) { }
    }

    /** 全部课表条目（按 weekday、start 排序无关的 JSONArray）。 */
    public static JSONArray getEntries(Context ctx) {
        try { return new JSONArray(sp(ctx).getString(KEY_ENTRIES, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    /** 新增或更新一条课表（按 weekday+start+subject 去重）。 */
    public static void upsertEntry(Context ctx, JSONObject e) {
        try {
            int wd = clampWeekday(e.optInt("weekday", 1));
            String start = e.optString("start", "");
            String end = e.optString("end", "");
            String subject = e.optString("subject", "课程");
            String section = e.optString("section", "");
            String room = e.optString("room", "");
            if (!validHHmm(start) || !validHHmm(end)) return;
            JSONArray arr = getEntries(ctx);
            JSONArray next = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optInt("weekday", 0) == wd && o.optString("start", "").equals(start)
                        && o.optString("subject", "").equals(subject)) {
                    next.put(e); replaced = true; continue;
                }
                next.put(o);
            }
            if (!replaced) next.put(e);
            sp(ctx).edit().putString(KEY_ENTRIES, next.toString()).apply();
            DebugState.append(ctx, "课表已更新：" + weekdayName(wd) + " " + start + " " + subject);
        } catch (Exception ex) {
            DebugState.append(ctx, "课表更新失败：" + ScreenshotService.shortMsg(ex));
        }
    }

    /** 删除一条课表（按 weekday+start+subject 匹配）。 */
    public static void removeEntry(Context ctx, int weekday, String start, String subject) {
        try {
            JSONArray arr = getEntries(ctx);
            JSONArray next = new JSONArray();
            int wd = clampWeekday(weekday);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optInt("weekday", 0) == wd && o.optString("start", "").equals(start)
                        && o.optString("subject", "").equals(subject)) continue;
                next.put(o);
            }
            sp(ctx).edit().putString(KEY_ENTRIES, next.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static void importEntries(Context ctx, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) upsertEntry(ctx, o);
        }
    }

    /** 清空全部课表（手机端「清空课表」按钮用）。返回是否写入成功。 */
    public static boolean clearAll(Context ctx) {
        try {
            boolean ok = sp(ctx).edit().putString(KEY_ENTRIES, "[]").commit();
            DebugState.append(ctx, ok ? "课表已清空" : "课表清空失败");
            return ok;
        } catch (Exception e) { return false; }
    }

    /** 当前是否处于上课状态（今天、当前时间在某一节课 [start, end) 内）。 */
    public static boolean isInClassNow(Context ctx) {
        return enabled(ctx) && currentClass(ctx) != null;
    }

    /** 返回当前正在上的课（不在上课返回 null）。 */
    public static JSONObject currentClass(Context ctx) {
        try {
            int today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); // 1=周日..7=周六
            int wd = today == 1 ? 7 : today - 1; // 映射为 1=周一..7=周日
            int now = nowMinutes();
            JSONArray arr = getEntries(ctx);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optInt("weekday", 0) != wd) continue;
                int s = toMinutes(o.optString("start", ""));
                int e = toMinutes(o.optString("end", ""));
                if (s < 0 || e < 0) continue;
                if (now >= s && now < e) return o;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 距离最近一节「今天还要上」的课还有多少毫秒（用于课前提醒）；无则返回 -1。 */
    public static long msToNextClassToday(Context ctx) {
        try {
            int today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            int wd = today == 1 ? 7 : today - 1;
            int now = nowMinutes();
            long best = Long.MAX_VALUE;
            JSONArray arr = getEntries(ctx);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optInt("weekday", 0) != wd) continue;
                int s = toMinutes(o.optString("start", ""));
                if (s < 0) continue;
                long delta = (s - now) * 60_000L;
                if (delta >= 0 && delta < best) best = delta;
            }
            return best == Long.MAX_VALUE ? -1 : best;
        } catch (Exception e) { return -1; }
    }

    // ============ 调度器钩子 ============

    private static long lastPreNotifyKey = 0;
    private static long lastPostNotifyAt = 0;

    /** 调度器每个 Tick 调用：上课期间提醒调用方静默；并触发课前/下课联动提醒。返回是否处于上课（供调度器免打扰）。 */
    public static boolean evaluate(Context ctx) {
        if (!enabled(ctx)) { lastPreNotifyKey = 0; lastPostNotifyAt = 0; return false; }
        JSONObject cur = currentClass(ctx);
        if (cur != null) {
            // 上课中：静默守护（调度器据此不主动打扰）。
            DebugState.append(ctx, "课表：上课中 " + cur.optString("subject", "") + " @ " + cur.optString("room", ""));
            return true;
        }
        long toNext = msToNextClassToday(ctx);
        if (toNext >= 0 && toNext <= PRE_CLASS_MS) {
            long key = System.currentTimeMillis() / (PRE_CLASS_MS); // 每个窗口只提醒一次
            if (key != lastPreNotifyKey) {
                lastPreNotifyKey = key;
                CompanionService.showReminderNotification(ctx, "课前提醒", "快上课了，准备好课本，掌心窗会帮你安静守护。");
            }
        }
        // 下课提醒：上一节课的 end 在过去 5 分钟内
        try {
            int today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
            int wd = today == 1 ? 7 : today - 1;
            int now = nowMinutes();
            JSONArray arr = getEntries(ctx);
            long lastEnd = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optInt("weekday", 0) != wd) continue;
                int e = toMinutes(o.optString("end", ""));
                if (e >= 0 && e <= now && (now - e) * 60_000L <= POST_CLASS_MS) lastEnd = Math.max(lastEnd, (now - e) * 60_000L);
            }
            if (lastEnd > 0 && Math.abs(System.currentTimeMillis() - lastPostNotifyAt) > POST_CLASS_MS) {
                lastPostNotifyAt = System.currentTimeMillis();
                CompanionService.showReminderNotification(ctx, "下课啦", "这节结束啦，喝口水、伸个懒腰再继续。");
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ============ 命令路由 ============

    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        try {
            String action = cmd.optString("action", "");
            if ("get_class_schedule".equals(action)) {
                return out.put("ok", true).put("result", "class_schedule").put("entries", getEntries(ctx));
            }
            if ("add_class_entry".equals(action) || "upsert_class_entry".equals(action)) {
                JSONObject e = cmd.optJSONObject("entry");
                if (e == null) e = cmd;
                upsertEntry(ctx, e);
                return out.put("ok", true).put("result", "class_entry_saved");
            }
            if ("remove_class_entry".equals(action)) {
                removeEntry(ctx, cmd.optInt("weekday", 1), cmd.optString("start", ""), cmd.optString("subject", ""));
                return out.put("ok", true).put("result", "class_entry_removed");
            }
            if ("import_class_schedule".equals(action)) {
                importEntries(ctx, cmd.optJSONArray("entries"));
                return out.put("ok", true).put("result", "class_schedule_imported");
            }
            return out.put("ok", false).put("error", "unknown_class_schedule_action");
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
            return out;
        }
    }

    public static String pretty(Context ctx) {
        try {
            JSONArray arr = getEntries(ctx);
            if (arr.length() == 0) return "课表为空，点「录入课表」添加。";
            StringBuilder sb = new StringBuilder("课表（共 ").append(arr.length()).append(" 节）：\n");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                sb.append("- ").append(weekdayName(o.optInt("weekday", 1))).append(" ")
                  .append(o.optString("start", "")).append("-").append(o.optString("end", "")).append(" ")
                  .append(o.optString("subject", ""));
                if (!o.optString("room", "").isEmpty()) sb.append(" @").append(o.optString("room", ""));
                sb.append("\n");
            }
            JSONObject cur = currentClass(ctx);
            if (cur != null) sb.append("\n当前上课中：").append(cur.optString("subject", ""));
            return sb.toString().trim();
        } catch (Exception e) { return "课表读取失败：" + ScreenshotService.shortMsg(e); }
    }

    // ============ 工具 ============

    private static int clampWeekday(int wd) { return Math.max(1, Math.min(7, wd)); }
    private static boolean validHHmm(String s) { return toMinutes(s) >= 0; }
    private static int toMinutes(String s) {
        if (s == null) return -1;
        try { String[] p = s.trim().split("[:：]"); return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]); }
        catch (Exception e) { return -1; }
    }
    private static int nowMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }
    public static String weekdayName(int wd) {
        String[] names = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[Math.max(1, Math.min(7, wd))];
    }
}
