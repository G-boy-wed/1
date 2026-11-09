package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * 真实天气轻量层：
 * 1. App 端按“当前地区城市”查询 Open-Meteo；
 * 2. 结果缓存到本地，生活状态层和首页卡片都能读取；
 * 3. 查询失败不影响掌心窗主功能，只回退到天气备注/地区占位。
 */
public class WeatherLive {
    private static final String KEY_CITY = "weather_live_city";
    private static final String KEY_JSON = "weather_live_json";
    private static final String KEY_AT = "weather_live_at";
    private static final long DEFAULT_FRESH_MS = 45L * 60L * 1000L;

    public interface Callback { void onResult(JSONObject weather); }

    public static JSONObject cached(Context ctx, String city) {
        return cached(ctx, city, "");
    }

    public static JSONObject cached(Context ctx, String city, String district) {
        try {
            city = clean(city); district = clean(district);
            if (city.isEmpty()) return null;
            SharedPreferences p = AppPrefs.get(ctx);
            String savedKey = p.getString(KEY_CITY, "");
            String raw = p.getString(KEY_JSON, "");
            if (raw == null || raw.isEmpty() || !cacheKey(city, district).equals(savedKey)) return null;
            JSONObject o = new JSONObject(raw);
            o.put("cache_age_ms", Math.max(0, System.currentTimeMillis() - p.getLong(KEY_AT, 0)));
            return o;
        } catch (Exception e) { return null; }
    }

    private static String cacheKey(String city, String district) {
        return clean(city) + (clean(district).isEmpty() ? "" : "|" + clean(district));
    }

    public static boolean isFresh(Context ctx, String city, long freshMs) {
        return isFresh(ctx, city, "", freshMs);
    }

    public static boolean isFresh(Context ctx, String city, String district, long freshMs) {
        try {
            city = clean(city); district = clean(district);
            if (city.isEmpty()) return false;
            SharedPreferences p = AppPrefs.get(ctx);
            return cacheKey(city, district).equals(p.getString(KEY_CITY, "")) && (System.currentTimeMillis() - p.getLong(KEY_AT, 0)) <= Math.max(60_000L, freshMs);
        } catch (Exception e) { return false; }
    }

    public static void refreshAsync(Context ctx, String city, Callback callback) {
        refreshAsync(ctx, city, "", 0, 0, callback);
    }

    public static void refreshAsync(Context ctx, String city, String district, Callback callback) {
        refreshAsync(ctx, city, district, 0, 0, callback);
    }

    /**
     * Bug#2：坐标优先刷新。传入标准经纬度（本机定位 / 用户「使用当前定位」保存）时，
     * 直接按坐标查 forecast，完全跳过名称地理编码，从根本上杜绝「北川羌族自治县」被
     * Open-Meteo 模糊匹配到贵州遵义（27.5385,106.439）这类省市漂移。仅无坐标时才回退名称检索。
     */
    public static void refreshAsync(Context ctx, String city, String district,
                                    double lat, double lon, Callback callback) {
        final Context app = ctx.getApplicationContext();
        final String chosenCity = clean(city);
        final String chosenDistrict = clean(district);
        final boolean hasCoords = (lat != 0 || lon != 0) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
        if (chosenCity.isEmpty() && !hasCoords) {
            if (callback != null) callback.onResult(error("missing_city", chosenCity));
            return;
        }
        if (isFresh(app, chosenCity, chosenDistrict, DEFAULT_FRESH_MS)) {
            JSONObject cached = cached(app, chosenCity, chosenDistrict);
            if (callback != null && cached != null) callback.onResult(cached);
            return;
        }
        new Thread(() -> {
            JSONObject result;
            try {
                result = hasCoords ? fetchByCoords(lat, lon, chosenCity, chosenDistrict)
                                   : fetch(chosenCity, chosenDistrict);
                AppPrefs.get(app).edit()
                        .putString(KEY_CITY, cacheKey(chosenCity, chosenDistrict))
                        .putString(KEY_JSON, result.toString())
                        .putLong(KEY_AT, System.currentTimeMillis())
                        .apply();
                DebugState.append(app, "天气已刷新：" + cacheKey(chosenCity, chosenDistrict)
                        + " · " + result.optString("condition", "") + " · " + result.optString("source", ""));
            } catch (Exception e) {
                result = error(ScreenshotService.shortMsg(e), cacheKey(chosenCity, chosenDistrict));
                DebugState.append(app, "天气刷新失败：" + cacheKey(chosenCity, chosenDistrict) + " · " + ScreenshotService.shortMsg(e));
            }
            if (callback != null) callback.onResult(result);
        }, "peek-weather-live").start();
    }

    private static JSONObject fetch(String city) throws Exception {
        return fetch(city, "");
    }

    // Bug#2：已知易错的中文长地名内置坐标兜底。Open-Meteo 会把「北川羌族自治县」等模糊到外省，
    // 命中此表直接按坐标取天气，跳过名称地理编码，杜绝省市漂移（坐标取本机定位实测值）。
    private static final String[][] CITY_COORD_OVERRIDES = {
            {"北川羌族自治县", "四川", "绵阳", "31.596", "104.458"},
            {"北川县", "四川", "绵阳", "31.596", "104.458"},
            {"北川", "四川", "绵阳", "31.596", "104.458"},
            {"绵阳", "四川", "绵阳", "31.4694", "104.6759"},
    };

    /**
     * Bug#2：中文地名容错归一化——逐级剥掉「省/市/区/县/自治州/自治县」等行政后缀，
     * 取核心城市名用于地理编码检索，避免「北川羌族自治县」整串被模糊匹配到外省。
     * 对应需求的 searchGeoFuzzy：city = input.replace(行政后缀正则, "")。
     */
    public static String normalizeCityName(String raw) {
        String s = clean(raw);
        if (s.isEmpty()) return "";
        String[] suffixes = {"羌族自治县", "各族自治县", "自治县", "自治州", "地区", "省", "市", "区", "县", "盟", "旗"};
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            for (String suf : suffixes) {
                if (s.length() > suf.length() && s.endsWith(suf)) {
                    s = s.substring(0, s.length() - suf.length());
                    changed = true;
                    break;
                }
            }
        }
        // 兼容需求里的正则语义：出现首个行政层级词即截断其后内容。
        String truncated = s.replaceAll("(省|市|区|县|自治州|自治县).*$", "");
        if (!truncated.isEmpty()) s = truncated;
        return s.isEmpty() ? clean(raw) : s;
    }

    private static JSONObject coordOverride(String raw) {
        String key = clean(raw);
        if (key.isEmpty()) return null;
        for (String[] row : CITY_COORD_OVERRIDES) {
            if (key.equals(row[0]) || key.contains(row[0])) {
                try {
                    return new JSONObject()
                            .put("latitude", Double.parseDouble(row[3]))
                            .put("longitude", Double.parseDouble(row[4]))
                            .put("name", row[0])
                            .put("admin1", row[1])
                            .put("resolved_city", row[2])
                            .put("country", "中国");
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /** Bug#2：坐标优先取天气——只按经纬度查 forecast，零名称地理编码，天然不会省市漂移。 */
    private static JSONObject fetchByCoords(double lat, double lon, String city, String district) throws Exception {
        JSONObject out = forecast(lat, lon);
        String c = clean(city);
        String d = clean(district);
        out.put("source", "device_coords");
        out.put("queried_city", c.isEmpty() ? "当前位置" : c);
        out.put("resolved_city", c.isEmpty() ? "当前位置" : c);
        out.put("admin1", "");
        out.put("admin2", d);
        out.put("district", d);
        out.put("country", "中国");
        out.put("latitude", lat);
        out.put("longitude", lon);
        out.put("geo_display", "当前定位 · " + String.format(Locale.US, "%.4f", lat) + "," + String.format(Locale.US, "%.4f", lon));
        out.put("geo_source", "device_location");
        finalizeWeather(out);
        return out;
    }

    private static JSONObject fetch(String city, String district) throws Exception {
        String chosenCity = clean(city);
        String chosenDistrict = clean(district);
        if (chosenCity.isEmpty()) return error("missing_city", chosenDistrict);

        // ① 已知易错地名：直接命中内置坐标，跳过 geocoding。
        JSONObject hit = coordOverride(chosenCity);
        if (hit == null) hit = coordOverride(normalizeCityName(chosenCity));
        boolean fromOverride = hit != null;
        String queryUsed = fromOverride ? "local_override" : "";

        // ② 未命中：优先用剥掉行政后缀的核心城市名检索，再试「区县 + 城市」「原始全名」。
        if (!fromOverride) {
            String core = normalizeCityName(chosenCity);
            java.util.LinkedHashSet<String> queries = new java.util.LinkedHashSet<>();
            if (!core.isEmpty()) queries.add(core);
            if (!chosenDistrict.isEmpty()) queries.add(chosenDistrict + " " + (core.isEmpty() ? chosenCity : core));
            if (!core.isEmpty() && !chosenDistrict.isEmpty()) queries.add(chosenDistrict + " " + chosenCity);
            queries.add(chosenCity);
            for (String q : queries) {
                JSONObject one = geocode(q);
                if (one != null) { hit = one; queryUsed = q; break; }
            }
        }
        if (hit == null) return error("city_not_found", chosenCity);
        double lat = hit.optDouble("latitude", 0);
        double lon = hit.optDouble("longitude", 0);
        if (lat == 0 && lon == 0) return error("city_not_found", chosenCity);

        JSONObject out = forecast(lat, lon);
        out.put("source", fromOverride ? "local_override" : "open-meteo");
        out.put("queried_city", chosenCity);
        out.put("resolved_city", hit.optString("name", chosenCity));
        out.put("admin1", hit.optString("admin1", ""));
        out.put("admin2", hit.optString("admin2", ""));
        out.put("district", chosenDistrict.isEmpty() ? hit.optString("admin2", "") : chosenDistrict);
        out.put("country", hit.optString("country", ""));
        out.put("latitude", lat);
        out.put("longitude", lon);
        // Bug#2：解析结果以「省 + 市」回传，前端保存时展示给用户二次确认，不再静默错配。
        String display = hit.optString("admin1", "") + hit.optString("name", chosenCity);
        out.put("geo_display", display.isEmpty() ? chosenCity : display);
        out.put("geo_query_used", queryUsed);
        out.put("geo_source", fromOverride ? "local_override" : "geocoding");
        finalizeWeather(out);
        return out;
    }

    private static JSONObject geocode(String q) throws Exception {
        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(q, "UTF-8") + "&count=1&language=zh&format=json";
        JSONObject geo = new JSONObject(get(geoUrl));
        JSONArray results = geo.optJSONArray("results");
        if (results != null && results.length() > 0) return results.getJSONObject(0);
        return null;
    }

    /** 按坐标请求 Open-Meteo forecast，组装与展示无关的气象字段（fetch / fetchByCoords 共用）。 */
    private static JSONObject forecast(double lat, double lon) throws Exception {
        String forecastUrl = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2",
                lat, lon);
        JSONObject data = new JSONObject(get(forecastUrl));
        JSONObject current = data.optJSONObject("current");
        JSONObject daily = data.optJSONObject("daily");
        int code = current == null ? -999 : current.optInt("weather_code", current.optInt("weathercode", -999));
        double temp = current == null ? 0 : current.optDouble("temperature_2m", current.optDouble("temperature", 0));
        double wind = current == null ? 0 : current.optDouble("wind_speed_10m", 0);
        double max = firstDouble(daily, "temperature_2m_max", temp);
        double min = firstDouble(daily, "temperature_2m_min", temp);
        int rain = (int) Math.round(firstDouble(daily, "precipitation_probability_max", 0));
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("temperature", Math.round(temp));
        out.put("temperature_raw", temp);
        out.put("weather_code", code);
        out.put("condition", weatherCodeText(code));
        out.put("wind_speed", Math.round(wind));
        out.put("temp_max", Math.round(max));
        out.put("temp_min", Math.round(min));
        out.put("precipitation_probability", rain);
        out.put("updated_at_ms", System.currentTimeMillis());
        return out;
    }

    private static void finalizeWeather(JSONObject out) {
        try {
            out.put("summary", cardText(out, ""));
            out.put("advice", advice(out, "当前地区"));
        } catch (Exception ignored) { }
    }

    private static double firstDouble(JSONObject obj, String key, double fallback) {
        try {
            if (obj == null) return fallback;
            JSONArray arr = obj.optJSONArray(key);
            if (arr == null || arr.length() == 0) return fallback;
            return arr.optDouble(0, fallback);
        } catch (Exception e) { return fallback; }
    }

    private static JSONObject error(String error, String city) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("error", error); o.put("queried_city", city); } catch (Exception ignored) { }
        return o;
    }

    private static String get(String u) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "LinjianPeekPublic/0.3.9.0");
        try {
            InputStream is = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally { conn.disconnect(); }
    }

    public static String cardText(JSONObject weather, String name) {
        if (weather == null || !weather.optBoolean("ok")) return "天气\n待刷新";
        String city = weather.optString("resolved_city", weather.optString("queried_city", name));
        String district = weather.optString("district", "");
        String where = district.isEmpty() ? city : (district + "·" + city);
        String condition = weather.optString("condition", "天气");
        int temp = weather.optInt("temperature", 0);
        return where + " · " + condition + "\n" + temp + "℃";
    }

    public static String advice(JSONObject weather, String name) {
        if (weather == null || !weather.optBoolean("ok")) return "出门建议：实时天气没查到，先按体感穿衣。";
        String n = (name == null || name.trim().isEmpty()) ? "当前地区" : name.trim();
        String condition = weather.optString("condition", "");
        int temp = weather.optInt("temperature", 0);
        int max = weather.optInt("temp_max", temp);
        int min = weather.optInt("temp_min", temp);
        int rain = weather.optInt("precipitation_probability", 0);
        String head = n + "现在" + condition + "，约 " + temp + "℃，今天 " + min + "~" + max + "℃。";
        if (rain >= 50 || condition.contains("雨") || condition.contains("雪")) return "出门建议：" + head + "伞拿上，别淋湿。";
        if (max >= 32) return "出门建议：" + head + "今天偏热，水杯和防晒都带着。";
        if (min <= 8 || Math.abs(max - min) >= 10) return "出门建议：" + head + "温差明显，外套别嘴硬。";
        if (condition.contains("雾") || condition.contains("霾")) return "出门建议：" + head + "能少在外面吹就少吹一会儿。";
        return "出门建议：" + head + "正常出门就好。";
    }

    public static String weatherCodeText(int code) {
        switch (code) {
            case 0: return "晴";
            case 1: return "大部晴朗";
            case 2: return "多云";
            case 3: return "阴";
            case 45: return "雾";
            case 48: return "雾凇";
            case 51: return "小毛毛雨";
            case 53: return "毛毛雨";
            case 55: return "强毛毛雨";
            case 61: return "小雨";
            case 63: return "中雨";
            case 65: return "大雨";
            case 71: return "小雪";
            case 73: return "中雪";
            case 75: return "大雪";
            case 80: return "阵雨";
            case 81: return "中等阵雨";
            case 82: return "强阵雨";
            case 95: return "雷暴";
            case 96: return "雷暴伴冰雹";
            case 99: return "强雷暴伴冰雹";
            default: return "天气代码 " + code;
        }
    }

    /** Bug#2：主动「使用当前定位」或手改城市后强制缓存失效，下一次刷新立即走新坐标/新城市。 */
    public static void invalidateCache(Context ctx) {
        try { AppPrefs.get(ctx.getApplicationContext()).edit().remove(KEY_AT).apply(); } catch (Exception ignored) { }
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
