package dev.linjian.peek;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONObject;

import java.nio.ByteBuffer;

/**
 * 实时识图与视窗理解服务（对应 PRD 模块六）。
 *
 *  - 本地结构化提取：复用 OcrPlugin，从无障碍读屏节点树拿到当前界面关键文字/可点击标签/时间（始终可用）；
 *  - 真实位图截屏：若已通过 MediaProjection 拿到 ImageReader（由前台 Activity 授权后注入），
 *    可捕获当前屏幕位图；否则安全降级为节点提取；
 *  - 服务端视觉增强：把本地结果（含位图可用性）发给 /api/ocr/analyze 做语义推理，best-effort。
 *
 * 与 OcrPlugin 的关系：OcrPlugin 是「纯本地节点提取」实现；本服务在其之上叠加「位图截屏 + 服务端视觉」，
 * 作为「看看这里 / 帮我看屏幕」类诉求的统一入口。
 */
public final class ScreenOcrService {

    private ScreenOcrService() { }

    /** MediaProjection 截屏通道：由前台 Activity 取得 MediaProjection 后注册一个 ImageReader 到这里。无则位图截屏降级。 */
    private static volatile android.media.ImageReader sImageReader;
    private static volatile Object sProjection; // android.media.projection.MediaProjection（避免硬依赖，运行时反射/弱引用均可）

    public static void setImageReader(android.media.ImageReader reader) { sImageReader = reader; }
    public static void setProjection(Object projection) { sProjection = projection; }

    /**
     * 真实位图截屏：若 ImageReader 已就绪则取最新一帧并转 Bitmap；否则返回 null（调用方降级到节点提取）。
     * 全程 try/catch，绝不抛异常（截屏通道未建立属正常降级）。
     */
    public static Bitmap captureBitmap() {
        android.media.ImageReader r = sImageReader;
        if (r == null) return null;
        android.media.Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) return null;
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return null;
            ByteBuffer buf = planes[0].getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            if (buf == null || width <= 0 || height <= 0) return null;
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes));
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            if (image != null) try { image.close(); } catch (Exception ignored) { }
        }
    }

    /** 本地提取：优先无障碍节点结构化结果，并标注位图截屏是否可用。 */
    public static JSONObject extractLocal(Context ctx) {
        JSONObject local = OcrPlugin.extractLocal(ctx);
        boolean hasBitmap = captureBitmap() != null;
        try { local.put("bitmap_capture_available", hasBitmap); } catch (Exception ignored) { }
        return local;
    }

    /** 可选远程增强：本地结果 + 位图可用性发给服务端视觉模型，best-effort。 */
    public static JSONObject analyze(Context ctx, String serverUrl, String token) {
        JSONObject local = extractLocal(ctx);
        if (serverUrl == null || serverUrl.trim().isEmpty() || token == null || token.trim().isEmpty()) {
            try { local.put("remote", "skipped_no_server"); } catch (Exception ignored) { }
            return local;
        }
        try {
            String resp = CompanionService.postJsonSafe(serverUrl.replaceAll("/+$", "") + "/api/ocr/analyze", token, local);
            if (resp != null && !resp.isEmpty() && !resp.startsWith("ERROR")) {
                try {
                    JSONObject remote = new JSONObject(resp);
                    local.put("remote", remote);
                    local.put("remote_used", true);
                } catch (Exception ignored) { local.put("remote", "parse_failed"); }
            } else {
                local.put("remote", "unavailable");
            }
        } catch (Exception e) {
            try { local.put("remote", "error:" + ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return local;
    }

    /** 命令路由：screen_ocr。 */
    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        try {
            boolean remote = cmd.optBoolean("remote", false);
            String server = AppPrefs.server(ctx), token = AppPrefs.token(ctx);
            JSONObject res = remote ? analyze(ctx, server, token) : extractLocal(ctx);
            out.put("ok", res.optBoolean("ok", false));
            out.put("result", "ocr_extract");
            out.put("data", res);
        } catch (Exception e) {
            try { out.put("ok", false).put("error", ScreenshotService.shortMsg(e)); } catch (Exception ignored) { }
        }
        return out;
    }
}
