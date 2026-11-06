package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 掌心窗共同记忆插件（对应 PRD P1.6）。
 *
 *  - 在掌心窗内落地「总结与记忆」能力；双方均可查看、新增、编辑、删除；
 *  - 支持双向修改并实时生效（手机端读写与 MCP 下发同步到同一份本机存储）；
 *  - 数据只存本机，不上云。
 */
public final class SharedMemoryState {

    private static final String PREFS = "shared_memory_store";
    private static final String KEY_ITEMS = "items_v1";
    private static final String KEY_ENABLED = "shared_memory_enabled";
    private static final int MAX_ITEMS = 200;

    private SharedMemoryState() { }

    private static SharedPreferences sp(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ============ 共同记忆总开关（守护页卡片可实际操作）============
    // 关闭后：进入只读保护——新增/编辑/删除一律拒绝（本机与 MCP 均不可写入），
    // 已有记忆不删除、可正常查看；重新打开即恢复写入。
    public static boolean enabled(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_ENABLED, true); } catch (Exception e) { return true; }
    }

    public static void setEnabled(Context ctx, boolean v) {
        try {
            sp(ctx).edit().putBoolean(KEY_ENABLED, v).commit();
            DebugState.append(ctx, v ? "共同记忆已开启：恢复写入" : "共同记忆已关闭：只读保护（不可新增/编辑/删除）");
        } catch (Exception ignored) { }
    }

    /** 全部记忆条目（最新在前）。 */
    public static JSONArray getItems(Context ctx) {
        try { return new JSONArray(sp(ctx).getString(KEY_ITEMS, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static String newId() { return String.valueOf(System.currentTimeMillis()); }

    /** 新增一条记忆（author 标识是谁写的）。 */
    public static JSONObject add(Context ctx, String text, String author) {
        JSONObject o = new JSONObject();
        try {
            text = text == null ? "" : text.trim();
            if (text.isEmpty()) return new JSONObject().put("ok", false).put("error", "empty_text");
            long now = System.currentTimeMillis();
            o.put("id", newId());
            o.put("text", text);
            o.put("author", author == null || author.isEmpty() ? "用户" : author);
            o.put("created_at_ms", now);
            o.put("updated_at_ms", now);
            o.put("created_at", iso(now));
            JSONArray arr = getItems(ctx);
            JSONArray next = new JSONArray();
            next.put(o);
            for (int i = 0; i < arr.length() && next.length() < MAX_ITEMS; i++) next.put(arr.optJSONObject(i));
            sp(ctx).edit().putString(KEY_ITEMS, next.toString()).commit();
            DebugState.append(ctx, "共同记忆新增：" + text);
        } catch (Exception e) {
            try { o.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return o;
    }

    /** 编辑一条记忆（按 id）。 */
    public static boolean edit(Context ctx, String id, String text) {
        try {
            JSONArray arr = getItems(ctx);
            JSONArray next = new JSONArray();
            boolean found = false;
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optString("id", "").equals(id)) {
                    o.put("text", text == null ? o.optString("text", "") : text.trim());
                    o.put("updated_at_ms", now);
                    found = true;
                }
                next.put(o);
            }
            if (!found) return false;
            sp(ctx).edit().putString(KEY_ITEMS, next.toString()).commit();
            DebugState.append(ctx, "共同记忆编辑：" + id);
            return true;
        } catch (Exception e) { return false; }
    }

    /** 删除一条记忆（按 id）。返回是否删除成功。 */
    public static boolean remove(Context ctx, String id) {
        try {
            JSONArray arr = getItems(ctx);
            JSONArray next = new JSONArray();
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optString("id", "").equals(id)) { found = true; continue; }
                next.put(o);
            }
            if (!found) return false;
            sp(ctx).edit().putString(KEY_ITEMS, next.toString()).commit();
            DebugState.append(ctx, "共同记忆删除：" + id);
            return true;
        } catch (Exception e) { return false; }
    }

    /** 命令路由：get/add/edit/remove 共同记忆。 */
    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        try {
            String action = cmd.optString("action", "");
            if ("get_shared_memory".equals(action) || "list_shared_memory".equals(action)) {
                return out.put("ok", true).put("result", "shared_memory").put("items", getItems(ctx))
                        .put("enabled", enabled(ctx));
            }
            if (!enabled(ctx)) {
                return out.put("ok", false).put("result", "shared_memory_disabled")
                        .put("error", "共同记忆开关已关闭（只读保护），请在守护页打开后再写入");
            }
            if ("add_shared_memory".equals(action) || "add_memory".equals(action)) {
                String author = cmd.optString("author", AppPrefs.userName(ctx));
                JSONObject added = add(ctx, cmd.optString("text", cmd.optString("content", "")), author);
                boolean ok = added.optBoolean("ok", false);
                return out.put("ok", ok).put("result", ok ? "memory_added" : added.optString("error", "add_failed"));
            }
            if ("edit_shared_memory".equals(action)) {
                boolean ok = edit(ctx, cmd.optString("id", ""), cmd.optString("text", ""));
                return out.put("ok", ok).put("result", ok ? "memory_edited" : "not_found");
            }
            if ("remove_shared_memory".equals(action)) {
                boolean ok = remove(ctx, cmd.optString("id", ""));
                return out.put("ok", ok).put("result", ok ? "memory_removed" : "not_found");
            }
            return out.put("ok", false).put("error", "unknown_shared_memory_action");
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
            return out;
        }
    }

    public static String pretty(Context ctx) {
        try {
            JSONArray arr = getItems(ctx);
            if (arr.length() == 0) return "共同记忆为空，点「新增记忆」写下你们的故事。";
            StringBuilder sb = new StringBuilder("共同记忆（共 ").append(arr.length()).append(" 条）：\n");
            int show = Math.min(arr.length(), 20);
            for (int i = 0; i < show; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                sb.append("· [").append(o.optString("author", "")).append("] ")
                  .append(o.optString("text", "")).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) { return "记忆读取失败：" + ScreenshotService.shortMsg(e); }
    }

    private static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }
}
