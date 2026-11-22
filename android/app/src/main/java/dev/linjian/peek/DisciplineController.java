package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 训诫管束模块集中控制器（对应 PRD 模块一）。
 *
 *  - [DISC-NO-TIER] 数字档位(level)已彻底删除，不显示档位、不加固定标签后缀；
 *  - [DISC-POOL-CHOOSE] 训诫话术来自 MCP 写入的动态话术池：温柔提醒池/严厉管束池两份数据分开保存，
 *    由用户选中当前卡池（手机设置页或 MCP set_discipline_mode），触发时只从选中池随机取整句。
 *    提示词不硬编码在底代码，已移除自定义文案。
 *  - 强/软判断统一走情绪强度阈值 DISC_STRONG_INTENSITY(≥80)：强度够高才锁 App + 弹窗不给拒绝；
 *  - 锁 App 固定 DISC_LOCK_MINUTES(3) 分钟；
 *  - 触发训诫：强行弹出「不可直接滑掉」的居中弹窗（仅按钮可关闭）+ 震动；
 *  - 通过 handleCommand 接入：get_discipline_state / trigger_discipline / sync_discipline_phrases。
 */
public final class DisciplineController {

    // [DISC-PHRASE-POOL] 手机端话术池镜像（服务端下发权威数据，离线也能取用）。
    private static final String POOL_PREFS = "peek_disc_phrases";
    private static final String KEY_GENTLE = "gentle";
    private static final String KEY_STRICT = "strict";

    private DisciplineController() { }

    // ===== [DISC-PHRASE-POOL] 动态话术池：手机端镜像读写 =====
    private static SharedPreferences poolPrefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE);
    }

    /** 从服务端同步话术池（MCP 下发）。两池数据分开保存，取用只从当前选中池。 */
    public static void syncPhrases(Context ctx, JSONArray gentle, JSONArray strict) {
        poolPrefs(ctx).edit().putString(KEY_GENTLE, gentle == null ? "[]" : gentle.toString())
                .putString(KEY_STRICT, strict == null ? "[]" : strict.toString()).apply();
        DebugState.append(ctx, "训诫话术池已同步：温柔=" + (gentle == null ? 0 : gentle.length())
                + " 严厉=" + (strict == null ? 0 : strict.length()) + "（当前卡池=" + PolicyStore.disciplinePool(ctx) + "）");
    }

    /** 读取单个池。 */
    private static String[] onePool(Context ctx, String key) {
        String json = poolPrefs(ctx).getString(key, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i, "").trim();
            return out;
        } catch (Exception e) { return new String[0]; }
    }

    /** [DISC-POOL-CHOOSE] 当前选中池：gentle 温柔提醒 / strict 严厉管束；只从该池抽取。 */
    private static String[] chosenPool(Context ctx) {
        String pool = PolicyStore.disciplinePool(ctx);
        return onePool(ctx, "strict".equals(pool) ? KEY_STRICT : KEY_GENTLE);
    }

    /** 从当前选中池随机取整句（不连续重复）。 */
    private static String pickPhrase(Context ctx) {
        String[] pool = chosenPool(ctx);
        if (pool.length == 0) return "";
        if (pool.length == 1) return pool[0];
        int idx = (int) (Math.random() * pool.length);
        String picked = pool[idx];
        // 与上一句相同则顺延一位，避免连续重复；按池分开记 last，避免切池后误判。
        String lastKey = "last_phrase_" + PolicyStore.disciplinePool(ctx);
        String last = poolPrefs(ctx).getString(lastKey, "");
        if (pool.length > 1 && picked.equals(last)) picked = pool[(idx + 1) % pool.length];
        poolPrefs(ctx).edit().putString(lastKey, picked).apply();
        return picked;
    }

    /** 当前训诫配置快照（供状态层 / MCP 读取）——不暴露数字档位，只暴露卡池选择。 */
    public static JSONObject config(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            String phrase = resolveDisciplineText(ctx, null);
            String pool = PolicyStore.disciplinePool(ctx);
            o.put("enabled", PolicyStore.disciplineEnabled(ctx));
            // [DISC-POOL-CHOOSE] 当前选中卡池 gentle/strict；文案取自该池，不显示数字档位、不加标签。
            o.put("pool", pool);
            o.put("text", phrase);
            o.put("text_is_fallback", chosenPool(ctx).length == 0);
            o.put("phrase_count", chosenPool(ctx).length);
            o.put("gentle_count", onePool(ctx, KEY_GENTLE).length);
            o.put("strict_count", onePool(ctx, KEY_STRICT).length);
            o.put("strong_intensity_threshold", PolicyStore.DISC_STRONG_INTENSITY);
            o.put("lock_minutes", PolicyStore.DISC_LOCK_MINUTES);
            o.put("user_only", PolicyStore.disciplineUserOnly(ctx));
            o.put("badge", PolicyStore.disciplineBadge(ctx));
            o.put("strong_now", PolicyStore.disciplineStrongNow(ctx));
        } catch (Exception ignored) { }
        return o;
    }

    /**
     * [DISC-POOL-CHOOSE] 统一文案解析：从当前选中卡池随机取整句，不显示数字档位、不加标签后缀。
     *  - 选中池为空时用极简中性兜底（不硬编码任何温柔/严厉话术库），提示通过 MCP 写入话术；
     *  - customReason 非空时以「【原因】」开头。
     */
    public static String resolveDisciplineText(Context ctx, String customReason) {
        String reason = customReason == null ? "" : customReason.trim();
        String reasonText = reason.isEmpty() ? "" : "【" + reason + "】\n";
        String phrase = pickPhrase(ctx);
        if (phrase.isEmpty()) {
            return reasonText + "掌心窗提醒你回到身边（尚未配置管束话术，可让陪伴对象用 add_discipline_phrase 写入）。";
        }
        return reasonText + phrase;
    }

    /**
     * 触发一次训诫：不可滑掉居中弹窗 + 震动；情绪强度≥80 时联动门禁锁当前前台 App。
     * 返回执行结果 JSON。
     */
    public static JSONObject trigger(Context ctx, String reason) {
        JSONObject out = new JSONObject();
        try {
            if (!PolicyStore.disciplineEnabled(ctx)) {
                return out.put("ok", false).put("result", "discipline_disabled");
            }
            // [LOCK-TIMING-FIX] 先抓真正的前台 App，再弹窗——避免弹窗把掌心窗拉到前台后
            // currentPackage() 读到掌心窗自己而被"受保护App"拦截，导致永远锁不住真正的应用。
            String pkg = ScreenshotService.currentPackage();
            boolean strong = PolicyStore.disciplineStrongNow(ctx);

            String text = resolveDisciplineText(ctx, reason);
            String tone = strong ? "angry" : "gentle";
            String title = "掌心窗管束";
            CompanionService.showCenterAlert(ctx, title, text, tone, "我错了", "训诫管束", true);
            out.put("ok", true).put("result", "discipline_shown")
                    .put("strong", strong)
                    .put("text", text);

            // [DISC-NO-TIER] 情绪强度≥80 才锁 App；pkg 必须是真实前台且非掌心窗自身。
            boolean gateLocked = false;
            if (strong && pkg != null && !pkg.trim().isEmpty()
                    && !pkg.equals(ctx.getPackageName())) {
                JSONObject lock = AppGate.handleCommand(ctx, new JSONObject()
                        .put("action", "lock_app")
                        .put("package", pkg)
                        .put("mode", "strict")
                        .put("reason", "训诫管束：情绪强度达阈值")
                        .put("minutes", PolicyStore.DISC_LOCK_MINUTES));
                gateLocked = lock.optBoolean("ok", false);
                out.put("gate_result", lock.optString("result", ""));
            }
            out.put("gate_locked", gateLocked);
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
            if ("set_discipline_mode".equals(action)) {
                // [DISC-POOL-CHOOSE] 恢复：用来选择当前卡池（gentle 温柔 / strict 严厉），非数字档位。
                JSONObject p = cmd.optJSONObject("payload");
                if (p == null) p = cmd;
                String pool = p.optString("pool", p.optString("mode", p.optString("tone", "")));
                if (pool.trim().isEmpty()) return out.put("ok", false).put("error", "missing_pool");
                PolicyStore.setDisciplinePool(ctx, pool);
                return out.put("ok", true).put("result", "pool_changed")
                        .put("pool", PolicyStore.disciplinePool(ctx));
            }
            return out.put("ok", false).put("error", "unknown_discipline_action");
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
            return out;
        }
    }
}
