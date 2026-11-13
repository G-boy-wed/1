package dev.linjian.peek;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * 本机「查岗与管束」独立调度器（离线可跑，不依赖后端 / MCP）。
 *
 * 设计要点（对应需求）：
 *  - 用本机 Handler 自循环定时器，不需要网络、不需要后端、不需要 MCP；
 *    即便 CompanionService 因没配服务器而没启动，只要无障碍服务开着，本调度器照常执行；
 *  - 查岗：在 [随机下限, 随机上限] 之间随机间隔触发分级拉回弹窗；
 *  - 拉回分级：想你达到阈值 / 强情绪 → 强级别单按钮（点「现在就回去」跳到 target_package）；
 *            低于阈值 → 软级别双按钮（可拒绝并留理由，拒绝写日志 + 强制留痕上报）；
 *  - 主动找她：用户沉默超过阈值且达到最小间隔、未超每日上限 → 主动到 Kelivo 并留痕；
 *  - 分心判定：切到其他 App 记为分心；是否立即拉回由情绪阈值决定（distractionEveryTime 时每次都拉回）；
 *  - 权限开关逐项生效：屏幕时长 / 睡前息屏 / 深夜宵禁 / 学习白名单 / 喝水久坐 / 每周回顾 / 到家报备 / 生理期照顾；
 *  - 训诫插件：开启时界面有明确标志，违规时在日志留痕（分四档）；
 *  - 所有触发都尊重总开关、冷却、安静时段；落盘与日志只写本机。
 *
 * 宿主：ScreenshotService（无障碍常驻，离线也活）+ CompanionService + MainActivity 都会调用 start()，
 * start() 幂等，全局只有一个实例在跑。
 */
public final class PolicyScheduler {

    private static final long TICK_MS = 20_000L; // 每 20 秒评估一次
    private static final String SELF_PKG = "dev.linjian.peek";

    /**
     * Bug #34：系统级白名单 —— 输入法、系统面板、媒体选择器、截屏工具、桌面/启动器
     * 等永远不会被记成「分心」。这是系统级兜底，不进 exempt_packages 配置、不可被用户关闭，
     * 否则打字弹输入法、相册选图都会被误判分心并触发训诫。
     */
    private static final Set<String> SYSTEM_EXEMPT_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.providers.media.module",   // 媒体选择器
            "com.android.documentsui",              // 文件选择器
            "com.android.externalstorage",          // 存储访问
            "com.android.systemui",                 // 系统 UI / 状态栏 / 截屏
            "com.android.systemui.screenshot",
            "com.android.settings",                 // 系统设置
            "com.android.quickstep",                // 多任务/桌面手势
            "com.android.launcher3",
            "com.miui.home", "com.miui.launcher", "com.miui.screenshot",
            "com.vivo.launcher", "com.bbk.launcher2", "com.vivo.card", "com.vivo.screenshot",
            "com.huawei.android.launcher", "com.huawei.systemmanager",
            "com.sec.android.app.launcher", "com.sec.android.app.taskmanager",
            "com.oppo.launcher", "com.coloros.launcher",
            "com.bbk.launcher", "com.touchtype.swiftkey",
            "im.weshine.keyboard", "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin", "com.baidu.input",
            "com.sohu.inputmethod", "com.iflytek.inputmethod",
            "com.cootek.smartinputv5", "com.tencent.qqpinyin",
            "com.simeji.android", "com.komoe.inputmethod", "com.zui.input",
            // BE-09：vivo 上滑「交互池」、Kelivo 等瞬时层，1 分钟内可与抖音来回抖十余次，
            // 绝不能计分心，否则会误触二档训诫。
            "com.vivo.upslide", "com.vivo.interactionpool", "com.kelivo"
    ));

    /** BE-09：公开为两处共用（PolicyScheduler 分心统计 + ActivityEventStore 事件记录），名单单一来源。 */
    public static boolean isSystemExemptPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (SYSTEM_EXEMPT_PACKAGES.contains(pkg)) return true;
        String p = pkg.toLowerCase();
        return p.contains("inputmethod") || p.endsWith("keyboard") || p.contains(".keyboard")
                || p.contains(".ime") || p.contains("systemui") || p.contains("launcher")
                || p.contains("documentsui") || p.contains("providers.media")
                || p.contains("externalstorage") || p.contains("screenshot")
                || p.contains("com.android.settings") || p.contains("quickstep") || p.contains("recents")
                || p.contains("upslide") || p.contains("interactionpool") || p.contains("kelivo")
                || p.contains("weshine");
    }

    private static PolicyScheduler instance;

    private final Context appCtx;
    private HandlerThread thread;
    private Handler handler;
    private boolean running = false;

    // —— 状态（调度器存活期间保存在内存，足够覆盖「改完立即生效」）——
    private long nextCheckInAt = 0;
    // BE-04：用单调时钟(开机至今)锚定「上次实际拉回」时刻，避免系统时间跳变 / 深睡恢复导致的间隔漂移。
    private long lastCheckInElapsed = 0;
    private long lastUserActivityAt = 0;
    private long lastInitiativeAt = 0;
    private int initiativeTodayCount = 0;
    private String initiativeToday = "";
    private int distractLoopCount = 0;        // 切出→切回 的连续来回次数
    // 修复「点进聊天没有开始聊天、切出又开始计分心」：
    // 只有真正开始聊天（在聊天界面连续停留达到 MIN_CHAT_STAY_MS）后，切出才计入分心。
    private static final long MIN_CHAT_STAY_MS = 20_000L; // 在聊天界面停留 ≥20 秒视为「开始聊天」
    private boolean inChatNow = false;        // 上一 tick 是否在聊天界面
    private long chatEnterAt = 0;             // 本次进入聊天的时刻
    private boolean chatArmed = false;        // 最近一次聊天会话是否算「真正开始聊天」
    private boolean leftChat = false;         // 已离开一个「真正聊过」的会话，等待切回（每次离场只计一次切出）
    private String lastSwitchOutPkg = "";     // 切出时所在的 App
    private long lastSwitchOutAt = 0;         // 切出时刻（用于和「切回」配对）
    private boolean wasInClass = false;       // 上一 tick 是否处于上课（用于检测「进入上课」以自动关闭弹窗）
    private long screenOnSince = 0;
    private long lastDrinkAt = 0;
    private long lastSitAt = 0;
    private long lastScreenTimeNotify = 0;
    private long lastCurfewNotify = 0;
    private long lastStudyNotify = 0;
    private long lastSleepNotify = 0;
    private long lastWeeklyAt = 0;
    private long lastHomeAt = 0;
    private long lastPeriodAt = 0;
    private String usagePkg = "";          // 正在统计连续使用时长的包名
    private long usageStartAt = 0;         // 该包连续使用起始时间
    private long lastUsageLockAt = 0;      // 上次自动锁定的时间（限频）
    private long lastEmotionPopupAt = 0;   // 上次情绪自动弹窗的时间（按最小间隔限频）

    private PolicyScheduler(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    /** 幂等启动：全局只有一个实例在跑。host 为宿主标识（accessibility/companion/activity/boot/watchdog）。 */
    public static synchronized void start(Context ctx) { start(ctx, "default"); }

    public static synchronized void start(Context ctx, String host) {
        if (ctx == null) return;
        // 看门狗只负责拉活，不注册为常驻宿主：宿主计数归零时调度器仍可正常停止，
        // 15 分钟内看门狗会再把它拉起（总开关关闭时 evaluate 直接返回，不会打扰）。
        if (host != null && !host.isEmpty() && !"watchdog".equals(host)) aliveHosts.add(host);
        if (instance != null && instance.running) { scheduleWatchdog(ctx); return; }
        if (instance == null) instance = new PolicyScheduler(ctx);
        instance.running = true;
        if (instance.thread == null) {
            instance.thread = new HandlerThread("LinjianPolicyScheduler");
            instance.thread.start();
            instance.handler = new Handler(instance.thread.getLooper());
        }
        long now = System.currentTimeMillis();
        if (instance.lastUserActivityAt == 0) instance.lastUserActivityAt = now;
        // 让情绪「想你程度」从首次使用就能随时间累积（否则情绪达标自动弹窗永远不触发）
        CompanionEmotionState.ensureFirstSeen(ctx);
        instance.handler.removeCallbacks(instance.tick);
        instance.handler.post(instance.tick);
        scheduleWatchdog(ctx);
        DebugState.append(ctx, "查岗与管束本地调度器已启动（离线可跑，宿主=" + host + "）");
    }

    /** 停止（宿主销毁时调用）——按宿主引用计数：还有任一宿主存活就继续跑。 */
    public static synchronized void stop(Context ctx) { stop(ctx, "default"); }

    public static synchronized void stop(Context ctx, String host) {
        if (host != null && !host.isEmpty()) aliveHosts.remove(host);
        // 修复「插件挂后台不稳」：无障碍服务与陪伴服务互为宿主，任何一个销毁
        // （如用户重开无障碍、系统回收陪伴服务）都不再连坐停掉调度器；
        // 只有全部宿主都退出才真正停止。看门狗闹钟会在 15 分钟内把误杀的调度器拉活。
        if (!aliveHosts.isEmpty() && instance != null && instance.running) {
            DebugState.append(ctx, "调度器宿主 " + host + " 退出，其余宿主仍在，继续运行");
            return;
        }
        if (instance == null) return;
        instance.running = false;
        if (instance.handler != null) instance.handler.removeCallbacks(instance.tick);
        if (instance.thread != null) { instance.thread.quitSafely(); instance.thread = null; }
        DebugState.append(ctx, "查岗与管束本地调度器已停止");
    }

    // ============ AlarmManager 看门狗 ============
    // HandlerThread 在 Doze/深睡或进程被杀后会停摆；用 setAndAllowWhileIdle
    // 的周期闹钟（约 15 分钟一次）把调度器重新拉起（start 幂等，已在跑则无副作用）。
    private static final long WATCHDOG_INTERVAL_MS = 15 * 60_000L;
    private static final String WATCHDOG_ACTION = "dev.linjian.peek.POLICY_SCHEDULER_WATCHDOG";
    private static final java.util.Set<String> aliveHosts = new HashSet<>();
    private static android.app.PendingIntent watchdogPi;

    private static void scheduleWatchdog(Context ctx) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            if (watchdogPi == null) {
                android.content.Intent i = new android.content.Intent(WATCHDOG_ACTION);
                i.setClass(ctx, SchedulerWatchdogReceiver.class);
                watchdogPi = android.app.PendingIntent.getBroadcast(ctx, 1001, i,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            }
            long triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;
            if (android.os.Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS, watchdogPi);
            else am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, WATCHDOG_INTERVAL_MS, watchdogPi);
        } catch (Exception e) {
            DebugState.append(ctx, "调度器看门狗注册失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { evaluate(); } catch (Exception e) { DebugState.append(appCtx, "调度器评估异常：" + ScreenshotService.shortMsg(e)); }
            if (running && handler != null) handler.postDelayed(this, TICK_MS);
        }
    };

    private void evaluate() {
        if (!PolicyStore.isMasterOn(appCtx)) {
            // 总开关关闭：不查岗、不拉回、不主动找、不计分心；但保留参数以便一键恢复。
            return;
        }
        long now = System.currentTimeMillis();
        resetDailyIfNeeded(now);

        String current = ScreenshotService.currentPackage();
        boolean inTarget = current != null && (current.equals(PolicyStore.targetPackage(appCtx)) || current.equals(SELF_PKG));
        if (inTarget) {
            lastUserActivityAt = now;
            // 不在清 lastDistractionPkg：留给 evaluateDistraction 判定「切出→切回」来回。
            if (PolicyStore.distractionCount(appCtx) != 0) PolicyStore.setDistractionCount(appCtx, 0); // 连续分心计数清零
        }

        // 底层情绪感知：由代码依据用户状态（时间、分心来回、离开时长、深夜、上课等）
        // 自动算出「你对用户的情绪」并写入本机；后续查岗 / 情绪弹窗 / 分心拉回 /
        // 陪伴话术 / 守护页情绪卡全部读取这个结果，不依赖模型在聊天里「感到」。
        try {
            EmotionPerception.Signals sig = new EmotionPerception.Signals();
            sig.now = now;
            sig.currentPackage = current == null ? "" : current;
            sig.inChat = inTarget;
            sig.distractionLoopCount = distractLoopCount;
            sig.distractionCount = PolicyStore.distractionCount(appCtx);
            EmotionPerception.perceive(appCtx, sig);
        } catch (Exception ignored) { }

        boolean inClass = ClassScheduleState.isInClassNow(appCtx);

        // 上课期间「自动关闭」所有可能引起弹窗的评估：查岗拉回 / 情绪感知 / 主动聊天 /
        // 分心查岗 / 使用时长自动锁 / 权限提醒，全部跳过；课表评估始终运行（含上课检测与课前/下课提醒）。
        // 课程结束后 inClass 置否，上述评估自动恢复（「课程结束后自动开启」）。
        if (!inClass) {
            // 已在聊天陪伴页 / 目标聊天 App（inTarget）：用户本就在「该回去的地方」，
            // 查岗拉回 / 情绪弹窗 / 主动找 一律不弹（否则会出现「在聊天软件里被拉回聊天」的荒谬弹窗）。
            // 分心判定仍运行：它只在「离开聊天去了别的 App」时才计，不会在聊天里误弹。
            if (!inTarget) {
                evaluateCheckIn(now, current);
                evaluateEmotionPopup(now);
                evaluateInitiative(now, current);
                evaluateUsageLock(now, current);
                evaluatePermissions(now, current);
            }
            evaluateDistraction(now, current);
        }
        evaluateClassSchedule();

        // 弹窗矩阵每日触发：天气出门 / 守护日历大事件（每天各一次，上课/安静时段不弹）。
        try { AlertWindowManager.onTick(appCtx); } catch (Exception ignored) { }

        // 检测到「刚进入上课」：立即关闭当前任何正在显示的弹窗，落实上课静默。
        if (inClass && !wasInClass) {
            try { PopupMutex.dismissAll(); } catch (Exception ignored) { }
            DebugState.append(appCtx, "检测到进入上课：自动关闭所有弹窗（查岗/情绪/分心等静默）");
        }
        wasInClass = inClass;

        // 应用门禁：除无障碍前台事件外，调度器每个 tick 也主动把当前前台 App 喂给门禁检查，
        // 真正「写入即监测」——避免无障碍事件被节流/丢失、或写入锁定时用户已在该 App 上而始终不拦截。
        // 锁定界面已显示时 onForegroundPackage 内部去重，不会每 20 秒重新闪一次。
        try { AppGate.onForegroundPackage(appCtx, current); } catch (Exception ignored) { }
    }

    // ============ 查岗与分级拉回 ============

    private void evaluateCheckIn(long now, String current) {
        if (!PolicyStore.checkinEnabled(appCtx)) {
            lastCheckInElapsed = 0;
            return;
        }
        if (ClassScheduleState.isInClassNow(appCtx)) {
            // 上课期间静默守护（免打扰）：不主动查岗，仅跳过本次；不把下一次查岗提前，
            // 避免「下课瞬间立即拉回」破坏设定的间隔节奏（BE-04）。
            return;
        }
        long elapsed = android.os.SystemClock.elapsedRealtime();
        long interval = delayWithScale();
        if (lastCheckInElapsed == 0) lastCheckInElapsed = elapsed;
        // BE-04：以「上次实际拉回」为锚点计算下一次，而不是以「跳过时的 now」为锚点。
        nextCheckInAt = lastCheckInElapsed + interval;
        if (now < nextCheckInAt) return;

        if (PolicyStore.isQuietNow(appCtx)) {
            // 安静时段不打扰：本次跳过，不提前下次；离开安静时段后按原间隔节奏恢复。
            return;
        }
        // BE-04 二次校验：距上次实际拉回应 >= 间隔，且不在冷却内，才真正触发。
        if (elapsed - lastCheckInElapsed < interval) return;
        if (PolicyStore.isInCooldown(appCtx)) return;

        // 组装分级拉回配置（与远程 force_back_to_chat 同一套逻辑，但用本机情绪与阈值）。
        JSONObject cfg = new JSONObject();
        try {
            String tag = CompanionEmotionState.popupEmotionTag(appCtx);
            int miss = CompanionEmotionState.effectiveMissPercent(appCtx);
            int intensity = CompanionEmotionState.effectiveIntensity(appCtx);
            int threshold = PolicyStore.forceBackThreshold(appCtx);
            boolean strongEmotion = CompanionEmotionState.isStrong(appCtx);
            boolean strong = miss >= threshold || strongEmotion;
            cfg.put("level", strong ? "strong" : "soft");
            cfg.put("strong", strong);
            cfg.put("allow_decline", !strong);
            cfg.put("emotion_tag", tag);
            cfg.put("miss_percent", miss);
            cfg.put("intensity", intensity);
            cfg.put("threshold", threshold);
            cfg.put("title", strong ? AppPrefs.companionName(appCtx) + "要你回来" : AppPrefs.companionName(appCtx) + "在等你");
            cfg.put("message", CompanionEmotionState.message(appCtx));
            cfg.put("tone", strong ? "angry" : CompanionEmotionState.tone(appCtx));
            cfg.put("from", "local_checkin");
            // 弹窗类型标识：查岗拉回
            cfg.put("kind", "checkin");
            cfg.put("kind_label", "查岗拉回");
        } catch (Exception ignored) { }

        PullBackActivity.show(appCtx, cfg);
        PolicyStore.markAction(appCtx);
        ActivityEventStore.recordForced(appCtx, "checkin_pull_back",
                "查岗拉回(" + (CompanionEmotionState.isStrong(appCtx) || CompanionEmotionState.effectiveMissPercent(appCtx) >= PolicyStore.forceBackThreshold(appCtx) ? "强" : "软") + ")",
                "想你 " + CompanionEmotionState.effectiveMissPercent(appCtx) + "%");
        DebugState.append(appCtx, "本地查岗触发分级拉回：想你 " + CompanionEmotionState.effectiveMissPercent(appCtx) + "%");

        // BE-04：以「实际触发时刻」为锚点重置，下一次 = 上次实际拉回 + 间隔。
        lastCheckInElapsed = android.os.SystemClock.elapsedRealtime();
        nextCheckInAt = lastCheckInElapsed + delayWithScale();
    }

    /** 查岗间隔：基础随机延迟再乘「想你越深拉回越频繁」的倍率。 */
    private long delayWithScale() {
        long base = PolicyStore.nextCheckInDelayMs(appCtx);
        double scale = CompanionEmotionState.pullBackIntervalScale(appCtx);
        return (long) (base * scale);
    }

    // ============ 情绪感知自动弹窗（独立于查岗，想你达阈值弹「同意/拒绝」）============

    /**
     * 你（陪伴对象）对用户的情绪（想你程度）达到设定阈值时，独立触发一个全局悬浮弹窗，
     * 用户可选择「同意，回去陪她」或「拒绝，再等会儿」（拒绝留理由）。
     * 与随机查岗彻底分开：尊重总开关、安静时段、上课免打扰、冷却与最小间隔，
     * 触发后写冷却，避免和查岗弹窗在同一时刻叠加。
     */
    private void evaluateEmotionPopup(long now) {
        if (!PolicyStore.emotionPopupEnabled(appCtx)) return;
        if (ClassScheduleState.isInClassNow(appCtx) || PolicyStore.isQuietNow(appCtx)) return;
        int miss = CompanionEmotionState.effectiveMissPercent(appCtx);
        int threshold = PolicyStore.emotionPopupThreshold(appCtx);
        if (miss < threshold) return;
        long interval = PolicyStore.emotionPopupIntervalMin(appCtx) * 60_000L;
        if (now - lastEmotionPopupAt < interval) return;
        if (PolicyStore.isInCooldown(appCtx)) return;

        // 情绪判定弹窗：标明情绪标签与百分比，并按百分比决定弹窗样式。
        boolean strong = strongByThreshold();
        JSONObject cfg = new JSONObject();
        try {
            String companion = AppPrefs.companionName(appCtx);
            cfg.put("level", strong ? "strong" : "soft");
            cfg.put("strong", strong);
            cfg.put("allow_decline", !strong); // 未达阈值可拒绝，达到阈值只能回到软件
            cfg.put("emotion_tag", CompanionEmotionState.popupEmotionTag(appCtx));
            cfg.put("miss_percent", miss);
            cfg.put("intensity", CompanionEmotionState.effectiveIntensity(appCtx));
            cfg.put("threshold", PolicyStore.forceBackThreshold(appCtx));
            cfg.put("title", companion + "想你了");
            cfg.put("message", CompanionEmotionState.message(appCtx)); // 随机文案，每次不同
            cfg.put("tone", strong ? "angry" : CompanionEmotionState.tone(appCtx));
            cfg.put("from", "emotion_popup");
            // 弹窗类型标识：情绪感知
            cfg.put("kind", "emotion");
            cfg.put("kind_label", "情绪感知");
        } catch (Exception ignored) { }

        lastEmotionPopupAt = now;
        PullBackActivity.show(appCtx, cfg);   // 悬浮窗优先，盖在当前 App 之上，不拉起掌心窗
        PolicyStore.markAction(appCtx);        // 写冷却，避免查岗弹窗紧接着叠加
        ActivityEventStore.recordForced(appCtx, "emotion_popup",
                "情绪自动弹窗(想你 " + miss + "%≥" + threshold + "%)",
                strong ? "达到拉回阈值，只能回到软件" : "可回软件 / 可拒绝");
        DebugState.append(appCtx, "情绪感知自动弹窗：想你 " + miss + "% 达阈值 " + threshold + "%（" + (strong ? "强制" : "可拒绝") + "）");
    }

    // ============ 主动找她 ============

    private void evaluateInitiative(long now, String current) {
        if (!PolicyStore.initiativeEnabled(appCtx)) return;
        if (ClassScheduleState.isInClassNow(appCtx)) return;   // 上课期间不主动找用户

        long silence = PolicyStore.initiativeSilenceMinutes(appCtx) * 60_000L;
        long minInterval = PolicyStore.initiativeMinInterval(appCtx) * 60_000L;
        if (now - lastUserActivityAt < silence) return;          // 用户没沉默够久
        if (now - lastInitiativeAt < minInterval) return;          // 两次间隔不够
        int limit = PolicyStore.initiativeDailyLimit(appCtx);
        if (limit > 0 && initiativeTodayCount >= limit) return;    // 当天已到上限

        String message = composeInitiative();
        // 主动聊天弹窗：不再静默跳走，改为在当前正在使用的 App 之上弹「主动聊天」弹窗；
        // 用户点「去找 TA」才跳到目标 App，不跳掌心窗、不打断当前界面。
        JSONObject cfg = new JSONObject();
        try {
            String companion = AppPrefs.companionName(appCtx);
            cfg.put("level", "soft");
            cfg.put("strong", false);
            cfg.put("allow_decline", true);
            cfg.put("emotion_tag", CompanionEmotionState.popupEmotionTag(appCtx));
            cfg.put("miss_percent", CompanionEmotionState.effectiveMissPercent(appCtx));
            cfg.put("intensity", CompanionEmotionState.effectiveIntensity(appCtx));
            cfg.put("threshold", PolicyStore.forceBackThreshold(appCtx));
            cfg.put("title", companion + "主动找你");
            cfg.put("message", message);
            cfg.put("tone", CompanionEmotionState.tone(appCtx));
            cfg.put("from", "initiative");
            cfg.put("kind", "initiative");
            cfg.put("kind_label", "主动聊天");
            cfg.put("accept_label", "去找 " + companion);
            cfg.put("decline_label", "晚点再说");
        } catch (Exception ignored) { }
        PullBackActivity.show(appCtx, cfg);
        ActivityEventStore.recordForced(appCtx, "initiative_send", "主动聊天（弹窗）", message);
        DebugState.append(appCtx, "主动找她弹窗已发出（主动聊天）：" + message);

        initiativeTodayCount++;
        lastInitiativeAt = now;
        lastUserActivityAt = now;
        if (PolicyStore.disciplineEnabled(appCtx)) discipline("initiative", message);
    }

    private String composeInitiative() {
        String mode = PolicyStore.initiativeMode(appCtx);
        String companion = AppPrefs.companionName(appCtx);
        if ("template".equals(mode)) {
            String tpl = PolicyStore.initiativeTemplate(appCtx);
            if (tpl != null && !tpl.trim().isEmpty())
                return tpl.replace("{companion}", companion).replace("{user}", AppPrefs.userName(appCtx));
        }
        // 自由生成：从一组温柔的主动话术里随机挑一句。
        String[] pool = {
                companion + "，想你了，今天过得好吗？",
                "有空回" + companion + "一句呀，在等你。",
                companion + "刚发呆了一下，突然想找你说话。",
                "今天有没有好好吃饭？" + companion + "有点惦记。",
                companion + "在呢，想聊点什么都行。",
                "忙完了没？" + companion + "想听你说说话。"
        };
        int i = (int) (System.currentTimeMillis() / 1000L) % pool.length;
        return pool[i];
    }

    // ============ 分心判定 ============

    /**
     * 分心查岗定义：用户在聊天（掌心窗陪伴页 / 目标 App）真正开始聊过后切出去到其它 App，
     * 又「立马切回来」，来回几次就是分心。检测始终开启（常开，不只在聊天时才开）。
     * 修复误计 / 频繁弹窗（历史 bug）：
     *  - 只有「真正开始聊天」（在聊天界面连续停留 ≥20 秒）之后切出去，才计一次分心；
     *  - 每次离开聊天只记一次「切出」（leftChat 去重），不会因在多个 App 间来回切换而反复累加；
     *  - 「切回」聊天才累计一次来回；只有真正完成「切出→切回」回路才计入分心，避免误计；
     *  - 弹窗（分心查岗）悬浮在当前 App 之上、标注类型，并受冷却 / 安静时段 / 上课静默约束。
     */
    private void evaluateDistraction(long now, String current) {
        if (!PolicyStore.distractionEnabled(appCtx)) {
            inChatNow = false; chatArmed = false; leftChat = false; distractLoopCount = 0;
            lastSwitchOutPkg = ""; lastSwitchOutAt = 0;
            return;
        }
        if (current == null || current.isEmpty()) return;
        // BE-09：系统面板 / 输入法 / 交互池等瞬时层视为「透明」——既不计分心，也不打断
        // 「聊天→切出→切回」状态机。原代码只算出 excluded 却从未使用，导致抖音↔交互池↔Kelivo
        // 之类抖动把分心计数虚增并误触二档训诫。
        if (isSystemExemptPackage(current)) return;

        boolean inChat = current.equals(SELF_PKG) || current.equals(PolicyStore.targetPackage(appCtx));

        if (inChat) {
            if (!inChatNow) {
                inChatNow = true;
                boolean returning = leftChat;          // 是否从「分心在外」切回聊天
                if (returning) {
                    // 切回：与上次切出配对，计一个完整来回。
                    long windowMs = (long) PolicyStore.distractionLoopWindowSec(appCtx) * 1000L;
                    if (now - lastSwitchOutAt <= windowMs) distractLoopCount++;
                    else distractLoopCount = 0;
                    leftChat = false;
                    lastSwitchOutPkg = "";
                    lastSwitchOutAt = 0;

                    int loopTimes = PolicyStore.distractionLoopTimes(appCtx);
                    if (PolicyStore.distractionLoopEnabled(appCtx) && distractLoopCount >= loopTimes
                            && !PolicyStore.isInCooldown(appCtx) && !PolicyStore.isQuietNow(appCtx)
                            && !ClassScheduleState.isInClassNow(appCtx)) {
                        distractLoopCount = 0;
                        boolean strong = strongByThreshold();
                        String msg = CompanionEmotionState.message(appCtx)
                                + "\n（刚才切出去又切回来了 " + loopTimes + " 次，是在分心吗？）";
                        showPullBack(strong, "distraction_loop", "分心查岗", msg);
                        PolicyStore.markAction(appCtx);
                        ActivityEventStore.recordForced(appCtx, "distraction_pull_back",
                                "分心查岗拉回(" + (strong ? "强制" : "可拒绝") + ")", "切出切回 " + loopTimes + " 次");
                    }
                }
                // 新聊天会话：重新计时，未停留够久不算「真正开始聊天」。
                chatArmed = false;
                chatEnterAt = now;
            }
        } else {
            if (inChatNow) {
                inChatNow = false;
                long dwell = chatEnterAt > 0 ? now - chatEnterAt : 0;
                chatEnterAt = 0;
                if (dwell >= MIN_CHAT_STAY_MS) {
                    chatArmed = true;   // 本次会话真的聊够了：武装
                } else {
                    // 看一眼就走：不算分心，清空待配对与来回累计。
                    chatArmed = false;
                    distractLoopCount = 0;
                    leftChat = false;
                    lastSwitchOutPkg = "";
                    lastSwitchOutAt = 0;
                }
            }
            // 在别的 App：若刚离开一个「真正聊过」的会话，记一次「切出」（每次离场只计一次，不去重则会反复弹窗）。
            if (chatArmed && !leftChat) {
                leftChat = true;
                lastSwitchOutPkg = current;
                lastSwitchOutAt = now;
                int cnt = PolicyStore.distractionCount(appCtx) + 1;
                PolicyStore.setDistractionCount(appCtx, cnt);
                ActivityEventStore.recordPhone(appCtx, "distraction", "分心到" + appLabel(current) + "（累计 " + cnt + " 次）", current);
                DebugState.append(appCtx, "检测到切屏分心（已开始聊天后切出）：" + appLabel(current) + "（累计 " + cnt + " 次）");
                if (PolicyStore.disciplineEnabled(appCtx)) discipline("distraction", "切到" + appLabel(current));

                // 兼容「每次切屏都拉回 / 累计超阈值」逻辑：仅在真正切出时触发一次，受冷却/安静/上课约束。
                boolean every = PolicyStore.distractionEveryTime(appCtx);
                boolean byAccum = PolicyStore.distractionAutoPullback(appCtx)
                        && cnt >= PolicyStore.distractionThreshold(appCtx);
                if ((every || byAccum)
                        && !PolicyStore.isInCooldown(appCtx) && !PolicyStore.isQuietNow(appCtx)
                        && !ClassScheduleState.isInClassNow(appCtx)) {
                    boolean strong = strongByThreshold();
                    String msg = byAccum
                            ? ("已经分心 " + cnt + " 次了，先回来陪" + AppPrefs.companionName(appCtx) + "。")
                            : CompanionEmotionState.message(appCtx);
                    showPullBack(strong, byAccum ? "distraction_accumulated" : "distraction", "分心查岗", msg);
                    PolicyStore.markAction(appCtx);
                    if (byAccum) PolicyStore.setDistractionCount(appCtx, 0);
                    ActivityEventStore.recordForced(appCtx, "distraction_pull_back",
                            "分心拉回(" + (strong ? "强制" : "可拒绝") + ")", "切到 " + appLabel(current));
                }
            }
        }
    }

    /** 是否达到拉回阈值：优先看训诫高档位（加强管控强制拉回），否则由底层代码算出的想你程度与强情绪决定。 */
    private boolean strongByThreshold() {
        if (PolicyStore.disciplineForceStrong(appCtx)) return true;
        return EmotionPerception.isStrongNow(appCtx);
    }

    /** 统一组装分级拉回配置并显示：悬浮在当前 App 之上，不拉起掌心窗、不自动跳转；kindLabel 标明弹窗类型。 */
    private void showPullBack(boolean strong, String from, String kindLabel, String message) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("level", strong ? "strong" : "soft");
            cfg.put("strong", strong);
            cfg.put("allow_decline", !strong);
            cfg.put("emotion_tag", CompanionEmotionState.popupEmotionTag(appCtx));
            cfg.put("miss_percent", CompanionEmotionState.effectiveMissPercent(appCtx));
            cfg.put("intensity", CompanionEmotionState.effectiveIntensity(appCtx));
            cfg.put("threshold", PolicyStore.forceBackThreshold(appCtx));
            cfg.put("title", strong ? AppPrefs.companionName(appCtx) + "要你回来" : AppPrefs.companionName(appCtx) + "在等你");
            cfg.put("message", (message != null && !message.trim().isEmpty()) ? message : CompanionEmotionState.message(appCtx));
            cfg.put("tone", strong ? "angry" : CompanionEmotionState.tone(appCtx));
            cfg.put("from", from);
            // 弹窗类型标识：卡片顶部会显示这个徽标，让用户一眼知道这是什么弹窗。
            cfg.put("kind", from);
            cfg.put("kind_label", kindLabel == null || kindLabel.trim().isEmpty() ? "守护提醒" : kindLabel);
        } catch (Exception ignored) { }
        PullBackActivity.show(appCtx, cfg);
    }

    // ============ 权限开关逐项生效 ============

    private void evaluatePermissions(long now, String current) {
        if (ClassScheduleState.isInClassNow(appCtx)) return;   // 上课期间不弹任何权限提醒
        boolean quiet = PolicyStore.isQuietNow(appCtx); // 普通提醒在安静时段静默

        // 屏幕时长上限：持续停留在非本机 App 的时长超过上限 → 提醒。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_SCREEN_TIME)) {
            int limitMin = parseMinutes(PolicyStore.permValue(appCtx, PolicyStore.KEY_PERM_SCREEN_TIME));
            if (limitMin > 0) {
                if (screenOnSince == 0 && current != null && !current.equals(SELF_PKG)) screenOnSince = now;
                if (screenOnSince > 0 && now - screenOnSince > (long) limitMin * 60_000L) {
                    if (now - lastScreenTimeNotify > 30 * 60_000L) {
                        notify("屏幕时长已到", "今天看屏幕有点久，歇一会儿吧。");
                        lastScreenTimeNotify = now;
                    }
                    screenOnSince = now; // 重置统计，避免反复弹
                }
            }
        }

        // 睡前息屏：到达设定时间就尝试息屏（有本机无障碍权限时）。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_SLEEP)) {
            String t = PolicyStore.permValue(appCtx, PolicyStore.KEY_PERM_SLEEP);
            if (isAtTime(now, t) && now - lastSleepNotify > 60 * 60_000L) {
                boolean off = false;
                if (ScreenshotService.ready()) off = ScreenshotService.getInstance().doLockScreen();
                if (!off) notify("该休息了", "到睡前时间了，早点放下手机吧。");
                lastSleepNotify = now;
            }
        }

        // 深夜宵禁：在深夜时段且停留在非目标 App → 提醒。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_CURFEW)) {
            if (PolicyStore.isNightNow(appCtx) && current != null && !current.equals(SELF_PKG)
                    && !current.equals(PolicyStore.targetPackage(appCtx)) && now - lastCurfewNotify > 30 * 60_000L) {
                if (!quiet) notify("深夜宵禁", "已经很晚了，该回" + AppPrefs.companionName(appCtx) + "身边了。");
                lastCurfewNotify = now;
            }
        }

        // 学习白名单：停留在白名单之外的 App → 提醒。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_STUDY)) {
            String list = PolicyStore.permValue(appCtx, PolicyStore.KEY_PERM_STUDY);
            if (list != null && !list.trim().isEmpty() && current != null && !current.equals(SELF_PKG)) {
                boolean inWhite = false;
                for (String p : list.split("[,，\\s]+")) {
                    if (p.trim().equals(current)) { inWhite = true; break; }
                }
                if (!inWhite && now - lastStudyNotify > 20 * 60_000L) {
                    if (!quiet) notify("学习白名单", "现在不是学习名单里的 App，专心一点哦。");
                    lastStudyNotify = now;
                }
            }
        }

        // 喝水提醒（按间隔）。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_DRINK)) {
            int m = parseMinutes(PolicyStore.permValue(appCtx, PolicyStore.KEY_PERM_DRINK));
            if (m > 0 && now - lastDrinkAt > (long) m * 60_000L) {
                if (!quiet) notify("喝水提醒", "记得喝口水，照顾好自己。");
                lastDrinkAt = now;
            }
        }

        // 久坐提醒（按间隔）。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_SIT)) {
            int m = parseMinutes(PolicyStore.permValue(appCtx, PolicyStore.KEY_PERM_SIT));
            if (m > 0 && now - lastSitAt > (long) m * 60_000L) {
                if (!quiet) notify("久坐提醒", "起来走两步，别一直坐着。");
                lastSitAt = now;
            }
        }

        // 每周回顾：在设定星期几的设定时间，每周一次。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_WEEKLY)) {
            if (isWeeklyDue(now) && now - lastWeeklyAt > 6L * 24 * 60 * 60_000L) {
                if (!quiet) notify("每周回顾", "这一周过得怎么样？回来和" + AppPrefs.companionName(appCtx) + "聊聊。");
                lastWeeklyAt = now;
            }
        }

        // 到家报备：本机无定位能力，做一次温和提醒（每天一次）。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_HOME)) {
            if (now - lastHomeAt > 24L * 60 * 60_000L) {
                if (!quiet) notify("到家报备", "到家了记得和" + AppPrefs.companionName(appCtx) + "说一声。");
                lastHomeAt = now;
            }
        }

        // 生理期照顾：按周期天数做一次温柔提醒。
        if (PolicyStore.permEnabled(appCtx, PolicyStore.KEY_PERM_PERIOD)) {
            int days = parseMinutes(PolicyStore.permValue(appCtx, PolicyStore.KEY_PERM_PERIOD)); // 这里复用为周期天数
            if (days > 0 && now - lastPeriodAt > (long) days * 24 * 60 * 60_000L) {
                if (!quiet) notify("生理期照顾", "这几天要好好照顾自己，别逞强。");
                lastPeriodAt = now;
            }
        }
    }

    // ============ 应用连续使用时长自动锁 ============

    /** 跟踪前台连续使用时长，超阈值自动锁该 App（用时长与锁定时间手机端/MCP 可调）。 */
    private void evaluateUsageLock(long now, String current) {
        if (!PolicyStore.usageLockEnabled(appCtx)) { usagePkg = ""; usageStartAt = 0; return; }
        if (ClassScheduleState.isInClassNow(appCtx)) { usagePkg = ""; usageStartAt = 0; return; }   // 上课期间不自动锁
        // 计算要监控的包名集合：未指定则只监控目标 App（聊天对象所在 App）。
        java.util.Set<String> watch = new java.util.HashSet<>();
        String pkgs = PolicyStore.usageLockPackages(appCtx);
        if (pkgs == null || pkgs.trim().isEmpty()) {
            watch.add(PolicyStore.targetPackage(appCtx));
        } else {
            for (String p : pkgs.split("[,，\\s]+")) if (!p.trim().isEmpty()) watch.add(p.trim());
        }
        boolean watched = current != null && watch.contains(current);
        if (!watched) { usagePkg = ""; usageStartAt = 0; return; }
        if (!current.equals(usagePkg)) { usagePkg = current; usageStartAt = now; return; }
        if (usageStartAt == 0) { usageStartAt = now; return; }
        long usedMin = (now - usageStartAt) / 60_000L;
        int limit = PolicyStore.usageLockMinutes(appCtx);
        if (usedMin >= limit && now - lastUsageLockAt > 60_000L) {
            lastUsageLockAt = now; usageStartAt = now; // 锁定后重置统计
            int lockMin = PolicyStore.usageLockDuration(appCtx);
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("action", "lock_app");
                cmd.put("package", current);
                cmd.put("duration_minutes", lockMin);
                cmd.put("reason", "连续使用超 " + limit + " 分钟，自动锁 " + lockMin + " 分钟");
                cmd.put("app_name", AppGate.labelOf(appCtx, current));
                AppGate.handleCommand(appCtx, cmd);
                DebugState.append(appCtx, "使用时长自动锁：" + AppGate.labelOf(appCtx, current) + " 锁 " + lockMin + " 分");
                ActivityEventStore.recordForced(appCtx, "usage_auto_lock", "连续使用超阈值自动锁", AppGate.labelOf(appCtx, current) + " → " + lockMin + "分");
            } catch (Exception e) {
                DebugState.append(appCtx, "使用时长自动锁失败：" + ScreenshotService.shortMsg(e));
            }
        }
    }

    // ============ 课表插件评估（上课静默守护 + 课前/下课提醒）============

    private void evaluateClassSchedule() {
        try {
            // ClassScheduleState.evaluate 内部会发课前/下课提醒；上课期间的免打扰由
            // evaluateCheckIn / evaluateDistraction 调用 inClassNow 实现。
            ClassScheduleState.evaluate(appCtx);
        } catch (Exception e) {
            DebugState.append(appCtx, "课表评估异常：" + ScreenshotService.shortMsg(e));
        }
    }
    /** 当前是否上课中（供其它评估逻辑判断是否免打扰）。 */
    public static boolean inClassNow(Context ctx) { return ClassScheduleState.isInClassNow(ctx); }

    private void discipline(String cause, String detail) {
        // #35：单一开关语义——总开关关闭或档位为 0 都视为「关闭」，不得产生任何训诫事件。
        // 避免 enabled=false / level=0 时仍输出「训诫(0档)」污染事件流。
        if (!PolicyStore.disciplineEnabled(appCtx)) return;
        int level = PolicyStore.disciplineLevel(appCtx);
        if (level <= 0) return;
        ActivityEventStore.recordForced(appCtx, "discipline", "训诫(" + level + "档)", cause + "：" + detail);
        DebugState.append(appCtx, "训诫插件触发 " + level + " 档，原因=" + cause);
    }

    // ============ 工具 ============

    private boolean notify(String title, String message) {
        // 弹窗/通知类型标识：提醒类通知标题统一带「提醒 · 」前缀（本身已含“提醒”字样的不重复加）。
        String t = title == null ? "" : title.trim();
        if (!t.contains("提醒")) t = "提醒 · " + t;
        boolean ok = CompanionService.showReminderNotification(appCtx, t, message);
        if (!ok) DebugState.append(appCtx, "权限提醒未发出（通知权限缺失）：" + t);
        return ok;
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.ApplicationInfo info = appCtx.getPackageManager().getApplicationInfo(pkg, 0);
            return String.valueOf(appCtx.getPackageManager().getApplicationLabel(info));
        } catch (Exception ignored) { return pkg; }
    }

    private int parseMinutes(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    /** 是否正好到了 "HH:mm"（±2 分钟窗口内）。 */
    private boolean isAtTime(long now, String hhmm) {
        if (hhmm == null || hhmm.trim().isEmpty()) return false;
        try {
            String[] p = hhmm.trim().split("[:：]");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            Calendar c = Calendar.getInstance();
            int cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            int target = h * 60 + m;
            return Math.abs(cur - target) <= 2;
        } catch (Exception e) { return false; }
    }

    /** 每周回顾是否到期："weekday,HH:mm"，weekday 1=周一…7=周日。 */
    private boolean isWeeklyDue(long now) {
        String v = PolicyStore.permValue(appCtx, PolicyStore.KEY_PERM_WEEKLY);
        if (v == null || v.trim().isEmpty()) return false;
        String[] parts = v.split("[,，]");
        if (parts.length < 2) return false;
        int wd;
        try { wd = Integer.parseInt(parts[0].trim()); } catch (Exception e) { return false; }
        int calWd = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); // Calendar：SUNDAY=1…SATURDAY=7
        // 输入 1=周一..7=周日 → Calendar：周一=2 … 周六=7，周日=1
        int mapped = (wd % 7) + 1;
        if (calWd != mapped) return false;
        return isAtTime(now, parts[1].trim());
    }

    private void resetDailyIfNeeded(long now) {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(new java.util.Date(now));
        if (!today.equals(initiativeToday)) {
            initiativeToday = today;
            initiativeTodayCount = 0;
        }
    }
}
