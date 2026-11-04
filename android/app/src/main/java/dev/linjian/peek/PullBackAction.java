package dev.linjian.peek;

import android.content.Context;

import org.json.JSONObject;

/**
 * 拉回弹窗的公共动作处理：无论从悬浮窗还是锁屏 Activity 触发，行为完全一致，
 * 拒绝理由一定会写进本地事件日志、调试日志，并随活动事件上报到服务端。
 */
public class PullBackAction {

    private PullBackAction() { }

    /** 用户选择「现在就回去」：跳到本机设定的目标 App（默认 com.psyche.kelivo），并把想你清零。 */
    public static void accept(Context ctx, JSONObject cfg) {
        try {
            String companion = AppPrefs.companionName(ctx);
            int miss = cfg == null ? 0 : cfg.optInt("miss_percent", 0);
            String tag = cfg == null ? "" : cfg.optString("emotion_tag", "");
            ActivityEventStore.recordForced(ctx, "pull_back_accept",
                    "答应拉回" + companion, "情绪 " + tag + " · 想你 " + miss + "% · 目标 " + PolicyStore.targetPackage(ctx));
            DebugState.append(ctx, "拉回被接受：" + tag + " " + miss + "%，跳转到 " + PolicyStore.targetPackage(ctx));
            // 「现在就回去」跳到本机设定的目标 App（默认 com.psyche.kelivo）。
            String result = CompanionService.openPackageResult(ctx, PolicyStore.targetPackage(ctx));
            if (!result.startsWith("opened_")) {
                // 目标 App 没装或打不开：退回掌心窗陪伴页，保证不会卡住。
                DebugState.append(ctx, "目标 App 跳转失败(" + result + ")，退回掌心窗");
                CompanionService.bringAppToFront(ctx);
            }
            CompanionEmotionState.markReturned(ctx);
        } catch (Exception e) {
            DebugState.append(ctx, "拉回处理异常：" + ScreenshotService.shortMsg(e));
        }
    }

    /** 用户选择「再等一会儿」：理由必须留下，写入日志。 */
    public static void decline(Context ctx, JSONObject cfg, String reason) {
        try {
            String companion = AppPrefs.companionName(ctx);
            String why = reason == null ? "" : reason.trim();
            if (why.isEmpty()) why = "没有说理由";
            int miss = cfg == null ? 0 : cfg.optInt("miss_percent", 0);
            String tag = cfg == null ? "" : cfg.optString("emotion_tag", "");
            ActivityEventStore.recordForced(ctx, "pull_back_decline",
                    "拒绝拉回" + companion, why + "（情绪 " + tag + " · 想你 " + miss + "%）");
            DebugState.append(ctx, "拉回被拒绝，理由：" + why);
        } catch (Exception e) {
            DebugState.append(ctx, "拉回拒绝记录异常：" + ScreenshotService.shortMsg(e));
        }
    }

    /** 用户没有表态就关掉了弹窗。 */
    public static void dismiss(Context ctx, JSONObject cfg) {
        try {
            int miss = cfg == null ? 0 : cfg.optInt("miss_percent", 0);
            ActivityEventStore.recordForced(ctx, "pull_back_ignore", "忽略了拉回弹窗", "想你 " + miss + "%");
            DebugState.append(ctx, "拉回弹窗被忽略");
        } catch (Exception e) {
            DebugState.append(ctx, "拉回忽略记录异常：" + ScreenshotService.shortMsg(e));
        }
    }
}
