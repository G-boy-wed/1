package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 掌心窗「图1桌面」插件管理（单一事实来源）。
 *
 * 桌面上的每个插件卡都是一条可编辑记录：显示名、图标（emoji 或字符）、点击动作。
 * 全部以 JSON 落在 SharedPreferences，支持：新增 / 改名 / 换图标 / 移动位置 /
 * 删除 / 保存 / 重置默认。默认列表来自 {@link PluginRegistry} 去重后再补图1里的
 * 常用快捷，保证「全部插件不重复」。
 *
 * action 约定（点击时由 PalmDeskActivity 解析）：
 *   policy:<section>   打开 PolicySettingsActivity 并深链到 section
 *   page:music         切到「一起听」页
 *   page:guard         切到守护标签页
 *   page:classic       打开旧 MainActivity（完整功能）
 *   app:<包名>         打开系统应用
 *   none               仅展示
 */
public final class DeskPluginStore {
    private static final String PREF = "desk_plugins_v1";
    private static final String KEY_LIST = "list";

    public static class Plugin {
        public String id;
        public String label;
        public String icon;
        public String action;
        public String desc;
        public Plugin(String id, String label, String icon, String action, String desc) {
            this.id = id; this.label = label; this.icon = icon; this.action = action; this.desc = desc;
        }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("label", label); o.put("icon", icon);
                o.put("action", action); o.put("desc", desc);
            } catch (Exception ignored) { }
            return o;
        }
        static Plugin fromJson(JSONObject o) {
            return new Plugin(
                    o.optString("id", "p" + System.currentTimeMillis()),
                    o.optString("label", "插件"),
                    o.optString("icon", "✦"),
                    o.optString("action", "none"),
                    o.optString("desc", ""));
        }
    }

    /** 可选图标池：换图标时从这里循环 / 选取，全部为单字符，渲染稳定无需额外 drawable。 */
    public static final String[] ICONS = {
            "✦", "◷", "▦", "☷", "☻", "♡", "☁", "⌂", "✉", "▤", "♫", "◎",
            "✎", "∞", "★", "⚑", "⏰", "☾", "☀", "♧", "⚙", "✂", "⚡", "❀"
    };

    private DeskPluginStore() { }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 默认插件：图1常见快捷 + PluginRegistry 的全部能力，按 id 去重。 */
    public static List<Plugin> defaults() {
        List<Plugin> list = new ArrayList<>();
        list.add(new Plugin("messages", "消息", "✉", "app:com.tencent.mm", "打开消息"));
        list.add(new Plugin("calendar", "日历", "▦", "policy:calendar", "守护日历与纪念日"));
        list.add(new Plugin("photos", "图片", "❀", "app:com.android.gallery3d", "打开相册"));
        list.add(new Plugin("contacts", "联系人", "☻", "none", "通讯录"));
        list.add(new Plugin("notes", "便签", "✎", "page:classic", "打开掌心窗"));
        list.add(new Plugin("heart", "心动", "♡", "policy:emotion", "情绪感知与想你程度"));
        list.add(new Plugin("checkin", "查岗", "◎", "policy:checkin", "随机查岗与分级拉回"));
        list.add(new Plugin("gate", "门禁", "⚑", "policy:appgate", "应用门禁"));
        list.add(new Plugin("focus", "专注", "◷", "policy:focus", "全机专注模式"));
        list.add(new Plugin("music", "一起听", "♫", "page:music", "网易云同款共同听歌"));
        list.add(new Plugin("weather", "天气", "☁", "policy:weather", "当前位置天气"));
        list.add(new Plugin("guidian", "归电", "⚡", "policy:guidian", "归电敲门设置"));
        list.add(new Plugin("schedule", "课表", "▤", "policy:schedule", "课表作息"));
        list.add(new Plugin("memory", "记忆", "★", "policy:memory", "共同记忆"));
        list.add(new Plugin("discipline", "训诫", "✂", "policy:discipline", "温柔 / 严厉训诫"));
        list.add(new Plugin("reminder", "提醒", "⏰", "policy:reminder", "电量 / 喝水 / 休息提醒"));
        list.add(new Plugin("night", "夜间", "☾", "page:guard", "夜间息屏与守护"));
        list.add(new Plugin("ocr", "识图", "☷", "policy:ocr", "屏幕文字识别"));
        list.add(new Plugin("home", "回家", "⌂", "policy:homemode", "回家模式"));
        list.add(new Plugin("more", "更多", "⚙", "page:classic", "完整功能与设置"));
        return dedup(list);
    }

    private static List<Plugin> dedup(List<Plugin> in) {
        List<Plugin> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Plugin p : in) {
            if (p == null || p.id == null || seen.contains(p.id)) continue;
            seen.add(p.id);
            out.add(p);
        }
        return out;
    }

    /** 读取当前列表；首次调用写入默认。 */
    public static List<Plugin> all(Context ctx) {
        String raw = sp(ctx).getString(KEY_LIST, "");
        if (raw == null || raw.isEmpty()) {
            List<Plugin> def = defaults();
            save(ctx, def);
            return def;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            List<Plugin> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(Plugin.fromJson(arr.optJSONObject(i)));
            return dedup(list);
        } catch (Exception e) {
            List<Plugin> def = defaults();
            save(ctx, def);
            return def;
        }
    }

    /** 整表保存（编辑模式「保存」调用）。 */
    public static void save(Context ctx, List<Plugin> list) {
        JSONArray arr = new JSONArray();
        for (Plugin p : list) arr.put(p.toJson());
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static void add(Context ctx, String label, String icon, String action) {
        List<Plugin> list = all(ctx);
        String id = "custom_" + System.currentTimeMillis();
        list.add(new Plugin(id, safe(label, "新插件"), safe(icon, "✦"), safe(action, "none"), "自定义插件"));
        save(ctx, list);
    }

    public static void update(Context ctx, String id, String label, String icon, String action) {
        List<Plugin> list = all(ctx);
        for (Plugin p : list) if (p.id.equals(id)) {
            p.label = safe(label, p.label); p.icon = safe(icon, p.icon); p.action = safe(action, p.action);
        }
        save(ctx, list);
    }

    public static void remove(Context ctx, String id) {
        List<Plugin> list = all(ctx);
        java.util.Iterator<Plugin> it = list.iterator();
        while (it.hasNext()) if (it.next().id.equals(id)) it.remove();
        save(ctx, list);
    }

    /** 位置交换：把 from 位置的插件移动到 to 位置。 */
    public static void move(Context ctx, int from, int to) {
        List<Plugin> list = all(ctx);
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size() || from == to) return;
        Plugin p = list.remove(from);
        list.add(to, p);
        save(ctx, list);
    }

    /** 恢复默认。 */
    public static void reset(Context ctx) {
        List<Plugin> def = defaults();
        save(ctx, def);
    }

    private static String safe(String s, String fallback) {
        return (s == null || s.trim().isEmpty()) ? fallback : s.trim();
    }
}
