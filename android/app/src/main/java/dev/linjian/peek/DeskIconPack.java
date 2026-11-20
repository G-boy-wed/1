package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import org.json.JSONObject;

/**
 * 掌心窗内置角标图标包 —— 与 UI 同色系的莫兰迪几何 App 图标。
 *
 * 全部为纯几何图形（圆/弧/条/点/环），颜色取自 UI 色系（米白/柔绿/藕粉/淡蓝/浅灰…），
 * 不含任何 emoji、文字或简笔画。默认每个角标按语义稳定映射到一张图，
 * 用户也可为任意角标（含旧功能入口）另选一张内置图，或自定义上传图片。
 * 优先级：自定义上传 URI > 内置图片。
 */
public final class DeskIconPack {
    private static final String PREF = "desk_icon_pack_v1";
    private static final String K_BUILTIN = "builtin"; // {"checkin":3, "old_diary":1, ...}

    /** 内置图片资源（drawable-nodpi），0..11，均为 UI 同色系纯几何图标。 */
    public static final int[] RES = {
            R.drawable.ic_badge_0, R.drawable.ic_badge_1,
            R.drawable.ic_badge_2, R.drawable.ic_badge_3,
            R.drawable.ic_badge_4, R.drawable.ic_badge_5,
            R.drawable.ic_badge_6, R.drawable.ic_badge_7,
            R.drawable.ic_badge_8, R.drawable.ic_badge_9,
            R.drawable.ic_badge_10, R.drawable.ic_badge_11
    };
    public static final String[] NAMES = {
            "柔绿", "藕粉", "淡蓝", "米杏", "浅灰", "淡紫",
            "燕麦", "青灰", "暖粉", "雾蓝", "芽绿", "豆沙"
    };
    public static final int COUNT = RES.length;

    private DeskIconPack() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    private static JSONObject obj(Context c) {
        try { return new JSONObject(sp(c).getString(K_BUILTIN, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** 未指定时的默认内置索引：按 id 稳定哈希分布，前四个功能强制 0..3。 */
    public static int defaultIndexFor(String assetKey) {
        if ("checkin".equals(assetKey)) return 0;
        if ("emotion".equals(assetKey)) return 1;
        if ("initiative".equals(assetKey)) return 2;
        if ("discipline".equals(assetKey)) return 3;
        int h = assetKey == null ? 0 : assetKey.hashCode();
        return Math.abs(h) % COUNT;
    }

    /** 取某角标当前选定的内置索引；-1 表示用户显式关闭了内置图。 */
    public static int indexOf(Context c, String assetKey) {
        JSONObject o = obj(c);
        if (!o.has(assetKey)) return defaultIndexFor(assetKey);
        return o.optInt(assetKey, -1);
    }

    public static void setIndex(Context c, String assetKey, int index) {
        JSONObject o = obj(c);
        try {
            if (index < 0) o.remove(assetKey);
            else o.put(assetKey, index % COUNT);
        } catch (Exception ignored) { }
        sp(c).edit().putString(K_BUILTIN, o.toString()).apply();
    }

    public static void resetOne(Context c, String assetKey) {
        JSONObject o = obj(c);
        try { o.remove(assetKey); } catch (Exception ignored) { }
        sp(c).edit().putString(K_BUILTIN, o.toString()).apply();
    }

    public static void resetAll(Context c) {
        sp(c).edit().remove(K_BUILTIN).apply();
    }

    public static Drawable drawable(Context c, int index) {
        try {
            Resources r = c.getResources();
            return r.getDrawable(RES[((index % COUNT) + COUNT) % COUNT], null);
        } catch (Exception e) { return null; }
    }
}
