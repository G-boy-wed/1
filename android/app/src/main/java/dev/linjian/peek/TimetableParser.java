package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 课表导入与时间轴对齐（对应 PRD 模块二）。
 *
 * 支持三种来源：
 *  1) ICS 格式（高校教务导出的 .ics，含 VEVENT / DTSTART / DTEND / RRULE BYDAY / SUMMARY / LOCATION）；
 *  2) 图片 OCR：先把课表截图发给服务端视觉模型（/api/ocr/analyze），再从结构化结果里抽取条目；
 *  3) JSON 结构（与 ClassScheduleState 条目格式一致，直接批量写入）。
 *
 * 解析结果统一转换为课表条目 {weekday(1-7),start,end,subject,room}，交给 ClassScheduleState 持久化。
 */
public final class TimetableParser {

    private TimetableParser() { }

    /** 把 ICS 文本解析为课表条目。返回 {ok,entries,count,errors}。 */
    public static JSONObject parseICS(String ics) {
        JSONObject out = new JSONObject();
        try {
            JSONArray entries = new JSONArray();
            List<String> errors = new ArrayList<>();
            if (ics == null || ics.trim().isEmpty()) {
                return out.put("ok", false).put("error", "empty_ics");
            }
            // 处理行折叠：以空格/制表符开头的下一行是上一行续行。
            String[] rawLines = ics.split("\\r?\\n");
            StringBuilder unfolded = new StringBuilder();
            for (String ln : rawLines) {
                if (ln.startsWith(" ") || ln.startsWith("\t")) {
                    if (unfolded.length() > 0) unfolded.append(ln.substring(1));
                } else {
                    if (unfolded.length() > 0) unfolded.append("\n");
                    unfolded.append(ln);
                }
            }
            // 按 VEVENT 切分。
            String[] events = unfolded.toString().split("(?i)BEGIN:VEVENT");
            for (String ev : events) {
                if (!ev.toUpperCase().contains("END:VEVENT")) continue;
                String dtstart = field(ev, "DTSTART");
                String dtend = field(ev, "DTEND");
                String summary = unescape(field(ev, "SUMMARY"));
                String location = unescape(field(ev, "LOCATION"));
                String rrule = field(ev, "RRULE");

                String startTime = timeOf(dtstart);
                String endTime = timeOf(dtend);
                if (startTime.isEmpty()) { errors.add("缺少 DTSTART 时间：" + summary); continue; }
                if (endTime.isEmpty()) {
                    // 无 DTEND：默认课时 100 分钟。
                    endTime = addMinutes(startTime, 100);
                }

                List<Integer> weekdays = parseWeekdays(rrule);
                if (weekdays.isEmpty()) {
                    Integer wd = weekdayFromDate(dtstart);
                    if (wd == null) { errors.add("无法推断星期：" + summary); continue; }
                    weekdays.add(wd);
                }
                for (int wd : weekdays) {
                    JSONObject e = new JSONObject();
                    e.put("weekday", wd);
                    e.put("start", startTime);
                    e.put("end", endTime);
                    e.put("subject", summary.isEmpty() ? "课程" : summary);
                    e.put("section", "");
                    e.put("room", location);
                    entries.put(e);
                }
            }
            out.put("ok", true).put("entries", entries).put("count", entries.length());
            out.put("errors", new JSONArray(errors));
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    /** 从服务端视觉分析结果里抽取课表条目（图片 OCR 课表）。返回 {ok,entries,count}。 */
    public static JSONObject parseImageOcr(JSONObject remote) {
        JSONObject out = new JSONObject();
        try {
            JSONArray entries = new JSONArray();
            // 兼容多种返回结构：remote.entries / remote.timetable / remote.schedule / data.*。
            JSONArray src = pickArray(remote, "entries");
            if (src == null) src = pickArray(remote, "timetable");
            if (src == null) src = pickArray(remote, "schedule");
            if (src == null) src = pickArray(remote, "courses");
            if (src == null) {
                JSONObject data = remote.optJSONObject("data");
                if (data != null) {
                    if (src == null) src = pickArray(data, "entries");
                    if (src == null) src = pickArray(data, "timetable");
                }
            }
            if (src == null) {
                return out.put("ok", false).put("error", "no_entries_in_ocr_result")
                        .put("hint", "服务端视觉结果需包含 entries/timetable/schedule 数组，每项含 weekday/start/end/subject/room");
            }
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o == null) continue;
                JSONObject e = new JSONObject();
                e.put("weekday", o.optInt("weekday", o.optInt("day", 0)));
                e.put("start", o.optString("start", o.optString("begin", "")));
                e.put("end", o.optString("end", o.optString("finish", "")));
                e.put("subject", o.optString("subject", o.optString("course", o.optString("name", "课程"))));
                e.put("section", o.optString("section", ""));
                e.put("room", o.optString("room", o.optString("location", "")));
                entries.put(e);
            }
            out.put("ok", true).put("entries", entries).put("count", entries.length());
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    /** 自动识别文本来源（ICS / JSON），解析并写入本机课表。 */
    public static JSONObject importFromText(Context ctx, String text) {
        JSONObject out = new JSONObject();
        try {
            if (text == null || text.trim().isEmpty()) return out.put("ok", false).put("error", "empty_text");
            if (text.contains("BEGIN:VCALENDAR") || text.contains("BEGIN:VEVENT")) {
                JSONObject parsed = parseICS(text);
                if (!parsed.optBoolean("ok", false)) return out.put("ok", false).put("error", "ics_parse_failed").put("detail", parsed.optString("error", ""));
                ClassScheduleState.importEntries(ctx, parsed.optJSONArray("entries"));
                return out.put("ok", true).put("result", "timetable_imported_ics").put("count", parsed.optInt("count", 0));
            }
            // 尝试当作 JSON 数组
            try {
                JSONArray arr = new JSONArray(text.trim());
                ClassScheduleState.importEntries(ctx, arr);
                return out.put("ok", true).put("result", "timetable_imported_json").put("count", arr.length());
            } catch (Exception je) {
                return out.put("ok", false).put("error", "unsupported_format").put("hint", "支持 ICS、或 JSON 课表数组");
            }
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
            return out;
        }
    }

    /** 从服务端 OCR 视觉结果写入课表。 */
    public static JSONObject importFromImageOcr(Context ctx, JSONObject remote) {
        JSONObject parsed = parseImageOcr(remote);
        if (!parsed.optBoolean("ok", false)) return parsed;
        ClassScheduleState.importEntries(ctx, parsed.optJSONArray("entries"));
        try { parsed.put("result", "timetable_imported_ocr"); } catch (Exception ignored) { }
        return parsed;
    }

    // ============ 解析工具 ============

    private static String field(String block, String key) {
        // 匹配 KEY 或 KEY;PARAMS:VALUE
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)^" + key + "(?:;[^:]*)?:(.*)$", java.util.regex.Pattern.MULTILINE)
                .matcher(block);
        if (m.find()) return m.group(1).trim();
        return "";
    }

    /** 从 DTSTART/DTEND 取值里抽取 HH:mm（支持 YYYYMMDDTHHMMSS / YYYYMMDDTHHMM / 纯日期）。 */
    private static String timeOf(String value) {
        if (value == null) return "";
        int t = value.indexOf('T');
        String timePart = t >= 0 ? value.substring(t + 1) : "";
        if (timePart.length() < 4) return "";
        return timePart.substring(0, 2) + ":" + timePart.substring(2, 4);
    }

    /** 从 DTSTART 日期（YYYYMMDD）推断星期（1=周一..7=周日）。 */
    private static Integer weekdayFromDate(String value) {
        if (value == null) return null;
        int t = value.indexOf('T');
        String date = (t >= 0 ? value.substring(0, t) : value).trim();
        if (date.length() < 8) return null;
        try {
            int y = Integer.parseInt(date.substring(0, 4));
            int mo = Integer.parseInt(date.substring(4, 6));
            int d = Integer.parseInt(date.substring(6, 8));
            Calendar c = Calendar.getInstance();
            c.set(y, mo - 1, d);
            int dow = c.get(Calendar.DAY_OF_WEEK); // 1=周日..7=周六
            return dow == 1 ? 7 : dow - 1;
        } catch (Exception e) { return null; }
    }

    /** 解析 RRULE 的 BYDAY（MO,TU,WE,TH,FR,SA,SU），支持多值。 */
    private static List<Integer> parseWeekdays(String rrule) {
        List<Integer> out = new ArrayList<>();
        if (rrule == null || rrule.isEmpty()) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)BYDAY=([^;]+)").matcher(rrule);
        if (!m.find()) return out;
        for (String code : m.group(1).split(",")) {
            Integer wd = byday(code.trim().toUpperCase(Locale.US));
            if (wd != null && !out.contains(wd)) out.add(wd);
        }
        return out;
    }

    private static Integer byday(String code) {
        if (code == null) return null;
        // 去掉前面的 +2 之类的序号前缀
        code = code.replaceAll("^[+-]?\\d+", "");
        switch (code) {
            case "MO": return 1;
            case "TU": return 2;
            case "WE": return 3;
            case "TH": return 4;
            case "FR": return 5;
            case "SA": return 6;
            case "SU": return 7;
            default: return null;
        }
    }

    private static String addMinutes(String hhmm, int minutes) {
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            int m = Integer.parseInt(hhmm.substring(3, 5));
            int total = h * 60 + m + minutes;
            total = ((total % 1440) + 1440) % 1440;
            return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
        } catch (Exception e) { return hhmm; }
    }

    private static JSONArray pickArray(JSONObject o, String key) {
        if (o == null) return null;
        JSONArray a = o.optJSONArray(key);
        if (a != null) return a;
        Object v = o.opt(key);
        if (v instanceof JSONArray) return (JSONArray) v;
        return null;
    }

    /** ICS 文本转义还原：\, -> ,  \; -> ;  \\ -> \  \n -> 换行。 */
    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").replace("\\N", "\n").replace("\\n", "\n");
    }
}
