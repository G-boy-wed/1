package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 掌心窗自由桌面的「摆放」持久化：每个元素（角标 / 贴图 / 文字卡）的位置与尺寸。
 *
 * 坐标单位为 dp（相对页面左上角的 x/y，宽 w、高 h），与屏幕密度无关，重启不丢。
 * key 形如 "2:feature:checkin"，即 页:类型:id。所有方法对损坏数据兜底，绝不抛异常。
 */
public final class DeskLayoutStore {
    private static final String PREF = "desk_layout_v1";
    private static final String K_POS = "pos";

    private DeskLayoutStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static JSONObject obj(Context c) {
        try { return new JSONObject(sp(c).getString(K_POS, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    public static final class Pos {
        public float x, y, w, h;
        public Pos() { }
        public Pos(float x, float y, float w, float h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    }

    private static String key(int page, String kind, String id) {
        return page + ":" + kind + ":" + id;
    }

    /** 读取元素位置；无自定义则返回默认值 def（不为 null）。 */
    public static Pos get(Context c, int page, String kind, String id, Pos def) {
        if (def == null) def = new Pos(0, 0, 60, 80);
        try {
            JSONObject o = obj(c).optJSONObject(key(page, kind, id));
            if (o == null) return def;
            return new Pos((float) o.optDouble("x", def.x), (float) o.optDouble("y", def.y),
                    (float) o.optDouble("w", def.w), (float) o.optDouble("h", def.h));
        } catch (Exception e) { return def; }
    }

    public static void set(Context c, int page, String kind, String id, Pos p) {
        if (p == null) return;
        try {
            JSONObject root = obj(c);
            JSONObject o = new JSONObject();
            o.put("x", p.x); o.put("y", p.y); o.put("w", p.w); o.put("h", p.h);
            root.put(key(page, kind, id), o);
            sp(c).edit().putString(K_POS, root.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static void remove(Context c, int page, String kind, String id) {
        try {
            JSONObject root = obj(c);
            root.remove(key(page, kind, id));
            sp(c).edit().putString(K_POS, root.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** 恢复某页所有元素的默认摆放。 */
    public static void resetPage(Context c, int page) {
        try {
            JSONObject root = obj(c);
            String prefix = page + ":";
            java.util.List<String> del = new java.util.ArrayList<>();
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) { String k = it.next(); if (k.startsWith(prefix)) del.add(k); }
            for (String k : del) root.remove(k);
            sp(c).edit().putString(K_POS, root.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static void resetAll(Context c) {
        sp(c).edit().remove(K_POS).apply();
    }
}
