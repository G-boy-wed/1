package dev.linjian.peek;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/**
 * 掌心窗 全站 UI 美化规范 v2026-11-14-03 · THEME-02 主题预设。
 *
 * 六套预设：经典白 / 雾灰 / 暖米 / 暮夜蓝紫 / 云海青灰 / 落日莓雾，
 * 每套都有「亮 / 暗」两版，切换只写 SharedPreferences 再走 applyVisualTheme()，不重启 Activity。
 *
 * 兼容约定：保留旧版全部 public 字段与方法签名（bgTop/bgBottom/card/cardSoft/
 * primary/primarySoft/accent/text/subtext/line/danger/dark、background/card/soft/pill/
 * chip/hero/navBar/navIconIsland/navItem/windowPanel/dp），旧页面无需改动即可编译；
 * 旧主题名经 normalize() 映射到新预设，老用户升级不会掉回默认主题或崩溃。
 */
public class UITheme {
    public final String name;
    public final int bgTop, bgBottom, card, cardSoft, primary, primarySoft, accent, text, subtext, line, danger;
    public final boolean dark;

    private UITheme(String name, int bgTop, int bgBottom, int card, int cardSoft, int primary,
                    int primarySoft, int accent, int text, int subtext, int line, int danger, boolean dark) {
        this.name = name; this.bgTop = bgTop; this.bgBottom = bgBottom; this.card = card;
        this.cardSoft = cardSoft; this.primary = primary; this.primarySoft = primarySoft;
        this.accent = accent; this.text = text; this.subtext = subtext;
        this.line = line; this.danger = danger; this.dark = dark;
    }

    // ---- THEME-02：{page, card, accent} 三元组 ----
    private static final String[] NAMES = {"经典白", "雾灰", "暖米", "暮夜蓝紫", "云海青灰", "落日莓雾"};

    private static final int[][] LIGHT = {
            {0xFFF4F4F4, 0xFFFFFFFF, 0xFF4A4A4A}, // 经典白
            {0xFFEDEDED, 0xFFFAFAFA, 0xFF555555}, // 雾灰
            {0xFFF6F2EC, 0xFFFFFDF9, 0xFF5A5248}, // 暖米
            {0xFFE9E8F2, 0xFFFFFFFF, 0xFF5F5C93}, // 暮夜蓝紫·亮（浅紫灰版）
            {0xFFE8EDEE, 0xFFFBFDFD, 0xFF4A5A5E}, // 云海青灰
            {0xFFF4EAEA, 0xFFFFF9F9, 0xFF6B4E52}, // 落日莓雾
    };

    private static final int[][] DARK = {
            {0xFF1B1B1B, 0xFF262626, 0xFFE8E8E8}, // 经典白·暗
            {0xFF191919, 0xFF232323, 0xFFE0E0E0}, // 雾灰·暗
            {0xFF1E1B17, 0xFF2A2621, 0xFFE8DDD1}, // 暖米·暗
            {0xFF2A2C38, 0xFF343748, 0xFFD9D6E8}, // 暮夜蓝紫·暗 = 规范原值
            {0xFF171E20, 0xFF222C2E, 0xFFD6E2E4}, // 云海青灰·暗
            {0xFF201718, 0xFF2C2224, 0xFFECD9DA}, // 落日莓雾·暗
    };

    /** 设置页 / MCP 取主题名，避免多处维护。 */
    public static String[] themeNames() { return NAMES.clone(); }

    private static int indexOf(String base) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(base)) return i;
        return 0;
    }

    /** 旧主题名 / 别名 / 亮暗后缀统一归一到六套预设之一。 */
    public static String normalize(String raw) {
        if (raw == null) return NAMES[0];
        String n = raw.trim()
                .replace("·暗", "").replace("·亮", "")
                .replace("-dark", "").replace("-light", "").trim();
        if (n.isEmpty()) return NAMES[0];
        for (String s : NAMES) if (s.equals(n)) return s;
        // 旧版彩色主题按色相就近迁移，保证升级后不回到默认且不崩溃
        if ("白桃粉".equals(n) || "粉色".equals(n) || "pink".equalsIgnoreCase(n)) return "落日莓雾";
        if ("奶油绿".equals(n) || "奶油".equals(n)) return "暖米";
        if ("薄荷透明".equals(n) || "薄荷".equals(n)) return "云海青灰";
        if ("雾蓝白".equals(n) || "星云紫".equals(n)) return "云海青灰";
        if ("夜航黑".equals(n) || "黑色".equals(n)) return "暮夜蓝紫";
        if ("白色".equals(n)) return "经典白";
        return NAMES[0];
    }

    public static String currentBaseName(Context ctx) {
        return normalize(AppPrefs.get(ctx).getString(AppPrefs.KEY_THEME, NAMES[0]));
    }

    public static boolean isDarkMode(Context ctx) {
        return AppPrefs.get(ctx).getBoolean(AppPrefs.KEY_THEME_DARK, false);
    }

    /** 只写偏好，不重建 Activity；调用方随后 applyVisualTheme() 即完成不重启换肤。 */
    public static void setDarkMode(Context ctx, boolean dark) {
        AppPrefs.get(ctx).edit().putBoolean(AppPrefs.KEY_THEME_DARK, dark).apply();
    }

    public static boolean toggleDarkMode(Context ctx) {
        boolean next = !isDarkMode(ctx);
        setDarkMode(ctx, next);
        return next;
    }

    public static void setTheme(Context ctx, String baseName) {
        String b = normalize(baseName);
        AppPrefs.get(ctx).edit().putString(AppPrefs.KEY_THEME, b).apply();
        // 暮夜蓝紫本身是暗色预设，切到它时默认给暗版；切回浅色预设恢复亮版
        setDarkMode(ctx, "暮夜蓝紫".equals(b));
    }

    public static UITheme current(Context ctx) {
        return fromSpec(currentBaseName(ctx), isDarkMode(ctx));
    }

    /** 兼容旧调用：支持旧名、新名与「·亮 / ·暗」后缀。 */
    public static UITheme byName(String n) {
        String raw = n == null ? "" : n;
        boolean dark = raw.contains("·暗") || "夜航黑".equals(raw) || "暮夜蓝紫".equals(raw);
        return fromSpec(normalize(raw), dark);
    }

    private static UITheme fromSpec(String base, boolean dark) {
        String b = normalize(base);
        int[] c = dark ? DARK[indexOf(b)] : LIGHT[indexOf(b)];
        int page = c[0], cardColor = c[1], accentColor = c[2];
        int bgTop = page;
        int bgBottom = UiTokens.blend(page, cardColor, 0.45f);
        int cardSoft = UiTokens.blend(cardColor, page, 0.10f);
        int primarySoft = UiTokens.blend(cardColor, accentColor, dark ? 0.20f : 0.10f);
        int textColor = dark ? 0xFFF2F2F2 : UiTokens.blend(UiTokens.TEXT_MAIN, accentColor, 0.10f);
        int subtextColor = dark ? 0xFFA8A8A8 : UiTokens.TEXT_SUB;
        int lineColor = dark ? UiTokens.blend(cardColor, 0xFFFFFFFF, 0.12f)
                             : UiTokens.blend(cardColor, 0xFF000000, 0.07f);
        return new UITheme(b + (dark ? "·暗" : "·亮"),
                bgTop, bgBottom, cardColor, cardSoft, accentColor, primarySoft, accentColor,
                textColor, subtextColor, lineColor, UiTokens.DANGER, dark);
    }

    // ---- Drawable 工厂：全部改用 THEME-01 令牌，去掉彩色 ----

    public GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bgTop, bgBottom});
    }

    public GradientDrawable card(float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke((int) dp(strokeDp), line);
        return g;
    }

    public GradientDrawable soft(float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cardSoft);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke((int) dp(0.6f), line);
        return g;
    }

    /** 主按钮深色胶囊：selected=true 用 accent 深底白字；未选用白/浅灰底。 */
    public GradientDrawable pill(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(selected ? primary : (dark ? cardSoft : Color.argb(235, 255, 255, 255)));
        g.setCornerRadius(dp(UiTokens.RADIUS_ROW));
        g.setStroke((int) dp(0.6f), selected ? primary : line);
        return g;
    }

    /** COMPONENT chip：选中白底深字，未选 #EFEFEF 灰字。 */
    public GradientDrawable chip(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        if (selected) {
            g.setColor(dark ? cardSoft : Color.WHITE);
            g.setStroke((int) dp(1f), primary);
        } else {
            g.setColor(dark ? UiTokens.blend(cardSoft, 0xFFFFFFFF, 0.06f) : 0xFFEFEFEF);
            g.setStroke((int) dp(0.6f), line);
        }
        g.setCornerRadius(dp(UiTokens.CHIP_H / 2f));
        return g;
    }

    /** 首页 hero：浅灰白渐变 + 大圆角 + 白色描边，不再用粉色渐变。 */
    public GradientDrawable hero() {
        int start = dark ? UiTokens.blend(card, primary, 0.10f) : Color.WHITE;
        int end = dark ? cardSoft : UiTokens.blend(Color.WHITE, cardSoft, 0.72f);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        g.setCornerRadius(dp(UiTokens.RADIUS_CARD));
        g.setStroke((int) dp(UiTokens.STROKE_DP), dark ? line : UiTokens.glass(0.8f));
        return g;
    }

    /** NAV-01 底部悬浮 Dock：64dp 毛玻璃胶囊，白色 60% / 1dp 白色描边。 */
    public GradientDrawable navBar() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(dark ? Color.argb(214, 42, 42, 46) : UiTokens.GLASS_CARD);
        g.setCornerRadius(dp(UiTokens.RADIUS_DOCK));
        g.setStroke((int) dp(UiTokens.STROKE_DP),
                dark ? Color.argb(70, 255, 255, 255) : UiTokens.STROKE);
        return g;
    }

    public GradientDrawable navIconIsland() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(dark ? UiTokens.blend(cardSoft, primary, 0.14f)
                        : UiTokens.blend(Color.WHITE, cardSoft, 0.70f));
        g.setStroke((int) dp(0.6f), line);
        return g;
    }

    public GradientDrawable navItem(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        int selectedColor = dark ? UiTokens.blend(cardSoft, primary, 0.16f)
                                 : UiTokens.blend(Color.WHITE, cardSoft, 0.60f);
        g.setColor(selected ? selectedColor : Color.TRANSPARENT);
        g.setCornerRadius(dp(UiTokens.RADIUS_SM));
        return g;
    }

    /** 保留旧壁纸窗口面板入口，改为中性灰渐变。 */
    public GradientDrawable windowPanel(boolean left) {
        int edge = dark ? UiTokens.blend(bgTop, primary, 0.12f) : UiTokens.blend(bgTop, cardSoft, 0.58f);
        int center = dark ? UiTokens.blend(card, primary, 0.10f) : UiTokens.blend(Color.WHITE, cardSoft, 0.76f);
        GradientDrawable.Orientation direction = left
                ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT;
        GradientDrawable g = new GradientDrawable(direction, new int[]{edge, center});
        g.setStroke((int) dp(0.5f), line);
        return g;
    }

    public static float dp(float v) { return UiTokens.dp(v); }
}
