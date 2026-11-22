package dev.linjian.peek;

/**
 * 弹窗互斥仲裁器：保证全 App 同一时刻只有一个弹窗（悬浮窗或弹窗 Activity）展示。
 *
 * 背景（修复「弹窗重复冲突」）：
 *  - 悬浮窗通道有两条独立实现（PullBackOverlay 分级拉回 / AlertCenterOverlay 居中弹窗），
 *    各自持有自己的视图引用，互相不知道对方存在，会同时叠在屏幕上；
 *  - 悬浮窗 TYPE_APPLICATION_OVERLAY 层级高于 Activity：Activity 兜底通道
 *    （PullBackActivity / AlertCenterActivity）启动时，若已有悬浮窗在屏，
 *    会出现「悬浮窗 + Activity」双重展示。
 *
 * 规则（last-write-wins，永远只有一个）：
 *  - 任何通道展示前调用 {@link #beforeShow(String)}：先移除其它通道的悬浮窗，
 *    并登记自己为当前占用者；后到的弹窗替换先到的，不排队不叠加。
 */
public final class PopupMutex {

    /** 分级拉回弹窗（悬浮窗 + Activity 兜底共用一个 key）。 */
    public static final String PULL_BACK = "pull_back";
    /** 屏幕居中弹窗（悬浮窗 + Activity 兜底共用一个 key）。 */
    public static final String CENTER_ALERT = "center_alert";

    private static volatile String owner = "";
    private static long lastShownAt = 0;

    // [POPUP-RANDOM-FIX] 全局中心弹窗节奏：任意弹窗触发后，下一次最早在 8-60 分钟随机后。
    // 查岗/情绪弹窗等所有「中心弹窗」共用这条闸门，保证：(1) 间隔 8-60 分钟随机；(2) 不会同时弹多个。
    private static long nextCenterAlertAt = 0;

    private PopupMutex() { }

    /** 生成 8-60 分钟之间的随机毫秒数。 */
    public static long randomCenterIntervalMs() {
        int lo = 8, hi = 60;
        int min = lo + (int) (Math.random() * (hi - lo + 1));
        return (long) min * 60_000L;
    }

    /** [POPUP-RANDOM-FIX] 是否允许触发下一个中心弹窗（全局节奏闸门）。 */
    public static boolean canTriggerCenterAlert(long nowWallClock) {
        return nowWallClock >= nextCenterAlertAt;
    }

    /** [POPUP-RANDOM-FIX] 任意中心弹窗触发后调用：登记并推进下一次为 8-60 分钟后。 */
    public static void markCenterAlertTriggered(long nowWallClock) {
        nextCenterAlertAt = nowWallClock + randomCenterIntervalMs();
        lastShownAt = nowWallClock;
    }

    /** 距下次允许触发还剩多少毫秒（诊断用）。 */
    public static long msUntilNextCenterAlert(long nowWallClock) {
        return Math.max(0, nextCenterAlertAt - nowWallClock);
    }

    /**
     * 展示前调用：移除其它通道的悬浮窗，把自己登记为当前占用者。
     * 之后调用方负责真正 addView / setContentView；失败也要调 {@link #onDismissed(String)} 释放。
     */
    public static void beforeShow(String who) {
        if (who == null) return;
        try {
            if (!PULL_BACK.equals(who)) PullBackOverlay.dismiss();
            if (!CENTER_ALERT.equals(who)) AlertCenterOverlay.dismiss();
        } catch (Exception ignored) { }
        owner = who;
        lastShownAt = System.currentTimeMillis();
    }

    /** 弹窗被用户点掉 / 移除后调用：只有当前占用者本人能清空登记。 */
    public static void onDismissed(String who) {
        if (who != null && who.equals(owner)) owner = "";
    }

    /** 强制关闭全部弹窗（悬浮窗两条通道），用于上课开始等需要「立即静默」的场景。 */
    public static void dismissAll() {
        try { PullBackOverlay.dismiss(); } catch (Exception ignored) { }
        try { AlertCenterOverlay.dismiss(); } catch (Exception ignored) { }
        owner = "";
    }

    /** 当前占用者（诊断用）。 */
    public static String currentOwner() { return owner; }

    /** 距上次任意弹窗展示的毫秒数（诊断/限频用）。 */
    public static long msSinceLastShown() {
        return lastShownAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastShownAt;
    }
}
