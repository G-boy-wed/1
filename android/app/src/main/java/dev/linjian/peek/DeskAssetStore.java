package dev.linjian.peek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

/**
 * 角标「自定义上传图标」持久化（单一事实来源）。
 *
 * 每个功能角标除了 DeskFeatureStore 里的字符图标外，还可由用户从相册上传一张图片作为图标，
 * URI 存在这里（取长期读取权限）。有自定义图标时桌面优先显示图片，无则回退到字符图标。
 */
public final class DeskAssetStore {
    private static final String PREF = "desk_assets_v1";
    private static final String K_FEATURE_ICONS = "feature_icons"; // {"checkin":"content://...", ...}
    public static final int REQ_FEATURE_ICON_BASE = 5000; // + feature 下标

    private DeskAssetStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static JSONObject obj(Context c, String key) {
        try { return new JSONObject(sp(c).getString(key, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static void saveObj(Context c, String key, JSONObject o) {
        sp(c).edit().putString(key, o.toString()).apply();
    }

    /** 读取某角标的自定义图标 URI；无则返回空串。 */
    public static String featureIconUri(Context c, String featureId) {
        return obj(c, K_FEATURE_ICONS).optString(featureId, "");
    }

    public static void setFeatureIconUri(Context c, String featureId, String uri) {
        JSONObject o = obj(c, K_FEATURE_ICONS);
        try {
            if (uri == null || uri.isEmpty()) o.remove(featureId);
            else o.put(featureId, uri);
        } catch (Exception ignored) { }
        saveObj(c, K_FEATURE_ICONS, o);
    }

    public static void clearFeatureIconUri(Context c, String featureId) {
        setFeatureIconUri(c, featureId, "");
    }

    /** 从相册选图结果里取 URI 并申请长期读取权限；失败返回 null。 */
    public static Uri pickResult(Context c, int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return null;
        Uri uri = data.getData();
        try {
            c.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        return uri;
    }
}
