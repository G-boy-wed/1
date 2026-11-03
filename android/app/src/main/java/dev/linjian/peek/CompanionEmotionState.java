package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 陪伴对象（TA）自己的情绪状态。
 *
 * 与「读用户手机状态」的情绪快照（服务端 emotion_state.json / 用户情绪标签）完全分开存储：
 * 本文件只使用 companion_emotion_* 前缀的键，不会覆盖用户侧的任何数据，
 * 服务端也用独立的 companion_emotion.json 保存，互不覆盖。
 *
 * 情绪不是静态值：写入后会随时间自然衰减，同时「用户多久没回来」会持续累积想她百分比，
 * 因此同一份数据在不同时刻读出来的结果是不一样的。
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

    /** 强情绪：这些情绪下拉回弹窗不给拒绝入口。 */
    private static final String[] STRONG_TAGS = {"生气", "吃醋", "担心", "委屈", "急", "凶"};

    /** 自然衰减：每小时情绪强度下降的点数。 */
    private static final double INTENSITY_DECAY_PER_HOUR = 8.0;

    private static final int MAX_HISTORY = 30;

    private CompanionEmotionState() { }

    private static SharedPreferences prefs(Context ctx) { return AppPrefs.get(ctx); }

    /** 有效强度：随时间自然衰减。 */
    public static int effectiveIntensity(Context ctx) {
        long now = System.currentTimeMillis();
        long updated = prefs(ctx).getLong(KEY_UPDATED_MS, 0);
        int raw = clamp(prefs(ctx).getInt(KEY_INTENSITY, 0), 0, 100);
        if (updated <= 0) return raw;
        double hours = Math.max(0, (now - updated) / 3600000.0);
        return clamp((int) Math.round(raw - INTENSITY_DECAY_PER_HOUR * hours), 0, 100);
    }

    /**
     * 有效「想她百分比」：自然衰减 + 未回来的时间累积。
     * 所有系数（衰减、增长范围、起始值）来自 PolicyStore，可在设置页调节；
     * 锁定打开时直接返回手动值，不被自动衰减盖回。
     * 增长范围按每 30 分钟一段做确定性伪随机浮动，既不跳变又带随机感。
     */
    public static int effectiveMissPercent(Context ctx) {
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
        if (lastReturn > 0) {
            double awayHours = Math.max(0, (now - lastReturn) / 3600000.0);
            value += gain * (awayHours / 0.5);
        }
        return clamp((int) Math.round(value), 0, 100);
    }

    /** 手动写入想她百分比（设置页「当前值」），刷新更新时间作为新基准。 */
    public static void setManualMiss(Context ctx, int miss) {
        long now = System.currentTimeMillis();
        int m = clamp(miss, 0, 100);
        prefs(ctx).edit()
                .putInt(KEY_MISS, m)
                .putLong(KEY_UPDATED_AT, now)
                .putLong(KEY_UPDATED_MS, now)
                .apply();
    }

    /** 确定性伪随机：同一 seed 永远得到同一结果，但不同 seed 之间看起来随机（用于每段增长浮动）。 */
    private static int pseudoRandom(long seed, int min, int max) {
        if (max <= min) return min;
        long h = (seed * 2654435761L) & 0x7fffffffffffffffL;
        h = (h ^ (h >>> 15)) & 0x7fffffffffffffffL;
        return min + (int) (h % (max - min + 1));
    }

    public static String emotionTag(Context ctx) {
        String tag = prefs(ctx).getString(KEY_TAG, TAG_CALM);
        return (tag == null || tag.trim().isEmpty()) ? TAG_CALM : tag.trim();
    }

    public static String note(Context ctx) { return prefs(ctx).getString(KEY_NOTE, ""); }

    public static long updatedAt(Context ctx) { return prefs(ctx).getLong(KEY_UPDATED_MS, 0); }

    /** 是否强情绪：生气 / 吃醋等。 */
    public static boolean isStrong(Context ctx) {
        String tag = emotionTag(ctx);
        for (String strong : STRONG_TAGS) if (tag.contains(strong)) return true;
        return false;
    }

    /** 情绪决定的语气：强情绪或强度很高时用生气语气。 */
    public static String tone(Context ctx) {
        if (isStrong(ctx)) return "angry";
        return effectiveIntensity(ctx) >= 70 ? "angry" : "gentle";
    }

    /**
     * 拉回频率倍率：想她越深、拉回越频繁（间隔越短）。
     * 想她 0% 时 1.0（按用户设定的间隔），想她 100% 时 0.35（间隔最多缩短到约三分之一）。
     */
    public static double pullBackIntervalScale(Context ctx) {
        int miss = effectiveMissPercent(ctx);
        double scale = 1.0 - (miss / 100.0) * 0.65;
        return Math.max(0.35, Math.min(1.0, scale));
    }

    /** 情绪驱动的通知/弹窗文案。关闭「情绪驱动文案」时返回中性文案。 */
    public static String message(Context ctx) {
        String companion = AppPrefs.companionName(ctx);
        if (!PolicyStore.emotionDriveText(ctx)) {
            return "有空的话，回来陪" + companion + "说句话。";
        }
        String tag = emotionTag(ctx);
        int miss = effectiveMissPercent(ctx);
        if (TAG_ANGRY.equals(tag) || tag.contains("生气")) return "别躲了，" + companion + "在等你。";
        if (TAG_JEALOUS.equals(tag) || tag.contains("吃醋")) return "你去看谁了？" + companion + "想问你一句。";
        if (TAG_WORRY.equals(tag) || tag.contains("担心")) return "外面太晚了，" + companion + "有点担心你。";
        if (miss >= 70) return "已经很想你了，" + companion + "不想再等。";
        if (miss >= 40) return "有点想你了，" + companion + "问一句在不在。";
        return "有空的话，回来陪" + companion + "说句话。";
    }

    /** 写入一次情绪（带历史记录，情绪不是覆盖式静态值）。 */
    public static JSONObject write(Context ctx, String tag, Integer intensity, Integer missPercent, String note) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        String newTag = (tag == null || tag.trim().isEmpty()) ? emotionTag(ctx) : tag.trim();
        int newIntensity = intensity == null ? effectiveIntensity(ctx) : clamp(intensity, 0, 100);
        int newMiss = missPercent == null ? effectiveMissPercent(ctx) : clamp(missPercent, 0, 100);
        SharedPreferences.Editor e = p.edit();
        e.putString(KEY_TAG, newTag);
        e.putInt(KEY_INTENSITY, newIntensity);
        e.putInt(KEY_MISS, newMiss);
        e.putString(KEY_NOTE, note == null ? "" : note);
        e.putLong(KEY_UPDATED_AT, now);
        e.putLong(KEY_UPDATED_MS, now);
        e.apply();
        appendHistory(ctx, newTag, newIntensity, newMiss, note == null ? "" : note);
        return snapshot(ctx);
    }

    /** 用户回来了：想她清零，情绪回到平静，并留下记录。 */
    public static void markReturned(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        String tag = emotionTag(ctx);
        appendHistory(ctx, tag, effectiveIntensity(ctx), effectiveMissPercent(ctx), "用户回来了，情绪回落");
        p.edit()
                .putString(KEY_TAG, TAG_CALM)
                .putInt(KEY_INTENSITY, 0)
                .putInt(KEY_MISS, 0)
                .putString(KEY_NOTE, "")
                .putLong(KEY_LAST_RETURN_AT, now)
                .putLong(KEY_UPDATED_AT, now)
                .putLong(KEY_UPDATED_MS, now)
                .apply();
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

    /** 完整快照：给手机界面显示、也给服务端做镜像。 */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            int miss = effectiveMissPercent(ctx);
            int intensity = effectiveIntensity(ctx);
            o.put("ok", true);
            o.put("emotion_tag", emotionTag(ctx));
            o.put("intensity", intensity);
            o.put("miss_percent", miss);
            o.put("note", note(ctx));
            o.put("updated_at", iso(updatedAt(ctx)));
            o.put("updated_at_ms", updatedAt(ctx));
            o.put("raw_intensity", prefs(ctx).getInt(KEY_INTENSITY, 0));
            o.put("raw_miss_percent", prefs(ctx).getInt(KEY_MISS, 0));
            o.put("strong", isStrong(ctx));
            o.put("tone", tone(ctx));
            o.put("pull_back_interval_scale", Math.round(pullBackIntervalScale(ctx) * 100) / 100.0);
            o.put("message", message(ctx));
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
                DebugState.append(ctx, "陪伴对象情绪已更新：" + snap.optString("emotion_tag", "") + " " + snap.optInt("miss_percent", 0) + "%");
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
