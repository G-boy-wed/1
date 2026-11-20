package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 掌心窗「可更改且重要」的功能角标 —— 单一事实来源。
 *
 * 每一项都对应掌心窗真实存在、可点开更改的功能（设置页的真实分区或主题切换），
 * 绝不塞入「消息 / 图片 / 联系人 / 便签」这类无关的系统应用快捷方式。
 * 支持：换图标 / 改名 / 排序 / 恢复默认，全部落 SharedPreferences。
 */
public final class DeskFeatureStore {
    private static final String PREF = "desk_features_v1";
    private static final String K_ORDER = "order";   // [id,...]
    private static final String K_ICONS = "icons";   // {id: icon}
    private static final String K_NAMES = "names";   // {id: name}

    public static class Feature {
        public final String id;
        public final String defName;
        public final String defIcon;
        public final String action;
        public String name;   // 用户可改
        public String icon;   // 用户可改
        Feature(String id, String name, String icon, String action) {
            this.id = id; this.defName = name; this.defIcon = icon; this.action = action;
            this.name = name; this.icon = icon;
        }
    }

    /** 可选图标池（单字符，渲染稳定）。 */
    public static final String[] ICONS = {
            "✦", "◷", "▦", "☷", "☻", "♡", "☁", "⌂", "✉", "▤", "♫", "◎",
            "✎", "∞", "★", "⚑", "⏰", "☾", "☀", "♧", "⚙", "✂", "⚡", "❀",
            "◈", "◐", "⛨", "✧", "❂", "⌁"
    };

    /** 16 个真实可更改功能，顺序即默认排列。 */
    private static final List<Feature> DEFS = new ArrayList<>();
    static {
        DEFS.add(new Feature("checkin",     "查岗",   "◎", "policy:checkin"));
        DEFS.add(new Feature("emotion",     "情绪感知", "♡", "policy:emotion"));
        DEFS.add(new Feature("initiative",  "主动找",  "✉", "policy:initiative"));
        DEFS.add(new Feature("discipline",  "训诫",   "✂", "policy:discipline"));
        DEFS.add(new Feature("distraction", "分心查岗", "⚡", "policy:distraction"));
        DEFS.add(new Feature("appgate",     "应用门禁", "⚑", "policy:appgate"));
        DEFS.add(new Feature("reminder",    "主动提醒", "⏰", "policy:reminder"));
        DEFS.add(new Feature("schedule",    "课表作息", "▤", "policy:schedule"));
        DEFS.add(new Feature("memory",      "共同记忆", "★", "policy:memory"));
        DEFS.add(new Feature("ocr",         "识图感知", "☷", "policy:ocr"));
        DEFS.add(new Feature("weather",     "天气地区", "☁", "policy:weather"));
        DEFS.add(new Feature("homemode",    "回家模式", "⌂", "policy:homemode"));
        DEFS.add(new Feature("global",      "全局",   "⚙", "policy:global"));
        DEFS.add(new Feature("permissions", "权限",   "☻", "policy:permissions"));
        DEFS.add(new Feature("debug",       "调试",   "✎", "policy:debug"));
        DEFS.add(new Feature("theme",       "主题",   "❀", "theme:next"));
    }

    private DeskFeatureStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static Feature byId(String id) {
        for (Feature f : DEFS) if (f.id.equals(id)) return f;
        return null;
    }

    /** 返回按用户保存顺序排列的角标（含图标/名称覆盖），已去重、不丢项。 */
    public static List<Feature> all(Context c) {
        String orderRaw = sp(c).getString(K_ORDER, "");
        JSONObject icons = obj(c, K_ICONS);
        JSONObject names = obj(c, K_NAMES);
        List<String> order = new ArrayList<>();
        if (orderRaw != null && !orderRaw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(orderRaw);
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i, "");
                    if (byId(id) != null && !order.contains(id)) order.add(id);
                }
            } catch (Exception ignored) { }
        }
        // 补齐用户顺序里缺失的默认项（防止旧数据缺项导致功能消失）
        for (Feature f : DEFS) if (!order.contains(f.id)) order.add(f.id);

        List<Feature> out = new ArrayList<>();
        for (String id : order) {
            Feature def = byId(id);
            if (def == null) continue;
            Feature f = new Feature(def.id, def.defName, def.defIcon, def.action);
            String ic = icons.optString(id, "");
            String nm = names.optString(id, "");
            if (!ic.isEmpty()) f.icon = ic;
            if (!nm.isEmpty()) f.name = nm;
            out.add(f);
        }
        return out;
    }

    private static JSONObject obj(Context c, String key) {
        try { return new JSONObject(sp(c).getString(key, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static void saveObj(Context c, String key, JSONObject o) {
        sp(c).edit().putString(key, o.toString()).apply();
    }

    private static void saveOrder(Context c, List<Feature> list) {
        JSONArray arr = new JSONArray();
        for (Feature f : list) arr.put(f.id);
        sp(c).edit().putString(K_ORDER, arr.toString()).apply();
    }

    public static void setIcon(Context c, String id, String icon) {
        JSONObject o = obj(c, K_ICONS);
        try { o.put(id, icon); } catch (Exception ignored) { }
        saveObj(c, K_ICONS, o);
    }

    public static void setName(Context c, String id, String name) {
        JSONObject o = obj(c, K_NAMES);
        try { o.put(id, name); } catch (Exception ignored) { }
        saveObj(c, K_NAMES, o);
    }

    /** 把 from 位置的角标移动到 to 位置。 */
    public static void move(Context c, int from, int to) {
        List<Feature> list = all(c);
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size() || from == to) return;
        Feature f = list.remove(from);
        list.add(to, f);
        saveOrder(c, list);
    }

    /** 恢复默认图标/名称/顺序。 */
    public static void reset(Context c) {
        sp(c).edit().remove(K_ORDER).remove(K_ICONS).remove(K_NAMES).apply();
    }
}
