package dev.linjian.peek;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同屏流媒体 / 短视频伴看守护插件（Companion Media Sync）。
 *
 * 职责：
 *  1. 前台保活：以 dataSync 前台服务常驻，vivo(OriginOS) 等系统锁后台也不易被回收；
 *     自身死亡时由 CompanionService / BootCompletedReceiver 重启。
 *  2. 感知：每 4 秒轮询 MediaState，检测白名单流媒体 App（抖音/B站/腾讯视频/小红书等）进入/离开前台。
 *  3. 事件同步：进入伴看 App 时向后端发送 media_watch_start；离开发送 media_watch_end；
 *     播放中每 5 秒节流上报一次 media_state（进度/标题/博主等）。
 *  4. 浮窗：伴看期间拉起画中画小挂件（祁晏头像 + 呼吸动效 + 当前在看的视频），点击可快捷查岗（切回聊天）。
 *  5. 管束：单 App 连续观看超阈值（默认 90 分钟）联动强制拉回 + 休息提醒。
 */
public class MediaSyncService extends Service {
    private static final String CHANNEL_ID = "linjian_media_sync";
    private static final int NOTIFICATION_ID = 20260914;
    private static volatile boolean running = false;

    private static final long POLL_MS = 4000L;
    private static final long STATE_UPLOAD_INTERVAL_MS = 5000L;
    private static final long DEFAULT_WATCH_LIMIT_MS = 90L * 60L * 1000L; // 单 App 连续观看上限

    // BE-12：会话事件统一用需求单的 enter / leave / timeout 三类，并写入 activity_events（source=media）。
    private static final String EVENT_ENTER = "enter";
    private static final String EVENT_LEAVE = "leave";
    private static final String EVENT_TIMEOUT = "timeout";

    private Handler pollHandler;
    private HandlerThread pollThread;
    private final AtomicLong lastStateUploadMs = new AtomicLong(0L);
    private final AtomicLong sessionWatchMs = new AtomicLong(0L);
    private volatile boolean wasWatching = false;
    private volatile JSONObject lastSnapshot = new JSONObject();
    private volatile long watchLimitMs = DEFAULT_WATCH_LIMIT_MS;
    // BE-12：会话心跳与超时；阈值取自 AppPrefs（可配，默认 120s）。
    private volatile long lastHeartbeatMs = 0L;
    private volatile long sessionTimeoutMs = AppPrefs.DEFAULT_MEDIA_SESSION_TIMEOUT_SEC * 1000L;

    public static boolean isRunning() { return running; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("伴看守护运行中"));
        if (!running || pollThread == null || !pollThread.isAlive()) {
            running = true;
            startPolling();
            DebugState.append(this, "伴看守护(MediaSync)已启动 v0.3.9.0");
        }
        return START_STICKY;
    }

    private void startPolling() {
        pollThread = new HandlerThread("LinjianMediaSync");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        pollHandler.post(this::pollTick);
    }

    private void pollTick() {
        if (!running) return;
        try {
            Context ctx = getApplicationContext();
            sessionTimeoutMs = AppPrefs.mediaSessionTimeoutSec(ctx) * 1000L;
            JSONObject m = MediaState.collect(ctx);
            boolean watchable = m.optBoolean("is_watchable", false);
            boolean playing = "playing".equals(m.optString("state", ""));
            boolean available = m.optBoolean("available", false) && m.optBoolean("is_watchable", false);

            if (watchable && (playing || available)) {
                long now = System.currentTimeMillis();
                lastHeartbeatMs = now; // 有活跃媒体 → 刷新心跳
                if (!wasWatching) {
                    wasWatching = true;
                    sessionWatchMs.set(0L);
                    CompanionFloatingWidget.show(ctx, m);
                    // BE-12：会话建立写 enter（同时落 activity_events，source=media）。
                    emitSessionEvent(ctx, EVENT_ENTER, m);
                    DebugState.append(ctx, "伴看：进入 " + m.optString("app", "媒体App"));
                } else {
                    CompanionFloatingWidget.update(ctx, m);
                    sessionWatchMs.addAndGet(POLL_MS);
                    if (sessionWatchMs.get() >= watchLimitMs) {
                        sessionWatchMs.set(0L); // 冷却重置，避免反复弹
                        uploadEvent(ctx, "media_watch_limit_exceeded", m);
                        // 联动现有拉回/查岗机制：强制切回掌心窗 + 休息提醒弹窗
                        CompanionService.bringAppToFront(ctx);
                        CompanionService.showCenterAlert(ctx,
                                AppPrefs.companionName(ctx) + "提醒你休息",
                                "都看了 " + (watchLimitMs / 60000L) + " 分钟啦，眼睛歇会儿，也看看我嘛~",
                                "gentle", "知道啦", "伴看休息提醒");
                    }
                }
                if (now - lastStateUploadMs.get() >= STATE_UPLOAD_INTERVAL_MS) {
                    lastStateUploadMs.set(now);
                    uploadState(ctx, m);
                }
            } else {
                boolean was = wasWatching;
                if (was) {
                    long now = System.currentTimeMillis();
                    // BE-12：会话销毁/超时——正常离开写 leave；长时间收不到活跃媒体（心跳超时）写 timeout。
                    boolean timedOut = lastHeartbeatMs > 0 && (now - lastHeartbeatMs) >= sessionTimeoutMs;
                    emitSessionEvent(ctx, timedOut ? EVENT_TIMEOUT : EVENT_LEAVE, lastSnapshot);
                    wasWatching = false;
                    lastHeartbeatMs = 0L;
                    CompanionFloatingWidget.hide(ctx);
                    DebugState.append(ctx, timedOut ? "伴看：会话超时，写 timeout" : "伴看：离开媒体App，本次伴看结束");
                } else if (!playing) {
                    CompanionFloatingWidget.hide(ctx);
                }
            }
            lastSnapshot = m;
        } catch (Exception e) {
            DebugState.append(this, "伴看轮询异常：" + ScreenshotService.shortMsg(e));
        }
        if (running) pollHandler.postDelayed(this::pollTick, POLL_MS);
    }

    /** BE-12：会话事件同时写本地 activity_events（source=media）并上报服务端，供 last_events 读取。 */
    private void emitSessionEvent(Context ctx, String canon, JSONObject media) {
        try {
            ActivityEventStore.add(ctx, new JSONObject()
                    .put("source", "media").put("type", "media_watch")
                    .put("action", "media_watch_" + canon)
                    .put("title", "伴看" + (EVENT_ENTER.equals(canon) ? "开始" : EVENT_LEAVE.equals(canon) ? "离开" : "超时"))
                    .put("subtitle", media.optString("app", "") + " · " + media.optString("title", ""))
                    .put("app_name", media.optString("app", ""))
                    .put("package_name", media.optString("package", ""))
                    .put("status", "completed"), false);
        } catch (Exception ignored) { }
        uploadEvent(ctx, canon, media);
    }

    /** 节流上报当前媒体上下文（media_state）。 */
    private void uploadState(Context ctx, JSONObject media) {
        try {
            JSONObject body = new JSONObject();
            body.put("device_id", AppPrefs.device(ctx));
            body.put("media_state", media);
            body.put("session_timeout_seconds", AppPrefs.mediaSessionTimeoutSec(ctx)); // BE-12 可配超时
            postJson(ctx, "/api/media/sync", body);
        } catch (Exception e) {
            DebugState.append(ctx, "伴看状态上报失败：" + ScreenshotService.shortMsg(e));
        }
    }

    /** 上报伴看事件（enter / leave / timeout / media_watch_limit_exceeded）。 */
    private void uploadEvent(Context ctx, String event, JSONObject media) {
        try {
            JSONObject body = new JSONObject();
            body.put("device_id", AppPrefs.device(ctx));
            body.put("event", event);
            body.put("media_state", media);
            body.put("session_timeout_seconds", AppPrefs.mediaSessionTimeoutSec(ctx)); // BE-12 可配超时
            postJson(ctx, "/api/media/sync", body);
        } catch (Exception e) {
            DebugState.append(ctx, "伴看事件上报失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private void postJson(Context ctx, String path, JSONObject obj) throws Exception {
        String server = ScreenshotService.normalizeUrl(AppPrefs.server(ctx));
        String token = AppPrefs.token(ctx);
        if (server == null || server.isEmpty() || token == null || token.isEmpty()) return;
        HttpURLConnection conn = (HttpURLConnection) new URL(server + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("X-Auth-Token", token);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) { os.write(data); }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) { byte[] buf = new byte[1024]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n); }
        conn.disconnect();
        if (code >= 400) DebugState.append(ctx, "伴看上报 HTTP " + code + " " + bos.toString(StandardCharsets.UTF_8.name()));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "掌心窗伴看守护", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("同屏流媒体/短视频伴看守护常驻");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle("掌心窗伴看守护")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        running = false;
        wasWatching = false;
        try { CompanionFloatingWidget.hide(this); } catch (Exception ignored) { }
        if (pollThread != null) pollThread.quitSafely();
        DebugState.append(this, "伴看守护(MediaSync)已停止");
        super.onDestroy();
    }
}
