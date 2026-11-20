package dev.linjian.peek;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 掌心窗主入口 —— 严格按「图1」两张独立手机截图的排列重构。
 *
 * 【关键】图1是相册 1/3 里【并排的两张独立手机屏幕】，不是一屏的上下两段：
 *  - 世界页（左屏）：顶部音乐胶囊 → 4 列插件宫格（含 2 张拍立得照片卡 + 1 张便签卡）→ Dock
 *  - 终端页（右屏）：大字时间 + 天气 → 周历卡 → 照片/唱片/照片 三卡 → 4 图标行 → 一句话横条 → Dock
 * 两屏各自独立满屏，用 Dock 的「世界 / 终端」两个键切换，绝不堆进同一个滚动页。
 *
 * 守护页（图2）的功能项改为图1那种宫格卡片排列、按 id 去重、每格可点开编辑并保存。
 * 一起听页：网易云同款，显示封面/进度/位置/头像/时间。
 *
 * 只还原排列结构（用户明确「只需排列，不参考颜色」）；配色统一从 UITheme/UiTokens 取。
 */
public class PalmDeskActivity extends Activity {
    private static final String TAG = "PalmDesk";

    // 图1 Dock 的三个页面 + 一起听 + 完整设置
    private static final int PAGE_WORLD = 0;      // 图1左屏「世界」
    private static final int PAGE_TERMINAL = 1;   // 图1右屏「终端」
    private static final int PAGE_GUARD = 2;      // 守护页（图2功能项，宫格排列）
    private static final int PAGE_MUSIC = 3;      // 一起听
    private static final int PAGE_CLASSIC = 4;    // 旧 MainActivity 完整功能
    private int currentPage = PAGE_WORLD;
    private boolean editMode = false;

    private UITheme t;
    private LinearLayout dock;
    private FrameLayout pages;
    private View musicViewRef;
    private TextView worldClock, terminalClock, terminalWeather;
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
    // 图1 左屏：世界页
    // 音乐胶囊 → 插件宫格（4列，含拍立得照片卡 + 便签卡）→ Dock
    // ============================================================
    private View buildWorldPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgTop);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(16), dpI(28), dpI(16), dpI(96));

        // 顶部音乐胶囊（封面 + 歌名 + 进度 + ◀ ▶ ▶| 列表 循环）
        col.addView(musicCapsule(), matchWrap());
        col.addView(spacer(16));

        // 宫格标题 + 编辑提示
        TextView gl = tv("✦  世界 · 插件宫格" + (editMode ? "  · 编辑中" : "  （长按进入编辑）"), UiTokens.FONT_VALUE, t.subtext);
        gl.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(gl, marginB(10));

        // 宫格：图1左屏 4 列，资讯/约会/论坛/群组 … 关心/细细/重逢/记忆
        col.addView(buildWorldGrid(), matchWrap());
        col.addView(spacer(14));

        // 编辑操作条（进入编辑后显示）
        col.addView(buildEditBar(), matchWrapTop(6));

        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshClock();
        return sv;
    }

    /** 音乐胶囊：方形封面 + CosmicLove + 进度条 + 控制行（严格按图1左屏顶部） */
    private View musicCapsule() {
        LinearLayout cap = cardCol(t.card, dp(12));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        // 方形封面
        ImageView cover = new ImageView(this);
        int cs = dpI(46);
        cover.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        cover.setBackground(shape(t.primarySoft, UiTokens.RADIUS_SM));
        cover.setImageResource(R.drawable.ic_heart_wave);
        cover.setPadding(dpI(8), dpI(8), dpI(8), dpI(8));
        head.addView(cover);

        // 歌名 + 歌手 + 进度
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv("CosmicLove", 14, t.text); title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView artist = tv("掌心窗 · 一起听", 10, t.subtext);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgress(24);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(3)));
        meta.addView(title); meta.addView(artist); meta.addView(bar, matchWrapTop(6));
        head.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cap.addView(head);

        // 控制行：◀ ▶ ▶| ≡ ↻
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);
        String[] ctl = {"◀", "▶", "▶|", "≡", "↻"};
        for (String s : ctl) {
            TextView b = tv(s, 16, t.text);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(dpI(40));
            b.setPadding(dpI(6), dpI(6), dpI(6), dpI(6));
            b.setOnClickListener(v -> {
                if ("▶".equals(s)) switchPage(PAGE_MUSIC);
                else toast("媒体播放控制 · " + s + "（跟随系统媒体会话）");
            });
            ctrl.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        cap.addView(ctrl, matchWrapTop(8));
        cap.setOnClickListener(v -> switchPage(PAGE_MUSIC));
        return cap;
    }

    /**
     * 图1左屏插件宫格：4 列。
     * 前 4 格：资讯/约会/论坛/群组；中部穿插 2 张拍立得照片卡；
     * 后 4 格：关心/细细/重逢/记忆；底部 1 张便签卡。
     * 数据来自 DeskPluginStore（已按 id 去重），保证「全部插件不重复」。
     */
    private View buildWorldGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        List<DeskPluginStore.Plugin> plugins = DeskPluginStore.all(this);
        int cols = 4;
        int idx = 0;

        // 行1：4 个插件（资讯 / 约会 / 论坛 / 群组）
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        for (int c = 0; c < cols && idx < plugins.size(); c++, idx++)
            row1.addView(worldPluginCell(plugins.get(idx), idx), weight1());
        grid.addView(row1, marginB(10));

        // 行2：2 个插件 + 2 张拍立得照片卡（图1右上叠放的照片卡位置）
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        for (int c = 0; c < 2 && idx < plugins.size(); c++, idx++)
            row2.addView(worldPluginCell(plugins.get(idx), idx), weight1());
        row2.addView(polaroidCard("🍏", "此刻的窗"), weight1());
        row2.addView(polaroidCard("🌿", "想你的花"), weight1());
        grid.addView(row2, marginB(10));

        // 行3：4 个插件（关心 / 细细 / 重逢 / 记忆）
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        for (int c = 0; c < cols && idx < plugins.size(); c++, idx++)
            row3.addView(worldPluginCell(plugins.get(idx), idx), weight1());
        grid.addView(row3, marginB(10));

        // 剩余插件继续 4 列铺开（所有插件不重复、全部排列）
        while (idx < plugins.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols && idx < plugins.size(); c++, idx++)
                row.addView(worldPluginCell(plugins.get(idx), idx), weight1());
            grid.addView(row, marginB(10));
        }

        // 便签卡（图1左下：无限象限 · 也许我们应该在自我热切的自由中执着）
        grid.addView(noteCard(), matchWrapTop(2));
        return grid;
    }

    /** 图1左屏的方形插件格：图标 + 名称（点击打开；长按进入编辑） */
    private View worldPluginCell(final DeskPluginStore.Plugin p, final int pos) {
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
            TextView del = tv("✕", 12, t.danger);
            TextView right = tv("▶", 12, t.subtext);
            left.setPadding(dpI(4), 0, dpI(4), 0); right.setPadding(dpI(4), 0, dpI(4), 0);
            left.setOnClickListener(v -> { DeskPluginStore.move(this, pos, pos - 1); refreshCurrentPage(); });
            right.setOnClickListener(v -> { DeskPluginStore.move(this, pos, pos + 1); refreshCurrentPage(); });
            del.setOnClickListener(v -> { DeskPluginStore.remove(this, p.id); refreshCurrentPage(); toast("已删除 " + p.label); });
            ops.addView(left); ops.addView(del); ops.addView(right);
            cell.addView(ops, matchWrapTop(2));
            cell.setOnClickListener(v -> showRenameDialog(p));
        }
        return cell;
    }

    /** 拍立得照片卡（图1左屏叠放的白色相片，仅还原排列，用 emoji + 色块代替图片） */
    private View polaroidCard(String emoji, String label) {
        LinearLayout card = cardCol(t.card, dp(8));
        View photo = new View(this);
        photo.setBackground(shape(t.cardSoft, UiTokens.RADIUS_SM));
        photo.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(56)));
        card.addView(photo);
        TextView ic = tv(emoji, 22, t.text);
        ic.setGravity(Gravity.CENTER);
        // 叠加 emoji 到照片色块之上（用 FrameLayout）
        FrameLayout frame = new FrameLayout(this);
        frame.removeAllViews();
        frame.addView(photo);
        TextView em = tv(emoji, 26, t.text);
        em.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(56));
        elp.gravity = Gravity.CENTER;
        frame.addView(em, elp);
        // 用 frame 替换刚才的 photo
        card.removeView(photo);
        card.addView(frame, 0);
        card.addView(tv(label, 9, t.subtext), matchWrapTop(4));
        card.setOnClickListener(v -> toast(label + " · 点开可在守护页更改情感相册"));
        return card;
    }

    /** 便签卡（图1左下：无限象限 / 也许我们应该在自我热切的自由中执着。/ 19:27） */
    private View noteCard() {
        LinearLayout box = cardCol(t.card, dp(14));
        box.addView(tv("无限象限", 12, t.text));
        box.addView(tv("4月8日 · 星期三  🌤", 9, t.subtext), matchWrapTop(2));
        box.addView(tv("也许我们应该在\n自我热切的自由中执着。", 14, t.text), matchWrapTop(8));
        TextView time = tv("◷  19:27", 10, t.subtext);
        time.setTextColor(t.subtext);
        box.addView(time, matchWrapTop(10));
        box.setOnClickListener(v -> switchPage(PAGE_GUARD));
        return box;
    }

    /** 编辑操作条（进入编辑模式才显示：新增 / 保存 / 恢复默认） */
    private View buildEditBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        if (!editMode) {
            Button manage = new Button(this);
            manage.setText("✎ 管理插件（长按任意图标）");
            manage.setTextSize(11); manage.setAllCaps(false);
            manage.setBackground(shape(t.cardSoft, UiTokens.RADIUS_ROW));
            manage.setTextColor(t.subtext);
            manage.setOnClickListener(v -> { editMode = true; refreshCurrentPage(); toast("编辑中：点图标换图、点 ◀▶ 移动、点 ✕ 删除、点格子改名"); });
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
    // 图1 右屏：终端页
    // 大字时间 + 天气 → 周历 → 照片/唱片/照片 → 4图标行 → 一句话横条 → Dock
    // ============================================================
    private View buildTerminalPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgTop);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(16), dpI(28), dpI(16), dpI(96));

        // 顶部：大字时间（左）+ 天气（右）
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
        wx.setOnClickListener(v -> switchPage(PAGE_GUARD)); // 天气 → 守护页天气模块
        head.addView(wx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(head, marginB(16));

        // 周历卡（2026年4月 / SUN..SAT / 5 6 7 8 9 10 11）
        col.addView(buildWeekStrip(), matchWrap());
        col.addView(spacer(16));

        // 照片 / 唱片 / 照片 三卡行（图1右屏中部）
        col.addView(buildTrioRow(), matchWrap());
        col.addView(spacer(16));

        // 4 图标行（信笺 / 织梦 / 相册 / 泡泡）
        col.addView(buildTerminalIconRow(), matchWrap());
        col.addView(spacer(14));

        // 一句话横条（影像之外的蝴蝶 以什么形态致死绵延）
        col.addView(whisperBar(), matchWrap());

        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshClock();
        refreshDeskWeather();
        return sv;
    }

    private View buildWeekStrip() {
        LinearLayout box = cardCol(t.card, dp(16));
        TextView head = tv(new SimpleDateFormat("yyyy年M月", Locale.CHINA).format(new Date()), 11, t.subtext);
        box.addView(head);
        LinearLayout week = new LinearLayout(this);
        week.setOrientation(LinearLayout.HORIZONTAL);
        String[] dayNames = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
        java.util.Calendar cal = java.util.Calendar.getInstance(Locale.CHINA);
        int today = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1;
        cal.add(java.util.Calendar.DAY_OF_MONTH, -dow);
        for (int i = 0; i < 7; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            TextView d = tv(dayNames[i], 9, t.subtext); d.setGravity(Gravity.CENTER);
            int dayNum = cal.get(java.util.Calendar.DAY_OF_MONTH);
            boolean selected = (i == dow);
            TextView n = tv(String.valueOf(dayNum), 15, selected ? t.dark ? t.text : Color.WHITE : t.text);
            n.setGravity(Gravity.CENTER);
            if (selected) {
                n.setBackground(shape(t.primary, 40));
                n.setTypeface(Typeface.DEFAULT_BOLD);
            }
            cell.addView(d); cell.addView(n, matchWrapTop(4));
            week.addView(cell, weight1());
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        box.addView(week, matchWrapTop(10));
        box.setOnClickListener(v -> {
            Intent i = new Intent(this, PolicySettingsActivity.class);
            i.putExtra("section", "calendar"); startActivity(i);
        });
        return box;
    }

    /** 图1右屏中部：左照片卡 + 中黑胶唱片 + 右拍立得照片卡 */
    private View buildTrioRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 左：泛光照片卡
        LinearLayout leftPhoto = new LinearLayout(this);
        View ph = new View(this);
        ph.setBackground(shape(t.cardSoft, UiTokens.RADIUS_SM));
        ph.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(84)));
        leftPhoto.addView(ph);
        row.addView(leftPhoto, weight1());

        // 中：黑胶唱片（黑白同心圆）
        FrameLayout discWrap = new FrameLayout(this);
        int ds = dpI(96);
        View disc = new View(this);
        disc.setBackground(shape(t.dark ? Color.WHITE : Color.parseColor("#222222"), ds / 2f));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ds, ds); dlp.gravity = Gravity.CENTER;
        discWrap.addView(disc, dlp);
        View hole = new View(this);
        hole.setBackground(shape(t.dark ? Color.parseColor("#222222") : Color.WHITE, 40));
        int hs = ds / 3;
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(hs, hs); hlp.gravity = Gravity.CENTER;
        discWrap.addView(hole, hlp);
        ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f).setDuration(12000).start();
        discWrap.setOnClickListener(v -> switchPage(PAGE_MUSIC));
        row.addView(discWrap, weight1());

        // 右：拍立得照片卡
        row.addView(polaroidCard("🍽", "影像之外"), weight1());
        return row;
    }

    /** 图1右屏 4 图标行：信笺 / 织梦 / 相册 / 泡泡（每个可点开） */
    private View buildTerminalIconRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"信笺", "织梦", "相册", "泡泡"};
        String[] icons = {"✉", "▤", "♡", "○○"};
        String[] acts = {"page:guard", "page:classic", "app:com.android.gallery3d", "page:music"};
        for (int i = 0; i < names.length; i++) {
            LinearLayout cell = cardCol(t.card, dp(10));
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView ic = tv(icons[i], 22, t.primary);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(shape(t.cardSoft, UiTokens.RADIUS_SM));
            ic.setPadding(0, dpI(8), 0, dpI(8));
            cell.addView(ic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(44)));
            cell.addView(tv(names[i], 10, t.text), matchWrapTop(6));
            final String act = acts[i];
            final String nm = names[i];
            cell.setOnClickListener(v -> openAction(act, nm));
            row.addView(cell, weight1());
        }
        return row;
    }

    /** 图1右屏底部一句话横条：影像之外的蝴蝶 以什么形态致死绵延（可点开编辑） */
    private View whisperBar() {
        LinearLayout bar = cardRow(t.card, dp(12));
        TextView ic = tv("❝", 18, t.primary);
        bar.addView(ic);
        String words = AppPrefs.get(this).getString("desk_whisper", "影像之外的蝴蝶 以什么形态致死绵延");
        TextView w = tv(words, 13, t.text);
        w.setMaxLines(2); w.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(w, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.setOnClickListener(v -> {
            final android.widget.EditText et = new android.widget.EditText(this);
            et.setText(words);
            new android.app.AlertDialog.Builder(this).setTitle("编辑这句话")
                    .setView(et)
                    .setPositiveButton("保存", (d, x) -> {
                        AppPrefs.get(this).edit().putString("desk_whisper", et.getText().toString()).apply();
                        refreshCurrentPage(); toast("已保存");
                    })
                    .setNegativeButton("取消", null).show();
        });
        return bar;
    }

    // ============================================================
    // 守护页（图2功能项）→ 图1宫格卡片排列、去重、可点开编辑
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

        // 标题 + 头像（头像可点开更改）
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
        avatar.setOnClickListener(v -> {
            Intent i = new Intent(this, PolicySettingsActivity.class);
            i.putExtra("section", "profile"); startActivity(i);
        });
        titleRow.addView(avatar);
        col.addView(titleRow, marginB(16));

        // 宫格（图1样式，4 列），按 id 去重
        List<GuardItem> items = dedupGuard(guardItems());
        int columns = 4, idx = 0;
        while (idx < items.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < columns && idx < items.size(); c++, idx++)
                row.addView(guardCell(items.get(idx)), weight1());
            col.addView(row, marginB(10));
        }

        // 底部：更改情感相册 / 打开完整设置
        Button album = new Button(this);
        album.setText("更改情感相册"); album.setTextSize(12); album.setAllCaps(false);
        album.setBackground(shape(t.primarySoft, UiTokens.RADIUS_ROW)); album.setTextColor(t.text);
        album.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class); i.putExtra("open_section", "diary_backup"); startActivity(i);
        });
        col.addView(album, matchWrapTop(16));
        Button more = new Button(this);
        more.setText("打开完整功能与设置"); more.setTextSize(12); more.setAllCaps(false);
        more.setBackground(shape(t.cardSoft, UiTokens.RADIUS_ROW)); more.setTextColor(t.subtext);
        more.setOnClickListener(v -> switchPage(PAGE_CLASSIC));
        col.addView(more, matchWrapTop(8));

        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** 守护项宫格卡：图标 + 名称 + 小开关；点卡片进编辑，开关即时保存 */
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
        cell.setOnClickListener(v -> openGuardEdit(it));
        return cell;
    }

    private void openGuardEdit(GuardItem it) {
        Intent i = new Intent(this, PolicySettingsActivity.class);
        i.putExtra("section", it.section);
        startActivity(i);
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

    /** 图2 守护功能项（去重后即全部插件来源），按分组顺序 */
    private List<GuardItem> guardItems() {
        List<GuardItem> l = new ArrayList<>();
        // 查岗与管束
        l.add(new GuardItem("查岗拉回", "查岗周期与分级拉回", "◎", "checkin", "desk_guard_checkin", true));
        l.add(new GuardItem("分心强拉回", "来回切 App 算分心与阈值分级", "⊘", "checkin", "desk_guard_distraction", true));
        l.add(new GuardItem("训诫", "小圈管束，温柔 / 严厉两种语气", "✂", "discipline", "desk_guard_discipline", true));
        // 情绪与陪伴
        l.add(new GuardItem("情绪感知", "依据状态产生情绪与想你程度", "♡", "emotion", "desk_guard_emotion", true));
        l.add(new GuardItem("主动找用户", "太久没消息主动弹窗", "❀", "initiative", "desk_guard_initiative", true));
        // 管束与提醒
        l.add(new GuardItem("应用门禁", "锁 App 时长，自主添加受限应用", "⚑", "appgate", "desk_guard_appgate", true));
        l.add(new GuardItem("主动提醒", "电量/屏幕/喝水/休息/周期", "⏰", "reminder", "desk_guard_reminder", true));
        l.add(new GuardItem("专注模式", "全机专注、应急放行与留言", "◷", "focus", "desk_guard_focus", false));
        // 日子与作息
        l.add(new GuardItem("守护日历", "纪念日、节日与倒数日", "▦", "calendar", "desk_guard_calendar", true));
        l.add(new GuardItem("上课作息", "上课静默守护与停提醒", "▤", "schedule", "desk_guard_schedule", true));
        l.add(new GuardItem("共同记忆", "双方可改的共享记忆", "★", "memory", "desk_guard_memory", true));
        l.add(new GuardItem("识图感知", "从当前屏幕读取文字", "☷", "ocr", "desk_guard_ocr", true));
        l.add(new GuardItem("插件分类", "全部扩展能力归类总览", "✦", "plugins", "desk_guard_plugins", true));
        // 地点与设备
        l.add(new GuardItem("天气地区", "家与常用地点，坐标优先", "☁", "weather", "desk_guard_weather", true));
        l.add(new GuardItem("回家模式", "回来时打开熟悉应用", "⌂", "homemode", "desk_guard_homemode", false));
        l.add(new GuardItem("归电设置", "间隔/冷却/安静时段", "⚡", "guidian", "desk_guard_guidian", true));
        // 安全与备份
        l.add(new GuardItem("屏幕日记", "记录屏幕使用与今日轨迹", "▧", "checkin", "desk_guard_screen_diary", true));
        l.add(new GuardItem("自拍记录", "定时记录此刻的自己", "☻", "reminder", "desk_guard_selfie", true));
        l.add(new GuardItem("保密模式", "隐私与记录的保护", "⚙", "checkin", "desk_guard_private", true));
        l.add(new GuardItem("备份相册", "日记本与相册导出备份", "▥", "memory", "desk_guard_backup", true));
        return l;
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
        // 图1 Dock：世界 / 终端 / 主题 / 设置
        String[] labels = {"世界", "终端", "主题", "设置"};
        final int[] pageOf = {PAGE_WORLD, PAGE_TERMINAL, -1 /*主题弹窗*/, PAGE_GUARD};
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

    /** 重绘当前页（编辑 / 换主题 / 换图标后） */
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
                Intent i = new Intent(this, PolicySettingsActivity.class);
                i.putExtra("section", a.substring(7)); startActivity(i);
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
        if (worldClock != null) worldClock.setText(time);
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
