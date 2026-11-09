package dev.linjian.peek;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.provider.AlarmClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Calendar;
import java.util.Locale;

public class CompanionService extends Service {
    private static final String CHANNEL_ID = "linjian_peek_service";
    private static final String REMINDER_CHANNEL_ID = "linjian_peek_heads_up_v3";
    private static final int NOTIFICATION_ID = 20260715;
    private static final int HOME_MODE_NOTIFICATION_ID = 20260913;
    private static volatile boolean running = false;

    private String serverUrl;
    private String token;
    private Handler pollHandler;
    private HandlerThread pollThread;
    private long rateLimitedUntilMs = 0L;
    private ConnectivityManager.NetworkCallback netCallback;
    private boolean netWasLost = false;
    private static long lastStateUploadMs = 0L;

    public static boolean isRunning() { return running; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        createNotificationChannel();
        NowState.start(this);
        startForeground(NOTIFICATION_ID, buildNotification("已启动，等待掌心窗命令"));
        if (intent != null) {
            serverUrl = ScreenshotService.normalizeUrl(intent.getStringExtra("server_url"));
            token = intent.getStringExtra("token");
        }
        if (serverUrl == null || token == null) {
            serverUrl = ScreenshotService.normalizeUrl(AppPrefs.server(this));
            token = AppPrefs.token(this);
        }
        if (serverUrl == null || token == null || serverUrl.isEmpty() || token.isEmpty()) {
            DebugState.append(this, "服务启动失败：服务器地址或 Token 为空");
            stopSelf(); return START_NOT_STICKY;
        }
        DebugState.append(this, "掌心窗公开版 v0.3.9.0 服务已启动，目标：" + serverUrl);
        // 保活修复：服务在运行但轮询线程已死（异常退出）时，重新拉起轮询，
        // 避免“显示已连接但其实不再取命令 / 命令一直卡在 pending”的问题。
        if (!running || pollThread == null || !pollThread.isAlive()) {
            running = true;
            startPolling();
        } else DebugState.append(this, "服务已在运行，继续轮询");
        PolicyScheduler.start(this, "companion");
        registerNetworkRecovery();
        // 服务拉起即对齐一次：把本机最后写入的管理配置推给服务器（内部 15 分钟节流），
        // 保证保存设置那一刻没网也不会永久失联；此后每次轮询周期内自动重试对齐。
        PolicySync.pushAll(this, false);
        // 伴看守护(MediaSync)前台保活：与守护服务同生共死，vivo/OriginOS 锁后台也不易失活。
        startMediaSync(this);
        return START_STICKY;
    }

    private void startPolling() {
        pollThread = new HandlerThread("LinjianUnifiedPoll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        DebugState.append(this, "前台轮询线程已启动");
        pollHandler.post(this::pollLoop);
    }

    private void pollLoop() {
        if (!running) return;
        long now = System.currentTimeMillis();
        long delay = AppPrefs.interval(this);
        try {
            uploadStateThrottled(serverUrl, token, this, false);
            PolicySync.pushAll(this, false);   // 运行期周期对齐管理配置（内部 15 分钟节流，best-effort）
            if (rateLimitedUntilMs > now) {
                delay = Math.max(delay, rateLimitedUntilMs - now);
            } else {
                String body = pollServer();
                if (body != null && body.length() > 0) handleCommandBody(this, body, serverUrl, token);
            }
        } catch (Exception e) { DebugState.append(this, "轮询异常：" + ScreenshotService.shortMsg(e)); }
        if (running) pollHandler.postDelayed(this::pollLoop, Math.max(AppPrefs.MIN_POLL_INTERVAL_MS, delay));
    }

    private String pollServer() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "/api/poll?device_id=" + java.net.URLEncoder.encode(AppPrefs.device(this), "UTF-8")).openConnection();
        conn.setConnectTimeout(10000); conn.setReadTimeout(15000); conn.setRequestMethod("GET"); conn.setRequestProperty("X-Auth-Token", token);
        try {
            int code = conn.getResponseCode(); String body = ScreenshotService.readBody(conn, code);
            if (code == 200) {
                if (body.contains("\"command\": null") || body.contains("\"command\":null")) return "";
                DebugState.append(this, "轮询成功：收到命令包"); return body;
            } else {
                if (code == 429) {
                    long retryMs = parseRetryAfterMs(conn.getHeaderField("Retry-After"));
                    rateLimitedUntilMs = System.currentTimeMillis() + retryMs;
                    DebugState.append(this, "轮询限流：HTTP 429，约 " + Math.max(1, retryMs / 1000) + " 秒后重试");
                } else DebugState.append(this, "轮询失败：HTTP " + code + " " + ScreenshotService.clip(body));
            }
            return "";
        } finally { conn.disconnect(); }
    }

    private static long parseRetryAfterMs(String header) {
        try {
            if (header == null || header.trim().isEmpty()) return 15000L;
            long seconds = Long.parseLong(header.trim());
            return Math.max(5000L, Math.min(60000L, seconds * 1000L));
        } catch (Exception ignored) { return 15000L; }
    }

    public static void handleCommandBody(Context ctx, String body, String serverUrl, String token) {
        try {
            JSONObject obj = new JSONObject(body);
            Object raw = obj.opt("command");
            if (raw == null || raw == JSONObject.NULL) return;
            if (raw instanceof String) {
                String s = (String) raw;
                if ("peek".equals(s)) executeCommand(ctx, "", "peek", "", "", 0,0,0,0,0,0,350,0,0,"掌心窗", "掌心窗截图", true, serverUrl, token);
                return;
            }
            JSONObject cmd = (JSONObject) raw;
            String id = cmd.optString("id", "");
            String action = cmd.optString("action", "noop");
            String app = cmd.optString("app", "");
            String pkg = cmd.optString("package", "");
            float x = (float) cmd.optDouble("x", 0);
            float y = (float) cmd.optDouble("y", 0);
            float x1 = (float) cmd.optDouble("x1", 0);
            float y1 = (float) cmd.optDouble("y1", 0);
            float x2 = (float) cmd.optDouble("x2", 0);
            float y2 = (float) cmd.optDouble("y2", 0);
            long duration = cmd.optLong("duration", 350);
            int hour = cmd.optInt("hour", -1);
            int minute = cmd.optInt("minute", -1);
            String title = cmd.optString("title", "掌心窗提醒");
            String message = cmd.optString("message", cmd.optString("text", title.length() > 0 ? title : "掌心窗闹钟"));
            boolean vibrate = cmd.optBoolean("vibrate", true);
            boolean skipUi = cmd.optBoolean("skip_ui", cmd.optBoolean("skipUi", true));
            if ("set_alarm".equals(action) && (hour < 0 || minute < 0)) {
                double delayMinutes = cmd.optDouble("minutes", 0);
                if (delayMinutes <= 0) delayMinutes = cmd.optDouble("duration_minutes", 0);
                if (delayMinutes <= 0) delayMinutes = cmd.optDouble("delay_minutes", 0);
                if (delayMinutes > 0) {
                    Calendar c = Calendar.getInstance();
                    c.add(Calendar.SECOND, (int)Math.max(1, Math.round(delayMinutes * 60.0)));
                    hour = c.get(Calendar.HOUR_OF_DAY);
                    minute = c.get(Calendar.MINUTE);
                    DebugState.append(ctx, "远程闹钟延迟换算：" + delayMinutes + " 分钟 → " + hour + ":" + String.format(Locale.CHINA, "%02d", minute));
                }
            }
            String targetText = cmd.optString("target_text", cmd.optString("query", ""));
            String inputText = cmd.optString("text", cmd.optString("input_text", ""));
            String match = cmd.optString("match", "contains");
            int index = cmd.optInt("index", 1);
            boolean append = cmd.optBoolean("append", false);
            if ("get_calendar_state".equals(action) || "upsert_calendar_event".equals(action) || "add_calendar_event".equals(action) || "delete_calendar_event".equals(action)) {
                // 写入类日历操作（增/改/删）需用户明确请求（explicit），避免自动把内容写进日历。
                boolean isWrite = !"get_calendar_state".equals(action);
                if (isWrite && !cmd.optBoolean("explicit", false)) {
                    String result = "calendar_write_requires_explicit_request";
                    DebugState.append(ctx, "拒绝自动写入日历 " + action + "（缺少 explicit）");
                    try { reportCommand(ctx, serverUrl, token, id, false, result); } catch (Exception ignored) { }
                    return;
                }
                JSONObject rr = CalendarState.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行守护日历命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, true); } catch (Exception ignored) { }
                return;
            }
            if (action.contains("diary")) {
                JSONObject rr = DiaryState.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行 TA 的日记命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); } catch (Exception ignored) { }
                return;
            }
            if ("get_guidian_state".equals(action) || "set_guidian_config".equals(action) || "trigger_guidian".equals(action) || "mark_guidian_returned".equals(action)) {
                JSONObject rr = GuidianState.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行归电命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, false); } catch (Exception ignored) { }
                return;
            }
            if ("save_known_app".equals(action)) {
                String alias = cmd.optString("alias", app);
                String p = cmd.optString("package", pkg);
                AppPrefs.saveCustomApp(ctx, alias, p);
                String result = "saved_known_app:" + alias + "=" + p;
                DebugState.append(ctx, result);
                try { reportCommand(ctx, serverUrl, token, id, true, result); uploadStateThrottled(serverUrl, token, ctx, false); } catch (Exception ignored) { }
                return;
            }
            if (FocusMode.isFocusAction(action)) {
                Context focusCtx = ScreenshotService.getInstance() != null ? ScreenshotService.getInstance() : ctx;
                JSONObject rr = FocusMode.handleCommand(focusCtx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                if (ok && ("start_focus_mode".equals(action) || "enable_focus_mode".equals(action))) {
                    FocusMode.forceShowLockActivity(focusCtx);
                }
                DebugState.append(ctx, "执行专注模式命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, focusCtx, true); } catch (Exception ignored) { }
                return;
            }
            if (isAppGateAction(action)) {
                JSONObject rr = AppGate.handleCommand(ctx, normalizedGateCommand(cmd));
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行应用门禁命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, false); } catch (Exception ignored) { }
                return;
            }

            if ("get_plugin_registry".equals(action) || "plugin_registry".equals(action)) {
                JSONObject rr = PluginRegistry.categorizedJson();
                DebugState.append(ctx, "执行插件分类查询：" + rr.length() + " 个分类");
                try { reportCommand(ctx, serverUrl, token, id, true, rr.toString()); } catch (Exception ignored) { }
                return;
            }
            if (action.contains("class_schedule")) {
                JSONObject rr = ClassScheduleState.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行课表命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); } catch (Exception ignored) { }
                return;
            }
            if (action.contains("shared_memory")) {
                // 写入类记忆操作（增/改/删）需用户明确请求（explicit），避免自动把内容写进共同记忆。
                boolean isWrite = !("get_shared_memory".equals(action) || "list_shared_memory".equals(action));
                if (isWrite && !cmd.optBoolean("explicit", false)) {
                    String result = "memory_write_requires_explicit_request";
                    DebugState.append(ctx, "拒绝自动写入共同记忆 " + action + "（缺少 explicit）");
                    try { reportCommand(ctx, serverUrl, token, id, false, result); } catch (Exception ignored) { }
                    return;
                }
                JSONObject rr = SharedMemoryState.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行共同记忆命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); } catch (Exception ignored) { }
                return;
            }
            if (action.contains("ocr") || "extract_screen".equals(action) || "scan_screen".equals(action)) {
                // screen_ocr 走 ScreenOcrService（含位图截屏 + 服务端视觉增强）；其余仍走本地 OcrPlugin。
                JSONObject rr = "screen_ocr".equals(action) ? ScreenOcrService.handleCommand(ctx, cmd) : OcrPlugin.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行识图命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); } catch (Exception ignored) { }
                return;
            }

            if ("show_center_alert".equals(action)) {
                String tone = cmd.optString("tone", "gentle");
                String buttonText = cmd.optString("button_text", "知道啦");
                // 弹窗类型标识：服务端/MCP 可用 kind 或 alert_kind 标明这是什么弹窗，默认「陪伴消息」。
                String kind = cmd.optString("kind", cmd.optString("alert_kind", "陪伴消息"));
                if (kind.trim().isEmpty()) kind = "陪伴消息";
                CompanionService.showCenterAlert(ctx, title, message, tone, buttonText, kind);
                try { reportCommand(ctx, serverUrl, token, id, true, "center_alert_shown"); } catch (Exception ignored) { }
                return;
            }
            if ("force_back_to_chat".equals(action)) {
                // 分级拉回：先弹窗，不直接跳转。
                // 想你程度低于阈值 → 软级别（两个按钮，可拒绝并留下理由）；
                // 达到阈值或强情绪 → 强级别（只有一个按钮，点击强制跳回聊天界面）。
                JSONObject cfg = buildPullBackConfig(ctx, cmd);
                PullBackActivity.show(ctx, cfg);
                boolean strong = PullBackCard.isStrongLevel(cfg);
                String result = (strong ? "pull_back_strong_shown" : "pull_back_soft_shown")
                        + " level=" + cfg.optString("level", "soft")
                        + " miss=" + cfg.optInt("miss_percent", 0)
                        + " tag=" + cfg.optString("emotion_tag", "");
                try { reportCommand(ctx, serverUrl, token, id, true, result); } catch (Exception ignored) { }
                return;
            }
            if ("set_companion_emotion".equals(action) || "get_companion_emotion".equals(action)
                    || "write_companion_emotion".equals(action)) {
                JSONObject rr = CompanionEmotionState.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "陪伴对象情绪命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, true); } catch (Exception ignored) { }
                return;
            }

            // —— 训诫管束模块（DisciplineController）：set/get/trigger ——
            if ("set_discipline_mode".equals(action) || "get_discipline_state".equals(action) || "trigger_discipline".equals(action)) {
                JSONObject rr = DisciplineController.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行训诫命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, true); } catch (Exception ignored) { }
                return;
            }

            // —— 课表导入（TimetableParser）：ICS / 图片OCR / JSON ——
            if ("import_timetable".equals(action) || "parse_timetable".equals(action)) {
                JSONObject rr = new JSONObject();
                try {
                    if (cmd.has("ics") || cmd.has("text")) {
                        rr = TimetableParser.importFromText(ctx, cmd.optString(cmd.has("ics") ? "ics" : "text", ""));
                    } else if (cmd.has("image_ocr") || cmd.has("ocr_result")) {
                        rr = TimetableParser.importFromImageOcr(ctx, cmd.optJSONObject(cmd.has("image_ocr") ? "image_ocr" : "ocr_result"));
                    } else if (cmd.has("entries")) {
                        ClassScheduleState.importEntries(ctx, cmd.optJSONArray("entries"));
                        rr.put("ok", true).put("result", "timetable_imported_entries");
                    } else {
                        rr.put("ok", false).put("error", "no_timetable_source");
                    }
                } catch (Exception e) { try { rr.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { } }
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行课表导入命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, true); } catch (Exception ignored) { }
                return;
            }

            if ("start_tour".equals(action) || "stop_tour".equals(action)) {
                try { reportCommand(ctx, serverUrl, token, id, true, action + "_ack"); } catch (Exception ignored) { }
                return;
            }

            // —— 策略配置（PRD 全部可调参数）：list / get / set / reset，手机端与 MCP 双向可改，立即生效 ——
            if ("set_policy".equals(action) || "get_policy".equals(action)
                    || "list_policy".equals(action) || "reset_policy".equals(action)) {
                JSONObject rr = PolicyCommand.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.optString("result", rr.toString());
                DebugState.append(ctx, "执行策略配置命令 " + action + "：" + result);
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, true); } catch (Exception ignored) { }
                return;
            }

            if ("run_sequence".equals(action)) {
                executeSequence(ctx, id, cmd, serverUrl, token);
                return;
            }
            executeCommand(ctx, id, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, skipUi, targetText, inputText, match, index, append, cmd.optBoolean("explicit", false));
        } catch (Exception e) { DebugState.append(ctx, "命令解析异常：" + ScreenshotService.shortMsg(e)); }
    }

    private static String normalizeGateAction(String action) {
        if ("screen_break_app".equals(action) || "start_screen_break".equals(action) || "screen_break".equals(action)) return "lock_app";
        if ("end_screen_break".equals(action) || "stop_screen_break".equals(action)) return "unlock_app";
        if ("temporary_screen_break_release".equals(action) || "temporary_screen_release".equals(action)) return "temporary_unlock_app";
        if ("extend_screen_break".equals(action)) return "extend_lock";
        if ("deny_screen_break_release_request".equals(action) || "deny_break_release_request".equals(action)) return "deny_unlock_request";
        if ("get_screen_break_state".equals(action)) return "get_lock_state";
        if ("set_screen_break_passphrase".equals(action)) return "set_emergency_passphrase";
        if ("add_screen_break_app".equals(action)) return "add_locked_app";
        if ("remove_screen_break_app".equals(action)) return "remove_locked_app";
        if ("list_screen_break_apps".equals(action)) return "list_lockable_apps";
        return action;
    }

    private static JSONObject normalizedGateCommand(JSONObject cmd) throws Exception {
        JSONObject copy = new JSONObject(cmd.toString());
        copy.put("action", normalizeGateAction(copy.optString("action", "")));
        return copy;
    }

    private static boolean isAppGateAction(String action) {
        action = normalizeGateAction(action);
        return "lock_app".equals(action) || "unlock_app".equals(action) || "temporary_unlock_app".equals(action) || "extend_lock".equals(action) || "deny_unlock_request".equals(action) || "get_lock_state".equals(action) || "set_emergency_passphrase".equals(action) || "add_locked_app".equals(action) || "remove_locked_app".equals(action) || "list_lockable_apps".equals(action);
    }
    private static void executeCommand(Context ctx, String id, String action, String app, String pkg, float x, float y, float x1, float y1, float x2, float y2, long duration, int hour, int minute, String title, String message, boolean vibrate, String serverUrl, String token) {
        executeCommand(ctx, id, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, true, "", "", "contains", 1, false, false);
    }

    private static void executeCommand(Context ctx, String id, String action, String app, String pkg, float x, float y, float x1, float y1, float x2, float y2, long duration, int hour, int minute, String title, String message, boolean vibrate, String serverUrl, String token, boolean skipUi) {
        executeCommand(ctx, id, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, skipUi, "", "", "contains", 1, false, false);
    }

    private static void executeCommand(Context ctx, String id, String action, String app, String pkg, float x, float y, float x1, float y1, float x2, float y2, long duration, int hour, int minute, String title, String message, boolean vibrate, String serverUrl, String token, boolean skipUi, String targetText, String inputText, String match, int index, boolean append, boolean explicit) {
        JSONObject one = performAction(ctx, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, skipUi, targetText, inputText, match, index, append, explicit);
        boolean ok = one.optBoolean("ok", false);
        String result = one.optString("result", one.toString());
        DebugState.append(ctx, "执行命令 " + action + "：" + result);
        try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, false); } catch (Exception ignored) { }
    }

    private static JSONObject performAction(Context ctx, String action, String app, String pkg, float x, float y, float x1, float y1, float x2, float y2, long duration, int hour, int minute, String title, String message, boolean vibrate, String serverUrl, String token, boolean skipUi, String targetText, String inputText, String match, int index, boolean append, boolean explicit) {
        JSONObject out = new JSONObject();
        boolean ok = false; String result = "";
        try {
            ScreenshotService svc = ScreenshotService.getInstance();
            if ("wait".equals(action)) { ok = true; result = "wait";
            } else if ("get_life_state".equals(action)) { ok = true; result = LifeState.collect(ctx).toString();
            } else if ("get_calendar_state".equals(action) || "upsert_calendar_event".equals(action) || "add_calendar_event".equals(action) || "delete_calendar_event".equals(action)) { // 日历命令统一由 handleCommandBody(ctx, cmd) 处理：那里带完整 payload，能解析出稳定的事件 id。 // performAction 这条旧标量路径没有 payload，若继续用命令 id 顶替会重现「删除 not_found:命令id / 更新换 id」(R1/R2)， // 因此明确拒绝，绝不再悄悄构造一个缺 id 的 cmd。 ok = false; result = "calendar_command_requires_payload_use_handleCommandBody";
            } else if (FocusMode.isFocusAction(action)) {
                JSONObject focusCmd = new JSONObject().put("action", action).put("app", app).put("package", pkg);
                if (duration > 0 && duration != 350) focusCmd.put("duration_minutes", Math.max(1, Math.round(duration / 60000.0)));
                if (message != null && message.trim().length() > 0) focusCmd.put("message", message);
                if (title != null && title.trim().length() > 0) focusCmd.put("goal", title);
                Context focusCtx = ScreenshotService.getInstance() != null ? ScreenshotService.getInstance() : ctx;
                JSONObject rr = FocusMode.handleCommand(focusCtx, focusCmd); ok = rr.optBoolean("ok", false); result = rr.optString("result", rr.toString());
                if (ok && ("start_focus_mode".equals(action) || "enable_focus_mode".equals(action))) FocusMode.forceShowLockActivity(focusCtx);
            } else if (isAppGateAction(action)) { JSONObject rr = AppGate.handleCommand(ctx, new JSONObject().put("action", normalizeGateAction(action)).put("app", app).put("package", pkg)); ok = rr.optBoolean("ok", false); result = rr.optString("result", rr.toString());
            } else if ("get_screen_nodes".equals(action)) {
                if (svc != null) { svc.refreshScreenModel(); ok = true; result = svc.getScreenNodesJsonNow(); }
                else result = "accessibility service not ready";
            } else if ("tap_text".equals(action)) {
                if (svc != null) { JSONObject rr = svc.tapText(targetText, match, index); ok = rr.optBoolean("ok", false); result = rr.toString(); }
                else result = "accessibility service not ready";
            } else if ("input_text".equals(action)) {
                if (svc != null) { JSONObject rr = svc.inputText(inputText, append); ok = rr.optBoolean("ok", false); result = rr.toString(); }
                else result = "accessibility service not ready";
            } else if ("peek".equals(action)) {
                if (svc != null) { svc.doScreenshot(serverUrl, token); ok = true; result = "screenshot requested"; }
                else result = "accessibility service not ready";
            } else if ("open_app".equals(action)) {
                if (pkg == null || pkg.length() == 0) pkg = AppPrefs.packageForApp(ctx, app);
                result = openPackageResult(ctx, pkg);
                ok = result.startsWith("opened_");
            } else if ("home".equals(action)) { ok = svc != null && svc.doHome(); result = ok ? "home" : "home_failed_or_accessibility_missing";
            } else if ("back".equals(action)) { ok = svc != null && svc.doBack(); result = ok ? "back" : "back_failed_or_accessibility_missing";
            } else if ("recents".equals(action)) { ok = svc != null && svc.doRecents(); result = ok ? "recents" : "recents_failed_or_accessibility_missing";
            } else if ("screen_off".equals(action) || "turn_screen_off".equals(action) || "lock_screen".equals(action) || "phone_screen_off".equals(action)) { ok = svc != null && svc.doLockScreen(); result = ok ? "screen_off" : "screen_off_failed_or_accessibility_missing_or_android_too_old";
            } else if ("tap".equals(action)) { ok = svc != null && svc.doTap(x, y); result = ok ? ("tap:" + x + "," + y) : "tap_failed_or_accessibility_missing";
            } else if ("swipe".equals(action)) { ok = svc != null && svc.doSwipe(x1, y1, x2, y2, duration); result = ok ? "swipe" : "swipe_failed_or_accessibility_missing";
            } else if ("set_alarm".equals(action)) {
                // 定闹钟是强副作用：仅当命令明确带有 explicit（用户明确说「帮我定闹钟」并给出时间）时才执行；
                // 任何自动/隐式触发的 set_alarm 一律拒绝，避免「没说要闹钟也被定」。
                if (!explicit) { ok = false; result = "alarm_requires_explicit_request"; }
                else { ok = setAlarm(ctx, hour, minute, message, vibrate, skipUi); result = ok ? "alarm " + hour + ":" + minute : "cannot set alarm"; }
            } else if ("send_notification".equals(action)) { ok = showReminderNotification(ctx, title, message); result = ok ? "heads_up_notification_sent" : "notification permission missing";
            } else if ("get_guidian_state".equals(action) || "set_guidian_config".equals(action) || "trigger_guidian".equals(action) || "mark_guidian_returned".equals(action)) { JSONObject rr = GuidianState.handleCommand(ctx, new JSONObject().put("action", action)); ok = rr.optBoolean("ok", false); result = rr.toString();
            } else if ("send_watch_whisper".equals(action)) {
                // 伴看气泡：在画中画小挂件上弹出一句陪伴/吐槽；无浮窗权限时静默忽略。
                String whisper = (message != null && message.trim().length() > 0) ? message.trim() : "我在陪你看呢~";
                CompanionFloatingWidget.showWhisper(whisper);
                ok = true; result = "watch_whisper_shown";
            } else if ("pause_media".equals(action)) {
                // 强制暂停当前媒体：提醒顾钰看自己或休息。返回是否成功发起暂停。
                ok = MediaState.pauseActiveMedia(ctx);
                result = ok ? "media_paused" : "no_active_media_or_pause_failed";
            } else if ("media_switch_screen".equals(action)) {
                // 把画面切回掌心窗陪伴页（聊天界面）。
                CompanionService.bringAppToFront(ctx);
                ok = true; result = "switched_to_chat";
            } else if ("get_live_media_context".equals(action)) {
                // 读取当前播放上下文（标题/博主/进度等），供陪伴对象实时了解对方在刷什么。
                ok = true; result = MediaState.collect(ctx).toString();
            } else { ok = true; result = "noop"; }
        } catch (Exception e) { result = ScreenshotService.shortMsg(e); }
        try { out.put("ok", ok); out.put("action", action); out.put("result", result); } catch (Exception ignored) { }
        return out;
    }

    private static void executeSequence(Context ctx, String id, JSONObject cmd, String serverUrl, String token) {
        JSONArray report = new JSONArray();
        boolean allOk = true;
        int executed = 0;
        boolean stopOnError = cmd.optBoolean("stop_on_error", true);
        JSONArray steps = cmd.optJSONArray("steps");
        if (steps == null) {
            JSONObject payload = cmd.optJSONObject("payload");
            if (payload != null) steps = payload.optJSONArray("steps");
        }
        if (steps == null) steps = new JSONArray();
        int count = Math.min(12, steps.length());
        DebugState.append(ctx, "开始执行动作序列：" + count + " 步，stop_on_error=" + stopOnError);
        for (int i = 0; i < count; i++) {
            JSONObject stepReport = new JSONObject();
            try {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) step = new JSONObject();
                String action = step.optString("action", "noop");
                String label = step.optString("label", action);
                String app = step.optString("app", "");
                String pkg = step.optString("package", step.optString("pkg", ""));
                float x = (float) step.optDouble("x", 0); float y = (float) step.optDouble("y", 0);
                float x1 = (float) step.optDouble("x1", 0); float y1 = (float) step.optDouble("y1", 0);
                float x2 = (float) step.optDouble("x2", 0); float y2 = (float) step.optDouble("y2", 0);
                long duration = step.optLong("duration", 350);
                int hour = step.optInt("hour", -1); int minute = step.optInt("minute", -1);
                String title = step.optString("title", "掌心窗提醒");
                String message = step.optString("message", AppPrefs.userName(ctx) + "，看一眼这里。");
                boolean vibrate = step.optBoolean("vibrate", true);
                boolean skipUi = step.optBoolean("skip_ui", true);
                String targetText = step.optString("target_text", step.optString("query", ""));
                String inputText = step.optString("text", step.optString("input_text", ""));
                String match = step.optString("match", "contains");
                int textIndex = step.optInt("index", 1);
                boolean append = step.optBoolean("append", false);
                boolean explicit = step.optBoolean("explicit", false);
                JSONObject r = performAction(ctx, action, app, pkg, x, y, x1, y1, x2, y2, duration, hour, minute, title, message, vibrate, serverUrl, token, skipUi, targetText, inputText, match, textIndex, append, explicit);
                int wait = clampWait(step.optInt("wait_ms", step.optInt("delay_ms", 0)));
                if (wait > 0) Thread.sleep(wait);
                String expect = step.optString("expect_app", "").trim();
                if (expect.length() > 0) {
                    String expectedPkg = AppPrefs.packageForApp(ctx, expect);
                    if (expectedPkg.length() == 0 && AppPrefs.isPackageLike(expect)) expectedPkg = expect;
                    String current = ScreenshotService.currentPackage();
                    boolean expectMatch = current != null && current.equals(expectedPkg);
                    r.put("expect_app", expect);
                    r.put("expected_package", expectedPkg);
                    r.put("current_package", current == null ? "" : current);
                    r.put("expect_ok", expectMatch);
                    if (!expectMatch) r.put("ok", false);
                }
                boolean ok = r.optBoolean("ok", false);
                stepReport.put("index", i + 1); stepReport.put("label", label); stepReport.put("action", action); stepReport.put("ok", ok); stepReport.put("detail", r);
                report.put(stepReport);
                executed++;
                DebugState.append(ctx, "序列步骤 " + (i + 1) + "/" + count + " [" + label + "]：" + r.optString("result", ""));
                if (!ok) { allOk = false; if (stopOnError) break; }
            } catch (Exception e) {
                allOk = false;
                try { stepReport.put("index", i + 1); stepReport.put("ok", false); stepReport.put("error", ScreenshotService.shortMsg(e)); report.put(stepReport); } catch (Exception ignored) { }
                DebugState.append(ctx, "序列步骤 " + (i + 1) + " 异常：" + ScreenshotService.shortMsg(e));
                if (stopOnError) break;
            }
        }
        JSONObject finalReport = new JSONObject();
        try {
            finalReport.put("ok", allOk);
            finalReport.put("executed", executed);
            finalReport.put("total", count);
            finalReport.put("current_package", ScreenshotService.currentPackage());
            finalReport.put("steps", report);
        } catch (Exception ignored) { }
        DebugState.append(ctx, "动作序列结束：" + (allOk ? "全部成功" : "有步骤失败") + "，执行 " + executed + "/" + count);
        try { reportCommand(ctx, serverUrl, token, id, allOk, finalReport.toString()); uploadStateThrottled(serverUrl, token, ctx, false); } catch (Exception ignored) { }
    }

    private static int clampWait(int v) { return Math.max(0, Math.min(5000, v)); }

    public static String openPackageResult(Context ctx, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "package_empty";
        String target = pkg.trim();
        try {
            PackageManager pm = ctx.getPackageManager();
            try { pm.getPackageInfo(target, 0); } catch (Exception missing) { return "package_not_found:" + target; }

            Intent standard = pm.getLaunchIntentForPackage(target);
            if (standard != null) {
                standard.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    ctx.startActivity(standard);
                    return "opened_standard:" + target;
                } catch (Exception e) {
                    DebugState.append(ctx, "标准启动失败 " + target + "：" + ScreenshotService.shortMsg(e));
                }
            }

            Intent query = new Intent(Intent.ACTION_MAIN);
            query.addCategory(Intent.CATEGORY_LAUNCHER);
            query.setPackage(target);
            List<ResolveInfo> launchers = pm.queryIntentActivities(query, 0);
            if (launchers == null || launchers.isEmpty()) return "no_launch_intent:" + target;

            ResolveInfo best = launchers.get(0);
            if (best == null || best.activityInfo == null) return "no_launch_activity:" + target;

            Intent explicit = new Intent(Intent.ACTION_MAIN);
            explicit.addCategory(Intent.CATEGORY_LAUNCHER);
            explicit.setClassName(best.activityInfo.packageName, best.activityInfo.name);
            explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(explicit);
            return "opened_launcher_activity:" + best.activityInfo.packageName + "/" + best.activityInfo.name;
        } catch (Exception e) {
            return "activity_start_failed:" + target + ":" + ScreenshotService.shortMsg(e);
        }
    }

    /** 屏幕正中弹窗：优先用悬浮窗直接盖在当前 App 之上；没有悬浮窗权限或失败时退回 Activity 兜底。 */
    public static void showCenterAlert(Context ctx, String title, String message, String tone, String buttonText) {
        showCenterAlert(ctx, title, message, tone, buttonText, "陪伴消息");
    }

    public static void showCenterAlert(Context ctx, String title, String message, String tone, String buttonText, String kindLabel) {
        showCenterAlert(ctx, title, message, tone, buttonText, kindLabel, false);
    }

    /** 带震动标志的居中弹窗：训诫等强制类弹窗调用，弹窗同时震动提示。 */
    public static void showCenterAlert(Context ctx, String title, String message, String tone, String buttonText, String kindLabel, boolean vibrate) {
        try {
            if (vibrate) vibrate(ctx, "strict".equalsIgnoreCase(tone) || "angry".equalsIgnoreCase(tone));
            if (AlertCenterOverlay.canShow(ctx) && AlertCenterOverlay.show(ctx, title, message, tone, buttonText, kindLabel)) {
                DebugState.append(ctx, "屏幕居中弹窗已下发（悬浮窗，类型=" + kindLabel + "）：" + (title == null ? "" : title));
                return;
            }
            // 没有悬浮窗权限或悬浮失败：退回 Activity 兜底，仍然弹得出。
            Intent i = new Intent(ctx, AlertCenterActivity.class);
            i.putExtra("title", title == null ? "" : title);
            i.putExtra("message", message == null ? "" : message);
            i.putExtra("tone", tone == null ? "gentle" : tone);
            i.putExtra("button_text", buttonText == null ? "知道啦" : buttonText);
            i.putExtra("kind", kindLabel == null ? "陪伴消息" : kindLabel);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            DebugState.append(ctx, "屏幕居中弹窗已下发（Activity 兜底，类型=" + kindLabel + "）：" + (title == null ? "" : title));
        } catch (Exception e) { DebugState.append(ctx, "居中弹窗异常：" + ScreenshotService.shortMsg(e)); }
    }

    /** 震动提示：strong=true 用较长较强模式（训诫），否则短促一震。无权限/旧系统静默失败。 */
    public static void vibrate(Context ctx, boolean strong) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.os.Vibrator v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 26) {
                    long[] pattern = strong ? new long[]{0, 120, 60, 120, 60, 220} : new long[]{0, 90, 50, 90};
                    v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(strong ? new long[]{0, 120, 60, 120, 60, 220} : new long[]{0, 90, 50, 90}, -1);
                }
            } else {
                android.os.Vibrator v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(strong ? 500 : 200);
            }
        } catch (Exception ignored) { }
    }

    /** 强制拉回：不管用户当前停在哪个 App，都把手机切回掌心窗陪伴页（聊天界面）。 */
    public static void bringAppToFront(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (i == null) i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            DebugState.append(ctx, "已强制拉回掌心窗陪伴页");
        } catch (Exception e) { DebugState.append(ctx, "强制拉回异常：" + ScreenshotService.shortMsg(e)); }
    }

    /**
     * 组装分级拉回弹窗的配置。
     * 服务端可以在 payload 里覆盖 level / threshold / 文案；
     * 没给的时候按本机保存的陪伴对象情绪判断：想你程度达到阈值、或情绪是生气/吃醋等强情绪 → 强级别。
     */
    private static JSONObject buildPullBackConfig(Context ctx, JSONObject cmd) {
        JSONObject cfg = new JSONObject();
        try {
            JSONObject payload = cmd.optJSONObject("payload");
            String tag = CompanionEmotionState.emotionTag(ctx);
            int miss = CompanionEmotionState.effectiveMissPercent(ctx);
            int intensity = CompanionEmotionState.effectiveIntensity(ctx);
            int threshold = 70;
            String level = "";
            String message = "";
            String title = "";

            if (payload != null) {
                if (payload.has("emotion_tag")) tag = payload.optString("emotion_tag", tag);
                if (payload.has("miss_percent")) miss = payload.optInt("miss_percent", miss);
                if (payload.has("intensity")) intensity = payload.optInt("intensity", intensity);
                if (payload.has("threshold")) threshold = payload.optInt("threshold", threshold);
                if (payload.has("level")) level = payload.optString("level", "");
                message = payload.optString("message", "");
                title = payload.optString("title", "");
            }
            // 顶层平铺字段兜底（服务端会把 payload 平铺到命令顶层）。
            if (cmd.has("emotion_tag")) tag = cmd.optString("emotion_tag", tag);
            if (cmd.has("miss_percent")) miss = cmd.optInt("miss_percent", miss);
            if (cmd.has("intensity")) intensity = cmd.optInt("intensity", intensity);
            if (cmd.has("threshold")) threshold = cmd.optInt("threshold", threshold);
            if (cmd.has("level")) level = cmd.optString("level", level);
            if (title.isEmpty()) title = cmd.optString("title", "");
            if (message.isEmpty()) message = cmd.optString("message", "");
            // 服务端自动生成的文案不含本机真实称呼，auto_message 时改用本地文案。
            boolean autoMessage = payload != null && payload.optBoolean("auto_message", false);
            if (cmd.optBoolean("auto_message", false)) autoMessage = true;
            if (autoMessage) message = "";
            if (autoMessage) title = "";

            threshold = Math.max(1, Math.min(100, threshold));
            boolean strongEmotion = CompanionEmotionState.isStrong(ctx) || isStrongTag(tag);
            // level 为空或 auto 时按情绪自动判定；显式指定 soft/strong 时以指定为准。
            if ("auto".equalsIgnoreCase(level)) level = "";
            boolean strong = "strong".equalsIgnoreCase(level)
                    || (level.isEmpty() && (miss >= threshold || strongEmotion));

            if (message.isEmpty()) message = CompanionEmotionState.message(ctx);
            if (title.isEmpty()) title = strong ? AppPrefs.companionName(ctx) + "要你回来" : AppPrefs.companionName(ctx) + "在等你";

            cfg.put("level", strong ? "strong" : "soft");
            cfg.put("strong", strong);
            cfg.put("allow_decline", !strong);
            cfg.put("emotion_tag", tag);
            cfg.put("miss_percent", miss);
            cfg.put("intensity", intensity);
            cfg.put("threshold", threshold);
            cfg.put("title", title);
            cfg.put("message", message);
            cfg.put("tone", strong ? "angry" : CompanionEmotionState.tone(ctx));
            // 弹窗类型标识：服务端下发 / MCP 触发的远程查岗拉回（payload 可用 kind_label 覆盖）。
            cfg.put("kind", "remote_checkin");
            cfg.put("kind_label", payload != null && payload.has("kind_label")
                    ? payload.optString("kind_label", "查岗拉回") : "查岗拉回");
            if (cmd.has("kind_label")) cfg.put("kind_label", cmd.optString("kind_label", "查岗拉回"));
        } catch (Exception e) {
            DebugState.append(ctx, "拉回配置组装异常：" + ScreenshotService.shortMsg(e));
        }
        return cfg;
    }

    private static boolean isStrongTag(String tag) {
        if (tag == null) return false;
        String[] strong = {"生气", "吃醋", "担心", "委屈"};
        for (String s : strong) if (tag.contains(s)) return true;
        return false;
    }

    public static boolean showReminderNotification(Context ctx, String title, String message) {
        return showReminderNotification(ctx, title, message, (int)(System.currentTimeMillis() % Integer.MAX_VALUE));
    }

    public static boolean showHomeModeNotification(Context ctx, String title, String message) {
        return showReminderNotification(ctx, title, message, HOME_MODE_NOTIFICATION_ID);
    }

    /** 公开的 best-effort POST：用于识图等插件的远程可选增强通道，失败返回以 "ERROR" 开头的描述。 */
    public static String postJsonSafe(String urlStr, String token, JSONObject obj) {
        try {
            return postJson(urlStr, token, obj);
        } catch (Exception e) {
            return "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static void cancelHomeModeNotification(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(HOME_MODE_NOTIFICATION_ID);
        } catch (Exception ignored) { }
    }

    private static boolean showReminderNotification(Context ctx, String title, String message, int notificationId) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            String safeTitle = (title == null || title.trim().isEmpty()) ? "掌心窗提醒" : title.trim();
            String safeMessage = (message == null || message.trim().isEmpty()) ? AppPrefs.userName(ctx) + "，看一眼这里。" : message.trim();

            Intent detail = new Intent(ctx, ReminderActivity.class);
            detail.putExtra("title", safeTitle);
            detail.putExtra("message", safeMessage);
            detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, notificationId, detail, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(REMINDER_CHANNEL_ID, "掌心窗悬浮横幅提醒", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("像微信消息一样从顶部弹出的横幅提醒；点开后可进入详情页。");
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                channel.enableVibration(true);
                nm.createNotificationChannel(channel);
            }
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(ctx, REMINDER_CHANNEL_ID) : new Notification.Builder(ctx);
            Notification n = builder
                    .setContentTitle(safeTitle)
                    .setContentText(safeMessage)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .build();
            nm.notify(notificationId, n);
            DebugState.append(ctx, "悬浮横幅通知已发送：" + safeTitle);
            return true;
        } catch (Exception e) { DebugState.append(ctx, "悬浮横幅通知异常：" + ScreenshotService.shortMsg(e)); return false; }
    }

    private static boolean setAlarm(Context ctx, int hour, int minute, String message, boolean vibrate, boolean skipUi) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return false;
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, hour);
            i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, message == null || message.length() == 0 ? "掌心窗闹钟" : message);
            i.putExtra(AlarmClock.EXTRA_VIBRATE, vibrate);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) { DebugState.append(ctx, "远程闹钟异常：" + ScreenshotService.shortMsg(e)); return false; }
    }

    private static void uploadStateThrottled(String serverUrl, String token, Context ctx, boolean force) throws Exception {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastStateUploadMs < AppPrefs.STATE_UPLOAD_INTERVAL_MS) return;
        lastStateUploadMs = now;
        JSONObject state = LifeState.collect(ctx);
        // 陪伴对象情绪随状态一起上报，服务端据此做镜像，MCP 读到的和手机上是同一份。
        try { state.put("companion_emotion", CompanionEmotionState.snapshot(ctx)); } catch (Exception ignored) { }
        // 归电自动补弹：随生活状态上传定期检查；已限频，避免公开后端被状态上报打到 429。
        GuidianState.evaluate(ctx, state);
        state = LifeState.collect(ctx);
        // 共同记忆以手机本机存储为唯一数据源：每次状态上报把记忆镜像到服务端，
        // 保证 /api/shared_memory 读取、网页与 MCP 检索看到的是手机上的最新记忆（可改可查）。
        try { state.put("shared_memory", SharedMemoryState.getItems(ctx)); } catch (Exception ignored) { }
        postJson(serverUrl + "/api/device/state", token, state);
        ActiveReminder.evaluate(ctx, state);
        HomeMode.evaluate(ctx, state);
    }

    private static void reportCommand(Context ctx, String serverUrl, String token, String id, boolean ok, String result) throws Exception {
        if (id == null || id.length() == 0) return;
        JSONObject report = new JSONObject(); report.put("device_id", AppPrefs.device(ctx)); report.put("command_id", id); report.put("ok", ok); report.put("result", result);
        postJson(serverUrl + "/api/device/report", token, report);
    }

    private static String postJson(String urlStr, String token, JSONObject obj) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(urlStr).openConnection(); conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); conn.setRequestProperty("X-Auth-Token", token); conn.setDoOutput(true);
        byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8); try (OutputStream os = conn.getOutputStream()) { os.write(data); }
        InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); if (is != null) { byte[] buf = new byte[1024]; int n; while ((n = is.read(buf)) > 0) bos.write(buf,0,n); }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private void createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { NotificationManager nm = getSystemService(NotificationManager.class); NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "掌心窗", NotificationManager.IMPORTANCE_LOW); channel.setDescription("掌心窗正在等待你授权的截图与手机动作请求"); nm.createNotificationChannel(channel); NotificationChannel reminder = new NotificationChannel(REMINDER_CHANNEL_ID, "掌心窗悬浮横幅提醒", NotificationManager.IMPORTANCE_HIGH); reminder.setDescription("来自掌心窗的悬浮横幅、生活提醒与回家模式"); reminder.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); reminder.enableVibration(true); nm.createNotificationChannel(reminder); } }
    private Notification buildNotification(String text) { Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this); return builder.setContentTitle("掌心窗运行中").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build(); }
    /** Bug 4.2：监听网络恢复，断网重连后立刻重算情绪并强制上报，消除「一次 Tick 滞后」。 */
    private void registerNetworkRecovery() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onLost(Network network) { netWasLost = true; }
                @Override public void onAvailable(Network network) {
                    if (!netWasLost) return;
                    netWasLost = false;
                    onNetworkReconnected();
                }
            };
            cm.registerNetworkCallback(req, netCallback);
        } catch (Exception ignored) { }
    }

    private void onNetworkReconnected() {
        try {
            DebugState.append(this, "网络恢复：立即重算情绪并强制上报");
            EmotionPerception.perceive(this, new EmotionPerception.Signals());
            if (serverUrl != null && token != null && !serverUrl.isEmpty() && !token.isEmpty()) {
                uploadStateThrottled(serverUrl, token, this, true);
            }
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        running = false;
        DebugState.append(this, "服务已销毁/停止");
        if (netCallback != null) {
            try { ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback); } catch (Exception ignored) { }
        }
        try { stopService(new Intent(this, MediaSyncService.class)); } catch (Exception ignored) { }
        PolicyScheduler.stop(this, "companion");
        if (pollThread != null) pollThread.quitSafely();
        super.onDestroy();
    }

    /** 启动伴看守护前台服务（锁后台保活）。 */
    public static void startMediaSync(Context ctx) {
        if (ctx == null) return;
        try {
            Intent mi = new Intent(ctx, MediaSyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(mi);
            else ctx.startService(mi);
        } catch (Exception e) {
            DebugState.append(ctx, "启动伴看守护失败：" + ScreenshotService.shortMsg(e));
        }
    }
}
