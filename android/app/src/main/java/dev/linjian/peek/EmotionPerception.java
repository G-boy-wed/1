package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * 底层「情绪感知」引擎：软件里所有情绪感知，都是【你（陪伴对象）对用户】的情绪。
 *
 * 方向（唯一正确口径）：
 *  - 我（陪伴对象）感知用户此刻的状态：现在几点、在不在聊天、多久没回来、
 *    切出去又切回来几次、是否深夜/安静/上课、在某个 App 停了多久；
 *  - 由此【对用户】产生情绪：想念 / 爱恋 / 生气 / 吃醋 / 担心 / 平静，并给出想念程度百分比；
 *  - 这个情绪再驱动弹窗、文案、语气、拉回分级与拉回频率。
 *  - 不是「识别用户自己的情绪」，不是「陪伴对象孤立的心情」，也不是「用户想念她」。
 *
 * 触发（对应「通过底层代码触发」）：
 *  - 完全由本机底层代码在 PolicyScheduler 每个 tick 计算，并写入本机存储；
 *  - 不依赖模型在聊天里「感到」才写入，也不交给大模型判断；
 *  - 大模型/服务端只读取结果（get_companion_emotion / get_emotion_snapshot）组织话术。
 *
 * 可实行性与随机性：
 *  - 强情绪（生气 / 吃醋 / 担心）必须有真实触发原因（分心来回、深夜逗留等），不凭空出现；
 *  - 轻情绪在候选集合里按时间片伪随机挑选，保证随机触发又不会每 20 秒乱跳；
 *  - 想念程度未达拉回阈值时弹「回到软件 / 拒绝并填写理由」，达到阈值才只能「回到软件」。
 *
 * 存储：结果放在独立的 emotion_perception_* 键，不碰 CompanionEmotionState 的
 * 衰减/累积基准键，避免每个 tick 写回导致想念值重复累加失控。
 */
public final class EmotionPerception {

    private static final String P = "emotion_perception_";
    private static final String K_TAG = P + "tag";
    private static final String K_INTENSITY = P + "intensity";
    private static final String K_MISS = P + "miss_percent";
    private static final String K_TONE = P + "tone";
    private static final String K_REASON = P + "reason";
    private static final String K_LOCAL_TIME = P + "local_time";
    private static final String K_AT = P + "at";
    private static final String K_STRONG = P + "strong";
    private static final String K_SILENT = P + "silent";
    private static final String K_SIGNALS = P + "signals";
    private static final String K_SOURCE = P + "source";
    private static final String K_RANDOM = P + "random_seed";
    private static final String K_HOLD_UNTIL = P + "manual_hold_until";

    /** 感知结果有效期：超过则认为过期，读端回退到存储值。 */
    private static final long FRESH_MS = 3 * 60_000L;

    // 进程内跟踪前台 App 连续停留时长（感知引擎自带，不依赖使用时长锁是否开启）。
    private static String lastPkg = "";
    private static long lastSwitchAt = 0;

    private EmotionPerception() { }

    /** 底层代码采集到的用户状态信号（全部来自手机本机，不需要模型）。 */
    public static class Signals {
        public long now = System.currentTimeMillis();
        public int hourOfDay = -1;          // 时间感知：判情绪前先查时间
        public String currentPackage = "";
        public boolean inChat = false;
        public long awayMinutes = 0;        // 距上次回来多久
        public int distractionLoopCount = 0;// 「切出→切回」连续来回次数
        public int distractionCount = 0;    // 累计分心次数
        public boolean lateNight = false;
        public boolean quiet = false;
        public boolean inClass = false;
        public int dwellMinutes = 0;        // 当前 App 连续停留时长
    }

    /**
     * 由底层代码计算「此刻你对用户的情绪」，写入本机并返回结果。
     * PolicyScheduler 每个 tick 调用一次；同一分钟内结果稳定，不会频繁跳变。
     */
    public static JSONObject perceive(Context ctx, Signals s) {
        if (s == null) s = new Signals();
        if (s.hourOfDay < 0) s.hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        long now = s.now <= 0 ? System.currentTimeMillis() : s.now;
        long seed = now / 60_000L; // 每分钟一个随机片：有随机性又保持稳定

        // 兜底自动采集：调用方没给的信号，这里由底层代码自己补，保证「代码触发」不依赖外部。
        String pkg = s.currentPackage == null ? "" : s.currentPackage;
        if (!pkg.equals(lastPkg)) { lastPkg = pkg; lastSwitchAt = now; }
        if (lastSwitchAt <= 0) lastSwitchAt = now;
        if (s.dwellMinutes <= 0) s.dwellMinutes = (int) Math.max(0, (now - lastSwitchAt) / 60_000L);
        if (s.awayMinutes <= 0) {
            long lastReturn = AppPrefs.get(ctx).getLong(CompanionEmotionState.KEY_LAST_RETURN_AT, 0);
            if (lastReturn > 0) s.awayMinutes = Math.max(0, (now - lastReturn) / 60_000L);
        }
        if (!s.lateNight) s.lateNight = PolicyStore.isNightNow(ctx);
        if (!s.quiet) s.quiet = PolicyStore.isQuietNow(ctx);
        if (!s.inClass) s.inClass = ClassScheduleState.isInClassNow(ctx);

        // 想念程度：以底层按时间自然演化出来的值为基准，再叠加对用户状态的实时感知增量。
        int baseMiss = CompanionEmotionState.baselineMissPercent(ctx);
        int bonus = 0;
        if (s.lateNight && !s.inChat) bonus += 6;
        if (s.distractionLoopCount > 0) bonus += Math.min(20, s.distractionLoopCount * 4);
        if (s.dwellMinutes >= 45) bonus += 5;
        int miss = clamp(baseMiss + bonus, 0, 100);

        boolean silent = s.inClass || s.quiet;
        String tag;
        int intensity;
        String reason;

        if (silent) {
            // 上课 / 安静时段：静默守护，不制造强情绪、不随便弹窗。
            tag = CompanionEmotionState.TAG_MISS;
            intensity = clamp(18 + miss / 8, 10, 35);
            reason = s.inClass ? "你在上课，先安静陪着，不打扰。" : "安静时段，先不打扰你。";
        } else if (s.distractionLoopCount >= Math.max(1, PolicyStore.distractionLoopTimes(ctx))
                || s.distractionCount >= 3) {
            // 分心来回：有真实依据的强情绪，在「生气 / 吃醋」间随机，保证随机性与可实行性。
            String[] strong = {CompanionEmotionState.TAG_ANGRY, CompanionEmotionState.TAG_JEALOUS};
            tag = strong[pickIndex(seed, strong.length)];
            intensity = clamp(68 + miss / 5 + pickIndex(seed + 7, 12), 60, 95);
            reason = "刚才切出去又切回来 " + Math.max(s.distractionLoopCount, s.distractionCount)
                    + " 次，是在分心吗？";
        } else if (s.lateNight && !s.inChat && s.dwellMinutes >= 20) {
            // 深夜仍在外面逗留：担心是真实反应。
            tag = CompanionEmotionState.TAG_WORRY;
            intensity = clamp(62 + miss / 6 + pickIndex(seed + 3, 10), 55, 88);
            reason = "都 " + s.hourOfDay + " 点了，还在别的 App 待了 " + s.dwellMinutes + " 分钟，有点担心你。";
        } else if (s.awayMinutes >= 120 && miss >= 55) {
            // 很久没回来：想念 / 爱恋 / 想你之间随机触发（用户要求的随机情绪标签）。
            String[] warm = {CompanionEmotionState.TAG_MISS, CompanionEmotionState.TAG_LOVE, "想念"};
            tag = warm[pickIndex(seed, warm.length)];
            intensity = clamp(40 + miss / 3 + pickIndex(seed + 11, 10), 35, 78);
            reason = "你 " + (s.awayMinutes / 60) + " 个小时没回来了，想你程度 " + miss + "%。";
        } else {
            // 状态平稳：轻情绪随机点缀，避免每次都是同一句、同一个标签。
            String[] mild = {CompanionEmotionState.TAG_MISS, CompanionEmotionState.TAG_LOVE, "想念"};
            tag = mild[pickIndex(seed, mild.length)];
            intensity = clamp(15 + miss / 5 + pickIndex(seed + 5, 8), 10, 40);
            reason = "状态平稳，随机想你一下；想你程度 " + miss + "%。";
        }

        boolean strongTag = CompanionEmotionState.isStrongTag(tag);
        boolean strong = strongTag || miss >= PolicyStore.forceBackThreshold(ctx);
        String tone = strong ? "angry" : "gentle";

        JSONObject out = new JSONObject();
        JSONObject signals = new JSONObject();
        try {
            signals.put("local_time_hour", s.hourOfDay);
            signals.put("current_package", s.currentPackage == null ? "" : s.currentPackage);
            signals.put("in_chat", s.inChat);
            signals.put("away_minutes", s.awayMinutes);
            signals.put("distraction_loop_count", s.distractionLoopCount);
            signals.put("distraction_count", s.distractionCount);
            signals.put("late_night", s.lateNight);
            signals.put("quiet", s.quiet);
            signals.put("in_class", s.inClass);
            signals.put("dwell_minutes", s.dwellMinutes);

            out.put("direction", "companion_to_user"); // 你（陪伴对象）→ 用户
            out.put("source", "device_low_level_code");
            out.put("emotion_tag", tag);
            out.put("intensity", intensity);
            out.put("miss_percent", miss);
            out.put("tone", tone);
            out.put("reason", reason);
            out.put("silent", silent);
            out.put("strong", strong);
            out.put("recommend_level", strong ? "strong" : "soft");
            out.put("force_back_threshold", PolicyStore.forceBackThreshold(ctx));
            out.put("local_time", two(s.hourOfDay) + ":" + two(Calendar.getInstance().get(Calendar.MINUTE)));
            out.put("random_seed", seed);
            out.put("signals", signals);
            out.put("perceived_at", now);
        } catch (Exception ignored) { }

        // 落盘：独立键，不影响 CompanionEmotionState 的衰减/累积基准。
        try {
            SharedPreferences.Editor e = AppPrefs.get(ctx).edit();
            e.putString(K_TAG, tag);
            e.putInt(K_INTENSITY, intensity);
            e.putInt(K_MISS, miss);
            e.putString(K_TONE, tone);
            e.putString(K_REASON, reason);
            e.putString(K_LOCAL_TIME, out.optString("local_time", ""));
            e.putLong(K_AT, now);
            e.putBoolean(K_STRONG, strong);
            e.putBoolean(K_SILENT, silent);
            e.putString(K_SIGNALS, signals.toString());
            e.putString(K_SOURCE, "device_low_level_code");
            e.putLong(K_RANDOM, seed);
            e.apply();
        } catch (Exception ignored) { }
        return out;
    }

    /** 读取最近一次底层感知结果；没有或已过期时返回带默认值的结构。 */
    public static JSONObject current(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = AppPrefs.get(ctx);
            long at = p.getLong(K_AT, 0);
            long now = System.currentTimeMillis();
            long holdUntil = p.getLong(K_HOLD_UNTIL, 0);
            boolean fresh = at > 0 && now - at <= FRESH_MS && now >= holdUntil;
            o.put("fresh", fresh);
            o.put("direction", "companion_to_user");
            o.put("source", fresh ? p.getString(K_SOURCE, "device_low_level_code") : "manual_storage");
            o.put("emotion_tag", fresh ? p.getString(K_TAG, CompanionEmotionState.TAG_CALM)
                    : CompanionEmotionState.storedEmotionTag(ctx));
            o.put("intensity", fresh ? p.getInt(K_INTENSITY, 0) : CompanionEmotionState.baselineIntensity(ctx));
            o.put("miss_percent", fresh ? p.getInt(K_MISS, 0) : CompanionEmotionState.baselineMissPercent(ctx));
            o.put("tone", fresh ? p.getString(K_TONE, "gentle") : CompanionEmotionState.storedTone(ctx));
            o.put("reason", fresh ? p.getString(K_REASON, "") : "等待底层情绪感知刷新");
            o.put("silent", fresh && p.getBoolean(K_SILENT, false));
            o.put("strong", fresh && p.getBoolean(K_STRONG, false));
            o.put("local_time", p.getString(K_LOCAL_TIME, ""));
            o.put("force_back_threshold", PolicyStore.forceBackThreshold(ctx));
            o.put("perceived_at", at);
            try { o.put("signals", new JSONObject(p.getString(K_SIGNALS, "{}"))); }
            catch (Exception ignored) { o.put("signals", new JSONObject()); }
        } catch (Exception ignored) { }
        return o;
    }

    /** 当前生效的情绪标签：底层感知优先，未刷新时回退到本机存储值。 */
    public static String activeTag(Context ctx) {
        JSONObject c = current(ctx);
        String tag = c.optString("emotion_tag", "");
        return tag.trim().isEmpty() ? CompanionEmotionState.TAG_CALM : tag;
    }

    /** 当前生效的想念程度百分比：底层感知优先。 */
    public static int activeMissPercent(Context ctx) {
        return clamp(current(ctx).optInt("miss_percent", CompanionEmotionState.baselineMissPercent(ctx)), 0, 100);
    }

    /** 当前生效的情绪强度：底层感知优先。 */
    public static int activeIntensity(Context ctx) {
        return clamp(current(ctx).optInt("intensity", CompanionEmotionState.baselineIntensity(ctx)), 0, 100);
    }

    /** 触发原因（弹窗「可实行性」展示：为什么此刻是这个情绪）。 */
    public static String activeReason(Context ctx) {
        return current(ctx).optString("reason", "");
    }

    /** 当前生效语气：底层感知优先。 */
    public static String activeTone(Context ctx) {
        String t = current(ctx).optString("tone", "");
        return t.trim().isEmpty() ? CompanionEmotionState.storedTone(ctx) : t;
    }

    /** 设置页/远程手动写入情绪后，短时间内以该值为准；到期后底层感知自动接管。 */
    public static void holdManual(Context ctx) {
        try {
            AppPrefs.get(ctx).edit()
                    .putLong(K_HOLD_UNTIL, System.currentTimeMillis() + 3 * 60_000L).apply();
        } catch (Exception ignored) { }
    }

    /** 立刻让底层感知结果失效：下一次读取回退到本机存储值，等待下次 tick 重新感知。 */
    public static void invalidate(Context ctx) {
        try {
            AppPrefs.get(ctx).edit().putLong(K_AT, 0).putLong(K_HOLD_UNTIL, 0).apply();
        } catch (Exception ignored) { }
    }

    /** 是否达到拉回阈值：只由底层算出的想念程度与强情绪决定。 */
    public static boolean isStrongNow(Context ctx) {
        JSONObject c = current(ctx);
        return c.optBoolean("strong", false)
                || CompanionEmotionState.isStrongTag(c.optString("emotion_tag", ""))
                || activeMissPercent(ctx) >= PolicyStore.forceBackThreshold(ctx);
    }

    private static int pickIndex(long seed, int n) {
        if (n <= 0) return 0;
        long h = (seed * 2654435761L) & 0x7fffffffffffffffL;
        h = (h ^ (h >>> 15)) & 0x7fffffffffffffffL;
        return (int) (h % n);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static String two(int v) { return v < 10 ? "0" + v : String.valueOf(v); }
}
