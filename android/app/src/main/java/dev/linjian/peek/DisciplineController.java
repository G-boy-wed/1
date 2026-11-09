package dev.linjian.peek;

import android.content.Context;

import org.json.JSONObject;

/**
 * 训诫管束模块集中控制器（对应 PRD 模块一）。
 *
 *  - 多档位管束（0~4 档）与双语气（gentle 温柔 / strict 严厉），由 PolicyStore 持久化；
 *  - 陪伴对象可直出训诫文案（discipline_text），不强制兜底；
 *  - 触发训诫：强行弹出「不可直接滑掉」的居中弹窗（仅按钮可关闭）+ 震动；
 *  - strict 模式 / 中档及以上（level>=3 强制强弹）联动应用门禁，把当前非白名单前台应用切入临时休息/锁屏；
 *  - 通过 handleCommand 接入后端命令：set_discipline_mode / get_discipline_state / trigger_discipline。
 */
public final class DisciplineController {

    private DisciplineController() { }

    /** 设置训诫模式（档位、语气、陪伴对象直出文案）。 */
    public static void setMode(Context ctx, int level, String mode, String text) {
        PolicyStore.setDisciplineLevel(ctx, level);
        PolicyStore.setDisciplineMode(ctx, mode);
        if (text != null) PolicyStore.setDisciplineText(ctx, text);
        DebugState.append(ctx, "训诫模式已设置：level=" + level + " mode=" + normalizedMode(mode));
    }

    /** 当前训诫配置快照（供状态层 / MCP 读取）。 */
    public static JSONObject config(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", PolicyStore.disciplineEnabled(ctx));
            o.put("level", PolicyStore.disciplineLevel(ctx));
            o.put("mode", PolicyStore.disciplineMode(ctx));
            o.put("text", PolicyStore.disciplineText(ctx));
            o.put("force_strong", PolicyStore.disciplineForceStrong(ctx));
            o.put("user_only", PolicyStore.disciplineUserOnly(ctx));
            o.put("badge", PolicyStore.disciplineBadge(ctx));
        } catch (Exception ignored) { }
        return o;
    }

    /**
     * 触发一次训诫：不可滑掉居中弹窗 + 震动；strict/强档联动门禁锁当前前台。
     * 返回执行结果 JSON。
     */
    public static JSONObject trigger(Context ctx, String reason) {
        JSONObject out = new JSONObject();
        try {
            if (!PolicyStore.disciplineEnabled(ctx)) {
                return out.put("ok", false).put("result", "discipline_disabled");
            }
            String text = PolicyStore.disciplineText(ctx);
            if (text == null || text.trim().isEmpty()) text = defaultText(ctx, reason);
            else if (reason != null && !reason.trim().isEmpty()) text = text + "\n（" + reason + "）";

            boolean strong = "strict".equals(PolicyStore.disciplineMode(ctx)) || PolicyStore.disciplineLevel(ctx) >= 2;
            String tone = strong ? "angry" : "gentle";
            // 不可直接滑掉的居中弹窗：仅按钮可关闭（Overlay 不响应外部滑掉），并伴随震动。
            CompanionService.showCenterAlert(ctx, "训诫", text, tone, "我错了", "训诫管束", true);
            out.put("ok", true).put("result", "discipline_shown")
                    .put("level", PolicyStore.disciplineLevel(ctx))
                    .put("mode", PolicyStore.disciplineMode(ctx));

            // strict / 强档联动应用门禁：把当前非白名单前台应用切入临时休息/锁屏。
            if (PolicyStore.disciplineForceStrong(ctx)) {
                String pkg = ScreenshotService.currentPackage();
                if (pkg != null && !pkg.trim().isEmpty()) {
                    JSONObject lock = AppGate.handleCommand(ctx, new JSONObject()
                            .put("action", "lock_app")
                            .put("package", pkg)
                            .put("mode", "strict")
                            .put("reason", "训诫管束：违反作息/沉迷/拒归电")
                            .put("minutes", 30));
                    out.put("gate_locked", lock.optBoolean("ok", false));
                    out.put("gate_result", lock.optString("result", ""));
                }
            }
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    /** 命令路由。 */
    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        try {
            String action = cmd.optString("action", "get_discipline_state");
            if ("get_discipline_state".equals(action)) {
                return config(ctx).put("ok", true);
            }
            if ("set_discipline_mode".equals(action)) {
                JSONObject p = cmd.optJSONObject("payload");
                if (p == null) p = cmd;
                int level = clamp(p.optInt("level", PolicyStore.disciplineLevel(ctx)), 0, 4);
                String mode = normalizedMode(p.optString("mode", PolicyStore.disciplineMode(ctx)));
                String text = p.optString("text", null);
                if (text != null && text.trim().isEmpty()) text = null;
                if (p.has("enabled")) PolicyStore.setDisciplineEnabled(ctx, p.optBoolean("enabled", true));
                PolicyStore.setDisciplineLevel(ctx, level);
                PolicyStore.setDisciplineMode(ctx, mode);
                if (text != null) PolicyStore.setDisciplineText(ctx, text);
                DebugState.append(ctx, "训诫模式由 MCP 设置：level=" + level + " mode=" + mode);
                return config(ctx).put("ok", true).put("result", "discipline_saved");
            }
            if ("trigger_discipline".equals(action)) {
                return trigger(ctx, cmd.optString("reason", ""));
            }
            return out.put("ok", false).put("error", "unknown_discipline_action");
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
            return out;
        }
    }

    private static String defaultText(Context ctx, String reason) {
        String u = AppPrefs.userName(ctx);
        String c = AppPrefs.companionName(ctx);
        String base = u + "，你又没听话。";
        if (reason != null && !reason.trim().isEmpty()) base += "（" + reason + "）";
        return base + " " + c + "在看着你，现在按规矩来。";
    }

    private static String normalizedMode(String raw) {
        return "strict".equalsIgnoreCase(raw) ? "strict" : "gentle";
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
