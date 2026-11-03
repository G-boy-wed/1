package dev.linjian.peek;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * 本机「查岗与管束」独立调度器（离线可跑，不依赖后端 / MCP）。
 *
 * 设计要点（对应需求）：
 *  - 用本机 Handler 自循环定时器，不需要网络、不需要后端、不需要 MCP；
 *    即便 CompanionService 因没配服务器而没启动，只要无障碍服务开着，本调度器照常执行；
 *  - 查岗：在 [随机下限, 随机上限] 之间随机间隔触发分级拉回弹窗；
 *  - 拉回分级：想她达到阈值 / 强情绪 → 强级别单按钮（点「现在就回去」跳到 target_package）；
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

    private static PolicyScheduler instance;

    private final Context appCtx;
    private HandlerThread thread;
    private Handler handler;
    private boolean running = false;

    // —— 状态（调度器存活期间保存在内存，足够覆盖「改完立即生效」）——
    private long nextCheckInAt = 0;
    private long lastUserActivityAt = 0;
    private long lastInitiativeAt = 0;
    private int initiativeTodayCount = 0;
    private String initiativeToday = "";
    private String lastDistractionPkg = "";
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

    private PolicyScheduler(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    /** 幂等启动：全局只有一个实例在跑。 */
    public static synchronized void start(Context ctx) {
        if (ctx == null) return;
        if (instance != null && instance.running) return;
        if (instance == null) instance = new PolicyScheduler(ctx);
        instance.running = true;
        if (instance.thread == null) {
            instance.thread = new HandlerThread("LinjianPolicyScheduler");
            instance.thread.start();
            instance.handler = new Handler(instance.thread.getLooper());
        }
        long now = System.currentTimeMillis();
        if (instance.lastUserActivityAt == 0) instance.lastUserActivityAt = now;
        instance.handler.removeCallbacks(instance.tick);
        instance.handler.post(instance.tick);
        DebugState.append(ctx, "查岗与管束本地调度器已启动（离线可跑）");
    }

    /** 停止（宿主销毁时调用）。 */
    public static synchronized void stop(Context ctx) {
        if (instance == null) return;
        instance.running = false;
        if (instance.handler != null) instance.handler.removeCallbacks(instance.tick);
        if (instance.thread != null) { instance.thread.quitSafely(); instance.thread = null; }
        DebugState.append(ctx, "查岗与管束本地调度器已停止");
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
            lastDistractionPkg = ""; // 回到目标 App，清空分心记忆
        }

        evaluateCheckIn(now, current);
        evaluateInitiative(now, current);
        evaluateDistraction(now, current);
        evaluatePermissions(now, current);
    }

    // ============ 查岗与分级拉回 ============

    private void evaluateCheckIn(long now, String current) {
        if (!PolicyStore.checkinEnabled(appCtx)) {
            nextCheckInAt = now + PolicyStore.nextCheckInDelayMs(appCtx);
            return;
        }
        if (nextCheckInAt == 0) nextCheckInAt = now + delayWithScale();
        if (now < nextCheckInAt) return;

        if (PolicyStore.isQuietNow(appCtx)) {
            // 安静时段不打扰：把下一次查岗推到 5 分钟后重新判断（离开安静时段会自动恢复）。
            nextCheckInAt = now + 5 * 60_000L;
            return;
        }
        if (PolicyStore.isInCooldown(appCtx)) {
            nextCheckInAt = now + Math.max(60_000L, PolicyStore.cooldownMinutes(appCtx) * 60_000L);
            return;
        }

        // 组装分级拉回配置（与远程 force_back_to_chat 同一套逻辑，但用本机情绪与阈值）。
        JSONObject cfg = new JSONObject();
        try {
            String tag = CompanionEmotionState.emotionTag(appCtx);
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
        } catch (Exception ignored) { }

        PullBackActivity.show(appCtx, cfg);
        PolicyStore.markAction(appCtx);
        ActivityEventStore.recordForced(appCtx, "checkin_pull_back",
                "查岗拉回(" + (CompanionEmotionState.isStrong(appCtx) || CompanionEmotionState.effectiveMissPercent(appCtx) >= PolicyStore.forceBackThreshold(appCtx) ? "强" : "软") + ")",
                "想她 " + CompanionEmotionState.effectiveMissPercent(appCtx) + "%");
        DebugState.append(appCtx, "本地查岗触发分级拉回：想她 " + CompanionEmotionState.effectiveMissPercent(appCtx) + "%");

        nextCheckInAt = now + delayWithScale();
    }

    /** 查岗间隔：基础随机延迟再乘「想她越深拉回越频繁」的倍率。 */
    private long delayWithScale() {
        long base = PolicyStore.nextCheckInDelayMs(appCtx);
        double scale = CompanionEmotionState.pullBackIntervalScale(appCtx);
        return (long) (base * scale);
    }

    // ============ 主动找她 ============

    private void evaluateInitiative(long now, String current) {
        if (!PolicyStore.initiativeEnabled(appCtx)) return;

        long silence = PolicyStore.initiativeSilenceMinutes(appCtx) * 60_000L;
        long minInterval = PolicyStore.initiativeMinInterval(appCtx) * 60_000L;
        if (now - lastUserActivityAt < silence) return;          // 用户没沉默够久
        if (now - lastInitiativeAt < minInterval) return;          // 两次间隔不够
        int limit = PolicyStore.initiativeDailyLimit(appCtx);
        if (limit > 0 && initiativeTodayCount >= limit) return;    // 当天已到上限

        String message = composeInitiative();
        // 主动到 Kelivo（本机目标 App），并把这条主动消息写进日志。
        CompanionService.openPackageResult(appCtx, PolicyStore.targetPackage(appCtx));
        ActivityEventStore.recordForced(appCtx, "initiative_send", "主动找" + AppPrefs.companionName(appCtx), message);
        DebugState.append(appCtx, "主动找她已发送（本机）：" + message);

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

    private void evaluateDistraction(long now, String current) {
        if (!PolicyStore.distractionEnabled(appCtx)) return;
        if (current == null || current.isEmpty() || current.equals(SELF_PKG)
                || current.equals(PolicyStore.targetPackage(appCtx))
                || current.contains("systemui") || current.contains("inputmethod")) {
            lastDistractionPkg = "";
            return;
        }
        if (current.equals(lastDistractionPkg)) return; // 同一个 App 不重复记
        lastDistractionPkg = current;
        String app = appLabel(current);
        ActivityEventStore.recordPhone(appCtx, "distraction", "分心到" + app, current);
        DebugState.append(appCtx, "检测到分心：" + app);
        if (PolicyStore.disciplineEnabled(appCtx)) discipline("distraction", "分心到" + app);

        boolean every = PolicyStore.distractionEveryTime(appCtx);
        boolean byEmotion = CompanionEmotionState.effectiveMissPercent(appCtx) >= PolicyStore.forceBackThreshold(appCtx)
                || CompanionEmotionState.isStrong(appCtx);
        if ((every || byEmotion) && !PolicyStore.isInCooldown(appCtx) && !PolicyStore.isQuietNow(appCtx)) {
            JSONObject cfg = new JSONObject();
            try {
                int miss = CompanionEmotionState.effectiveMissPercent(appCtx);
                boolean strong = byEmotion;
                cfg.put("level", strong ? "strong" : "soft");
                cfg.put("strong", strong);
                cfg.put("allow_decline", !strong);
                cfg.put("emotion_tag", CompanionEmotionState.emotionTag(appCtx));
                cfg.put("miss_percent", miss);
                cfg.put("intensity", CompanionEmotionState.effectiveIntensity(appCtx));
                cfg.put("threshold", PolicyStore.forceBackThreshold(appCtx));
                cfg.put("title", strong ? AppPrefs.companionName(appCtx) + "要你回来" : AppPrefs.companionName(appCtx) + "在等你");
                cfg.put("message", CompanionEmotionState.message(appCtx));
                cfg.put("tone", strong ? "angry" : CompanionEmotionState.tone(appCtx));
                cfg.put("from", "distraction");
            } catch (Exception ignored) { }
            PullBackActivity.show(appCtx, cfg);
            PolicyStore.markAction(appCtx);
            ActivityEventStore.recordForced(appCtx, "distraction_pull_back",
                    "分心拉回(" + (byEmotion ? "强" : "软") + ")", "切到 " + app);
        }
    }

    // ============ 权限开关逐项生效 ============

    private void evaluatePermissions(long now, String current) {
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

    // ============ 训诫插件 ============

    private void discipline(String cause, String detail) {
        if (!PolicyStore.disciplineEnabled(appCtx)) return;
        int level = PolicyStore.disciplineLevel(appCtx);
        ActivityEventStore.recordForced(appCtx, "discipline", "训诫(" + level + "档)", cause + "：" + detail);
        DebugState.append(appCtx, "训诫插件触发 " + level + " 档，原因=" + cause);
    }

    // ============ 工具 ============

    private boolean notify(String title, String message) {
        boolean ok = CompanionService.showReminderNotification(appCtx, title, message);
        if (!ok) DebugState.append(appCtx, "权限提醒未发出（通知权限缺失）：" + title);
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
        int calWd = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); // 1=周日
        int want = wd == 7 ? 1 : wd + 1; // 1=周一 → Calendar 周二? 这里统一用 1=周一映射
        // 重新映射：输入 1=周一..7=周日，Calendar SUNDAY=1..SATURDAY=7
        int mapped = wd + 1; if (mapped > 7) mapped = 1;
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
