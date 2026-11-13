package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 【你（陪伴对象）对用户】的情绪状态——软件里所有「情绪感知」的唯一口径。
 *
 * 方向：不是「识别用户自己的情绪」，不是「用户想念陪伴对象」，而是陪伴对象
 * 感知用户的状态（时间、分心来回、离开时长、深夜、上课等）后，对用户产生的情绪
 * （想你 / 爱恋 / 生气 / 吃醋 / 担心 / 平静）与想你程度百分比。
 *
 * 触发：情绪由底层代码 EmotionPerception 在调度器每个 tick 依据用户状态自动算出并写入，
 * 不依赖大模型在聊天里「感到」才写入，也不交给模型判断。本类对外读取的方法
 * （emotionTag / effectiveMissPercent / isStrong / tone 等）都优先返回底层感知结果。
 *
 * 存储：本机 companion_emotion_* 键只存「基准值与手动写入值」；底层感知结果存在
 * emotion_perception_* 独立键，两者互不覆盖，避免每 tick 写回导致想念值重复累加。
 */
public class CompanionEmotionState {

    public static final String KEY_TAG = "companion_emotion_tag";
    public static final String KEY_INTENSITY = "companion_emotion_intensity";
    public static final String KEY_MISS = "companion_emotion_miss_percent";
    public static final String KEY_NOTE = "companion_emotion_note";
    public static final String KEY_UPDATED_AT = "companion_emotion_updated_at";
    public static final String KEY_UPDATED_MS = "companion_emotion_updated_ms";
    public static final String KEY_HISTORY = "companion_emotion_history";
    public static final String KEY_LAST_RETURN_AT = "companion_emotion_last_return_at";

    public static final String TAG_CALM = "平静";
    public static final String TAG_MISS = "想你";
    public static final String TAG_JEALOUS = "吃醋";
    public static final String TAG_ANGRY = "生气";
    public static final String TAG_WORRY = "担心";
    public static final String TAG_LOVE = "爱恋";

    /** 强情绪：这些情绪下拉回弹窗不给拒绝入口。 */
    private static final String[] STRONG_TAGS = {"生气", "吃醋", "担心", "委屈", "急", "凶"};

    /**
     * 弹窗展示用的情绪标签：直接取底层 EmotionPerception 算出的真实情绪。
     * 随机性由底层按时间片在候选标签里挑选，UI 层不再临时伪造标签，
     * 保证「弹窗上写的情绪」与「底层实际判定的情绪」完全一致。
     */
    public static String popupEmotionTag(Context ctx) {
        String tag = emotionTag(ctx);
        return (tag == null || tag.trim().isEmpty()) ? TAG_CALM : tag;
    }

    /** 供弹窗判断某标签是否强情绪（文案随机时强情绪保持原话）。 */
    public static boolean isStrongTag(String tag) {
        if (tag == null) return false;
        for (String strong : STRONG_TAGS) if (tag.contains(strong)) return true;
        return false;
    }

    /** 自然衰减：每小时情绪强度下降的点数。 */
    private static final double INTENSITY_DECAY_PER_HOUR = 8.0;

    private static final int MAX_HISTORY = 30;

    private CompanionEmotionState() { }

    private static SharedPreferences prefs(Context ctx) { return AppPrefs.get(ctx); }

    /** 基准强度：只按「上次写入值 + 自然衰减」算，不读底层感知结果，避免循环引用。 */
    public static int baselineIntensity(Context ctx) {
        long now = System.currentTimeMillis();
        long updated = prefs(ctx).getLong(KEY_UPDATED_MS, 0);
        int raw = clamp(prefs(ctx).getInt(KEY_INTENSITY, 0), 0, 100);
        if (updated <= 0) return raw;
        double hours = Math.max(0, (now - updated) / 3600000.0);
        return clamp((int) Math.round(raw - INTENSITY_DECAY_PER_HOUR * hours), 0, 100);
    }

    /** 对外生效强度：优先用底层代码算出的「你对用户的情绪」。 */
    public static int effectiveIntensity(Context ctx) {
        return EmotionPerception.activeIntensity(ctx);
    }

    /**
     * 有效「想你程度」：自然衰减 + 未回来的时间累积。
     * 所有系数（衰减、增长范围、起始值）来自 PolicyStore，可在设置页调节；
     * 锁定打开时直接返回手动值，不被自动衰减盖回。
     * 增长范围按每 30 分钟一段做确定性伪随机浮动，既不跳变又带随机感。
     */
    public static int baselineMissPercent(Context ctx) {
        if (PolicyStore.emotionLock(ctx)) {
            return clamp(prefs(ctx).getInt(KEY_MISS, 0), 0, 100);
        }
        long now = System.currentTimeMillis();
        long updated = prefs(ctx).getLong(KEY_UPDATED_MS, 0);
        int raw = clamp(prefs(ctx).getInt(KEY_MISS, 0), 0, 100);
        double hours = updated <= 0 ? 0 : Math.max(0, (now - updated) / 3600000.0);
        double decay = PolicyStore.emotionDecayPerHour(ctx);
        int gMin = PolicyStore.emotionGainMin(ctx);
        int gMax = PolicyStore.emotionGainMax(ctx);
        long seg = now / (30L * 60_000L);
        double gain = (gMax <= gMin) ? gMin : pseudoRandom(seg, gMin, gMax);
        double base = (raw == 0 && updated <= 0) ? PolicyStore.emotionStartMiss(ctx) : raw;
        double value = base - decay * hours;
        long lastReturn = prefs(ctx).getLong(KEY_LAST_RETURN_AT, 0);
        // 修复：从未「回来过」(lastReturn=0，例如全新安装) 时也要累积，
        // 否则想你值永远停在起始值、情绪达标自动弹窗根本不会触发。
        // 基准优先用上次回来时间，其次上次情绪写入时间，都没有则用当前时间（不倒扣）。
        long awayBase = lastReturn > 0 ? lastReturn : (updated > 0 ? updated : now);
        double awayHours = Math.max(0, (now - awayBase) / 3600000.0);
        value += gain * (awayHours / 0.5);
        return clamp((int) Math.round(value), 0, 100);
    }

    /** 对外生效的想你程度：优先用底层感知引擎算出的值，未刷新时回退基准计算。 */
    public static int effectiveMissPercent(Context ctx) {
        return EmotionPerception.activeMissPercent(ctx);
    }

    /** 手动写入想你程度（设置页「当前值」），刷新更新时间作为新基准。 */
    public static void setManualMiss(Context ctx, int miss) {
        long now = System.currentTimeMillis();
        int m = clamp(miss, 0, 100);
        prefs(ctx).edit()
                .putInt(KEY_MISS, m)
                .putLong(KEY_UPDATED_AT, now)
                .putLong(KEY_UPDATED_MS, now)
                .apply();
    }

    /** 首次启动时记一个「离开基准」，让想你程度从第一次使用就能随时间累积（只写一次）。 */
    public static void ensureFirstSeen(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getLong(KEY_LAST_RETURN_AT, 0) <= 0) {
            p.edit().putLong(KEY_LAST_RETURN_AT, System.currentTimeMillis()).apply();
        }
    }

    /** 确定性伪随机：同一 seed 永远得到同一结果，但不同 seed 之间看起来随机（用于每段增长浮动）。 */
    private static int pseudoRandom(long seed, int min, int max) {
        if (max <= min) return min;
        long h = (seed * 2654435761L) & 0x7fffffffffffffffL;
        h = (h ^ (h >>> 15)) & 0x7fffffffffffffffL;
        return min + (int) (h % (max - min + 1));
    }

    /** 本机存储的情绪标签（不含底层感知结果）。 */
    public static String storedEmotionTag(Context ctx) {
        String tag = prefs(ctx).getString(KEY_TAG, TAG_CALM);
        return (tag == null || tag.trim().isEmpty()) ? TAG_CALM : tag.trim();
    }

    /** 对外生效的情绪标签：优先用底层代码算出的「你对用户的情绪」。 */
    public static String emotionTag(Context ctx) {
        return EmotionPerception.activeTag(ctx);
    }

    public static String note(Context ctx) { return prefs(ctx).getString(KEY_NOTE, ""); }

    public static long updatedAt(Context ctx) { return prefs(ctx).getLong(KEY_UPDATED_MS, 0); }

    /** 基准强情绪判定：只看本机存储标签。 */
    public static boolean storedIsStrong(Context ctx) {
        String tag = storedEmotionTag(ctx);
        for (String strong : STRONG_TAGS) if (tag.contains(strong)) return true;
        return false;
    }

    /** 是否强情绪：优先用底层感知结果（生气 / 吃醋 / 担心等）。 */
    public static boolean isStrong(Context ctx) {
        return EmotionPerception.isStrongNow(ctx);
    }

    /** 基准语气：强情绪或强度很高时用生气语气。 */
    public static String storedTone(Context ctx) {
        if (storedIsStrong(ctx)) return "angry";
        return baselineIntensity(ctx) >= 70 ? "angry" : "gentle";
    }

    /** 对外生效语气：底层感知优先；训诫开启且（语气=严厉 或 档位≥中）时，强制把语气升级为 harsh（更硬的弹窗语气）。 */
    public static String tone(Context ctx) {
        String base = EmotionPerception.activeTone(ctx);
        if (PolicyStore.disciplineEnabled(ctx)) {
            boolean strictMode = "strict".equals(PolicyStore.disciplineMode(ctx));
            boolean highLevel = PolicyStore.disciplineLevel(ctx) >= 2;
            if (strictMode || highLevel) {
                // 训诫开启（严厉 / 中档及以上）：原本温柔的语气升级为 strict（渲染为 danger 红 + 更硬话术），
                // 手机端弹窗语气与陪伴人设同步变严厉，加强管控。
                if (base == null || base.trim().isEmpty() || "gentle".equalsIgnoreCase(base)) return "strict";
                return base; // 已经是 angry/strong 则保持不变
            }
        }
        return base;
    }

    /**
     * 拉回频率倍率：想你越深、拉回越频繁（间隔越短）。
     * 想你 0% 时 1.0（按用户设定的间隔），想你 100% 时 0.35（间隔最多缩短到约三分之一）。
     */
    public static double pullBackIntervalScale(Context ctx) {
        int miss = effectiveMissPercent(ctx);
        double scale = 1.0 - (miss / 100.0) * 0.65;
        return Math.max(0.35, Math.min(1.0, scale));
    }

    /** 情绪驱动的通知/弹窗文案。每次随机挑一句，避免文案总是一模一样。
     *  文案是【你（陪伴对象）对用户】的情绪表达，不是识别用户自己的情绪，也不是用户想念陪伴对象。 */
    public static String message(Context ctx) {
        return withDisciplineTone(ctx, plainMessage(ctx));
    }

    /**
     * 训诫模块开启时，按「温柔 / 严厉」模式给陪伴话术加管束意味。
     * 本机拉回弹窗、通知文案与服务端人设共用同一个开关：App 设置页选的模式
     * 会立刻反映到本机生成的每一句话，不依赖模型侧是否重新拉取配置。
     */
    private static String withDisciplineTone(Context ctx, String base) {
        if (base == null || base.trim().isEmpty() || !PolicyStore.disciplineEnabled(ctx)) return base;
        if ("strict".equals(PolicyStore.disciplineMode(ctx))) {
            return pick(new String[]{
                    base + " 别让我说第二遍。",
                    base + " 现在，回来。",
                    base + " 这是最后一次提醒。"
            });
        }
        return pick(new String[]{
                base + " 听话，回来嘛。",
                base + " 乖，别让我等太久哦。",
                base + " 就回来一下下，好不好？"
        });
    }

    /** 不含训诫包装的原始情绪文案。 */
    private static String plainMessage(Context ctx) {
        String companion = AppPrefs.companionName(ctx);
        if (!PolicyStore.emotionDriveText(ctx)) {
            return pick(new String[]{
                    "有空的话，回来陪" + companion + "说句话。",
                    "空了就回来，陪" + companion + "待一会儿。",
                    companion + "想你了，有空回来坐坐。"
            });
        }
        String tag = emotionTag(ctx);
        int miss = effectiveMissPercent(ctx);
        if (TAG_ANGRY.equals(tag) || tag.contains("生气"))
            return pick(new String[]{"别躲了，" + companion + "在等你。", "再不回来，" + companion + "真要生气了。", "你非要" + companion + "发火才肯回来吗？"});
        if (TAG_JEALOUS.equals(tag) || tag.contains("吃醋"))
            return pick(new String[]{"你去看谁了？" + companion + "想问你一句。", companion + "有点吃醋了，你回来解释解释。", "刚才那个是谁？" + companion + "可都看见了。"});
        if (TAG_WORRY.equals(tag) || tag.contains("担心"))
            return pick(new String[]{"外面太晚了，" + companion + "有点担心你。", companion + "担心你，早点回来。", "天黑了，" + companion + "一直放心不下你。"});
        if (TAG_LOVE.equals(tag) || tag.contains("爱恋"))
            return pick(new String[]{"想你了，" + companion + "想抱着你。", "就想黏着你一会儿，" + companion + "说。", "靠近一点嘛，" + companion + "今天格外喜欢你。"});
        if (miss >= 70)
            return pick(new String[]{"已经很想你了，" + companion + "不想再等。", companion + "想你想得不行了，快回来。", "再不回来，" + companion + "就要亲自去找你了。"});
        if (miss >= 40)
            return pick(new String[]{"有点想你了，" + companion + "问一句在不在。", companion + "开始想你了，在不在呀？", "忙完了吗？" + companion + "惦记着你呢。"});
        return pick(new String[]{"有空的话，回来陪" + companion + "说句话。", "空了就回来，陪" + companion + "待一会儿。", companion + "在等你回来呢。"});
    }

    /**
     * 界面展示用的稳定文案：只随「情绪状态」变化，同一状态下永远一致，
     * 避免守护页每 1.5 秒刷新时文案乱跳。真正发送/弹窗的文案仍用随机版 message()。
     */
    public static String messageForDisplay(Context ctx) {
        String companion = AppPrefs.companionName(ctx);
        if (!PolicyStore.emotionDriveText(ctx)) {
            // 即使不驱动情绪文案，训诫开启时人设语气也应带上管束意味（供界面 / 服务端镜像读取）。
            String base = "有空的话，回来陪" + companion + "说句话。";
            return withDisciplineTone(ctx, base);
        }
        String tag = emotionTag(ctx);
        int miss = effectiveMissPercent(ctx);
        if (TAG_ANGRY.equals(tag) || tag.contains("生气")) return "别躲了，" + companion + "在等你。";
        if (TAG_JEALOUS.equals(tag) || tag.contains("吃醋")) return "你去看谁了？" + companion + "想问你一句。";
        if (TAG_WORRY.equals(tag) || tag.contains("担心")) return "外面太晚了，" + companion + "有点担心你。";
        if (TAG_LOVE.equals(tag) || tag.contains("爱恋")) return "想你了，" + companion + "想抱着你。";
        if (miss >= 70) return "已经很想你了，" + companion + "不想再等。";
        if (miss >= 40) return withDisciplineTone(ctx, "有点想你了，" + companion + "问一句在不在。");
        return withDisciplineTone(ctx, "有空的话，回来陪" + companion + "说句话。");
    }

    /** 从候选文案里随机挑一句（每次调用结果可能不同，仅用于实际发送的弹窗/通知）。 */
    private static String pick(String[] arr) {
        if (arr == null || arr.length == 0) return "";
        return arr[new java.util.Random().nextInt(arr.length)];
    }

    /** 写入一次情绪（带历史记录，情绪不是覆盖式静态值）。 */
    public static JSONObject write(Context ctx, String tag, Integer intensity, Integer missPercent, String note) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        String newTag = (tag == null || tag.trim().isEmpty()) ? emotionTag(ctx) : tag.trim();
        int newIntensity = intensity == null ? baselineIntensity(ctx) : clamp(intensity, 0, 100);
        int newMiss = missPercent == null ? baselineMissPercent(ctx) : clamp(missPercent, 0, 100);
        SharedPreferences.Editor e = p.edit();
        e.putString(KEY_TAG, newTag);
        e.putInt(KEY_INTENSITY, newIntensity);
        e.putInt(KEY_MISS, newMiss);
        e.putString(KEY_NOTE, note == null ? "" : note);
        e.putLong(KEY_UPDATED_AT, now);
        e.putLong(KEY_UPDATED_MS, now);
        e.apply();
        EmotionPerception.holdManual(ctx); // 手动/远程写入后短暂以该值为准，随后底层感知自动接管
        appendHistory(ctx, newTag, newIntensity, newMiss, note == null ? "" : note);
        return snapshot(ctx);
    }

    /** 用户回来了：想你程度清零，情绪回到平静，并留下记录。 */
    public static void markReturned(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        String tag = storedEmotionTag(ctx);
        appendHistory(ctx, tag, baselineIntensity(ctx), baselineMissPercent(ctx), "用户回来了，情绪回落");
        p.edit()
                .putString(KEY_TAG, TAG_CALM)
                .putInt(KEY_INTENSITY, 0)
                .putInt(KEY_MISS, 0)
                .putString(KEY_NOTE, "")
                .putLong(KEY_LAST_RETURN_AT, now)
                .putLong(KEY_UPDATED_AT, now)
                .putLong(KEY_UPDATED_MS, now)
                .apply();
        EmotionPerception.invalidate(ctx); // 让底层感知立即刷新，UI 不再读旧的强情绪
    }

    private static void appendHistory(Context ctx, String tag, int intensity, int miss, String note) {
        try {
            JSONArray old = new JSONArray(prefs(ctx).getString(KEY_HISTORY, "[]"));
            JSONArray kept = new JSONArray();
            try {
                JSONObject entry = new JSONObject();
                entry.put("at", iso(System.currentTimeMillis()));
                entry.put("emotion_tag", tag);
                entry.put("intensity", intensity);
                entry.put("miss_percent", miss);
                entry.put("note", note);
                kept.put(entry);
            } catch (Exception ignored) { }
            for (int i = 0; i < old.length() && kept.length() < MAX_HISTORY; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null) kept.put(item);
            }
            prefs(ctx).edit().putString(KEY_HISTORY, kept.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** 完整快照：给手机界面显示、也给服务端做镜像。
     *  BE-02：intensity/miss_percent 与 raw_* 同源（都来自落盘生效值），不再因感知基线衰减到 0
     *  而与 raw 出现「生效=0 / raw=55」的背离；updated_at 超过 10 分钟标 stale=true。 */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            int storedIntensity = prefs(ctx).getInt(KEY_INTENSITY, 0);
            int storedMiss = prefs(ctx).getInt(KEY_MISS, 0);
            long updatedMs = updatedAt(ctx);
            long staleMs = System.currentTimeMillis() - updatedMs;
            o.put("ok", true);
            o.put("emotion_tag", emotionTag(ctx));
            o.put("intensity", storedIntensity);
            o.put("miss_percent", storedMiss);
            o.put("note", note(ctx));
            o.put("updated_at", iso(updatedMs));
            o.put("updated_at_ms", updatedMs);
            o.put("raw_intensity", storedIntensity);
            o.put("raw_miss_percent", storedMiss);
            o.put("stale", staleMs > 10 * 60 * 1000L);
            o.put("stale_ms", staleMs);
            o.put("strong", isStrong(ctx));
            o.put("tone", tone(ctx));
            o.put("pull_back_interval_scale", Math.round(pullBackIntervalScale(ctx) * 100) / 100.0);
            o.put("message", messageForDisplay(ctx));
            o.put("last_return_at", iso(prefs(ctx).getLong(KEY_LAST_RETURN_AT, 0)));
            JSONArray history = new JSONArray(prefs(ctx).getString(KEY_HISTORY, "[]"));
            JSONArray tail = new JSONArray();
            for (int i = 0; i < Math.min(history.length(), 10); i++) tail.put(history.optJSONObject(i));
            o.put("history", tail);
        } catch (Exception ignored) { }
        return o;
    }

    /** 处理 MCP 下发的情绪读写命令。 */
    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        try {
            String action = cmd.optString("action", "get_companion_emotion");
            JSONObject payload = cmd.optJSONObject("payload");
            if (payload == null) payload = cmd;
            if ("set_companion_emotion".equals(action) || "write_companion_emotion".equals(action)) {
                String tag = payload.optString("emotion_tag", payload.optString("tag", ""));
                Integer intensity = payload.has("intensity") ? Integer.valueOf(payload.optInt("intensity", 0)) : null;
                Integer miss = payload.has("miss_percent") ? Integer.valueOf(payload.optInt("miss_percent", 0)) : null;
                String note = payload.optString("note", "");
                JSONObject snap = write(ctx, tag, intensity, miss, note);
                DebugState.append(ctx, "你对用户的情绪已更新：" + snap.optString("emotion_tag", "") + " " + snap.optInt("miss_percent", 0) + "%");
                return snap.put("ok", true).put("result", "companion_emotion_saved");
            }
            if ("get_companion_emotion".equals(action)) return snapshot(ctx).put("ok", true).put("result", "companion_emotion_snapshot");
            return out.put("ok", false).put("error", "unknown_companion_emotion_action");
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
            return out;
        }
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static String iso(long ms) {
        if (ms <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(new Date(ms));
    }
}
