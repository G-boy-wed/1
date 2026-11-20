package dev.linjian.peek;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

/**
 * 三页横滑美化方案的「皮肤」持久化（单一事实来源）。
 *
 * 负责三类用户自定义数据，全部落在 SharedPreferences：
 *  1. 贴图槽位 URI：封面 / 头像 / About me / 照片墙 / 好友头像 / 动态头像 等，
 *     由相册 ACTION_OPEN_DOCUMENT 选取，takePersistableUriPermission 长期授权。
 *  2. 每页自定义背景：图片 URI 或纯色（三页各自独立）。
 *  3. 可编辑文案：名字 / @账号 / 简介 / 地区 / 动态标题。
 *
 * 所有方法对空值/损坏数据做兜底，绝不抛异常导致桌面崩溃。
 */
public final class DeskSkinStore {
    private static final String PREF = "desk_skin_v1";
    private static final String K_SLOTS = "slots";       // {"cover":"content://...", ...}
    private static final String K_BG_URI = "bg_uri";     // ["", "", ""]
    private static final String K_BG_COLOR = "bg_color"; // [0,0,0] 0 表示用默认渐变
    private static final String K_TEXT = "text";         // {"name":"...", ...}

    // ---- 贴图槽位常量 ----
    public static final String S_COVER = "cover";
    public static final String S_AVATAR = "avatar";
    public static final String S_ABOUT = "about";
    public static final String S_PHOTO0 = "photo0";
    public static final String S_PHOTO1 = "photo1";
    public static final String S_PHOTO2 = "photo2";
    public static final String S_FRIEND0 = "friend0";
    public static final String S_FRIEND1 = "friend1";
    public static final String S_FRIEND2 = "friend2";
    public static final String S_FRIEND3 = "friend3";
    public static final String S_FEEDBG = "feedbg";
    public static final String S_FEEDAVATAR = "feedavatar";
    // 第二页「可增删贴图」动态槽：默认三张 photo0/1/2，用户可继续添加到 dyn0..dyn5
    public static final String S_DYN0 = "dyn0";
    public static final String S_DYN1 = "dyn1";
    public static final String S_DYN2 = "dyn2";
    public static final String S_DYN3 = "dyn3";
    public static final String S_DYN4 = "dyn4";
    public static final String S_DYN5 = "dyn5";

    /** 选图请求码 = REQ_PICK_BASE + 本数组下标；onActivityResult 据此还原槽位。 */
    public static final String[] PICK_SLOTS = {
            S_COVER, S_AVATAR, S_ABOUT,
            S_PHOTO0, S_PHOTO1, S_PHOTO2,
            S_FRIEND0, S_FRIEND1, S_FRIEND2, S_FRIEND3,
            S_FEEDBG, S_FEEDAVATAR,
            S_DYN0, S_DYN1, S_DYN2, S_DYN3, S_DYN4, S_DYN5
    };

    /** 第二页相册贴图的全部可用槽（含默认三张 + 动态增补）。 */
    public static final String[] ALBUM_SLOTS = {
            S_PHOTO0, S_PHOTO1, S_PHOTO2,
            S_DYN0, S_DYN1, S_DYN2, S_DYN3, S_DYN4, S_DYN5
    };

    /** 找到第一个还没有图片的相册槽，用于"添加贴图"；-full 返回 null。 */
    public static String firstEmptyAlbumSlot(Context c) {
        for (String s : ALBUM_SLOTS) if (slotUri(c, s).isEmpty()) return s;
        return null;
    }
    public static final int REQ_PICK_BASE = 3000;
    public static final int REQ_BG_BASE = 4000; // +page(0..2) 更换整页背景

    private DeskSkinStore() { }

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

    /** 读取某个贴图槽位的 URI 字符串；无则返回空串。 */
    public static String slotUri(Context c, String slot) {
        return obj(c, K_SLOTS).optString(slot, "");
    }

    public static void setSlotUri(Context c, String slot, String uri) {
        JSONObject o = obj(c, K_SLOTS);
        try { o.put(slot, uri == null ? "" : uri); } catch (Exception ignored) { }
        saveObj(c, K_SLOTS, o);
    }

    /** 某页背景图片 URI（空串表示使用默认渐变/星点）。 */
    public static String pageBgUri(Context c, int page) {
        try { return obj(c, K_BG_URI).optString(String.valueOf(page), ""); }
        catch (Exception e) { return ""; }
    }

    public static void setPageBgUri(Context c, int page, String uri) {
        JSONObject o = obj(c, K_BG_URI);
        try { o.put(String.valueOf(page), uri == null ? "" : uri); } catch (Exception ignored) { }
        saveObj(c, K_BG_URI, o);
    }

    /** 某页背景纯色；0 表示未设置（用默认渐变或背景图）。 */
    public static int pageBgColor(Context c, int page) {
        return obj(c, K_BG_COLOR).optInt(String.valueOf(page), 0);
    }

    public static void setPageBgColor(Context c, int page, int color) {
        JSONObject o = obj(c, K_BG_COLOR);
        try { o.put(String.valueOf(page), color); } catch (Exception ignored) { }
        saveObj(c, K_BG_COLOR, o);
    }

    /** 可编辑文案。 */
    public static String text(Context c, String key, String def) {
        String v = obj(c, K_TEXT).optString(key, "");
        return v.isEmpty() ? def : v;
    }

    public static void setText(Context c, String key, String value) {
        JSONObject o = obj(c, K_TEXT);
        try { o.put(key, value == null ? "" : value); } catch (Exception ignored) { }
        saveObj(c, K_TEXT, o);
    }

    /** 从相册选图结果里取得 URI 并申请长期读取权限；失败返回 null。 */
    public static Uri persistPick(Context c, int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return null;
        Uri uri = data.getData();
        try {
            c.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        return uri;
    }
}
