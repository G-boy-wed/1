package dev.linjian.peek;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 掌心窗主入口 —— 严格按「图1」两张独立手机截图的排列重构。
 *
 * 【数据来源：只认掌心窗真实存在的插件，绝不复制图一里的应用/文案】
 *  - 世界页（左屏）：顶部「一起听」入口（真实功能）→ 全部桌面插件宫格
 *                    数据来自 {@link DeskPluginStore#all()}（已按 id 去重，可增删改换图标）。
 *  - 终端页（右屏）：大字时间 + 真实天气 + 真实周历 + 能力宫格
 *                    能力来自 {@link PluginRegistry#all()}（掌心窗真实登记的 16 项能力）。
 *  - 守护页：图2 的守护功能项，按图1 宫格排列、去重、每格可点开更改。
 *
 * 图1 只是【排列样式】参考（两屏独立 / 宫格 / 大字时间天气 / Dock）。
 * 界面上每一个格子都是掌心窗真实插件，没有一处是照抄图一的假应用或假文案。
 *
 * 【已修的自身 bug】
 *  1. 删除硬编码假内容：CosmicLove / 拍立得照片卡 / 无限象限便签 / 影像之外句子 /
 *     假 4 图标行 / 黑胶唱片。
 *  2. polaroidCard() 里 View 被重复 addView 再 removeView 的废逻辑整体移除。
 *  3. 守护项深链分区修正：focus/calendar/guidian/plugins/profile 这些在设置页
 *     根本不存在的 section，统一走 openSection() 打开「全部设置」，不再跳空白页。
 */
public class PalmDeskActivity extends Activity {
    private static final String TAG = "PalmDesk";

    private static final int PAGE_WORLD = 0;      // 图1左屏「世界」
    private static final int PAGE_TERMINAL = 1;   // 图1右屏「终端」
    private static final int PAGE_GUARD = 2;      // 守护页
    private static final int PAGE_MUSIC = 3;      // 一起听
    private static final int PAGE_CLASSIC = 4;    // 旧 MainActivity 完整功能
    private int currentPage = PAGE_WORLD;
    private boolean editMode = false;

    /** 设置页真实存在的分区（与 PolicySettingsActivity 的 card() 一致）。 */
    private static final Set<String> VALID_SECTIONS = new HashSet<>(Arrays.asList(
            "checkin", "distraction", "discipline", "emotion", "initiative",
            "appgate", "reminder", "schedule", "memory", "ocr", "weather",
            "homemode", "global", "permissions", "debug"));

    private UITheme t;
    private LinearLayout dock;
    private FrameLayout pages;
    private View musicViewRef;
    private TextView terminalClock, terminalWeather;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            refreshClock();
            clock.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        t = UITheme.current(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bgTop);
        pages = new FrameLayout(this);
        pages.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pages);
        dock = buildDock();
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.gravity = Gravity.BOTTOM;
        root.addView(dock, dp);
        setContentView(root);
        switchPage(PAGE_WORLD);
        clock.post(clockTick);
    }

    @Override protected void onDestroy() {
        clock.removeCallbacks(clockTick);
        super.onDestroy();
    }

    // ============================================================
    // 图1 左屏：世界页 —— 一起听入口 + 全部真实桌面插件宫格
    // ============================================================
    private View buildWorldPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgTop);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(16), dpI(28), dpI(16), dpI(96));

        // 顶部：一起听入口（掌心窗真实功能，点击进一起听页；不写死任何假歌名）
        col.addView(buildMusicEntry(), matchWrap());
        col.addView(spacer(16));

        TextView gl = tv("✦  掌心窗 · 全部插件" + (editMode ? "  · 编辑中" : "  （长按图标编辑）"),
                UiTokens.FONT_VALUE, t.subtext);
        gl.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(gl, marginB(10));

        // 宫格：DeskPluginStore 的全部真实插件（已按 id 去重），4 列铺开
        col.addView(buildPluginGrid(), matchWrap());
        col.addView(spacer(14));

        col.addView(buildEditBar(), matchWrapTop(6));

        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** 一起听入口：显示真实功能名 + 当前位置/时间，点击进入一起听页。 */
    private View buildMusicEntry() {
        LinearLayout cap = cardRow(t.card, dp(12));
        TextView ic = tv("♫", 22, t.primary);
        cap.addView(ic);
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv("一起听 · 网易云同款", 14, t.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = tv("◉ " + currentPlaceLabel() + "   ⏱ " +
                new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()), 10, t.subtext);
        meta.addView(title);
        meta.addView(sub, matchWrapTop(2));
        cap.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = tv("›", 22, t.subtext);
        cap.addView(go);
        cap.setOnClickListener(v -> switchPage(PAGE_MUSIC));
        return cap;
    }

    /** 全部桌面插件宫格：数据唯一来源 DeskPluginStore，4 列，不重复。 */
    private View buildPluginGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        List<DeskPluginStore.Plugin> plugins = DeskPluginStore.all(this);
        final int cols = 4;
        int idx = 0;
        while (idx < plugins.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols && idx < plugins.size(); c++, idx++)
                row.addView(pluginCell(plugins.get(idx), idx), weight1());
            grid.addView(row, marginB(10));
        }
        return grid;
    }

    /** 真实插件格：图标 + 名称；点击打开；长按进入编辑；编辑态可换图标/移动/删除/改名。 */
    private View pluginCell(final DeskPluginStore.Plugin p, final int pos) {
        LinearLayout cell = cardCol(t.card, dp(10));
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView icon = tv(p.icon, 24, t.primary);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(t.cardSoft, UiTokens.RADIUS_SM));
        icon.setPadding(0, dpI(8), 0, dpI(8));
        cell.addView(icon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(46)));
        TextView name = tv(p.label, 11, t.text);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(name, matchWrapTop(6));

        if (!editMode) {
            cell.setOnClickListener(v -> openPlugin(p));
            cell.setOnLongClickListener(v -> { editMode = true; refreshCurrentPage(); return true; });
        } else {
            icon.setOnClickListener(v -> cycleIcon(p));
            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setGravity(Gravity.CENTER);
            TextView left = tv("◀", 12, t.subtext);
            TextView rename = tv("✎", 12, t.primary);
            TextView del = tv("✕", 12, t.danger);
            TextView right = tv("▶", 12, t.subtext);
            left.setPadding(dpI(4), 0, dpI(4), 0);
            right.setPadding(dpI(4), 0, dpI(4), 0);
            rename.setPadding(dpI(4), 0, dpI(4), 0);
            del.setPadding(dpI(4), 0, dpI(4), 0);
            left.setOnClickListener(v -> { DeskPluginStore.move(this, pos, pos - 1); refreshCurrentPage(); });
            right.setOnClickListener(v -> { DeskPluginStore.move(this, pos, pos + 1); refreshCurrentPage(); });
            rename.setOnClickListener(v -> showRenameDialog(p));
            del.setOnClickListener(v -> { DeskPluginStore.remove(this, p.id); refreshCurrentPage(); toast("已删除 " + p.label); });
            ops.addView(left); ops.addView(rename); ops.addView(del); ops.addView(right);
            cell.addView(ops, matchWrapTop(2));
        }
        return cell;
    }

    /** 编辑操作条：进入编辑 / 新增 / 保存 / 恢复默认。 */
    private View buildEditBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        if (!editMode) {
            Button manage = new Button(this);
            manage.setText("✎ 管理插件（长按任意图标）");
            manage.setTextSize(11); manage.setAllCaps(false);
            manage.setBackground(shape(t.cardSoft, UiTokens.RADIUS_ROW));
            manage.setTextColor(t.subtext);
            manage.setOnClickListener(v -> {
                editMode = true; refreshCurrentPage();
                toast("点图标换图、✎改名、◀▶移动、✕删除");
            });
            bar.addView(manage, weight1());
            return bar;
        }
        Button add = new Button(this);
        add.setText("＋ 新增"); add.setTextSize(11); add.setAllCaps(false);
        add.setBackground(shape(t.primary, UiTokens.RADIUS_ROW));
        add.setTextColor(t.dark ? t.text : Color.WHITE);
        add.setOnClickListener(v -> showAddPluginDialog());
        Button save = new Button(this);
        save.setText("✓ 保存"); save.setTextSize(11); save.setAllCaps(false);
        save.setBackground(shape(t.primarySoft, UiTokens.RADIUS_ROW)); save.setTextColor(t.text);
        save.setOnClickListener(v -> { editMode = false; refreshCurrentPage(); toast("已保存"); });
        Button reset = new Button(this);
        reset.setText("↺ 默认"); reset.setTextSize(11); reset.setAllCaps(false);
        reset.setBackground(shape(t.cardSoft, UiTokens.RADIUS_ROW)); reset.setTextColor(t.subtext);
        reset.setOnClickListener(v -> { DeskPluginStore.reset(this); editMode = false; refreshCurrentPage(); toast("已恢复默认排列"); });
        bar.addView(add, weight1()); bar.addView(save, weight1()); bar.addView(reset, weight1());
        return bar;
    }

    // ============================================================
    // 图1 右屏：终端页 —— 大字时间 + 真实天气 + 真实周历 + 能力宫格
    // ============================================================
    private View buildTerminalPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgTop);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(16), dpI(28), dpI(16), dpI(96));

        // 大字时间（左）+ 真实天气（右）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        terminalClock = tv("", UiTokens.FONT_DISPLAY, t.text);
        terminalClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        head.addView(terminalClock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout wx = new LinearLayout(this);
        wx.setOrientation(LinearLayout.VERTICAL); wx.setGravity(Gravity.END);
        terminalWeather = tv("☁ --°", 22, t.text); terminalWeather.setGravity(Gravity.END);
        TextView wxSub = tv("当前位置", 10, t.subtext); wxSub.setGravity(Gravity.END);
        wx.addView(terminalWeather); wx.addView(wxSub, matchWrapTop(2));
        wx.setOnClickListener(v -> openSection("weather"));
        head.addView(wx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(head, marginB(16));

        // 真实周历（当前日期来自系统日历，非硬编码），点击进守护日历
        col.addView(buildWeekStrip(), matchWrap());
        col.addView(spacer(16));

        TextView cl = tv("✦  掌心窗 · 能力插件", UiTokens.FONT_VALUE, t.subtext);
        cl.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(cl, marginB(10));

        // 能力宫格：PluginRegistry 全部真实登记能力，4 列，点开对应设置
        col.addView(buildCapabilityGrid(), matchWrap());

        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshClock();
        refreshDeskWeather();
        return sv;
    }

    /** 真实周历卡：日期来自系统日历，今天高亮；点击进守护日历分区。 */
    private View buildWeekStrip() {
        LinearLayout box = cardCol(t.card, dp(16));
        box.addView(tv(new SimpleDateFormat("yyyy年M月", Locale.CHINA).format(new Date()), 11, t.subtext));
        LinearLayout week = new LinearLayout(this);
        week.setOrientation(LinearLayout.HORIZONTAL);
        String[] dayNames = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
        java.util.Calendar cal = java.util.Calendar.getInstance(Locale.CHINA);
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1;
        cal.add(java.util.Calendar.DAY_OF_MONTH, -dow);
        for (int i = 0; i < 7; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            TextView d = tv(dayNames[i], 9, t.subtext); d.setGravity(Gravity.CENTER);
            int dayNum = cal.get(java.util.Calendar.DAY_OF_MONTH);
            boolean selected = (i == dow);
            TextView n = tv(String.valueOf(dayNum), 15, selected ? (t.dark ? t.text : Color.WHITE) : t.text);
            n.setGravity(Gravity.CENTER);
            if (selected) { n.setBackground(shape(t.primary, 40)); n.setTypeface(Typeface.DEFAULT_BOLD); }
            cell.addView(d); cell.addView(n, matchWrapTop(4));
            week.addView(cell, weight1());
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        box.addView(week, matchWrapTop(10));
        box.setOnClickListener(v -> openSection("schedule"));
        return box;
    }

    /** 能力宫格：数据唯一来源 PluginRegistry，按 id 去重，4 列。 */
    private View buildCapabilityGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        List<PluginRegistry.Plugin> caps = PluginRegistry.all();
        Set<String> seen = new LinkedHashSet<>();
        List<PluginRegistry.Plugin> uniq = new ArrayList<>();
        for (PluginRegistry.Plugin c : caps) {
            if (c == null || seen.contains(c.id)) continue;
            seen.add(c.id); uniq.add(c);
        }
        final int cols = 4;
        int idx = 0;
        while (idx < uniq.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols && idx < uniq.size(); c++, idx++)
                row.addView(capabilityCell(uniq.get(idx)), weight1());
            grid.addView(row, marginB(10));
        }
        return grid;
    }

    private View capabilityCell(final PluginRegistry.Plugin cap) {
        LinearLayout cell = cardCol(t.card, dp(10));
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView ic = tv(capabilityIcon(cap.id), 22, t.primary);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(shape(t.cardSoft, UiTokens.RADIUS_SM));
        ic.setPadding(0, dpI(8), 0, dpI(8));
        cell.addView(ic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(44)));
        TextView nm = tv(cap.name, 10, t.text);
        nm.setGravity(Gravity.CENTER); nm.setMaxLines(1); nm.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(nm, matchWrapTop(6));
        cell.setOnClickListener(v -> openCapability(cap));
        return cell;
    }

    /** 能力插件图标（只是掌心窗能力的字符标识，非图一应用）。 */
    private String capabilityIcon(String id) {
        switch (id) {
            case "checkin_pullback": return "◎";
            case "app_gate": return "⚑";
            case "usage_auto_lock": return "⏰";
            case "focus_mode": return "◷";
            case "companion_emotion": return "♡";
            case "initiative": return "❀";
            case "discipline": return "✂";
            case "class_schedule": return "▤";
            case "guard_calendar": return "▦";
            case "cycle_reminder": return "⏰";
            case "guidian": return "⚡";
            case "night_lock": return "☾";
            case "notifications": return "⏰";
            case "shared_memory": return "★";
            case "ocr": return "☷";
            case "guard_visual": return "✦";
            default: return "✦";
        }
    }

    private void openCapability(PluginRegistry.Plugin cap) {
        if ("guidian".equals(cap.id)) {
            try { startActivity(new Intent(this, GuidianActivity.class)); return; } catch (Throwable ignored) { }
        }
        openSection(PluginRegistry.guardSectionOf(cap.id));
    }

    // ============================================================
    // 守护页 —— 图2 守护功能项，按图1 宫格排列、去重、可点开更改
    // ============================================================
    private static class GuardItem {
        String name, desc, icon, section, prefKey;
        boolean on;
        GuardItem(String n, String d, String ic, String sec, String pref, boolean defOn) {
            name = n; desc = d; icon = ic; section = sec; prefKey = pref; on = defOn;
        }
    }

    private View buildGuardPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgTop);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(16), dpI(28), dpI(16), dpI(96));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.VERTICAL);
        tb.addView(tv("Toujours à tes côtés", 20, t.text));
        tb.addView(tv("守护功能 · 每项点开即可更改并保存", 11, t.subtext), matchWrapTop(2));
        titleRow.addView(tb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView avatar = tv("♡", 20, t.dark ? t.text : Color.WHITE);
        avatar.setBackground(shape(t.primarySoft, 40)); avatar.setGravity(Gravity.CENTER);
        int av = dpI(42); avatar.setLayoutParams(new LinearLayout.LayoutParams(av, av));
        avatar.setOnClickListener(v -> openSection(null)); // 个人资料在全部设置里改
        titleRow.addView(avatar);
        col.addView(titleRow, marginB(16));

        List<GuardItem> items = dedupGuard(guardItems());
        final int columns = 4;
        int idx = 0;
        while (idx < items.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < columns && idx < items.size(); c++, idx++)
                row.addView(guardCell(items.get(idx)), weight1());
            col.addView(row, marginB(10));
        }

        Button more = new Button(this);
        more.setText("打开完整功能与设置"); more.setTextSize(12); more.setAllCaps(false);
        more.setBackground(shape(t.cardSoft, UiTokens.RADIUS_ROW)); more.setTextColor(t.subtext);
        more.setOnClickListener(v -> switchPage(PAGE_CLASSIC));
        col.addView(more, matchWrapTop(16));

        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private View guardCell(final GuardItem it) {
        LinearLayout cell = cardCol(t.card, dp(10));
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView ic = tv(it.icon, 22, t.primary);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(shape(t.cardSoft, UiTokens.RADIUS_SM));
        ic.setPadding(0, dpI(8), 0, dpI(8));
        cell.addView(ic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(44)));
        TextView nm = tv(it.name, 11, t.text);
        nm.setGravity(Gravity.CENTER); nm.setMaxLines(1); nm.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(nm, matchWrapTop(6));
        Switch sw = new Switch(this);
        it.on = AppPrefs.get(this).getBoolean(it.prefKey, it.on);
        sw.setChecked(it.on);
        sw.setOnCheckedChangeListener((b, on) -> {
            AppPrefs.get(this).edit().putBoolean(it.prefKey, on).apply();
            toast(it.name + (on ? " 已开启" : " 已关闭"));
        });
        cell.addView(sw, matchWrapTop(2));
        cell.setOnClickListener(v -> openSection(it.section));
        return cell;
    }

    private List<GuardItem> dedupGuard(List<GuardItem> in) {
        List<GuardItem> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GuardItem g : in) {
            if (g == null || g.prefKey == null || seen.contains(g.prefKey)) continue;
            seen.add(g.prefKey); out.add(g);
        }
        return out;
    }

    /** 守护功能项（与图2 一致），section 用设置页真实存在的 key。 */
    private List<GuardItem> guardItems() {
        List<GuardItem> l = new ArrayList<>();
        l.add(new GuardItem("查岗拉回", "查岗周期与分级拉回", "◎", "checkin", "desk_guard_checkin", true));
        l.add(new GuardItem("分心强拉回", "来回切 App 算分心与阈值分级", "⊘", "distraction", "desk_guard_distraction", true));
        l.add(new GuardItem("训诫", "小圈管束，温柔 / 严厉两种语气", "✂", "discipline", "desk_guard_discipline", true));
        l.add(new GuardItem("情绪感知", "依据状态产生情绪与想你程度", "♡", "emotion", "desk_guard_emotion", true));
        l.add(new GuardItem("主动找用户", "太久没消息主动弹窗", "❀", "initiative", "desk_guard_initiative", true));
        l.add(new GuardItem("应用门禁", "锁 App 时长，自主添加受限应用", "⚑", "appgate", "desk_guard_appgate", true));
        l.add(new GuardItem("主动提醒", "电量/屏幕/喝水/休息/周期", "⏰", "reminder", "desk_guard_reminder", true));
        l.add(new GuardItem("专注模式", "全机专注、应急放行与留言", "◷", "focus", "desk_guard_focus", false));
        l.add(new GuardItem("守护日历", "纪念日、节日与倒数日", "▦", "calendar", "desk_guard_calendar", true));
        l.add(new GuardItem("上课作息", "上课静默守护与停提醒", "▤", "schedule", "desk_guard_schedule", true));
        l.add(new GuardItem("共同记忆", "双方可改的共享记忆", "★", "memory", "desk_guard_memory", true));
        l.add(new GuardItem("识图感知", "从当前屏幕读取文字", "☷", "ocr", "desk_guard_ocr", true));
        l.add(new GuardItem("插件分类", "全部扩展能力归类总览", "✦", "plugins", "desk_guard_plugins", true));
        l.add(new GuardItem("天气地区", "家与常用地点，坐标优先", "☁", "weather", "desk_guard_weather", true));
        l.add(new GuardItem("回家模式", "回来时打开熟悉应用", "⌂", "homemode", "desk_guard_homemode", false));
        l.add(new GuardItem("归电设置", "间隔/冷却/安静时段", "⚡", "guidian", "desk_guard_guidian", true));
        l.add(new GuardItem("屏幕日记", "记录屏幕使用与今日轨迹", "▧", "checkin", "desk_guard_screen_diary", true));
        l.add(new GuardItem("自拍记录", "定时记录此刻的自己", "☻", "reminder", "desk_guard_selfie", true));
        l.add(new GuardItem("保密模式", "隐私与记录的保护", "⚙", "global", "desk_guard_private", true));
        l.add(new GuardItem("备份相册", "日记本与相册导出备份", "▥", "memory", "desk_guard_backup", true));
        return l;
    }

    /**
     * 统一分区跳转：只在设置页真实存在的 section 才深链；
     * focus/calendar/plugins/profile/guidian 等不存在的 key 一律打开「全部设置」，
     * 绝不再跳空白页（这是修复的自身 bug）。
     */
    private void openSection(String section) {
        Intent i = new Intent(this, PolicySettingsActivity.class);
        if (section != null && VALID_SECTIONS.contains(section)) i.putExtra("section", section);
        try { startActivity(i); }
        catch (Exception e) { toast("打开设置失败：" + ScreenshotService.shortMsg(e)); }
    }

    // ============================================================
    // 一起听（网易云同款）
    // ============================================================
    private void buildMusicPageAsync() {
        musicViewRef = new ScrollView(this);
        ScrollView sv = (ScrollView) musicViewRef;
        sv.setBackgroundColor(t.bgTop);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(20), dpI(30), dpI(20), dpI(96));
        col.addView(tv("一起听", 22, t.text));
        col.addView(tv("网易云同款 · 显示位置、头像与时间", 11, t.subtext), matchWrapTop(2));
        TextView loading = tv("正在连接在线曲库…", 12, t.subtext);
        col.addView(loading, matchWrapTop(16));
        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new Thread(() -> {
            FreeMusic.Result res = FreeMusic.hot(12);
            runOnUiThread(() -> renderMusic(col, loading, res));
        }).start();
    }

    private void renderMusic(LinearLayout col, TextView loading, FreeMusic.Result res) {
        if (loading.getParent() != null) col.removeView(loading);
        JSONObject media = null;
        try { media = MediaState.collect(this); } catch (Exception ignored) { }
        FreeMusic.Song now = (media != null && media.optBoolean("available"))
                ? FreeMusic.fromMediaState(media)
                : (!res.songs.isEmpty() ? res.songs.get(0) : new FreeMusic.Song());

        LinearLayout nowCard = cardCol(t.card, dp(20));
        nowCard.setGravity(Gravity.CENTER_HORIZONTAL);
        View disc = new View(this);
        int ds = dpI(150);
        disc.setLayoutParams(new LinearLayout.LayoutParams(ds, ds));
        disc.setBackground(shape(t.primarySoft, ds / 2f));
        ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f).setDuration(12000).start();
        nowCard.addView(disc);
        TextView songName = tv(now.name.isEmpty() ? "选一首歌开始一起听" : now.name, 18, t.text);
        songName.setGravity(Gravity.CENTER); songName.setTypeface(Typeface.DEFAULT_BOLD);
        nowCard.addView(songName, matchWrapTop(12));
        TextView artist = tv(now.artist.isEmpty() ? "掌心窗 · 一起听" : now.artist, 12, t.subtext);
        artist.setGravity(Gravity.CENTER);
        nowCard.addView(artist, matchWrapTop(2));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgress(media != null ? media.optInt("progress_percent", 0) : 18);
        nowCard.addView(bar, matchWrapTop(12));
        String where = currentPlaceLabel();
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        TextView meta = tv("◉ " + where + "   ⏱ " + time + " 起一起听", 11, t.subtext);
        meta.setGravity(Gravity.CENTER);
        nowCard.addView(meta, matchWrapTop(10));
        nowCard.addView(buildAvatars(), matchWrapTop(12));
        col.addView(nowCard, matchWrapTop(10));

        if (!res.ok) {
            col.addView(tv(res.error, 11, t.danger), matchWrapTop(10));
        } else {
            col.addView(tv("在线曲库 · " + res.source.replace("https://", ""), 10, t.subtext), matchWrapTop(10));
            TextView lt = tv("热门歌曲（点选展示）", 13, t.text); lt.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(lt, matchWrapTop(14));
            for (int i = 0; i < res.songs.size(); i++) {
                final FreeMusic.Song s = res.songs.get(i);
                LinearLayout row = cardRow(t.cardSoft, dp(12));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(tv(String.valueOf(i + 1), 12, t.subtext));
                LinearLayout m = new LinearLayout(this); m.setOrientation(LinearLayout.VERTICAL);
                m.addView(tv(s.name, 13, t.text));
                m.addView(tv(s.artist.isEmpty() ? "未知歌手" : s.artist, 10, t.subtext));
                row.addView(m, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tv("♫", 16, t.primary));
                row.setOnClickListener(v -> { songName.setText(s.name); artist.setText(s.artist.isEmpty() ? "未知歌手" : s.artist); bar.setProgress(0); toast("已选：" + s.name); });
                col.addView(row, marginB(6));
            }
        }
    }

    private View buildAvatars() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER);
        String[] names = {AppPrefs.userName(this), AppPrefs.companionName(this), "我", "TA"};
        int[] colors = {t.primary, t.primarySoft, t.cardSoft, t.primarySoft};
        for (int i = 0; i < names.length; i++) {
            TextView a = tv(names[i].isEmpty() ? "?" : names[i].substring(0, 1), 14, t.dark ? t.text : Color.WHITE);
            a.setGravity(Gravity.CENTER); a.setBackground(shape(colors[i], 40));
            int d = dpI(36);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d, d);
            lp.setMargins(i == 0 ? 0 : -dpI(8), 0, 0, 0);
            row.addView(a, lp);
        }
        return row;
    }

    private String currentPlaceLabel() {
        try {
            JSONObject cur = WeatherState.currentLocation(this);
            if (cur != null) {
                String n = cur.optString("name", "");
                if (!n.isEmpty()) return n;
                double[] loc = NowState.lastKnownLocation(this);
                if (loc != null) return String.format(Locale.CHINA, "%.3f,%.3f", loc[0], loc[1]);
            }
        } catch (Exception ignored) { }
        return "当前位置";
    }

    // ============================================================
    // 导航 / 工具
    // ============================================================
    private LinearLayout buildDock() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(t.navBar());
        bar.setGravity(Gravity.CENTER);
        int h = dpI(UiTokens.DOCK_H);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        int side = dpI(UiTokens.DOCK_SIDE), bottom = dpI(UiTokens.DOCK_BOTTOM);
        lp.setMargins(side, 0, side, bottom);
        bar.setLayoutParams(lp);
        String[] labels = {"世界", "终端", "守护", "主题", "设置"};
        final int[] pageOf = {PAGE_WORLD, PAGE_TERMINAL, PAGE_GUARD, -1, PAGE_CLASSIC};
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this);
            b.setText(labels[i]); b.setTextSize(11); b.setAllCaps(false);
            b.setBackground(t.navItem(i == currentPage));
            b.setTextColor(t.text);
            final int pg = pageOf[i];
            b.setOnClickListener(v -> {
                if (pg == -1) showThemeDialog();
                else switchPage(pg);
            });
            bar.addView(b, weight1());
        }
        return bar;
    }

    private void showThemeDialog() {
        String[] names = UITheme.themeNames();
        String cur = UITheme.currentBaseName(this) + (UITheme.isDarkMode(this) ? " · 暗" : " · 亮");
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("主题：" + cur);
        b.setItems(names, (d, which) -> {
            UITheme.setTheme(this, names[which]);
            t = UITheme.current(this);
            dock.setBackground(t.navBar());
            refreshCurrentPage();
            toast("主题已切换为 " + names[which]);
        });
        b.setNeutralButton(UITheme.isDarkMode(this) ? "☀ 切到亮色" : "☾ 切到暗色", (d, w) -> {
            UITheme.toggleDarkMode(this);
            t = UITheme.current(this);
            dock.setBackground(t.navBar());
            refreshCurrentPage();
            toast(UITheme.isDarkMode(this) ? "已切到暗色" : "已切到亮色");
        });
        b.show();
    }

    private void switchPage(int page) {
        if (page == PAGE_CLASSIC) {
            startActivity(new Intent(this, MainActivity.class));
            return;
        }
        currentPage = page;
        for (int i = pages.getChildCount() - 1; i >= 0; i--) pages.removeViewAt(i);
        if (page == PAGE_WORLD) pages.addView(buildWorldPage());
        else if (page == PAGE_TERMINAL) pages.addView(buildTerminalPage());
        else if (page == PAGE_GUARD) pages.addView(buildGuardPage());
        else if (page == PAGE_MUSIC) { buildMusicPageAsync(); pages.addView(musicViewRef); }
        refreshDock();
    }

    private void refreshDock() {
        for (int i = 0; i < dock.getChildCount(); i++) {
            View v = dock.getChildAt(i);
            if (v instanceof Button) {
                boolean sel = (i == currentPage);
                v.setBackground(t.navItem(sel));
                ((Button) v).setTextColor(sel ? t.primary : t.subtext);
            }
        }
    }

    private void refreshCurrentPage() {
        int p = currentPage;
        for (int i = pages.getChildCount() - 1; i >= 0; i--) pages.removeViewAt(i);
        if (p == PAGE_WORLD) pages.addView(buildWorldPage());
        else if (p == PAGE_TERMINAL) pages.addView(buildTerminalPage());
        else if (p == PAGE_GUARD) pages.addView(buildGuardPage());
        else if (p == PAGE_MUSIC) { buildMusicPageAsync(); pages.addView(musicViewRef); }
    }

    private void cycleIcon(final DeskPluginStore.Plugin p) {
        String[] icons = DeskPluginStore.ICONS;
        int cur = 0;
        for (int i = 0; i < icons.length; i++) if (icons[i].equals(p.icon)) { cur = i; break; }
        String next = icons[(cur + 1) % icons.length];
        DeskPluginStore.update(this, p.id, p.label, next, p.action);
        refreshCurrentPage();
        toast("图标已换为 " + next + "（继续点可循环）");
    }

    private void openPlugin(DeskPluginStore.Plugin p) {
        openAction(p.action, p.label);
    }

    private void openAction(String a, String label) {
        try {
            if (a == null || a.isEmpty() || "none".equals(a)) {
                toast(label + " · 长按可编辑");
            } else if (a.startsWith("page:")) {
                String pg = a.substring(5);
                if ("music".equals(pg)) switchPage(PAGE_MUSIC);
                else if ("guard".equals(pg)) switchPage(PAGE_GUARD);
                else if ("world".equals(pg)) switchPage(PAGE_WORLD);
                else if ("terminal".equals(pg)) switchPage(PAGE_TERMINAL);
                else if ("classic".equals(pg)) switchPage(PAGE_CLASSIC);
                else toast("页面：" + pg);
            } else if (a.startsWith("policy:")) {
                openSection(a.substring(7));
            } else if (a.startsWith("app:")) {
                Intent i = getPackageManager().getLaunchIntentForPackage(a.substring(4));
                if (i != null) startActivity(i); else toast("本机未安装该应用");
            } else toast(label);
        } catch (Exception e) {
            toast("打开失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private void showAddPluginDialog() {
        final android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("插件名称");
        final android.widget.EditText action = new android.widget.EditText(this);
        action.setHint("动作：policy:calendar / page:music / app:包名（可留空）");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpI(20), dpI(12), dpI(20), dpI(4));
        box.addView(name); box.addView(action);
        new android.app.AlertDialog.Builder(this).setTitle("新增插件").setView(box)
                .setPositiveButton("添加", (d, w) -> {
                    DeskPluginStore.add(this, name.getText().toString(), "✦", action.getText().toString());
                    refreshCurrentPage(); toast("已添加");
                })
                .setNegativeButton("取消", null).show();
    }

    private void showRenameDialog(final DeskPluginStore.Plugin p) {
        final android.widget.EditText name = new android.widget.EditText(this);
        name.setText(p.label);
        final android.widget.EditText action = new android.widget.EditText(this);
        action.setText(p.action);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpI(20), dpI(12), dpI(20), dpI(4));
        box.addView(name); box.addView(action);
        new android.app.AlertDialog.Builder(this).setTitle("编辑插件：" + p.label).setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    DeskPluginStore.update(this, p.id, name.getText().toString(), p.icon, action.getText().toString());
                    refreshCurrentPage(); toast("已保存");
                })
                .setNegativeButton("取消", null).show();
    }

    // ---- 时钟与天气 ----
    private void refreshClock() {
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        if (terminalClock != null) terminalClock.setText(time);
        if (terminalWeather != null) terminalWeather.setText("☁ " + weatherShort());
    }

    private String weatherShort() {
        try {
            JSONObject cur = WeatherState.currentLocation(this);
            String city = cur == null ? "" : cur.optString("city", "");
            String district = cur == null ? "" : cur.optString("district", "");
            JSONObject live = WeatherLive.cached(this, city, district);
            if (live != null && live.optBoolean("ok")) return live.optInt("temperature", 0) + "°";
            return "--°";
        } catch (Exception ignored) { }
        return "--°";
    }

    private void refreshDeskWeather() {
        try {
            JSONObject cur = WeatherState.currentLocation(this);
            if (cur == null) return;
            double lat = cur.optDouble("latitude", 0), lon = cur.optDouble("longitude", 0);
            String city = cur.optString("city", ""), district = cur.optString("district", "");
            if ((lat != 0 || lon != 0) && !WeatherLive.isFresh(this, city, district, 45L * 60L * 1000L))
                WeatherLive.refreshAsync(this, city, district, lat, lon, w -> runOnUiThread(this::refreshClock));
        } catch (Exception ignored) { }
    }

    // ---- UI 小工具 ----
    private TextView tv(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s == null ? "" : s);
        v.setTextSize(size); v.setTextColor(color);
        return v;
    }
    private int dpI(float v) { return UiTokens.dpI(v); }
    private float dp(float v) { return UiTokens.dp(v); }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radius));
        return g;
    }
    private LinearLayout cardCol(int color, float pad) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(shape(color, UiTokens.RADIUS_CARD));
        l.setPadding(dpI(pad), dpI(pad), dpI(pad), dpI(pad));
        return l;
    }
    private LinearLayout cardRow(int color, float pad) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setBackground(shape(color, UiTokens.RADIUS_CARD));
        l.setPadding(dpI(pad), dpI(pad), dpI(pad), dpI(pad));
        return l;
    }
    private View spacer(int dpV) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(dpV)));
        return v;
    }
    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private LinearLayout.LayoutParams matchWrapTop(int dpTop) {
        LinearLayout.LayoutParams lp = matchWrap(); lp.topMargin = dpI(dpTop); return lp;
    }
    private LinearLayout.LayoutParams marginB(int dpB) {
        LinearLayout.LayoutParams lp = matchWrap(); lp.bottomMargin = dpI(dpB); return lp;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
