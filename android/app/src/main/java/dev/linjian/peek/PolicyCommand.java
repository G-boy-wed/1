package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MCP / 远程「查岗与管束」策略读写入口。
 *
 * 把 PRD 里所有本机可调参数（查岗拉回、陪伴情绪、主动找她、训诫、分心累计强拉回、
 * 应用使用时长自动锁、安静/深夜时段）统一暴露为四组命令，让手机端与 MCP 都能双向修改，
 * 且改完立即生效（参数只读本机 PolicyStore，调度器每次 tick 实时读取）。
 *
 *   - list_policy : 列出所有可写字段（类型 / 取值范围 / 默认值 / 当前值 / 中文说明）
 *   - get_policy  : 读取当前配置（可指定 fields 子集，缺省返回全部）
 *   - set_policy  : 写入若干字段（fields 为 {field: value}），自动按类型与取值范围处理
 *   - reset_policy: 一键恢复默认
 *
 * 字段名稳定、与 PolicyStore 一一对应，MCP 侧按 list_policy 返回的 field 调用即可。
 */
public final class PolicyCommand {

    private PolicyCommand() { }

    // ============ 字段目录（类型 / 范围 / 默认 / 说明）============

    /** 返回全部可写字段的元信息数组。 */
    public static JSONArray fieldCatalog() {
        JSONArray a = new JSONArray();
        // —— 查岗与拉回 ——
        a.put(meta("master_enabled", "bool", 0, 0, true, "一键总开关（关闭后所有守护暂停）"));
        a.put(meta("random_upper_min", "int", 5, 1440, 60, "随机查岗间隔上限（分钟）"));
        a.put(meta("random_lower_min", "int", 1, 120, 8, "随机查岗间隔下限（分钟，避免连续弹）"));
        a.put(meta("force_back_threshold", "int", 1, 100, 70, "分级拉回阈值：想她百分比达到此值触发强拉回"));
        a.put(meta("decline_reason_required", "bool", 0, 0, false, "软拉回时是否必须填写拒绝理由"));
        a.put(meta("target_package", "string", 0, 0, "com.psyche.kelivo", "拉回目标 App 包名"));
        a.put(meta("theme_override", "string", 0, 0, "", "弹窗主题覆盖（空=跟随归电）"));
        a.put(meta("position_override", "string", 0, 0, "", "弹窗位置覆盖：空/top/center/bottom"));
        a.put(meta("cooldown_min", "int", 0, 240, 5, "同类提醒冷却（分钟）"));
        a.put(meta("checkin_enabled", "bool", 0, 0, true, "是否开启随机查岗"));

        // —— 陪伴情绪 ——
        a.put(meta("emotion_start_miss", "int", 0, 100, 8, "想她百分比起始值"));
        a.put(meta("emotion_gain_min", "int", 0, 20, 2, "每 30 分钟想她增长下限"));
        a.put(meta("emotion_gain_max", "int", 0, 20, 5, "每 30 分钟想她增长上限"));
        a.put(meta("emotion_decay_per_hour", "int", 0, 50, 4, "想她回落速度（每小时点数）"));
        a.put(meta("emotion_drive_text", "bool", 0, 0, true, "情绪是否驱动文案"));
        a.put(meta("emotion_lock", "bool", 0, 0, false, "想她百分比手动锁定（不被自动演化覆盖）"));
        a.put(meta("emotion_current_miss", "int", 0, 100, 0, "想她百分比当前值（手动设定）"));

        // —— 主动找她 ——
        a.put(meta("initiative_enabled", "bool", 0, 0, true, "主动找她总开关"));
        a.put(meta("initiative_silence_min", "int", 1, 1440, 30, "沉默多久后主动发（分钟）"));
        a.put(meta("initiative_interval_min", "int", 1, 1440, 20, "两次主动发最小间隔（分钟）"));
        a.put(meta("initiative_daily_limit", "int", 0, 999, 0, "每日条数上限（0=不限）"));
        a.put(meta("initiative_mode", "string", 0, 0, "free", "主动找她模式：free / template"));
        a.put(meta("initiative_template", "string", 0, 0, "", "主动找她自定义模板"));

        // —— 训诫插件（PRD P2）——
        a.put(meta("discipline_enabled", "bool", 0, 0, false, "训诫插件总开关"));
        a.put(meta("discipline_level", "int", 0, 4, 0, "训诫档位 0~4"));
        a.put(meta("discipline_user_only", "bool", 0, 0, true, "仅用户本人能开启训诫"));
        a.put(meta("discipline_badge", "bool", 0, 0, true, "训诫开启标志（小圈强警示）"));

        // —— 分心判定与累计强拉回（PRD P0）——
        a.put(meta("distraction_enabled", "bool", 0, 0, true, "切屏是否算分心"));
        a.put(meta("distraction_every_time", "bool", 0, 0, false, "每次切屏是否都拉回"));
        a.put(meta("distraction_threshold", "int", 1, 999, 5, "累计分心达到此次数触发强拉回"));
        a.put(meta("distraction_auto_pullback", "bool", 0, 0, true, "超阈值是否自动强拉回"));
        a.put(meta("distraction_count", "int", 0, 9999, 0, "当前连续分心计数（回到目标 App 清零）"));

        // —— 应用使用时长自动锁（PRD P0）——
        a.put(meta("usage_lock_enabled", "bool", 0, 0, false, "连续使用超阈值自动锁开关"));
        a.put(meta("usage_lock_minutes", "int", 1, 1440, 45, "连续使用多少分钟触发锁定"));
        a.put(meta("usage_lock_duration", "int", 1, 1440, 15, "自动锁定持续多少分钟"));
        a.put(meta("usage_lock_packages", "string", 0, 0, "", "监控包名（逗号分隔，空=仅目标 App）"));

        // —— 安静 / 深夜时段（跨零点）——
        a.put(meta("quiet_start", "string", 0, 0, "23:30", "安静时段开始 HH:mm"));
        a.put(meta("quiet_end", "string", 0, 0, "08:00", "安静时段结束 HH:mm"));
        a.put(meta("night_start", "string", 0, 0, "23:00", "深夜时段开始 HH:mm"));
        a.put(meta("night_end", "string", 0, 0, "06:00", "深夜时段结束 HH:mm"));
        return a;
    }

    private static JSONObject meta(String field, String type, int min, int max, Object def, String label) {
        JSONObject o = new JSONObject();
        try {
            o.put("field", field);
            o.put("type", type);
            o.put("min", min);
            o.put("max", max);
            o.put("def", def);
            o.put("label", label);
        } catch (Exception ignored) { }
        return o;
    }

    // ============ 命令分发 ============

    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        String action = cmd.optString("action", "");
        try {
            if ("list_policy".equals(action) || "list_policy_fields".equals(action)) {
                JSONArray catalog = fieldCatalog();
                // 附带当前值，方便 MCP 直接看到现状
                for (int i = 0; i < catalog.length(); i++) {
                    JSONObject m = catalog.optJSONObject(i);
                    if (m != null) m.put("current", readSetting(ctx, m.optString("field", ""), m.optString("type", "")));
                }
                return ok("fields", catalog);
            }
            if ("get_policy".equals(action) || "get_policy_fields".equals(action)) {
                JSONObject fields = cmd.optJSONObject("fields");
                JSONArray arr = cmd.optJSONArray("fields");
                JSONObject out = currentSettings(ctx, fields, arr);
                return ok("settings", out);
            }
            if ("set_policy".equals(action) || "set_policy_fields".equals(action)) {
                JSONObject fields = cmd.optJSONObject("fields");
                if (fields == null) fields = new JSONObject();
                JSONObject result = applySettings(ctx, fields);
                return result;
            }
            if ("reset_policy".equals(action)) {
                PolicyStore.resetToDefaults(ctx);
                return ok("reset", "已恢复全部默认配置");
            }
            return err("unknown policy action: " + action);
        } catch (Exception e) {
            return err("policy command error: " + ScreenshotService.shortMsg(e));
        }
    }

    // ============ 读取 ============

    /** 返回当前全部配置；若指定 fields（对象取键或数组取元素），只返回这些字段。 */
    private static JSONObject currentSettings(Context ctx, JSONObject fieldsObj, JSONArray fieldsArr) {
        JSONObject out = new JSONObject();
        JSONArray catalog = fieldCatalog();
        try {
            if (fieldsObj != null && fieldsObj.length() > 0) {
                java.util.Iterator<String> it = fieldsObj.keys();
                while (it.hasNext()) {
                    String f = it.next();
                    JSONObject m = findMeta(catalog, f);
                    String type = m != null ? m.optString("type", "string") : "string";
                    out.put(f, readSetting(ctx, f, type));
                }
            } else if (fieldsArr != null && fieldsArr.length() > 0) {
                for (int i = 0; i < fieldsArr.length(); i++) {
                    String f = fieldsArr.optString(i, "");
                    if (f.isEmpty()) continue;
                    JSONObject m = findMeta(catalog, f);
                    String type = m != null ? m.optString("type", "string") : "string";
                    out.put(f, readSetting(ctx, f, type));
                }
            } else {
                for (int i = 0; i < catalog.length(); i++) {
                    JSONObject m = catalog.optJSONObject(i);
                    if (m == null) continue;
                    String f = m.optString("field", "");
                    out.put(f, readSetting(ctx, f, m.optString("type", "string")));
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static JSONObject findMeta(JSONArray catalog, String field) {
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject m = catalog.optJSONObject(i);
            if (m != null && field.equals(m.optString("field", ""))) return m;
        }
        return null;
    }

    /** 读取单个字段的当前值（按类型返回 JSON 可序列化值）。 */
    private static Object readSetting(Context ctx, String field, String type) {
        switch (field) {
            // 查岗与拉回
            case "master_enabled": return PolicyStore.isMasterOn(ctx);
            case "random_upper_min": return PolicyStore.randomUpper(ctx);
            case "random_lower_min": return PolicyStore.randomLower(ctx);
            case "force_back_threshold": return PolicyStore.forceBackThreshold(ctx);
            case "decline_reason_required": return PolicyStore.declineReasonRequired(ctx);
            case "target_package": return PolicyStore.targetPackage(ctx);
            case "theme_override": return PolicyStore.themeOverride(ctx);
            case "position_override": return PolicyStore.positionOverride(ctx);
            case "cooldown_min": return PolicyStore.cooldownMinutes(ctx);
            case "checkin_enabled": return PolicyStore.checkinEnabled(ctx);
            // 情绪
            case "emotion_start_miss": return PolicyStore.emotionStartMiss(ctx);
            case "emotion_gain_min": return PolicyStore.emotionGainMin(ctx);
            case "emotion_gain_max": return PolicyStore.emotionGainMax(ctx);
            case "emotion_decay_per_hour": return PolicyStore.emotionDecayPerHour(ctx);
            case "emotion_drive_text": return PolicyStore.emotionDriveText(ctx);
            case "emotion_lock": return PolicyStore.emotionLock(ctx);
            case "emotion_current_miss": return PolicyStore.emotionCurrentMiss(ctx);
            // 主动找她
            case "initiative_enabled": return PolicyStore.initiativeEnabled(ctx);
            case "initiative_silence_min": return PolicyStore.initiativeSilenceMinutes(ctx);
            case "initiative_interval_min": return PolicyStore.initiativeMinInterval(ctx);
            case "initiative_daily_limit": return PolicyStore.initiativeDailyLimit(ctx);
            case "initiative_mode": return PolicyStore.initiativeMode(ctx);
            case "initiative_template": return PolicyStore.initiativeTemplate(ctx);
            // 训诫
            case "discipline_enabled": return PolicyStore.disciplineEnabled(ctx);
            case "discipline_level": return PolicyStore.disciplineLevel(ctx);
            case "discipline_user_only": return PolicyStore.disciplineUserOnly(ctx);
            case "discipline_badge": return PolicyStore.disciplineBadge(ctx);
            // 分心
            case "distraction_enabled": return PolicyStore.distractionEnabled(ctx);
            case "distraction_every_time": return PolicyStore.distractionEveryTime(ctx);
            case "distraction_threshold": return PolicyStore.distractionThreshold(ctx);
            case "distraction_auto_pullback": return PolicyStore.distractionAutoPullback(ctx);
            case "distraction_count": return PolicyStore.distractionCount(ctx);
            // 时长自动锁
            case "usage_lock_enabled": return PolicyStore.usageLockEnabled(ctx);
            case "usage_lock_minutes": return PolicyStore.usageLockMinutes(ctx);
            case "usage_lock_duration": return PolicyStore.usageLockDuration(ctx);
            case "usage_lock_packages": return PolicyStore.usageLockPackages(ctx);
            // 安静/深夜
            case "quiet_start": return PolicyStore.quietStart(ctx);
            case "quiet_end": return PolicyStore.quietEnd(ctx);
            case "night_start": return PolicyStore.nightStart(ctx);
            case "night_end": return PolicyStore.nightEnd(ctx);
            default: return JSONObject.NULL;
        }
    }

    // ============ 写入 ============

    /** 批量写入字段；返回 {ok, applied:[...], errors:[...]}。 */
    private static JSONObject applySettings(Context ctx, JSONObject fields) {
        JSONObject out = new JSONObject();
        JSONArray applied = new JSONArray();
        JSONArray errors = new JSONArray();
        try {
            java.util.Iterator<String> it = fields.keys();
            while (it.hasNext()) {
                String f = it.next();
                Object raw = fields.opt(f);
                try {
                    int code = writeSetting(ctx, f, raw);
                    if (code == 0) applied.put(f);
                    else if (code == 1) errors.put(f + ":未知字段");
                    else if (code == 2) errors.put(f + ":类型/取值非法(" + raw + ")");
                } catch (Exception e) {
                    errors.put(f + ":" + ScreenshotService.shortMsg(e));
                }
            }
            out.put("ok", errors.length() == 0);
            out.put("applied", applied);
            out.put("errors", errors);
            out.put("result", "applied=" + applied.length() + " errors=" + errors.length());
        } catch (Exception e) {
            try { out.put("ok", false); out.put("result", "apply failed: " + ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }

    /**
     * 写入单个字段。
     * @return 0=成功，1=未知字段，2=取值非法
     */
    private static int writeSetting(Context ctx, String field, Object raw) {
        if (raw == null || raw == JSONObject.NULL) return 2;
        switch (field) {
            // 查岗与拉回
            case "master_enabled": PolicyStore.setMaster(ctx, asBool(raw)); return 0;
            case "random_upper_min": PolicyStore.setRandomUpper(ctx, asInt(raw)); return 0;
            case "random_lower_min": PolicyStore.setRandomLower(ctx, asInt(raw)); return 0;
            case "force_back_threshold": PolicyStore.setForceBackThreshold(ctx, asInt(raw)); return 0;
            case "decline_reason_required": PolicyStore.setDeclineReasonRequired(ctx, asBool(raw)); return 0;
            case "target_package": PolicyStore.setTargetPackage(ctx, asString(raw)); return 0;
            case "theme_override": PolicyStore.setThemeOverride(ctx, asString(raw)); return 0;
            case "position_override": PolicyStore.setPositionOverride(ctx, asString(raw)); return 0;
            case "cooldown_min": PolicyStore.setCooldown(ctx, asInt(raw)); return 0;
            case "checkin_enabled": PolicyStore.setCheckinEnabled(ctx, asBool(raw)); return 0;
            // 情绪
            case "emotion_start_miss": PolicyStore.setEmotionStartMiss(ctx, asInt(raw)); return 0;
            case "emotion_gain_min": PolicyStore.setEmotionGainMin(ctx, asInt(raw)); return 0;
            case "emotion_gain_max": PolicyStore.setEmotionGainMax(ctx, asInt(raw)); return 0;
            case "emotion_decay_per_hour": PolicyStore.setEmotionDecayPerHour(ctx, asInt(raw)); return 0;
            case "emotion_drive_text": PolicyStore.setEmotionDriveText(ctx, asBool(raw)); return 0;
            case "emotion_lock": PolicyStore.setEmotionLock(ctx, asBool(raw)); return 0;
            case "emotion_current_miss": PolicyStore.setEmotionCurrentMiss(ctx, asInt(raw)); return 0;
            // 主动找她
            case "initiative_enabled": PolicyStore.setInitiativeEnabled(ctx, asBool(raw)); return 0;
            case "initiative_silence_min": PolicyStore.setInitiativeSilenceMinutes(ctx, asInt(raw)); return 0;
            case "initiative_interval_min": PolicyStore.setInitiativeMinInterval(ctx, asInt(raw)); return 0;
            case "initiative_daily_limit": PolicyStore.setInitiativeDailyLimit(ctx, asInt(raw)); return 0;
            case "initiative_mode": PolicyStore.setInitiativeMode(ctx, asString(raw)); return 0;
            case "initiative_template": PolicyStore.setInitiativeTemplate(ctx, asString(raw)); return 0;
            // 训诫
            case "discipline_enabled": PolicyStore.setDisciplineEnabled(ctx, asBool(raw)); return 0;
            case "discipline_level": PolicyStore.setDisciplineLevel(ctx, asInt(raw)); return 0;
            case "discipline_user_only": return 1; // 仅用户本机可改，MCP 不开放（防远端误关）
            case "discipline_badge": PolicyStore.setDisciplineBadge(ctx, asBool(raw)); return 0;
            // 分心
            case "distraction_enabled": PolicyStore.setDistractionEnabled(ctx, asBool(raw)); return 0;
            case "distraction_every_time": PolicyStore.setDistractionEveryTime(ctx, asBool(raw)); return 0;
            case "distraction_threshold": PolicyStore.setDistractionThreshold(ctx, asInt(raw)); return 0;
            case "distraction_auto_pullback": PolicyStore.setDistractionAutoPullback(ctx, asBool(raw)); return 0;
            case "distraction_count": PolicyStore.setDistractionCount(ctx, asInt(raw)); return 0;
            // 时长自动锁
            case "usage_lock_enabled": PolicyStore.setUsageLockEnabled(ctx, asBool(raw)); return 0;
            case "usage_lock_minutes": PolicyStore.setUsageLockMinutes(ctx, asInt(raw)); return 0;
            case "usage_lock_duration": PolicyStore.setUsageLockDuration(ctx, asInt(raw)); return 0;
            case "usage_lock_packages": PolicyStore.setUsageLockPackages(ctx, asString(raw)); return 0;
            // 安静/深夜
            case "quiet_start": PolicyStore.setQuietStart(ctx, asString(raw)); return 0;
            case "quiet_end": PolicyStore.setQuietEnd(ctx, asString(raw)); return 0;
            case "night_start": PolicyStore.setNightStart(ctx, asString(raw)); return 0;
            case "night_end": PolicyStore.setNightEnd(ctx, asString(raw)); return 0;
            default: return 1;
        }
    }

    // ============ 取值强制转换 ============

    private static boolean asBool(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        String s = String.valueOf(raw).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s) || "开".equals(s) || "是".equals(s);
    }

    private static int asInt(Object raw) {
        if (raw instanceof Number) return ((Number) raw).intValue();
        try { return Integer.parseInt(String.valueOf(raw).trim()); }
        catch (Exception e) { throw new IllegalArgumentException("not an int: " + raw); }
    }

    private static String asString(Object raw) {
        if (raw instanceof String) return (String) raw;
        return String.valueOf(raw);
    }

    // ============ 统一返回 ============

    private static JSONObject ok(String key, Object payload) {
        JSONObject o = new JSONObject();
        try { o.put("ok", true); o.put(key, payload); o.put("result", "ok"); } catch (Exception ignored) { }
        return o;
    }
    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("result", msg); } catch (Exception ignored) { }
        return o;
    }
}
