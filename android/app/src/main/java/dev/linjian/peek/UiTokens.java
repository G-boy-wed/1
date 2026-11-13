package dev.linjian.peek;

import android.content.res.Resources;
import android.graphics.Color;

/**
 * 掌心窗 全站 UI 美化规范 v2026-11-14-03 · THEME-01 全局设计令牌。
 *
 * 全部页面 / 弹层 / 桌面组件 / 通用件只从这里取色、圆角、间距、字号与动效常量，
 * 不允许再散落 Color.parseColor("#...") 硬编码。六套预设在 UITheme 中按此派生。
 */
public final class UiTokens {
    private UiTokens() {}

    // ---- 颜色 · THEME-01 基准 ----
    public static final int PAGE       = 0xFFF4F4F4; // 页面底
    public static final int CARD       = 0xFFFFFFFF; // 卡片
    public static final int CARD_SUB   = 0xFFF7F7F7; // 卡内子块
    public static final int STROKE     = 0xCCFFFFFF; // 毛玻璃描边 白色 80% / 1dp
    public static final int TEXT_MAIN  = 0xFF3A3A3A;
    public static final int TEXT_SUB   = 0xFF9A9A9A;
    public static final int TEXT_WEAK  = 0xFFC4C4C4;
    public static final int ACCENT     = 0xFF4A4A4A; // 主按钮 / 选中
    public static final int DANGER     = 0xFFC98A8A; // 弱红，不用纯红
    public static final int ON         = 0xFFFFFFFF;

    // 毛玻璃：白色 55% 页面蒙版 / 60% 卡片，1dp 白色描边，24px 模糊
    public static final int   GLASS_PAGE = 0x8CFFFFFF;
    public static final int   GLASS_CARD = 0x99FFFFFF;
    public static final float BLUR_PX    = 24f;
    public static final float STROKE_DP  = 1f;

    // ---- 圆角 ----
    public static final float RADIUS_SM    = 14f;
    public static final float RADIUS_ROW   = 18f;
    public static final float RADIUS_CARD  = 24f;
    public static final float RADIUS_DOCK  = 28f;
    public static final float RADIUS_SHEET = 28f;

    // ---- 间距 ----
    public static final float SPACE_XS = 6f;
    public static final float SPACE_S  = 10f;
    public static final float SPACE_M  = 16f;
    public static final float SPACE_L  = 24f;

    // ---- 字号 ----
    public static final float FONT_DISPLAY    = 64f; // 时间大字 Light
    public static final float FONT_PAGE_LABEL = 11f; // 大写，letterSpacing 2sp
    public static final float FONT_PAGE_TITLE = 28f; // Bold
    public static final float FONT_ROW        = 16f; // Medium
    public static final float FONT_VALUE      = 13f;
    public static final float FONT_NOTE       = 12f;
    public static final float LETTER_SPACING  = 2f;  // sp

    // ---- 阴影 / 动效 · MOTION ----
    public static final float SHADOW_CARD_Y = 6f;
    public static final float SHADOW_CARD_R = 20f;
    public static final int   SHADOW_CARD_COLOR = 0x0D000000; // rgba(0,0,0,0.05)
    public static final int   MOTION_ENTER  = 220; // 页面进入 上浮 12dp + 淡入
    public static final float MOTION_ENTER_Y = 12f;
    public static final int   MOTION_EXPAND = 260; // 卡片展开
    public static final int   MOTION_SHEET  = 300; // 抽屉上滑
    public static final int   MOTION_TAP    = 120; // 点击缩放 0.97
    public static final int   MOTION_COUNT  = 400; // 数字滚动
    public static final float TAP_SCALE     = 0.97f;

    // ---- 组件尺寸 · COMPONENT ----
    public static final float CHIP_H     = 36f;
    public static final float ROW_H      = 64f;
    public static final float DOCK_H     = 64f;
    public static final float DOCK_SIDE  = 20f;
    public static final float DOCK_BOTTOM = 16f;
    public static final float ICON_ROUND = 40f;
    public static final float NAV_ICON   = 44f;
    public static final float DOT_SELECT = 4f;

    public static float dp(float v) {
        return v * Resources.getSystem().getDisplayMetrics().density;
    }

    public static int dpI(float v) {
        return (int) dp(v);
    }

    /** 白色玻璃层：whiteAlpha 取 0~1。 */
    public static int glass(float whiteAlpha) {
        float a = Math.max(0f, Math.min(1f, whiteAlpha));
        return Color.argb(Math.round(255 * a), 255, 255, 255);
    }

    /** 两色按 amount 线性混合，用于派生暗色版与柔和态。 */
    public static int blend(int from, int to, float amount) {
        float x = Math.max(0f, Math.min(1f, amount));
        return Color.argb(
                Math.round(Color.alpha(from) * (1 - x) + Color.alpha(to) * x),
                Math.round(Color.red(from)   * (1 - x) + Color.red(to)   * x),
                Math.round(Color.green(from) * (1 - x) + Color.green(to) * x),
                Math.round(Color.blue(from)  * (1 - x) + Color.blue(to)  * x));
    }
}
