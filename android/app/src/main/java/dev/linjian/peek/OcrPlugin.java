package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 自主识图链路（对应 PRD P2.7）。
 *
 * 不单一依赖主模型远程视觉推理：截屏后优先用本地轻量结构化提取，
 * 从「读屏节点树」里直接拿到关键文字与界面状态（包名、可见文本、可点击标签、时间等），
 * 回传结构化结果；远程 OCR / 视觉推理作为可选增强通道，失败也不影响本地结果。
 */
public final class OcrPlugin {

    private OcrPlugin() { }

    /**
     * 本地结构化提取当前屏幕。
     * 来源：ScreenshotService 维护的读屏节点树（screenNodesJson）+ 当前前台包名。
     */
    public static JSONObject extractLocal(Context ctx) {
        JSONObject out = new JSONObject();
        // 尊重用户开关：关闭时直接返回「未启用」，不强制读取
        if (!PolicyStore.ocrEnabled(ctx)) {
            try {
                out.put("ok", false);
                out.put("enabled", false);
                out.put("text", "识图感知已关闭（可在「查岗与管束 → 识图感知」中开启）");
            } catch (Exception ignored) { }
            return out;
        }
        try {
            out.put("ok", true);
            out.put("source", "local_screen_nodes");
            String pkg = ScreenshotService.currentPackage();
            out.put("package", pkg);
            out.put("app_label", AppGate.labelOf(ctx, pkg));
            String screenText = ScreenshotService.screenText();
            out.put("screen_text", screenText == null ? "" : screenText);

            JSONArray nodes = new JSONArray();
            try { nodes = new JSONArray(ScreenshotService.screenNodesJson()); } catch (Exception ignored) { }
            List<String> texts = new ArrayList<>();
            List<String> clickables = new ArrayList<>();
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject n = nodes.optJSONObject(i);
                if (n == null) continue;
                String t = n.optString("text", "").trim();
                boolean clickable = n.optBoolean("clickable", false);
                boolean visible = n.optBoolean("visible", true);
                if (!visible) continue;
                if (t.length() > 0 && t.length() <= 200) {
                    texts.add(t);
                    if (clickable && clickables.size() < 40) clickables.add(t);
                }
            }
            // 最多保留 60 条可见文本，避免过大
            JSONArray visibleTexts = new JSONArray();
            for (int i = 0; i < Math.min(texts.size(), 60); i++) visibleTexts.put(texts.get(i));
            out.put("visible_texts", visibleTexts);
            out.put("clickable_labels", new JSONArray(clickables));
            out.put("node_count", nodes.length());
            // 直接抽取看起来像时间的文本，便于作息/课表类判断
            JSONArray times = new JSONArray();
            for (String s : texts) {
                if (s.matches(".*\\b([01]?\\d|2[0-3]):[0-5]\\d\\b.*")) times.put(s);
            }
            out.put("time_like", times);
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    /**
     * 可选远程增强：把结构化结果（含可见文本）发给服务器做视觉/语义推理。
     * best-effort，失败返回本地结果，不阻塞。
     */
    public static JSONObject analyze(Context ctx, String serverUrl, String token) {
        JSONObject local = extractLocal(ctx);
        if (serverUrl == null || serverUrl.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            try { local.put("remote", "skipped_no_server"); } catch (Exception ignored) { }
            return local;
        }
        try {
            String resp = CompanionService.postJsonSafe(serverUrl.replaceAll("/+$", "") + "/api/ocr/analyze", token, local);
            if (resp != null && !resp.isEmpty() && !resp.startsWith("ERROR")) {
                try {
                    JSONObject remote = new JSONObject(resp);
                    local.put("remote", remote);
                    local.put("remote_used", true);
                } catch (Exception ignored) { local.put("remote", "parse_failed"); }
            } else {
                local.put("remote", "unavailable");
            }
        } catch (Exception e) {
            try { local.put("remote", "error:" + ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return local;
    }

    /** 命令路由。 */
    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        try {
            boolean remote = cmd.optBoolean("remote", false);
            String server = AppPrefs.server(ctx), token = AppPrefs.token(ctx);
            JSONObject res = remote ? analyze(ctx, server, token) : extractLocal(ctx);
            out.put("ok", res.optBoolean("ok", false));
            out.put("result", "ocr_extract");
            out.put("data", res);
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }
}
