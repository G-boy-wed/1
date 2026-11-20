package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 双人共同观看 / 吐槽 —— 手机端房间状态（单一事实来源的本地镜像）。
 *
 * 与服务端 watchparty_state.json 双向同步：
 *  - 服务端 MCP（watch_party_*）写入后经 /api/poll 下发命令，手机端 handleCommand 落地本类；
 *  - 手机端发起的吐槽/表情/进度同步写入本类，并随状态上报镜像给服务端，MCP 可读。
 *
 * 支持：发起/结束房间、进度与播放状态、双方位置/头像/时间、弹幕列表、表情互动。
 */
public final class WatchPartyState {

    private static final String PREFS = "watch_party_store";
    private static final String KEY_ROOM = "room_v1";
    private static final int MAX_ITEMS = 200;

    private WatchPartyState() { }

    private static SharedPreferences sp(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String nowTime() { return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()); }

    private static JSONObject defaultRoom() {
        JSONObject o = new JSONObject();
        try {
            o.put("active", false);
            o.put("media", new JSONObject());
            o.put("progress_ms", 0);
            o.put("duration_ms", 0);
            o.put("playing", false);
            o.put("member", new JSONObject());
            o.put("partner", new JSONObject());
            o.put("danmaku", new JSONArray());
            o.put("reactions", new JSONArray());
            o.put("round", 0);
            o.put("updated_at_ms", 0);
        } catch (Exception ignored) { }
        return o;
    }

    public static JSONObject room(Context ctx) {
        try {
            JSONObject o = new JSONObject(sp(ctx).getString(KEY_ROOM, ""));
            return o;
        } catch (Exception e) { return defaultRoom(); }
    }

    private static void save(Context ctx, JSONObject o) {
        try { sp(ctx).edit().putString(KEY_ROOM, o.toString()).commit(); } catch (Exception ignored) { }
    }

    public static boolean active(Context ctx) { return room(ctx).optBoolean("active", false); }

    /** 发起/更新房间（来自服务端命令或本机）。 */
    public static JSONObject start(Context ctx, JSONObject payload) {
        JSONObject o = defaultRoom();
        try {
            JSONObject cur = room(ctx);
            o = new JSONObject(cur.toString());
            if (payload != null) {
                java.util.Iterator<String> it = payload.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if ("action".equals(k) || "device_id".equals(k)) continue;
                    o.put(k, payload.opt(k));
                }
            }
            o.put("active", true);
            o.put("updated_at_ms", System.currentTimeMillis());
        } catch (Exception ignored) { }
        save(ctx, o);
        return o;
    }

    /** 同步进度/播放状态。 */
    public static JSONObject sync(Context ctx, long progressMs, boolean playing) {
        JSONObject o = room(ctx);
        try {
            o.put("progress_ms", progressMs);
            o.put("playing", playing);
            o.put("updated_at_ms", System.currentTimeMillis());
            o.put("round", o.optInt("round", 0) + 1);
        } catch (Exception ignored) { }
        save(ctx, o);
        return o;
    }

    /** 结束房间。 */
    public static JSONObject end(Context ctx) {
        JSONObject o = room(ctx);
        try { o.put("active", false); o.put("playing", false); o.put("updated_at_ms", System.currentTimeMillis()); }
        catch (Exception ignored) { }
        save(ctx, o);
        return o;
    }

    /** 追加一条弹幕/吐槽。 */
    public static JSONObject addDanmaku(Context ctx, String text, String author) {
        JSONObject o = room(ctx);
        try {
            JSONArray arr = o.optJSONArray("danmaku");
            if (arr == null) arr = new JSONArray();
            JSONObject e = new JSONObject();
            e.put("text", text);
            e.put("author", author == null || author.isEmpty() ? AppPrefs.userName(ctx) : author);
            e.put("at_ms", System.currentTimeMillis());
            e.put("at", nowTime());
            arr.put(e);
            while (arr.length() > MAX_ITEMS) arr.remove(0);
            o.put("danmaku", arr);
            o.put("round", o.optInt("round", 0) + 1);
            o.put("updated_at_ms", System.currentTimeMillis());
        } catch (Exception ignored) { }
        save(ctx, o);
        return o;
    }

    /** 追加一个表情互动。 */
    public static JSONObject addReaction(Context ctx, String emoji, String author) {
        JSONObject o = room(ctx);
        try {
            JSONArray arr = o.optJSONArray("reactions");
            if (arr == null) arr = new JSONArray();
            JSONObject e = new JSONObject();
            e.put("emoji", emoji);
            e.put("author", author == null || author.isEmpty() ? AppPrefs.userName(ctx) : author);
            e.put("at_ms", System.currentTimeMillis());
            arr.put(e);
            while (arr.length() > MAX_ITEMS) arr.remove(0);
            o.put("reactions", arr);
            o.put("round", o.optInt("round", 0) + 1);
            o.put("updated_at_ms", System.currentTimeMillis());
        } catch (Exception ignored) { }
        save(ctx, o);
        return o;
    }

    /**
     * 处理服务端下发的 watch_party_* 命令，返回是否已处理。
     */
    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        String action = cmd.optString("action", "");
        try {
            if ("watch_party_start".equals(action)) {
                return ok(start(ctx, cmd));
            } else if ("watch_party_sync".equals(action)) {
                JSONObject o = sync(ctx, cmd.optLong("progress_ms", 0), cmd.optBoolean("playing", false));
                return ok(o);
            } else if ("watch_party_danmaku".equals(action)) {
                String text = cmd.optString("text", cmd.optString("message", ""));
                if (text.isEmpty()) return err("empty_text");
                return ok(addDanmaku(ctx, text, cmd.optString("author", "")));
            } else if ("watch_party_reaction".equals(action)) {
                String emoji = cmd.optString("emoji", "❤️");
                return ok(addReaction(ctx, emoji, cmd.optString("author", "")));
            } else if ("watch_party_end".equals(action)) {
                return ok(end(ctx));
            }
        } catch (Exception e) {
            return err("watch_party_command_error: " + ScreenshotService.shortMsg(e));
        }
        return null; // 非本插件命令
    }

    /** 供状态上报镜像到服务端：把房间状态塞进 life_state。 */
    public static JSONObject snapshot(Context ctx) { return room(ctx); }

    private static JSONObject ok(JSONObject o) {
        try { return new JSONObject().put("ok", true).put("result", "watch_party_applied").put("room", o); }
        catch (Exception e) { return new JSONObject(); }
    }
    private static JSONObject err(String m) {
        try { return new JSONObject().put("ok", false).put("error", m); }
        catch (Exception e) { return new JSONObject(); }
    }
}
