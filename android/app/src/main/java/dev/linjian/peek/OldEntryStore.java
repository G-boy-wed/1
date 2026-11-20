package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 第四页「旧功能入口」角标的单一事实来源。
 *
 * 这些入口把用户找不到的旧版功能（陪伴 / TA 的日记 / MCP 连接设置 / 设置 /
 * 生活 / 守护 / 守护日历 / 归电）重新接回来——点击直达 MainActivity 对应页面。
 * 名称与字符图标可由用户长按覆盖；图片图标复用 DeskAssetStore；摆放复用 DeskLayoutStore。
 */
public final class OldEntryStore {
    private static final String PREF = "old_entries_v1";
    private static final String K_NAMES = "names";
    private static final String K_ICONS = "icons";

    public static final class Entry {
        public final String id;
        public final String defName;
        public final String defIcon;
        public final String action;
        public String name;
        public String icon;
        Entry(String id, String name, String icon, String action) {
            this.id = id; this.defName = name; this.defIcon = icon; this.action = action;
            this.name = name; this.icon = icon;
        }
    }

    /**
     * 第四页只保留两个真正的「设置 + 日记本」入口。
     * 陪伴/守护/生活/日历/归电等已作为主界面功能角标或陪伴展示卡直接呈现，
     * 不再在此重复；连接设置并入「设置」页内。
     */
    private static final List<Entry> DEFS = new ArrayList<>();
    static {
        DEFS.add(new Entry("settings", "设置",   "☻", "old:settings"));
        DEFS.add(new Entry("diary",    "日记本", "✎", "old:diary"));
    }

    /** 默认页：两个入口都在第四页。 */
    public static int defaultPage() { return 3; }

    private OldEntryStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    private static JSONObject obj(Context c, String key) {
        try { return new JSONObject(sp(c).getString(key, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }
    private static void saveObj(Context c, String key, JSONObject o) {
        sp(c).edit().putString(key, o.toString()).apply();
    }

    public static List<Entry> all(Context c) {
        JSONObject names = obj(c, K_NAMES);
        JSONObject icons = obj(c, K_ICONS);
        List<Entry> out = new ArrayList<>();
        for (Entry def : DEFS) {
            Entry e = new Entry(def.id, def.defName, def.defIcon, def.action);
            String nm = names.optString(def.id, "");
            String ic = icons.optString(def.id, "");
            if (!nm.isEmpty()) e.name = nm;
            if (!ic.isEmpty()) e.icon = ic;
            out.add(e);
        }
        return out;
    }

    public static void setName(Context c, String id, String name) {
        JSONObject o = obj(c, K_NAMES);
        try { o.put(id, name); } catch (Exception ignored) { }
        saveObj(c, K_NAMES, o);
    }

    public static void setIcon(Context c, String id, String icon) {
        JSONObject o = obj(c, K_ICONS);
        try { o.put(id, icon); } catch (Exception ignored) { }
        saveObj(c, K_ICONS, o);
    }

    public static void resetOne(Context c, String id) {
        JSONObject n = obj(c, K_NAMES), i = obj(c, K_ICONS);
        try { n.remove(id); i.remove(id); } catch (Exception ignored) { }
        saveObj(c, K_NAMES, n);
        saveObj(c, K_ICONS, i);
        DeskAssetStore.clearFeatureIconUri(c, "old_" + id);
    }

    public static void resetAll(Context c) {
        sp(c).edit().remove(K_NAMES).remove(K_ICONS).apply();
    }

    /** 供角标点击后解析 id（图片图标 key 用 old_ 前缀，避免与功能角标冲突）。 */
    public static String assetKey(Entry e) { return "old_" + e.id; }
}
