package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 训诫管束模块集中控制器（对应 PRD 模块一）。
 *
 *  - 多档位管束（0~4 档）与双语气（gentle 温柔 / strict 严厉），由 PolicyStore 持久化；
 *  - [DISC-PHRASE-POOL] 训诫话术来自 MCP 写入的动态话术池（温柔池/严厉池分开），
 *    弹窗按当前模式从对应池随机取整句；提示词不硬编码在底代码。已移除自定义文案。
 *  - 触发训诫：强行弹出「不可直接滑掉」的居中弹窗（仅按钮可关闭）+ 震动；
 *  - strict 模式 / 中档及以上（level>=3 强制强弹）联动应用门禁；
 *  - 通过 handleCommand 接入：set_discipline_mode / get_discipline_state / trigger_discipline / sync_discipline_phrases。
 */
public final class DisciplineController {

    // [DISC-PHRASE-POOL] 手机端话术池镜像（服务端下发权威数据，离线也能取用）。
    private static final String POOL_PREFS = "peek_disc_phrases";
    private static final String KEY_GENTLE = "gentle";
    private static final String KEY_STRICT = "strict";

    private DisciplineController() { }

    /** 设置训诫模式（档位、语气）。自定义文案已移除。 */
    public static void setMode(Context ctx, int level, String mode) {
        PolicyStore.setDisciplineLevel(ctx, level);
        PolicyStore.setDisciplineMode(ctx, mode);
        DebugState.append(ctx, "训诫模式已设置：level=" + level + " mode=" + normalizedMode(mode));
    }

    // ===== [DISC-PHRASE-POOL] 动态话术池：手机端镜像读写 =====
    private static SharedPreferences poolPrefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE);
    }

    /** 从服务端同步话术池（MCP 下发）。 */
    public static void syncPhrases(Context ctx, JSONArray gentle, JSONArray strict) {
        poolPrefs(ctx).edit().putString(KEY_GENTLE, gentle == null ? "[]" : gentle.toString())
                .putString(KEY_STRICT, strict == null ? "[]" : strict.toString()).apply();
        DebugState.append(ctx, "训诫话术池已同步：温柔=" + (gentle == null ? 0 : gentle.length())
                + " 严厉=" + (strict == null ? 0 : strict.length()));
    }

    private static String[] phrasePool(Context ctx, boolean strict) {
        String json = poolPrefs(ctx).getString(strict ? KEY_STRICT : KEY_GENTLE, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i, "").trim();
            return out;
        } catch (Exception e) { return new String[0]; }
    }

    /** 取一句话术：整句语气区分，不连续重复。 */
    private static String pickPhrase(Context ctx, boolean strict) {
        String[] pool = phrasePool(ctx, strict);
        if (pool.length == 0) return "";
        if (pool.length == 1) return pool[0];
        int idx = (int) (Math.random() * pool.length);
        return pool[idx];
    }

    /** 当前训诫配置快照（供状态层 / MCP 读取）。 */
    public static JSONObject config(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            String mode = PolicyStore.disciplineMode(ctx);
            o.put("enabled", PolicyStore.disciplineEnabled(ctx));
            o.put("level", PolicyStore.disciplineLevel(ctx));
            o.put("mode", mode);
            // [DISC-PHRASE-POOL] 文案来自动态话术池，无自定义文案字段。
            boolean useStrict = "strict".equals(mode) || PolicyStore.disciplineLevel(ctx) >= 4;
            o.put("text", resolveDisciplineText(ctx, null));
            o.put("tone_badge", toneBadge(ctx));
            o.put("tone_label", useStrict ? "严厉管束" : "温柔提醒");
            o.put("text_is_fallback", phrasePool(ctx, useStrict).length == 0);
            o.put("phrase_count_gentle", phrasePool(ctx, false).length);
            o.put("phrase_count_strict", phrasePool(ctx, true).length);
            o.put("force_strong", PolicyStore.disciplineForceStrong(ctx));
            o.put("user_only", PolicyStore.disciplineUserOnly(ctx));
            o.put("badge", PolicyStore.disciplineBadge(ctx));
        } catch (Exception ignored) { }
        return o;
    }

    /**
     * [DISC-PHRASE-POOL] 统一文案解析：从动态话术池按当前模式取整句。
     *  - 档位>=4 强制用严厉池（最高档=最严厉，解决"选最高档仍最温柔"）；
     *  - 其余按 mode 选温柔池/严厉池；整句话语气区分，不加固定后缀；
     *  - 池为空时用极简中性兜底（不硬编码任何温柔/严厉话术库），提示通过 MCP 写入话术；
     *  - customReason 非空时以「【原因】」开头。
     */
    public static String resolveDisciplineText(Context ctx, String customReason) {
        String reason = customReason == null ? "" : customReason.trim();
        String reasonText = reason.isEmpty() ? "" : "【" + reason + "】\n";
        boolean useStrict = "strict".equals(PolicyStore.disciplineMode(ctx)) || PolicyStore.disciplineLevel(ctx) >= 4;
        String phrase = pickPhrase(ctx, useStrict);
        if (phrase.isEmpty()) {
            // 中性兜底：不写死温柔/严厉话术；提示通过 MCP 的话术池工具写入。
            return reasonText + "掌心窗提醒你回到身边（尚未配置管束话术，可让陪伴对象用 add_discipline_phrase 写入温柔/严厉话术）。";
        }
        return reasonText + phrase;
    }

    /** 语气标签：按实际取用的池给出（档位>=4 或 strict 模式=严厉）。 */
    public static String toneBadge(Context ctx) {
        boolean useStrict = "strict".equals(PolicyStore.disciplineMode(ctx)) || PolicyStore.disciplineLevel(ctx) >= 4;
        return useStrict ? "【严厉管束】" : "【温柔提醒】";
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
                if (p.has("enabled")) PolicyStore.setDisciplineEnabled(ctx, p.optBoolean("enabled", true));
                PolicyStore.setDisciplineLevel(ctx, level);
                PolicyStore.setDisciplineMode(ctx, mode);
                // [DISC-PHRASE-POOL] 自定义文案已移除；话术统一由动态话术池提供。
                DebugState.append(ctx, "训诫模式由 MCP 设置：level=" + level + " mode=" + mode);
                return config(ctx).put("ok", true).put("result", "discipline_saved");
            }
            if ("sync_discipline_phrases".equals(action)) {
                // [DISC-PHRASE-POOL] 服务端下发权威话术池（温柔/严厉两池）到手机端镜像。
                JSONObject p = cmd.optJSONObject("payload");
                if (p == null) p = cmd;
                JSONArray gentle = p.optJSONArray("gentle");
                JSONArray strict = p.optJSONArray("strict");
                syncPhrases(ctx, gentle, strict);
                return out.put("ok", true).put("result", "phrases_synced")
                        .put("gentle", gentle == null ? 0 : gentle.length())
                        .put("strict", strict == null ? 0 : strict.length());
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
