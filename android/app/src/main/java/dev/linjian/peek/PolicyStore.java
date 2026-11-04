package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本机「查岗与管束」配置真相源。
 *
 * 设计原则（对应需求）：
 *  1. 全部参数只存本机 SharedPreferences，改完立即生效，不依赖后端 / MCP / 重启；
 *  2. 每一项都有默认值，可一键恢复默认；
 *  3. 安静时段支持跨零点（如 23:30 → 08:00）；
 *  4. 冷却时间内不重复提醒；改参数后冷却重新计算；
 *  5. 手动改过的想你程度可「锁定」，不被自动衰减盖回去；
 *  6. 参数变更留一行记录：谁改的、什么时候、改了什么；
 *  7. 支持导出 / 导入一份配置 JSON（用于重装或换机后恢复）；
 *  8. 数据只存本机，不上云（若用户配置了服务器，仅作可选同步，永不阻塞 UI）。
 *
 * 网络相关的读取/写入一律不在这里发生，UI 与本地调度器只依赖本文件。
 */
public final class PolicyStore {

    public static final String PREFS = "policy_store";
    private static final String PREFIX = "ps_";

    // —— 总开关（反向入口：一键全部关闭）——
    public static final String KEY_MASTER = PREFIX + "master_enabled";          // 默认 true

    // —— 查岗与拉回 ——
    public static final String KEY_RANDOM_UPPER = PREFIX + "random_upper_min";   // 随机上限，默认 60
    public static final String KEY_RANDOM_LOWER = PREFIX + "random_lower_min";   // 随机下限（避免连续挨着弹），默认 8
    public static final String KEY_FORCE_BACK_THRESHOLD = PREFIX + "fb_threshold"; // 拉回分级阈值，默认 70
    public static final String KEY_DECLINE_REASON_REQUIRED = PREFIX + "decline_required"; // 拒绝理由必填，默认 false
    public static final String KEY_TARGET_PACKAGE = PREFIX + "target_package";   // 拉回目标 App，默认 com.psyche.kelivo
    public static final String KEY_THEME_OVERRIDE = PREFIX + "theme_override";   // 弹窗主题覆盖（空=跟随归电）
    public static final String KEY_POSITION_OVERRIDE = PREFIX + "position_override"; // 弹窗位置覆盖（空=跟随归电）
    public static final String KEY_COOLDOWN = PREFIX + "cooldown_min";           // 冷却分钟，默认 5
    public static final String KEY_CHECKIN_ENABLED = PREFIX + "checkin_enabled"; // 是否开启查岗，默认 true

    // —— 陪伴情绪（参数可调；实际值仍由 CompanionEmotionState 存储与演化）——
    public static final String KEY_EMOTION_MISS = "companion_emotion_miss_percent"; // 复用 CompanionEmotionState 的键，单一存储
    public static final String KEY_EMOTION_START = PREFIX + "emo_start_miss";     // 起始值，默认 8
    public static final String KEY_EMOTION_GAIN_MIN = PREFIX + "emo_gain_min";   // 每 30 分钟增长下限，默认 2
    public static final String KEY_EMOTION_GAIN_MAX = PREFIX + "emo_gain_max";   // 每 30 分钟增长上限，默认 5
    public static final String KEY_EMOTION_DECAY = PREFIX + "emo_decay_per_hour"; // 回落速度（每小时点数），默认 4
    public static final String KEY_EMOTION_DRIVE_TEXT = PREFIX + "emo_drive_text"; // 是否驱动文案，默认 true
    public static final String KEY_EMOTION_LOCK = PREFIX + "emo_lock";           // 想你锁定，默认 false

    // —— 主动找她 ——
    public static final String KEY_INITIATIVE_ENABLED = PREFIX + "init_enabled";   // 总开关，默认 true
    public static final String KEY_INITIATIVE_SILENCE = PREFIX + "init_silence_min"; // 沉默多久后主动发，默认 30
    public static final String KEY_INITIATIVE_INTERVAL = PREFIX + "init_interval_min"; // 两次最小间隔，默认 20
    public static final String KEY_INITIATIVE_DAILY = PREFIX + "init_daily_limit"; // 每日条数上限，0=不限，默认 0
    public static final String KEY_INITIATIVE_MODE = PREFIX + "init_mode";        // free / template，默认 free
    public static final String KEY_INITIATIVE_TEMPLATE = PREFIX + "init_template"; // 自定义模板

    // —— 训诫插件 ——
    public static final String KEY_DISCIPLINE_ENABLED = PREFIX + "disc_enabled";  // 总开关，默认 false
    public static final String KEY_DISCIPLINE_LEVEL = PREFIX + "disc_level";     // 0~4 档，默认 0
    public static final String KEY_DISCIPLINE_USER_ONLY = PREFIX + "disc_user_only"; // 仅用户本人能开，默认 true
    public static final String KEY_DISCIPLINE_BADGE = PREFIX + "disc_badge";     // 开启标志，默认 true

    // —— 分心判定 ——
    public static final String KEY_DISTRACTION_ENABLED = PREFIX + "dist_enabled"; // 切屏算不算分心，默认 true
    public static final String KEY_DISTRACTION_EVERY = PREFIX + "dist_every";    // 是否每次切屏都拉回，默认 false
    public static final String KEY_DISTRACTION_THRESHOLD = PREFIX + "dist_threshold";   // 累计分心阈值（默认 5 次）
    public static final String KEY_DISTRACTION_AUTO_PULLBACK = PREFIX + "dist_auto_pullback"; // 超阈值自动强拉回，默认 true
    public static final String KEY_DISTRACTION_COUNT = PREFIX + "dist_count";     // 当前连续分心计数（运行时累计）
    public static final String KEY_DISTRACTION_LOOP_ENABLED = PREFIX + "dist_loop_enabled"; // 「切出→切回」算分心，默认 true
    public static final String KEY_DISTRACTION_LOOP_TIMES = PREFIX + "dist_loop_times";     // 来回次数阈值，默认 2
    public static final String KEY_DISTRACTION_LOOP_WINDOW = PREFIX + "dist_loop_window";   // 来回时间窗口(秒)，默认 90
    public static final String KEY_DISCIPLINE_MODE = PREFIX + "disc_mode";       // 训诫语气 gentle / strict，默认 gentle

    // —— 应用使用时长自动锁 ——
    public static final String KEY_USAGE_LOCK_ENABLED = PREFIX + "usage_lock_enabled"; // 连续使用超 N 分钟自动锁，默认 false
    public static final String KEY_USAGE_LOCK_MINUTES = PREFIX + "usage_lock_min";     // 连续使用 N 分钟触发锁定，默认 45
    public static final String KEY_USAGE_LOCK_DURATION = PREFIX + "usage_lock_dur";    // 锁定 M 分钟，默认 15
    public static final String KEY_USAGE_LOCK_PACKAGES = PREFIX + "usage_lock_pkgs";   // 监控的包名（逗号分隔，空=仅目标 App）

    // —— 识图感知（可开关；默认开启，用户可在「查岗与管束」里关闭）——
    public static final String KEY_OCR_ENABLED = PREFIX + "ocr_enabled";          // 识图感知总开关，默认 true

    // —— 情绪感知自动弹窗（想你程度达阈值时，独立触发「同意/拒绝」全局弹窗）——
    public static final String KEY_EMOTION_POPUP_ENABLED = PREFIX + "emo_popup_enabled";   // 自动弹窗总开关，默认 true
    public static final String KEY_EMOTION_POPUP_THRESHOLD = PREFIX + "emo_popup_threshold"; // 想你达到该 % 触发，默认 70
    public static final String KEY_EMOTION_POPUP_INTERVAL = PREFIX + "emo_popup_interval";  // 两次最小间隔（分钟），默认 30

    // —— 安静时段 / 深夜时段（支持跨零点）——
    public static final String KEY_QUIET_START = PREFIX + "quiet_start";         // "23:30"
    public static final String KEY_QUIET_END = PREFIX + "quiet_end";             // "08:00"
    public static final String KEY_NIGHT_START = PREFIX + "night_start";         // "23:00"
    public static final String KEY_NIGHT_END = PREFIX + "night_end";             // "06:00"

    // —— 权限开关（逐项）——
    public static final String KEY_PERM_SCREEN_TIME = PREFIX + "perm_screen_time"; // 屏幕时长上限（value=分钟，0=不限）
    public static final String KEY_PERM_SLEEP = PREFIX + "perm_sleep";            // 睡前息屏（value=时间 HH:mm）
    public static final String KEY_PERM_CURFEW = PREFIX + "perm_curfew";         // 深夜宵禁（value=时段 23:00-06:00）
    public static final String KEY_PERM_STUDY = PREFIX + "perm_study";           // 学习白名单（value=包名逗号分隔）
    public static final String KEY_PERM_DRINK = PREFIX + "perm_drink";           // 喝水提醒（value=间隔分钟）
    public static final String KEY_PERM_SIT = PREFIX + "perm_sit";               // 久坐提醒（value=间隔分钟）
    public static final String KEY_PERM_WEEKLY = PREFIX + "perm_weekly";         // 每周回顾（value=星期,HH:mm）
    public static final String KEY_PERM_HOME = PREFIX + "perm_home";             // 到家报备（value=地点/地理围栏）
    public static final String KEY_PERM_PERIOD = PREFIX + "perm_period";         // 生理期照顾（value=周期天数）

    public static final String KEY_CHANGE_LOG = PREFIX + "change_log";           // 变更记录 JSONArray 字符串

    // —— 默认值 ——
    private static final boolean DEF_MASTER = true;
    private static final int DEF_RANDOM_UPPER = 60;
    private static final int DEF_RANDOM_LOWER = 8;
    private static final int DEF_THRESHOLD = 70;
    private static final boolean DEF_DECLINE_REQUIRED = false;
    private static final String DEF_TARGET_PACKAGE = "com.psyche.kelivo";
    private static final int DEF_COOLDOWN = 5;
    private static final boolean DEF_CHECKIN = true;
    private static final int DEF_EMO_START = 8;
    private static final int DEF_EMO_GAIN_MIN = 2;
    private static final int DEF_EMO_GAIN_MAX = 5;
    private static final int DEF_EMO_DECAY = 4;
    private static final boolean DEF_EMO_DRIVE = true;
    private static final boolean DEF_EMO_LOCK = false;
    private static final boolean DEF_INIT_ENABLED = true;
    private static final int DEF_INIT_SILENCE = 30;
    private static final int DEF_INIT_INTERVAL = 20;
    private static final int DEF_INIT_DAILY = 0;
    private static final String DEF_INIT_MODE = "free";
    private static final boolean DEF_DISC_ENABLED = false;
    private static final int DEF_DISC_LEVEL = 0;
    private static final boolean DEF_DISC_USER_ONLY = true;
    private static final boolean DEF_DISC_BADGE = true;
    private static final boolean DEF_DIST_ENABLED = true;
    private static final boolean DEF_DIST_EVERY = false;
    private static final int DEF_DIST_THRESHOLD = 5;
    private static final boolean DEF_DIST_AUTO_PULLBACK = true;
    private static final boolean DEF_DIST_LOOP_ENABLED = true;
    private static final int DEF_DIST_LOOP_TIMES = 2;
    private static final int DEF_DIST_LOOP_WINDOW = 90;
    private static final String DEF_DISC_MODE = "gentle";
    private static final boolean DEF_USAGE_LOCK_ENABLED = false;
    private static final int DEF_USAGE_LOCK_MIN = 45;
    private static final int DEF_USAGE_LOCK_DUR = 15;
    private static final boolean DEF_OCR_ENABLED = true;
    private static final boolean DEF_EMO_POPUP_ENABLED = true;
    private static final int DEF_EMO_POPUP_THRESHOLD = 70;
    private static final int DEF_EMO_POPUP_INTERVAL = 30;
    private static final String DEF_QUIET_START = "23:30";
    private static final String DEF_QUIET_END = "08:00";
    private static final String DEF_NIGHT_START = "23:00";
    private static final String DEF_NIGHT_END = "06:00";

    private static final int MAX_LOG = 200;
    private static final int MAX_HISTORY_FILE = 10;

    private PolicyStore() { }

    public static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 把之前 apply() 的改动同步落盘，供设置页「保存设置」按钮显式确认持久化。 */
    public static void flush(Context ctx) {
        try { sp(ctx).edit().commit(); } catch (Exception ignored) { }
    }

    // ============ 通用读写（带变更记录）============

    public static boolean getBool(Context ctx, String key, boolean def) {
        return sp(ctx).getBoolean(key, def);
    }
    public static int getInt(Context ctx, String key, int def) {
        return sp(ctx).getInt(key, def);
    }
    public static float getFloat(Context ctx, String key, float def) {
        return sp(ctx).getFloat(key, def);
    }
    public static String getString(Context ctx, String key, String def) {
        String v = sp(ctx).getString(key, null);
        return v == null ? def : v;
    }

    /** 设置一个布尔值；变化时记录变更日志。 */
    private static void putBool(Context ctx, String key, boolean v, String label) {
        SharedPreferences.Editor e = sp(ctx).edit();
        boolean old = sp(ctx).getBoolean(key, false);
        if (old == v) { e.putBoolean(key, v).apply(); return; }
        e.putBoolean(key, v).apply();
        logChange(ctx, "用户", label, String.valueOf(old), String.valueOf(v));
        resetCooldown(ctx);
    }
    private static void putInt(Context ctx, String key, int v, String label) {
        SharedPreferences.Editor e = sp(ctx).edit();
        int old = sp(ctx).getInt(key, Integer.MIN_VALUE);
        if (old == v) { e.putInt(key, v).apply(); return; }
        e.putInt(key, v).apply();
        logChange(ctx, "用户", label, String.valueOf(old), String.valueOf(v));
        resetCooldown(ctx);
    }
    private static void putString(Context ctx, String key, String v, String label) {
        if (v == null) v = "";
        SharedPreferences.Editor e = sp(ctx).edit();
        String old = sp(ctx).getString(key, "");
        if (old.equals(v)) { e.putString(key, v).apply(); return; }
        e.putString(key, v).apply();
        logChange(ctx, "用户", label, old, v);
        resetCooldown(ctx);
    }

    // ============ 业务取值 ============

    public static boolean isMasterOn(Context ctx) { return getBool(ctx, KEY_MASTER, DEF_MASTER); }
    public static void setMaster(Context ctx, boolean v) { putBool(ctx, KEY_MASTER, v, "一键总开关"); }

    public static int randomUpper(Context ctx) { return getInt(ctx, KEY_RANDOM_UPPER, DEF_RANDOM_UPPER); }
    public static void setRandomUpper(Context ctx, int v) { putInt(ctx, KEY_RANDOM_UPPER, clamp(v, 5, 1440), "查岗随机上限(分钟)"); }
    public static int randomLower(Context ctx) { return getInt(ctx, KEY_RANDOM_LOWER, DEF_RANDOM_LOWER); }
    public static void setRandomLower(Context ctx, int v) { putInt(ctx, KEY_RANDOM_LOWER, clamp(v, 1, 120), "查岗随机下限(分钟)"); }

    public static int forceBackThreshold(Context ctx) { return getInt(ctx, KEY_FORCE_BACK_THRESHOLD, DEF_THRESHOLD); }
    public static void setForceBackThreshold(Context ctx, int v) { putInt(ctx, KEY_FORCE_BACK_THRESHOLD, clamp(v, 1, 100), "拉回分级阈值"); }

    public static boolean declineReasonRequired(Context ctx) { return getBool(ctx, KEY_DECLINE_REASON_REQUIRED, DEF_DECLINE_REQUIRED); }
    public static void setDeclineReasonRequired(Context ctx, boolean v) { putBool(ctx, KEY_DECLINE_REASON_REQUIRED, v, "拒绝理由必填"); }

    public static String targetPackage(Context ctx) {
        String v = getString(ctx, KEY_TARGET_PACKAGE, DEF_TARGET_PACKAGE);
        return (v == null || v.trim().isEmpty()) ? DEF_TARGET_PACKAGE : v.trim();
    }
    public static void setTargetPackage(Context ctx, String v) { putString(ctx, KEY_TARGET_PACKAGE, v, "拉回目标App"); }

    /** 弹窗主题：空字符串表示跟随归电。 */
    public static String themeOverride(Context ctx) { return getString(ctx, KEY_THEME_OVERRIDE, ""); }
    public static void setThemeOverride(Context ctx, String v) { putString(ctx, KEY_THEME_OVERRIDE, v == null ? "" : v, "弹窗主题覆盖"); }

    /** 弹窗位置：空字符串表示跟随归电；否则 top/center/bottom。 */
    public static String positionOverride(Context ctx) { return getString(ctx, KEY_POSITION_OVERRIDE, ""); }
    public static void setPositionOverride(Context ctx, String v) { putString(ctx, KEY_POSITION_OVERRIDE, v == null ? "" : v, "弹窗位置覆盖"); }

    public static int cooldownMinutes(Context ctx) { return getInt(ctx, KEY_COOLDOWN, DEF_COOLDOWN); }
    public static void setCooldown(Context ctx, int v) { putInt(ctx, KEY_COOLDOWN, clamp(v, 0, 240), "冷却时间(分钟)"); }
    public static boolean checkinEnabled(Context ctx) { return getBool(ctx, KEY_CHECKIN_ENABLED, DEF_CHECKIN); }
    public static void setCheckinEnabled(Context ctx, boolean v) { putBool(ctx, KEY_CHECKIN_ENABLED, v, "开启查岗"); }

    // —— 陪伴情绪参数 ——
    public static int emotionStartMiss(Context ctx) { return getInt(ctx, KEY_EMOTION_START, DEF_EMO_START); }
    public static void setEmotionStartMiss(Context ctx, int v) { putInt(ctx, KEY_EMOTION_START, clamp(v, 0, 100), "情绪起始值"); }
    public static int emotionGainMin(Context ctx) { return getInt(ctx, KEY_EMOTION_GAIN_MIN, DEF_EMO_GAIN_MIN); }
    public static void setEmotionGainMin(Context ctx, int v) { putInt(ctx, KEY_EMOTION_GAIN_MIN, clamp(v, 0, 20), "每30分钟增长下限"); }
    public static int emotionGainMax(Context ctx) { return getInt(ctx, KEY_EMOTION_GAIN_MAX, DEF_EMO_GAIN_MAX); }
    public static void setEmotionGainMax(Context ctx, int v) { putInt(ctx, KEY_EMOTION_GAIN_MAX, clamp(v, 0, 20), "每30分钟增长上限"); }
    public static int emotionDecayPerHour(Context ctx) { return getInt(ctx, KEY_EMOTION_DECAY, DEF_EMO_DECAY); }
    public static void setEmotionDecayPerHour(Context ctx, int v) { putInt(ctx, KEY_EMOTION_DECAY, clamp(v, 0, 50), "情绪回落速度"); }
    public static boolean emotionDriveText(Context ctx) { return getBool(ctx, KEY_EMOTION_DRIVE_TEXT, DEF_EMO_DRIVE); }
    public static void setEmotionDriveText(Context ctx, boolean v) { putBool(ctx, KEY_EMOTION_DRIVE_TEXT, v, "情绪驱动文案"); }
    public static boolean emotionLock(Context ctx) { return getBool(ctx, KEY_EMOTION_LOCK, DEF_EMO_LOCK); }
    public static void setEmotionLock(Context ctx, boolean v) { putBool(ctx, KEY_EMOTION_LOCK, v, "想你锁定"); }

    /** 手动改「想你程度当前值」。未锁定时调度器会在此基础上演化；锁定时固定不变。 */
    public static int emotionCurrentMiss(Context ctx) { return sp(ctx).getInt(KEY_EMOTION_MISS, 0); }
    public static void setEmotionCurrentMiss(Context ctx, int v) {
        int c = clamp(v, 0, 100);
        int old = sp(ctx).getInt(KEY_EMOTION_MISS, 0);
        CompanionEmotionState.setManualMiss(ctx, c); // 同时刷新更新时间作为新基准
        if (old != c) logChange(ctx, "用户", "想你程度当前值", String.valueOf(old), String.valueOf(c));
    }

    // —— 主动找她 ——
    public static boolean initiativeEnabled(Context ctx) { return getBool(ctx, KEY_INITIATIVE_ENABLED, DEF_INIT_ENABLED); }
    public static void setInitiativeEnabled(Context ctx, boolean v) { putBool(ctx, KEY_INITIATIVE_ENABLED, v, "主动找她开关"); }
    public static int initiativeSilenceMinutes(Context ctx) { return getInt(ctx, KEY_INITIATIVE_SILENCE, DEF_INIT_SILENCE); }
    public static void setInitiativeSilenceMinutes(Context ctx, int v) { putInt(ctx, KEY_INITIATIVE_SILENCE, clamp(v, 1, 1440), "沉默阈值(分钟)"); }
    public static int initiativeMinInterval(Context ctx) { return getInt(ctx, KEY_INITIATIVE_INTERVAL, DEF_INIT_INTERVAL); }
    public static void setInitiativeMinInterval(Context ctx, int v) { putInt(ctx, KEY_INITIATIVE_INTERVAL, clamp(v, 1, 1440), "两次最小间隔(分钟)"); }
    public static int initiativeDailyLimit(Context ctx) { return getInt(ctx, KEY_INITIATIVE_DAILY, DEF_INIT_DAILY); }
    public static void setInitiativeDailyLimit(Context ctx, int v) { putInt(ctx, KEY_INITIATIVE_DAILY, clamp(v, 0, 999), "每日条数上限"); }
    public static String initiativeMode(Context ctx) { return getString(ctx, KEY_INITIATIVE_MODE, DEF_INIT_MODE); }
    public static void setInitiativeMode(Context ctx, String v) { putString(ctx, KEY_INITIATIVE_MODE, v, "主动找她模式"); }
    public static String initiativeTemplate(Context ctx) { return getString(ctx, KEY_INITIATIVE_TEMPLATE, ""); }
    public static void setInitiativeTemplate(Context ctx, String v) { putString(ctx, KEY_INITIATIVE_TEMPLATE, v, "主动找她模板"); }

    // —— 训诫插件 ——
    public static boolean disciplineEnabled(Context ctx) { return getBool(ctx, KEY_DISCIPLINE_ENABLED, DEF_DISC_ENABLED); }
    public static void setDisciplineEnabled(Context ctx, boolean v) { putBool(ctx, KEY_DISCIPLINE_ENABLED, v, "训诫插件开关"); }
    public static int disciplineLevel(Context ctx) { return getInt(ctx, KEY_DISCIPLINE_LEVEL, DEF_DISC_LEVEL); }
    public static void setDisciplineLevel(Context ctx, int v) { putInt(ctx, KEY_DISCIPLINE_LEVEL, clamp(v, 0, 4), "训诫档位"); }
    public static boolean disciplineUserOnly(Context ctx) { return getBool(ctx, KEY_DISCIPLINE_USER_ONLY, DEF_DISC_USER_ONLY); }
    public static boolean disciplineBadge(Context ctx) { return getBool(ctx, KEY_DISCIPLINE_BADGE, DEF_DISC_BADGE); }
    public static void setDisciplineBadge(Context ctx, boolean v) { putBool(ctx, KEY_DISCIPLINE_BADGE, v, "训诫开启标志"); }

    // 训诫语气模式：gentle 温柔 / strict 严厉；开启后人设语气按所选模式变化。
    public static String disciplineMode(Context ctx) {
        return "strict".equalsIgnoreCase(getString(ctx, KEY_DISCIPLINE_MODE, DEF_DISC_MODE)) ? "strict" : "gentle";
    }
    public static void setDisciplineMode(Context ctx, String v) {
        putString(ctx, KEY_DISCIPLINE_MODE, "strict".equalsIgnoreCase(v) ? "strict" : "gentle", "训诫语气模式");
    }

    // —— 分心判定 ——
    public static boolean distractionEnabled(Context ctx) { return getBool(ctx, KEY_DISTRACTION_ENABLED, DEF_DIST_ENABLED); }
    public static void setDistractionEnabled(Context ctx, boolean v) { putBool(ctx, KEY_DISTRACTION_ENABLED, v, "切屏算分心"); }
    public static boolean distractionEveryTime(Context ctx) { return getBool(ctx, KEY_DISTRACTION_EVERY, DEF_DIST_EVERY); }
    public static void setDistractionEveryTime(Context ctx, boolean v) { putBool(ctx, KEY_DISTRACTION_EVERY, v, "每次切屏都拉回"); }
    public static int distractionThreshold(Context ctx) { return getInt(ctx, KEY_DISTRACTION_THRESHOLD, DEF_DIST_THRESHOLD); }
    public static void setDistractionThreshold(Context ctx, int v) { putInt(ctx, KEY_DISTRACTION_THRESHOLD, clamp(v, 1, 999), "分心自动拉回阈值(次)"); }
    public static boolean distractionAutoPullback(Context ctx) { return getBool(ctx, KEY_DISTRACTION_AUTO_PULLBACK, DEF_DIST_AUTO_PULLBACK); }
    public static void setDistractionAutoPullback(Context ctx, boolean v) { putBool(ctx, KEY_DISTRACTION_AUTO_PULLBACK, v, "超阈值自动强拉回"); }

    // 分心查岗（常开）：聊天中切出去、立马切回来，来回几次才算分心。
    public static boolean distractionLoopEnabled(Context ctx) { return getBool(ctx, KEY_DISTRACTION_LOOP_ENABLED, DEF_DIST_LOOP_ENABLED); }
    public static void setDistractionLoopEnabled(Context ctx, boolean v) { putBool(ctx, KEY_DISTRACTION_LOOP_ENABLED, v, "切出切回算分心"); }
    public static int distractionLoopTimes(Context ctx) { return getInt(ctx, KEY_DISTRACTION_LOOP_TIMES, DEF_DIST_LOOP_TIMES); }
    public static void setDistractionLoopTimes(Context ctx, int v) { putInt(ctx, KEY_DISTRACTION_LOOP_TIMES, clamp(v, 1, 99), "分心来回次数"); }
    public static int distractionLoopWindowSec(Context ctx) { return getInt(ctx, KEY_DISTRACTION_LOOP_WINDOW, DEF_DIST_LOOP_WINDOW); }
    public static void setDistractionLoopWindowSec(Context ctx, int v) { putInt(ctx, KEY_DISTRACTION_LOOP_WINDOW, clamp(v, 5, 3600), "分心来回时间窗口(秒)"); }

    /** 连续分心计数（运行时累计，回到目标 App 清零）。 */
    public static int distractionCount(Context ctx) { return getInt(ctx, KEY_DISTRACTION_COUNT, 0); }
    public static void setDistractionCount(Context ctx, int v) {
        sp(ctx).edit().putInt(KEY_DISTRACTION_COUNT, Math.max(0, v)).apply();
    }

    // —— 应用使用时长自动锁 ——
    public static boolean usageLockEnabled(Context ctx) { return getBool(ctx, KEY_USAGE_LOCK_ENABLED, DEF_USAGE_LOCK_ENABLED); }
    public static void setUsageLockEnabled(Context ctx, boolean v) { putBool(ctx, KEY_USAGE_LOCK_ENABLED, v, "连续使用自动锁"); }
    public static int usageLockMinutes(Context ctx) { return getInt(ctx, KEY_USAGE_LOCK_MINUTES, DEF_USAGE_LOCK_MIN); }
    public static void setUsageLockMinutes(Context ctx, int v) { putInt(ctx, KEY_USAGE_LOCK_MINUTES, clamp(v, 1, 1440), "连续使用阈值(分钟)"); }
    public static int usageLockDuration(Context ctx) { return getInt(ctx, KEY_USAGE_LOCK_DURATION, DEF_USAGE_LOCK_DUR); }
    public static void setUsageLockDuration(Context ctx, int v) { putInt(ctx, KEY_USAGE_LOCK_DURATION, clamp(v, 1, 1440), "自动锁定时长(分钟)"); }
    public static String usageLockPackages(Context ctx) { return getString(ctx, KEY_USAGE_LOCK_PACKAGES, ""); }
    public static void setUsageLockPackages(Context ctx, String v) { putString(ctx, KEY_USAGE_LOCK_PACKAGES, v, "时长自动锁监控包名"); }

    // —— 识图感知（可开关）——
    public static boolean ocrEnabled(Context ctx) { return getBool(ctx, KEY_OCR_ENABLED, DEF_OCR_ENABLED); }
    public static void setOcrEnabled(Context ctx, boolean v) { putBool(ctx, KEY_OCR_ENABLED, v, "识图感知开关"); }

    // —— 情绪感知自动弹窗（独立于查岗，想你达阈值即弹「同意/拒绝」）——
    public static boolean emotionPopupEnabled(Context ctx) { return getBool(ctx, KEY_EMOTION_POPUP_ENABLED, DEF_EMO_POPUP_ENABLED); }
    public static void setEmotionPopupEnabled(Context ctx, boolean v) { putBool(ctx, KEY_EMOTION_POPUP_ENABLED, v, "情绪自动弹窗开关"); }
    public static int emotionPopupThreshold(Context ctx) { return getInt(ctx, KEY_EMOTION_POPUP_THRESHOLD, DEF_EMO_POPUP_THRESHOLD); }
    public static void setEmotionPopupThreshold(Context ctx, int v) { putInt(ctx, KEY_EMOTION_POPUP_THRESHOLD, clamp(v, 1, 100), "情绪弹窗触发阈值(%)"); }
    public static int emotionPopupIntervalMin(Context ctx) { return getInt(ctx, KEY_EMOTION_POPUP_INTERVAL, DEF_EMO_POPUP_INTERVAL); }
    public static void setEmotionPopupIntervalMin(Context ctx, int v) { putInt(ctx, KEY_EMOTION_POPUP_INTERVAL, clamp(v, 1, 1440), "情绪弹窗最小间隔(分钟)"); }

    // —— 安静 / 深夜时段 ——
    public static String quietStart(Context ctx) { return getString(ctx, KEY_QUIET_START, DEF_QUIET_START); }
    public static void setQuietStart(Context ctx, String v) { putString(ctx, KEY_QUIET_START, v, "安静时段开始"); }
    public static String quietEnd(Context ctx) { return getString(ctx, KEY_QUIET_END, DEF_QUIET_END); }
    public static void setQuietEnd(Context ctx, String v) { putString(ctx, KEY_QUIET_END, v, "安静时段结束"); }
    public static String nightStart(Context ctx) { return getString(ctx, KEY_NIGHT_START, DEF_NIGHT_START); }
    public static void setNightStart(Context ctx, String v) { putString(ctx, KEY_NIGHT_START, v, "深夜时段开始"); }
    public static String nightEnd(Context ctx) { return getString(ctx, KEY_NIGHT_END, DEF_NIGHT_END); }
    public static void setNightEnd(Context ctx, String v) { putString(ctx, KEY_NIGHT_END, v, "深夜时段结束"); }

    // —— 权限开关（enabled + value 文本）——
    public static boolean permEnabled(Context ctx, String key) { return sp(ctx).getBoolean(key + "_on", false); }
    public static void setPermEnabled(Context ctx, String key, boolean v) { putBool(ctx, key + "_on", v, permLabel(key)); }
    public static String permValue(Context ctx, String key) { return getString(ctx, key + "_val", ""); }
    public static void setPermValue(Context ctx, String key, String v) { putString(ctx, key + "_val", v, permLabel(key) + "参数"); }

    private static String permLabel(String key) {
        if (KEY_PERM_SCREEN_TIME.equals(key)) return "屏幕时长上限";
        if (KEY_PERM_SLEEP.equals(key)) return "睡前息屏";
        if (KEY_PERM_CURFEW.equals(key)) return "深夜宵禁";
        if (KEY_PERM_STUDY.equals(key)) return "学习白名单";
        if (KEY_PERM_DRINK.equals(key)) return "喝水提醒";
        if (KEY_PERM_SIT.equals(key)) return "久坐提醒";
        if (KEY_PERM_WEEKLY.equals(key)) return "每周回顾";
        if (KEY_PERM_HOME.equals(key)) return "到家报备";
        if (KEY_PERM_PERIOD.equals(key)) return "生理期照顾";
        return key;
    }

    // ============ 安静 / 深夜时段判定（跨零点）============

    /** 当前是否处于安静时段（不打扰）。支持跨零点，如 23:30 → 08:00。 */
    public static boolean isQuietNow(Context ctx) { return inWindow(ctx, quietStart(ctx), quietEnd(ctx)); }
    /** 当前是否处于深夜时段（用于情绪感知等）。支持跨零点。 */
    public static boolean isNightNow(Context ctx) { return inWindow(ctx, nightStart(ctx), nightEnd(ctx)); }

    private static boolean inWindow(Context ctx, String start, String end) {
        int[] s = parseHHmm(start), e = parseHHmm(end);
        if (s == null || e == null) return false;
        int now = nowHHmm();
        boolean cross = e[0] * 60 + e[1] <= s[0] * 60 + s[1]; // 跨零点
        int cur = now, a = s[0] * 60 + s[1], b = e[0] * 60 + e[1];
        if (cross) return cur >= a || cur < b;
        return cur >= a && cur < b;
    }

    private static int[] parseHHmm(String s) {
        if (s == null) return null;
        try {
            String[] p = s.trim().split("[:：]");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        } catch (Exception e) { return null; }
    }
    private static int nowHHmm() {
        Date d = new Date();
        return d.getHours() * 60 + d.getMinutes();
    }

    // ============ 随机查岗间隔（带下限）============

    /** 下一次查岗延迟（毫秒）：在 [下限, 上限] 之间随机；下限保证不会连续挨着弹。 */
    public static long nextCheckInDelayMs(Context ctx) {
        int lo = Math.min(randomLower(ctx), Math.max(1, randomUpper(ctx) - 1));
        int hi = Math.max(randomUpper(ctx), lo + 1);
        int min = lo + (int) (Math.random() * (hi - lo));
        return (long) min * 60_000L;
    }

    // ============ 冷却 ============

    private static final String KEY_COOLDOWN_UNTIL = PREFIX + "cooldown_until_ms";
    /** 是否仍在冷却期内（针对同类提醒）。 */
    public static boolean isInCooldown(Context ctx) {
        long until = sp(ctx).getLong(KEY_COOLDOWN_UNTIL, 0);
        return System.currentTimeMillis() < until;
    }
    /** 打一次提醒，刷新冷却截止时间（改参数时也会调用，使冷却重新计算）。 */
    public static void markAction(Context ctx) {
        int cd = cooldownMinutes(ctx);
        sp(ctx).edit().putLong(KEY_COOLDOWN_UNTIL, System.currentTimeMillis() + cd * 60_000L).apply();
    }
    /** 改参数后冷却重新计算（立即清空冷却，让新参数可以马上生效）。 */
    public static void resetCooldown(Context ctx) {
        sp(ctx).edit().putLong(KEY_COOLDOWN_UNTIL, 0).apply();
    }

    // ============ 变更记录 ============

    /** 记录一行变更：谁改的、什么时候、改了什么。 */
    public static void logChange(Context ctx, String who, String field, String oldV, String newV) {
        try {
            JSONArray arr = new JSONArray(sp(ctx).getString(KEY_CHANGE_LOG, "[]"));
            JSONObject e = new JSONObject();
            e.put("who", who == null ? "用户" : who);
            e.put("field", field);
            e.put("from", oldV);
            e.put("to", newV);
            e.put("at", iso(System.currentTimeMillis()));
            arr.put(e);
            while (arr.length() > MAX_LOG) arr.remove(0);
            sp(ctx).edit().putString(KEY_CHANGE_LOG, arr.toString()).apply();
        } catch (Exception ignored) { }
    }
    public static JSONArray changeLog(Context ctx) {
        try { return new JSONArray(sp(ctx).getString(KEY_CHANGE_LOG, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    public static int changeLogCount(Context ctx) { return changeLog(ctx).length(); }

    // ============ 导出 / 导入 ============

    /** 导出全部配置为 JSON 字符串（便于重装或换机后恢复）。 */
    public static String exportJson(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = sp(ctx);
            for (String key : p.getAll().keySet()) {
                Object v = p.getAll().get(key);
                if (v instanceof Boolean) o.put(key, (Boolean) v);
                else if (v instanceof Integer) o.put(key, (Integer) v);
                else if (v instanceof Number) o.put(key, ((Number) v).doubleValue());
                else if (v instanceof String) o.put(key, (String) v);
            }
            o.put("_exported_at", iso(System.currentTimeMillis()));
            o.put("_device", AppPrefs.device(ctx));
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** 导入配置 JSON（仅覆盖已知键；不识别的键忽略）。返回是否成功。 */
    public static boolean importJson(Context ctx, String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor e = sp(ctx).edit();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (!key.startsWith(PREFIX) && !KEY_EMOTION_MISS.equals(key)) continue;
                if (key.startsWith("_")) continue;
                Object v = o.opt(key);
                if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
                else if (v instanceof Integer) e.putInt(key, (Integer) v);
                else if (v instanceof Long) e.putLong(key, (Long) v);
                else if (v instanceof Double) e.putInt(key, (int) Math.round((Double) v));
                else if (v instanceof String) e.putString(key, (String) v);
            }
            e.apply();
            logChange(ctx, "用户", "导入配置", "", "已导入");
            resetCooldown(ctx);
            return true;
        } catch (Exception e) {
            DebugState.append(ctx, "导入配置失败：" + ScreenshotService.shortMsg(e));
            return false;
        }
    }

    // ============ 一键恢复默认 ============

    /** 把所有参数恢复为默认值（不清除变更记录）。 */
    public static void resetToDefaults(Context ctx) {
        SharedPreferences.Editor e = sp(ctx).edit();
        e.putBoolean(KEY_MASTER, DEF_MASTER);
        e.putInt(KEY_RANDOM_UPPER, DEF_RANDOM_UPPER);
        e.putInt(KEY_RANDOM_LOWER, DEF_RANDOM_LOWER);
        e.putInt(KEY_FORCE_BACK_THRESHOLD, DEF_THRESHOLD);
        e.putBoolean(KEY_DECLINE_REASON_REQUIRED, DEF_DECLINE_REQUIRED);
        e.putString(KEY_TARGET_PACKAGE, DEF_TARGET_PACKAGE);
        e.putInt(KEY_COOLDOWN, DEF_COOLDOWN);
        e.putBoolean(KEY_CHECKIN_ENABLED, DEF_CHECKIN);
        e.putInt(KEY_EMOTION_START, DEF_EMO_START);
        e.putInt(KEY_EMOTION_GAIN_MIN, DEF_EMO_GAIN_MIN);
        e.putInt(KEY_EMOTION_GAIN_MAX, DEF_EMO_GAIN_MAX);
        e.putInt(KEY_EMOTION_DECAY, DEF_EMO_DECAY);
        e.putBoolean(KEY_EMOTION_DRIVE_TEXT, DEF_EMO_DRIVE);
        e.putBoolean(KEY_EMOTION_LOCK, DEF_EMO_LOCK);
        e.putBoolean(KEY_INITIATIVE_ENABLED, DEF_INIT_ENABLED);
        e.putInt(KEY_INITIATIVE_SILENCE, DEF_INIT_SILENCE);
        e.putInt(KEY_INITIATIVE_INTERVAL, DEF_INIT_INTERVAL);
        e.putInt(KEY_INITIATIVE_DAILY, DEF_INIT_DAILY);
        e.putString(KEY_INITIATIVE_MODE, DEF_INIT_MODE);
        e.putBoolean(KEY_DISCIPLINE_ENABLED, DEF_DISC_ENABLED);
        e.putInt(KEY_DISCIPLINE_LEVEL, DEF_DISC_LEVEL);
        e.putBoolean(KEY_DISCIPLINE_USER_ONLY, DEF_DISC_USER_ONLY);
        e.putBoolean(KEY_DISCIPLINE_BADGE, DEF_DISC_BADGE);
        e.putBoolean(KEY_DISTRACTION_ENABLED, DEF_DIST_ENABLED);
        e.putBoolean(KEY_DISTRACTION_EVERY, DEF_DIST_EVERY);
        e.putInt(KEY_DISTRACTION_THRESHOLD, DEF_DIST_THRESHOLD);
        e.putBoolean(KEY_DISTRACTION_AUTO_PULLBACK, DEF_DIST_AUTO_PULLBACK);
        e.putBoolean(KEY_DISTRACTION_LOOP_ENABLED, DEF_DIST_LOOP_ENABLED);
        e.putInt(KEY_DISTRACTION_LOOP_TIMES, DEF_DIST_LOOP_TIMES);
        e.putInt(KEY_DISTRACTION_LOOP_WINDOW, DEF_DIST_LOOP_WINDOW);
        e.putString(KEY_DISCIPLINE_MODE, DEF_DISC_MODE);
        e.putInt(KEY_DISTRACTION_COUNT, 0);
        e.putBoolean(KEY_USAGE_LOCK_ENABLED, DEF_USAGE_LOCK_ENABLED);
        e.putInt(KEY_USAGE_LOCK_MINUTES, DEF_USAGE_LOCK_MIN);
        e.putInt(KEY_USAGE_LOCK_DURATION, DEF_USAGE_LOCK_DUR);
        e.putString(KEY_USAGE_LOCK_PACKAGES, "");
        e.putBoolean(KEY_OCR_ENABLED, DEF_OCR_ENABLED);
        e.putBoolean(KEY_EMOTION_POPUP_ENABLED, DEF_EMO_POPUP_ENABLED);
        e.putInt(KEY_EMOTION_POPUP_THRESHOLD, DEF_EMO_POPUP_THRESHOLD);
        e.putInt(KEY_EMOTION_POPUP_INTERVAL, DEF_EMO_POPUP_INTERVAL);
        e.putString(KEY_QUIET_START, DEF_QUIET_START);
        e.putString(KEY_QUIET_END, DEF_QUIET_END);
        e.putString(KEY_NIGHT_START, DEF_NIGHT_START);
        e.putString(KEY_NIGHT_END, DEF_NIGHT_END);
        e.putString(KEY_THEME_OVERRIDE, "");
        e.putString(KEY_POSITION_OVERRIDE, "");
        e.apply();
        logChange(ctx, "用户", "一键恢复默认", "", "全部恢复默认");
        resetCooldown(ctx);
    }

    // ============ 工具 ============

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(new Date(ms));
    }

    /** 调试用：返回当前所有设置项的可读摘要。 */
    public static String summary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("总开关=").append(isMasterOn(ctx))
          .append(" 查岗随机[").append(randomLower(ctx)).append(",").append(randomUpper(ctx)).append("]分")
          .append(" 阈值=").append(forceBackThreshold(ctx))
          .append(" 目标App=").append(targetPackage(ctx))
          .append(" 冷却=").append(cooldownMinutes(ctx)).append("分")
          .append(" 想你锁定=").append(emotionLock(ctx))
          .append(" 分心计数=").append(distractionCount(ctx)).append("/").append(distractionThreshold(ctx))
          .append(" 时长自动锁=").append(usageLockEnabled(ctx) ? usageLockMinutes(ctx)+"分→锁"+usageLockDuration(ctx)+"分" : "关")
          .append(" 情绪自动弹窗=").append(emotionPopupEnabled(ctx) ? emotionPopupThreshold(ctx)+"%" : "关")
          .append(" 变更记录=").append(changeLogCount(ctx)).append("条");
        return sb.toString();
    }
}
