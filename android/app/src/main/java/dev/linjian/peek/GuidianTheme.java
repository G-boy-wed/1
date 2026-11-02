package dev.linjian.peek;

import android.graphics.Color;

/**
 * 归电主题：归电全屏页、屏幕居中弹窗、强制拉回弹窗共用这一份配色。
 * 主题名与位置都由 GuidianState 统一保存，改一处三处一起变，不维护第二套样式。
 */
public class GuidianTheme {
    public final String name;
    public final int background, panel, soft, primary, onPrimary, text, subtext, line, decor, wave, online, scrim, danger;
    public final float roseAlpha, butterflyAlpha;
    public final boolean dark;

    public GuidianTheme(String name, int background, int panel, int soft, int primary, int onPrimary, int text,
                        int subtext, int line, int decor, int wave, int online, int scrim, int danger,
                        float roseAlpha, float butterflyAlpha, boolean dark) {
        this.name = name;
        this.background = background; this.panel = panel; this.soft = soft;
        this.primary = primary; this.onPrimary = onPrimary; this.text = text; this.subtext = subtext;
        this.line = line; this.decor = decor; this.wave = wave; this.online = online;
        this.scrim = scrim; this.danger = danger;
        this.roseAlpha = roseAlpha; this.butterflyAlpha = butterflyAlpha; this.dark = dark;
    }

    /** 全部可选主题名；设置页与 MCP 都从这里取，避免两处维护。 */
    public static String[] themeNames() {
        return new String[]{"暮夜蓝紫", "云海青灰", "落日莓雾", "粉色", "白色", "黑色"};
    }

    /** 主题别名归一：旧名与新名都能落到同一套配色上。 */
    public static String normalize(String raw) {
        if (raw == null) return "暮夜蓝紫";
        String n = raw.trim();
        if (n.isEmpty()) return "暮夜蓝紫";
        if ("暮夜蓝紫".equals(n) || "蓝紫".equals(n) || "暮夜".equals(n) || "dusk".equalsIgnoreCase(n)) return "暮夜蓝紫";
        if ("云海青灰".equals(n) || "青灰".equals(n) || "云海".equals(n) || "cloud".equalsIgnoreCase(n)) return "云海青灰";
        if ("落日莓雾".equals(n) || "莓雾".equals(n) || "落日".equals(n) || "berry".equalsIgnoreCase(n)) return "落日莓雾";
        if ("白色".equals(n) || "白色".equals(n) || "white".equalsIgnoreCase(n)) return "白色";
        if ("黑色".equals(n) || "黑色".equals(n) || "black".equalsIgnoreCase(n)) return "黑色";
        if ("粉色".equals(n) || "粉色".equals(n) || "pink".equalsIgnoreCase(n)) return "粉色";
        return "暮夜蓝紫";
    }

    public static GuidianTheme from(String raw) {
        String n = normalize(raw);
        if ("暮夜蓝紫".equals(n)) {
            return new GuidianTheme(n, 0xFF17162A, 0xFF211F38, 0xFF282544, 0xFF8E8CF0, Color.WHITE,
                    0xFFF2F0FB, 0xFFA6A3C6, 0xFF34315A, 0xFF6F6BC9, 0xFF8E8CF0, 0xFF7FBF9B,
                    0xAA0B0A18, 0xFFE0637A, .26f, .12f, true);
        }
        if ("云海青灰".equals(n)) {
            return new GuidianTheme(n, 0xFFEAF1F3, 0xFFF8FBFC, 0xFFDDE9EC, 0xFF4E818B, Color.WHITE,
                    0xFF22333A, 0xFF6A8087, 0xFFD2E0E4, 0xFF9CC3CB, 0xFF4E818B, 0xFF6FAE8C,
                    0x660E1F24, 0xFFD2603F, .20f, .10f, false);
        }
        if ("落日莓雾".equals(n)) {
            return new GuidianTheme(n, 0xFFFCEFF2, 0xFFFFF8FA, 0xFFFCE3EA, 0xFFD9637F, Color.WHITE,
                    0xFF3B2730, 0xFF8A6B77, 0xFFF2D8E0, 0xFFF2A98F, 0xFFD9637F, 0xFF7FB08C,
                    0x660E0508, 0xFFC62828, .24f, .11f, false);
        }
        if ("白色".equals(n)) {
            return new GuidianTheme(n, 0xFFF7F6F5, 0xFFFFFFFF, 0xFFF0EDEF, 0xFF292529,
                    Color.WHITE, 0xFF242124, 0xFF777076, 0xFFE2DEE0, 0xFF514A50, 0xFFB7AFB4,
                    0xFFF0ECEE, 0xFF79AB8F, 0x55000000, 0xFFC62828, .18f, .09f, false);
        }
        if ("黑色".equals(n)) {
            return new GuidianTheme(n, 0xFF0E0D10, 0xFF19171B, 0xFF211E23, 0xFFF08EAF, Color.WHITE,
                    0xFFF8F3F5, 0xFFAFA1A7, 0xFF393139, 0xFFF4DCE5, 0xFF72505D,
                    0xFF29232A, 0xFF84B69B, 0x99000000, 0xFFE0637A, .20f, .11f, true);
        }
        return new GuidianTheme("粉色", 0xFFFFF3F7, 0xFFFFFCFD, 0xFFFFE7EF, 0xFFD46A91, Color.WHITE,
                0xFF392C31, 0xFF8F6E7A, 0xFFF1CBD8, 0xFFB64F75, 0xFFE2A7BB,
                0xFFFFDFE9, 0xFF78AE90, 0x550E0508, 0xFFC62828, .24f, .10f, false);
    }

    /** 按语气（温柔 / 生气）取强调色：生气时用 danger 色，其余用 primary。 */
    public int accentFor(String tone) {
        return "angry".equalsIgnoreCase(tone) ? danger : primary;
    }
}
