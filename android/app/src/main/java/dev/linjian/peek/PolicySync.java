package dev.linjian.peek;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 策略同步：把「掌心窗内最后写入的管理配置」best-effort 推给服务器，
 * 让 MCP / 陪伴对象侧读到与手机端一致的管理要求。
 *
 * 推送内容（两段，各自独立成功/失败，互不影响）：
 *  1. /api/tour/schedule：巡回查岗配置（开关、间隔、冷却、安静/深夜时段、强拉阈值）
 *  2. /api/discipline  ：训诫配置（开关、模式、档位）
 *
 * 设计要点：
 *  - 只推本机真实存在的配置，不硬编码假值（缺省键服务端保留原值，last-write-wins）；
 *  - best-effort：无服务器地址/网络失败时静默跳过，本机始终生效；
 *  - 保存设置时调用一次；陪伴服务启动时调用一次，运行期每 15 分钟对齐一次，
 *    保证哪怕保存那一刻没网，最终也会同步到 MCP 读取的同一份状态。
 */
public final class PolicySync {

    /** 距上次成功推送的最小间隔（运行期对齐频率）。 */
    private static final long MIN_INTERVAL_MS = 15 * 60_000L;
    private static volatile long lastPushAt = 0;

    private PolicySync() { }

    /** 推送全部管理配置（巡回 + 训诫）。force=true 忽略最小间隔（保存动作用）。 */
    public static void pushAll(Context ctx, boolean force) {
        try {
            long now = System.currentTimeMillis();
            if (!force && now - lastPushAt < MIN_INTERVAL_MS) return;
            lastPushAt = now;
            final Context app = ctx.getApplicationContext();
            final String base = AppPrefs.server(app), token = AppPrefs.token(app);
            if (base == null || base.trim().isEmpty()) return; // 未配置服务器：本机生效即可

            new Thread(() -> {
                try {
                    JSONObject tour = new JSONObject();
                    tour.put("enabled", PolicyStore.checkinEnabled(app));
                    tour.put("check_in_interval_minutes", PolicyStore.randomUpper(app));
                    tour.put("cooldown_minutes", PolicyStore.cooldownMinutes(app));
                    // BE-05：废弃 quiet_hours（{start,end} 冗余对象），只认 quiet_start/quiet_end。
                    tour.put("quiet_start", PolicyStore.quietStart(app));
                    tour.put("quiet_end", PolicyStore.quietEnd(app));
                    JSONObject n = new JSONObject();
                    n.put("start", PolicyStore.nightStart(app)); n.put("end", PolicyStore.nightEnd(app));
                    tour.put("night_hours", n);
                    tour.put("force_back_miss_threshold", PolicyStore.forceBackThreshold(app));
                    request(base, token, "POST", "/api/tour/schedule", tour.toString());
                } catch (Exception ignored) { }
                try {
                    JSONObject dis = new JSONObject();
                    dis.put("enabled", PolicyStore.disciplineEnabled(app));
                    dis.put("mode", PolicyStore.disciplineMode(app));
                    dis.put("level", PolicyStore.disciplineLevel(app));
                    request(base, token, "POST", "/api/discipline", dis.toString());
                } catch (Exception ignored) { }
            }, "policy-sync").start();
        } catch (Exception ignored) { }
    }

    /** 便捷重载：默认立即推送（保存动作）。 */
    public static void pushAll(Context ctx) { pushAll(ctx, true); }

    private static String request(String base, String token, String method, String path, String body) {
        HttpURLConnection conn = null;
        try {
            String urlStr = base.replaceAll("/+$", "") + path;
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            if (token != null && !token.trim().isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (body != null && (method.equals("POST") || method.equals("PUT"))) {
                conn.setDoOutput(true);
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(out.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(out); }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (is != null) { byte[] buf = new byte[1024]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n); }
            return "HTTP " + code + " " + bos.toString("UTF-8");
        } catch (Exception e) {
            return "ERROR " + ScreenshotService.shortMsg(e);
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) { }
        }
    }
}
