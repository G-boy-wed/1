package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 掌心窗功能角标的「开关」状态（单一事实来源）。
 *
 * 每个角标都有独立开/关，默认开启。关闭后：角标图标置灰，点击功能时提示已关闭，
 * 且机端（MCP / 服务端命令）与手机端看到的开关状态一致。
 * 只存用户显式改过的项，未改过的项默认 true，避免旧数据缺失导致功能全关。
 */
public final class DeskFeatureToggleStore {
    private static final String PREF = "desk_feature_toggles_v1";
    private static final String K_STATES = "states"; // {id: bool}

    private DeskFeatureToggleStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static JSONObject obj(Context c) {
        try { return new JSONObject(sp(c).getString(K_STATES, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** 默认开启；只返回显式关闭的项为 false。 */
    public static boolean isEnabled(Context c, String id) {
        if (id == null) return true;
        return obj(c).optBoolean(id, true);
    }

    public static void setEnabled(Context c, String id, boolean enabled) {
        if (id == null) return;
        JSONObject o = obj(c);
        try { o.put(id, enabled); } catch (Exception ignored) { }
        sp(c).edit().putString(K_STATES, o.toString()).apply();
    }

    public static void toggle(Context c, String id) {
        setEnabled(c, id, !isEnabled(c, id));
    }

    /** 恢复全部为开启。 */
    public static void resetAll(Context c) {
        sp(c).edit().remove(K_STATES).apply();
    }

    /** 导出快照，供状态上报镜像到服务端，MCP 可读取机端开关状态。 */
    public static JSONObject snapshot(Context c) {
        JSONObject o = obj(c);
        JSONObject out = new JSONObject();
        try {
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.optBoolean(k, true));
            }
        } catch (Exception ignored) { }
        return out;
    }
}
