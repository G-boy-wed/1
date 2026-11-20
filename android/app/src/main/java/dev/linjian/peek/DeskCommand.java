package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 桌面配置的机端命令处理器（MCP → 手机）。
 *
 * 让「掌心窗桌面上的东西」也能被机端（TA 通过 MCP）更改，与手机端形成闭环：
 *  - desk_set_badge_icon  : 给功能角标设置内置粉白图标 / 字符图标 / 清自定义上传图
 *  - desk_set_badge_name  : 改功能角标名字
 *  - desk_set_old_icon    : 给旧功能入口设置内置图标
 *  - desk_set_old_name    : 改旧功能入口名字
 *  - desk_set_page_bg     : 设置某页背景纯色（16 进制 RRGGBB）
 *  - desk_set_text        : 编辑桌面文字卡（名字/账号/简介/标题/便签等）
 *  - desk_reset           : 恢复默认摆放/图标/背景
 *  - desk_get_state       : 读取当前桌面配置快照
 *
 * 机端无法访问手机相册 URI，故图片类只支持内置图标索引；自定义上传仍由手机端完成。
 */
public final class DeskCommand {
    private DeskCommand() { }

    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        try {
            String action = cmd.optString("action", "");
            if ("desk_set_badge_icon".equals(action)) {
                String id = cmd.optString("id", "");
                if (cmd.has("builtin_index")) {
                    DeskIconPack.setIndex(ctx, id, cmd.optInt("builtin_index", 0));
                    out.put("builtin_index", DeskIconPack.indexOf(ctx, id));
                } else if (cmd.has("glyph")) {
                    DeskFeatureStore.setIcon(ctx, id, cmd.optString("glyph", "★"));
                    DeskIconPack.resetOne(ctx, id);
                } else if (cmd.optBoolean("clear_upload", false)) {
                    DeskAssetStore.clearFeatureIconUri(ctx, id);
                } else {
                    return out.put("ok", false).put("error", "missing_icon_arg");
                }
                out.put("ok", true).put("id", id);
            } else if ("desk_set_badge_name".equals(action)) {
                String id = cmd.optString("id", "");
                String name = cmd.optString("name", "").trim();
                if (id.isEmpty() || name.isEmpty()) return out.put("ok", false).put("error", "missing_id_or_name");
                DeskFeatureStore.setName(ctx, id, name);
                out.put("ok", true).put("id", id).put("name", name);
            } else if ("desk_set_old_icon".equals(action)) {
                String id = cmd.optString("id", "");
                if (cmd.has("builtin_index")) DeskIconPack.setIndex(ctx, "old_" + id, cmd.optInt("builtin_index", 0));
                else if (cmd.has("glyph")) { OldEntryStore.setIcon(ctx, id, cmd.optString("glyph", "★")); DeskIconPack.resetOne(ctx, "old_" + id); }
                else return out.put("ok", false).put("error", "missing_icon_arg");
                out.put("ok", true).put("id", id);
            } else if ("desk_set_old_name".equals(action)) {
                String id = cmd.optString("id", "");
                String name = cmd.optString("name", "").trim();
                if (id.isEmpty() || name.isEmpty()) return out.put("ok", false).put("error", "missing_id_or_name");
                OldEntryStore.setName(ctx, id, name);
                out.put("ok", true).put("id", id).put("name", name);
            } else if ("desk_set_page_bg".equals(action)) {
                int page = cmd.optInt("page", 0);
                String color = cmd.optString("color", "").trim();
                if (page < 0 || page > 3) return out.put("ok", false).put("error", "page_out_of_range");
                if (!color.isEmpty()) {
                    try {
                        int c = (int) Long.parseLong(color.replace("#", ""), 16);
                        if (color.length() <= 6) c |= 0xFF000000;
                        DeskSkinStore.setPageBgUri(ctx, page, "");
                        DeskSkinStore.setPageBgColor(ctx, page, c);
                        out.put("ok", true).put("page", page).put("color", String.format("#%06X", c & 0xFFFFFF));
                    } catch (Exception e) {
                        return out.put("ok", false).put("error", "bad_color");
                    }
                } else {
                    DeskSkinStore.setPageBgUri(ctx, page, "");
                    DeskSkinStore.setPageBgColor(ctx, page, 0);
                    out.put("ok", true).put("page", page).put("color", "default");
                }
            } else if ("desk_set_text".equals(action)) {
                String key = cmd.optString("key", "");
                String value = cmd.optString("value", "");
                if (key.isEmpty()) return out.put("ok", false).put("error", "missing_key");
                DeskSkinStore.setText(ctx, key, value);
                out.put("ok", true).put("key", key).put("value", value);
            } else if ("desk_reset".equals(action)) {
                String scope = cmd.optString("scope", "all");
                if ("layout".equals(scope)) DeskLayoutStore.resetAll(ctx);
                else if ("icons".equals(scope)) { DeskIconPack.resetAll(ctx); DeskAssetStore.clearAll(ctx); }
                else if ("bg".equals(scope)) { for (int p = 0; p < 4; p++) { DeskSkinStore.setPageBgUri(ctx, p, ""); DeskSkinStore.setPageBgColor(ctx, p, 0); } }
                else { DeskLayoutStore.resetAll(ctx); DeskIconPack.resetAll(ctx); DeskAssetStore.clearAll(ctx); DeskFeatureStore.reset(ctx); OldEntryStore.resetAll(ctx); }
                out.put("ok", true).put("scope", scope);
            } else if ("desk_get_state".equals(action)) {
                JSONArray badges = new JSONArray();
                for (DeskFeatureStore.Feature f : DeskFeatureStore.all(ctx)) {
                    JSONObject o = new JSONObject();
                    o.put("id", f.id).put("name", f.name).put("builtin_index", DeskIconPack.indexOf(ctx, f.id));
                    badges.put(o);
                }
                JSONArray olds = new JSONArray();
                for (OldEntryStore.Entry e : OldEntryStore.all(ctx)) {
                    JSONObject o = new JSONObject();
                    o.put("id", e.id).put("name", e.name).put("builtin_index", DeskIconPack.indexOf(ctx, "old_" + e.id));
                    olds.put(o);
                }
                out.put("ok", true).put("badges", badges).put("old_entries", olds)
                        .put("name", DeskSkinStore.text(ctx, "name", "掌心窗"))
                        .put("builtin_icon_count", DeskIconPack.COUNT);
            } else {
                return out.put("ok", false).put("error", "unknown_action").put("action", action);
            }
        } catch (Exception e) {
            try { out.put("ok", false).put("error", "exception").put("message", ScreenshotService.shortMsg(e)); }
            catch (Exception ignored) { }
        }
        return out;
    }
}
