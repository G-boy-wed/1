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
            String mode = PolicyStore.disciplineMode(ctx);
            o.put("enabled", PolicyStore.disciplineEnabled(ctx));
            o.put("level", PolicyStore.disciplineLevel(ctx));
            o.put("mode", mode);
            // Bug#1：text 不再直接吐空串。未配置自定义文案时，按 mode 返回可渲染的兜底文案，
            // 前端共同拿到 text + tone_badge，就能把「严厉 / 温柔」渲染成两种明显不同的弹窗。
            String custom = PolicyStore.disciplineText(ctx);
            o.put("custom_text", custom == null ? "" : custom);
            o.put("text", resolveDisciplineText(ctx, null));
            o.put("tone_badge", toneBadge(ctx));
            o.put("tone_label", "strict".equals(mode) ? "严厉管束" : "温柔提醒");
            o.put("text_is_fallback", custom == null || custom.trim().isEmpty());
            o.put("force_strong", PolicyStore.disciplineForceStrong(ctx));
            o.put("user_only", PolicyStore.disciplineUserOnly(ctx));
            o.put("badge", PolicyStore.disciplineBadge(ctx));
        } catch (Exception ignored) { }
        return o;
    }

    /**
     * Bug#1：统一文案解析（对应需求里的 resolveDisciplineText）。
     *  - 陪伴对象配置过自定义文案 → 用它，有 reason 时附加一行说明；
     *  - 未配置 → 按 mode 兜底：strict 用强硬话术，gentle 用温柔话术；
     *  - customReason 非空时以「【原因】」开头，保证弹窗永远有内容、且力度可感知。
     */
    public static String resolveDisciplineText(Context ctx, String customReason) {
        String configured = PolicyStore.disciplineText(ctx);
        String reason = customReason == null ? "" : customReason.trim();
        if (configured != null && !configured.trim().isEmpty()) {
            return reason.isEmpty() ? configured : configured + "\n（" + reason + "）";
        }
        String reasonText = reason.isEmpty() ? "" : "【" + reason + "】\n";
        String name = AppPrefs.userName(ctx);
        if (name == null || name.trim().isEmpty()) name = "顾钰";
        if ("strict".equals(PolicyStore.disciplineMode(ctx))) {
            return reasonText + name + "，立刻把手机放下。不是在跟你商量，现在回我身边来。";
        }
        return reasonText + "宝宝乖，先不看了好不好？眼睛该酸了，过来抱一下。";
    }

    /** Bug#1：语气标签，前端把它拼进弹窗标题，令「严厉 / 温柔」一眼可辨。 */
    public static String toneBadge(Context ctx) {
        return "strict".equals(PolicyStore.disciplineMode(ctx)) ? "【严厉管束】" : "【温柔提醒】";
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
            // Bug#1：兜底文案 + 语气标签统一由 resolveDisciplineText / toneBadge 提供，
            // 弹窗标题带标签，令 strict 与 gentle 视觉上完全不同（不再长得一模一样）。
            String text = resolveDisciplineText(ctx, reason);
            String mode = PolicyStore.disciplineMode(ctx);
            boolean strictMode = "strict".equals(mode);
            boolean strong = strictMode || PolicyStore.disciplineLevel(ctx) >= 2;
            String tone = strong ? "angry" : "gentle";
            String title = toneBadge(ctx) + " 掌心窗管束";
            // 不可直接滑掉的居中弹窗：仅按钮可关闭（Overlay 不响应外部滑掉），并伴随震动。
            CompanionService.showCenterAlert(ctx, title, text, tone, "我错了",
                    "训诫管束·" + (strictMode ? "严厉" : "温柔"), true);
            out.put("ok", true).put("result", "discipline_shown")
                    .put("level", PolicyStore.disciplineLevel(ctx))
                    .put("mode", mode)
                    .put("tone_badge", toneBadge(ctx))
                    .put("text", text);

            // Bug#1：strict 档位额外联动（弹窗 + 震动 + 门禁把前台非白名单 App 切入休息页）。
            // 原来只有 level>=3 才联动；现在只要语气是 strict 就联动，符合「力度可感知」诉求。
            if (strictMode || PolicyStore.disciplineForceStrong(ctx)) {
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

    /** 保留旧入口：统一走 resolveDisciplineText，避免两处兜底文案各自为政。 */
    private static String defaultText(Context ctx, String reason) {
        return resolveDisciplineText(ctx, reason);
    }

    private static String normalizedMode(String raw) {
        return "strict".equalsIgnoreCase(raw) ? "strict" : "gentle";
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
