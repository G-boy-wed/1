package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 主界面「陪伴展示卡」的数据来源（贴图形式直接展现，不是可点开的角标）。
 *
 * 五张卡：窗边(最近窗语) / 此刻状态 / 今日轨迹 / 陪伴倒计日 / 陪伴者行动。
 * 全部读取掌心窗已有的真实状态，不造数据；无数据时给温和占位文案。
 */
public final class DeskCompanionCards {
    // 卡片 id（也是 DeskLayoutStore 的 id，KIND_CARD）
    public static final String C_WINDOW   = "card_window";    // 窗边 / 最近窗语
    public static final String C_NOW      = "card_now";       // 此刻状态
    public static final String C_JOURNEY  = "card_journey";   // 今日轨迹
    public static final String C_COUNTDOWN= "card_countdown"; // 陪伴倒计日
    public static final String C_ACTION   = "card_action";    // 陪伴者行动

    public static final String[] IDS = {C_WINDOW, C_NOW, C_JOURNEY, C_COUNTDOWN, C_ACTION};
    public static final String[] TITLES = {"窗边 · 最近窗语", "此刻状态", "今日轨迹", "陪伴倒计日", "陪伴者行动"};

    private DeskCompanionCards() { }

    /** 卡片默认所在页：窗边在个人页，其余在动态页，便于一进主界面就看到。 */
    public static int defaultPage(String id) {
        if (C_WINDOW.equals(id)) return 0;
        return 2;
    }

    /** 卡片标题行（小标题）。 */
    public static String title(Context c, String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return TITLES[i];
        return "";
    }

    /** 卡片正文（真实数据，最多两行）。 */
    public static String body(Context c, String id) {
        try {
            switch (id) {
                case C_WINDOW: {
                    JSONObject w = PublicCompanionState.whisper(c);
                    String txt = w.optString("content", "");
                    String who = w.optString("author", AppPrefs.companionName(c));
                    return (txt.isEmpty() ? "今天也慢慢来，我会陪着你。" : txt) + "\n— " + who;
                }
                case C_NOW: {
                    String note = DeskSkinStore.text(c, "feednote", "");
                    if (!note.trim().isEmpty()) return note;
                    return "此刻 " + weatherShort(c) + " · " + place(c);
                }
                case C_JOURNEY: {
                    JSONArray j = CompanionWindowState.journey(c);
                    if (j.length() == 0) return "今天的轨迹正在整理…";
                    StringBuilder sb = new StringBuilder();
                    int from = Math.max(0, j.length() - 3);
                    for (int i = j.length() - 1; i >= from; i--) {
                        JSONObject e = j.optJSONObject(i);
                        if (e == null) continue;
                        String tm = e.optString("time", "");
                        String tt = e.optString("title", "");
                        if (tt.isEmpty()) continue;
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(tm.isEmpty() ? "· " : tm + "  ").append(tt);
                    }
                    return sb.length() == 0 ? "今天的轨迹正在整理…" : sb.toString();
                }
                case C_COUNTDOWN: {
                    int days = PublicCompanionState.daysTogether(c);
                    if (days <= 0) return "设置相识日，开启我们的倒数";
                    return "和" + AppPrefs.companionName(c) + "一起 · 第 " + days + " 天";
                }
                case C_ACTION: {
                    JSONArray a = PublicCompanionState.actions(c, 3);
                    if (a.length() == 0) return AppPrefs.companionName(c) + "现在安安静静的，暂时没有新行动。";
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < a.length() && i < 2; i++) {
                        JSONObject e = a.optJSONObject(i);
                        if (e == null) continue;
                        String tt = e.optString("title", "一条行动");
                        String ago = CompanionWindowState.elapsed(e.optString("created_at", e.optString("at", "")));
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(ago.isEmpty() ? "· " : ago + "  ").append(tt);
                    }
                    return sb.length() == 0 ? (AppPrefs.companionName(c) + "暂时没有新行动。") : sb.toString();
                }
                default: return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String weatherShort(Context c) {
        try {
            JSONObject cur = WeatherState.currentLocation(c);
            String city = cur == null ? "" : cur.optString("city", "");
            String district = cur == null ? "" : cur.optString("district", "");
            JSONObject live = WeatherLive.cached(c, city, district);
            if (live != null && live.optBoolean("ok")) return live.optInt("temperature", 0) + "°";
        } catch (Exception ignored) { }
        return "--°";
    }

    private static String place(Context c) {
        try {
            JSONObject cur = WeatherState.currentLocation(c);
            if (cur != null) {
                String n = cur.optString("name", "");
                if (!n.isEmpty()) return n;
            }
        } catch (Exception ignored) { }
        return "当前位置";
    }
}
