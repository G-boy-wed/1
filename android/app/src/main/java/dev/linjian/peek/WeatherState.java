package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** 天气地区层：手机端保存多个地区和当前地区；v0.3.8.2 会缓存真实天气，失败时回退到备注建议。 */
public class WeatherState {
    // 地点格式：名称|城市|区县|备注|是否当前(1/0)。区县可空，用于精确到区县的天气查询与展示。
    public static final String DEFAULT_LOCATIONS = "家|石家庄|||1\n学校|石家庄|||0\n常去地||||0\n";

    public static JSONObject collect(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = locationsJson(ctx);
            JSONObject current = currentLocation(ctx);
            o.put("enabled", true);
            o.put("current", current);
            o.put("locations", arr);
            JSONObject live = WeatherLive.cached(ctx, current.optString("city", ""), current.optString("district", ""));
            if (live != null) {
                o.put("live", live);
                // Bug #3：把真实地理编码得到的精确经纬度与省/市回填进 current。
                // 这样上传的 current_weather_location 自带标准坐标，服务端可按坐标直接查天气，
                // 不再用城市名重新做名称地理编码，避免「绵阳北川」被错配到贵州遵义等省市漂移。
                double liveLat = live.optDouble("latitude", 0);
                double liveLon = live.optDouble("longitude", 0);
                double curLat = current.optDouble("latitude", 0);
                double curLon = current.optDouble("longitude", 0);
                // Bug#2：本机定位坐标（device_location）优先；只在没有设备坐标时才回填 geocoding 坐标，
                // 避免把「北川」被 geocoding 错配到贵州遵义的坐标覆盖回来。
                if ((curLat == 0 && curLon == 0) && (liveLat != 0 || liveLon != 0)) {
                    current.put("latitude", liveLat);
                    current.put("longitude", liveLon);
                }
                // Bug#2：解析结果（省+市 / 当前定位）透传给前端做二次确认。
                if (live.has("geo_display") && !live.optString("geo_display", "").isEmpty()) current.put("geo_display", live.optString("geo_display", ""));
                if (live.has("geo_source") && !live.optString("geo_source", "").isEmpty()) current.put("geo_source", live.optString("geo_source", ""));
                if (live.has("admin1") && !live.optString("admin1", "").isEmpty()) current.put("admin1", live.optString("admin1", ""));
                if (live.has("admin2") && !live.optString("admin2", "").isEmpty()) current.put("admin2", live.optString("admin2", ""));
                if (live.has("resolved_city") && !live.optString("resolved_city", "").isEmpty()) current.put("resolved_city", live.optString("resolved_city", ""));
                if (live.has("country") && !live.optString("country", "").isEmpty()) current.put("country", live.optString("country", ""));
                o.put("current", current);
            }
            o.put("summary", pretty(ctx));
            o.put("outdoor_advice", live != null && live.optBoolean("ok") ? WeatherLive.advice(live, current.optString("name", "当前地区")) : localAdvice(current.optString("note", "")));
        } catch (Exception ignored) { }
        return o;
    }

    public static JSONArray locationsJson(Context ctx) {
        JSONArray arr = new JSONArray();
        try {
            for (Location loc : readLocations(ctx).values()) {
                JSONObject o = new JSONObject();
                o.put("name", loc.name);
                o.put("city", loc.city);
                o.put("district", loc.district);
                o.put("note", loc.note);
                o.put("current", loc.current);
                if (loc.latitude != 0 || loc.longitude != 0) {
                    o.put("latitude", loc.latitude);
                    o.put("longitude", loc.longitude);
                }
                arr.put(o);
            }
        } catch (Exception ignored) { }
        return arr;
    }

    public static JSONObject currentLocation(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Location chosen = null;
            for (Location loc : readLocations(ctx).values()) if (loc.current) { chosen = loc; break; }
            if (chosen == null) {
                for (Location loc : readLocations(ctx).values()) { chosen = loc; break; }
            }
            if (chosen == null) chosen = new Location("家", AppPrefs.get(ctx).getString(AppPrefs.KEY_CITY, ""), "", AppPrefs.get(ctx).getString(AppPrefs.KEY_WEATHER_NOTE, ""), true);
            o.put("name", chosen.name);
            o.put("city", chosen.city);
            o.put("district", chosen.district);
            o.put("note", chosen.note);
            o.put("current", true);
            // Bug#2：把本机定位坐标带进 current_weather_location，服务端/MCP 可直接按坐标查天气。
            if (chosen.latitude != 0 || chosen.longitude != 0) {
                o.put("latitude", chosen.latitude);
                o.put("longitude", chosen.longitude);
                o.put("coords_source", "device_location");
            }
        } catch (Exception ignored) { }
        return o;
    }

    public static String summaryLine(Context ctx) {
        try {
            JSONObject cur = currentLocation(ctx);
            String name = cur.optString("name", "当前地区");
            String city = cur.optString("city", "");
            return "天气地区 · " + name + (city.isEmpty() ? " · 未设城市" : (" · " + city));
        } catch (Exception e) { return "天气地区 · 读取中"; }
    }

    public static String pretty(Context ctx) {
        try {
            JSONObject cur = currentLocation(ctx);
            String name = cur.optString("name", "当前地区");
            String city = cur.optString("city", "");
            String district = cur.optString("district", "");
            String note = cur.optString("note", "");
            StringBuilder sb = new StringBuilder();
            sb.append("当前地区：").append(name);
            if (!city.isEmpty()) sb.append(" · ").append(city);
            if (!district.isEmpty()) sb.append(" · ").append(district);
            if (!note.isEmpty()) sb.append("\n天气备注：").append(note);
            sb.append("\n").append(localAdvice(note));
            return sb.toString();
        } catch (Exception e) { return "天气地区读取失败：" + ScreenshotService.shortMsg(e); }
    }

    public static String locationsText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Location loc : readLocations(ctx).values()) {
            sb.append(loc.current ? "✓ " : "  ").append(loc.name);
            if (!loc.city.isEmpty()) sb.append(" · ").append(loc.city);
            if (!loc.district.isEmpty()) sb.append(" · ").append(loc.district);
            if (!loc.note.isEmpty()) sb.append(" · ").append(loc.note);
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public static void saveLocation(Context ctx, String name, String city, String district, String note, boolean makeCurrent) {
        name = clean(name); city = clean(city); district = clean(district); note = clean(note);
        if (name.isEmpty()) name = city.isEmpty() ? "新地区" : city;
        LinkedHashMap<String, Location> map = readLocations(ctx);
        if (makeCurrent) for (Location loc : map.values()) loc.current = false;
        Location old = map.get(name);
        if (old == null) old = new Location(name, city, district, note, makeCurrent);
        // Bug#2：手改城市/区县后，旧的定位坐标不再适用，必须清掉，避免继续按旧坐标出天气。
        boolean geoChanged = !city.equals(old.city) || !district.equals(old.district);
        old.city = city; old.district = district; old.note = note; old.current = makeCurrent || old.current;
        if (geoChanged) { old.latitude = 0; old.longitude = 0; }
        map.put(name, old);
        writeLocations(ctx, map);
        if (old.current) syncLegacy(ctx, old);
    }

    /**
     * Bug#2：「使用当前定位」——把本机经纬度写到指定地点；name 为空时写当前地点。
     * 之后天气刷新走坐标优先路径，跳过名称地理编码，杜绝中文长地名省市漂移。
     */
    public static void saveLocationCoords(Context ctx, String name, double lat, double lon) {
        if ((lat == 0 && lon == 0) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
        LinkedHashMap<String, Location> map = readLocations(ctx);
        String key = clean(name);
        Location target = key.isEmpty() ? null : map.get(key);
        if (target == null) for (Location loc : map.values()) if (loc.current) { target = loc; break; }
        if (target == null) for (Location loc : map.values()) { target = loc; break; }
        if (target == null) {
            target = new Location("当前位置", "", "", "", true);
            map.put(target.name, target);
        }
        target.latitude = lat; target.longitude = lon;
        writeLocations(ctx, map);
        if (target.current) syncLegacy(ctx, target);
    }

    private static double parseCoord(String s) {
        try { return Double.parseDouble(clean(s)); } catch (Exception e) { return 0; }
    }

    private static String fmtCoord(double v) {
        return v == 0 ? "" : Double.toString(v);
    }

    public static boolean setCurrent(Context ctx, String name) {
        name = clean(name);
        if (name.isEmpty()) return false;
        LinkedHashMap<String, Location> map = readLocations(ctx);
        Location found = map.get(name);
        if (found == null) return false;
        for (Location loc : map.values()) loc.current = false;
        found.current = true;
        writeLocations(ctx, map);
        syncLegacy(ctx, found);
        return true;
    }

    private static void syncLegacy(Context ctx, Location loc) {
        AppPrefs.get(ctx).edit().putString(AppPrefs.KEY_CITY, loc.city).putString(AppPrefs.KEY_WEATHER_NOTE, loc.note).commit();
    }

    private static LinkedHashMap<String, Location> readLocations(Context ctx) {
        SharedPreferences p = AppPrefs.get(ctx);
        String raw = p.getString(AppPrefs.KEY_WEATHER_LOCATIONS, "");
        if (raw == null || raw.trim().isEmpty()) raw = DEFAULT_LOCATIONS;
        LinkedHashMap<String, Location> map = new LinkedHashMap<>();
        for (String line : raw.split("\\n")) {
            if (line == null || line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|", -1);
            String name = parts.length > 0 ? clean(parts[0]) : "";
            String city = parts.length > 1 ? clean(parts[1]) : "";
            // 兼容旧 4 段格式（名称|城市|备注|当前）：旧数据无区县，段[2]=备注、段[3]=当前。
            String district = "", note = "";
            boolean current = false;
            double lat = 0, lon = 0;
            if (parts.length >= 5) {
                district = clean(parts[2]);
                note = clean(parts[3]);
                current = "1".equals(parts[4].trim());
                // v0.4.1：第 6/7 段为本机定位坐标（「使用当前定位」写入）。旧 5 段数据无坐标，保持兼容。
                if (parts.length >= 7) { lat = parseCoord(parts[5]); lon = parseCoord(parts[6]); }
            } else if (parts.length == 4) {
                note = clean(parts[2]);
                current = "1".equals(parts[3].trim());
            } else if (parts.length == 3) {
                note = clean(parts[2]);
            }
            if (name.isEmpty()) continue;
            Location loc = new Location(name, city, district, note, current);
            loc.latitude = lat; loc.longitude = lon;
            map.put(name, loc);
        }
        if (map.isEmpty()) {
            map.put("家", new Location("家", p.getString(AppPrefs.KEY_CITY, "石家庄"), "", p.getString(AppPrefs.KEY_WEATHER_NOTE, ""), true));
            map.put("学校", new Location("学校", "石家庄", "", "", false));
            map.put("常去地", new Location("常去地", "", "", "", false));
        }
        boolean hasCurrent = false;
        for (Location loc : map.values()) if (loc.current) { hasCurrent = true; break; }
        if (!hasCurrent) {
            for (Location loc : map.values()) { loc.current = true; break; }
        }
        return map;
    }

    private static void writeLocations(Context ctx, LinkedHashMap<String, Location> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Location> e : map.entrySet()) {
            Location l = e.getValue();
            sb.append(escape(l.name)).append("|").append(escape(l.city)).append("|")
              .append(escape(l.district)).append("|").append(escape(l.note)).append("|")
              .append(l.current ? "1" : "0").append("|")
              .append(fmtCoord(l.latitude)).append("|").append(fmtCoord(l.longitude)).append("\n");
        }
        AppPrefs.get(ctx).edit().putString(AppPrefs.KEY_WEATHER_LOCATIONS, sb.toString()).commit();
    }

    public static String localAdvice(String note) {
        String n = note == null ? "" : note;
        if (n.contains("雨") || n.contains("雪")) return "出门建议：伞拿上，别淋湿。";
        if (n.contains("降温") || n.contains("冷") || n.contains("低温")) return "出门建议：外套带着，别嘴硬。";
        if (n.contains("热") || n.contains("高温") || n.contains("晒")) return "出门建议：水杯和防晒记得带。";
        if (n.contains("风") || n.contains("大风")) return "出门建议：风大，头发和外套都顾一下。";
        if (n.contains("空气") || n.contains("霾") || n.contains("污染")) return "出门建议：空气一般，少在外面吹太久。";
        return "出门建议：当前地区已设好，查看掌心窗时会一起显示。";
    }

    private static String clean(String s) { return s == null ? "" : s.trim().replace("|", " ").replace("\n", " "); }
    private static String escape(String s) { return clean(s); }

    private static class Location {
        String name; String city; String district; String note; boolean current;
        // Bug#2：本机定位坐标（「使用当前定位」写入），0 表示未设置。
        double latitude; double longitude;
        Location(String name, String city, String district, String note, boolean current) {
            this.name = name; this.city = city; this.district = district; this.note = note; this.current = current;
        }
    }
}
