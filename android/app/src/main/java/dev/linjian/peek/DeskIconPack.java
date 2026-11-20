package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import org.json.JSONObject;

/**
 * 掌心窗内置角标图标包（粉白/奶油色系，来自参考四宫格切分图）。
 *
 * - 默认：每个功能角标按序映射到一张内置图片（前四个正好对应四宫格切出的四张），
 *   使角标 UI 与参考图色系统一。
 * - 用户可为任意角标指定另一张内置图片（含旧功能入口），也仍可自定义上传图片或换字符图标。
 * 优先级：自定义上传 URI > 内置图片 > 字符图标。
 */
public final class DeskIconPack {
    private static final String PREF = "desk_icon_pack_v1";
    private static final String K_BUILTIN = "builtin"; // {"checkin":3, "old_diary":1, ...}

    /** 内置图片资源（drawable-nodpi），0..7；前 4 张即参考四宫格切分图。 */
    public static final int[] RES = {
            R.drawable.ic_badge_0, R.drawable.ic_badge_1,
            R.drawable.ic_badge_2, R.drawable.ic_badge_3,
            R.drawable.ic_badge_4, R.drawable.ic_badge_5,
            R.drawable.ic_badge_6, R.drawable.ic_badge_7
    };
    public static final String[] NAMES = {
            "玫瑰书页", "蝴蝶结甜点", "樱花冰饮", "粉瓷茶具",
            "玫瑰书页·亮", "蝴蝶结甜点·亮", "樱花冰饮·亮", "粉瓷茶具·亮"
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

    /** 取某角标当前选定的内置索引；-1 表示未启用内置图（走字符或自定义 URI）。 */
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
