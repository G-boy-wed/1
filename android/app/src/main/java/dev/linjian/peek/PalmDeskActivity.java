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
import java.util.List;
import java.util.Locale;

/**
 * 掌心窗「图1桌面」主入口。
 *
 * 单 Activity 三页，用底部 Dock 切换：
 *  - DESK   图1：顶部音乐胶囊 + 大字时间/天气/周历 + 3 列插件宫格 + 照片卡 + Dock
 *  - GUARD  图2：守护标签（分组 + 每行图标/标题/描述/开关，点击进编辑）
 *  - MUSIC  网易云同款「一起听」：封面/进度/位置/头像/时间/在线弹幕
 *
 * 只还原排列结构（用户明确「只需排列，不参考颜色」）；配色统一从 UITheme/UiTokens 取。
 * 插件增删改排序换图标由 {@link DeskPluginStore} 持久化，编辑模式简单明了、不频繁打扰。
 */
public class PalmDeskActivity extends Activity {
    private static final String TAG = "PalmDesk";

    private static final int PAGE_DESK = 0, PAGE_GUARD = 1, PAGE_MUSIC = 2, PAGE_CLASSIC = 3;
    private int currentPage = PAGE_DESK;
    private boolean editMode = false;

    private UITheme t;
    private LinearLayout dock;
    private FrameLayout pages;
    private View deskView, guardView, musicView;
    private TextView deskClock, deskWeather, deskDate;
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
        switchPage(PAGE_DESK);
        clock.post(clockTick);
    }

    @Override protected void onDestroy() {
        clock.removeCallbacks(clockTick);
        super.onDestroy();
    }

    // ============================ 图1 桌面 ============================
    private View buildDeskPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgTop);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(18), dpI(30), dpI(18), dpI(96));

        // --- 顶部：音乐胶囊 + 大字时间/天气 ---
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(musicCapsule(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);
        timeBox.setGravity(Gravity.END);
        deskClock = tv("", UiTokens.FONT_DISPLAY, t.text);
        deskClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        deskClock.setGravity(Gravity.END);
        deskWeather = tv("☁ --°", UiTokens.FONT_VALUE, t.subtext);
        deskWeather.setGravity(Gravity.END);
        timeBox.addView(deskClock);
        timeBox.addView(deskWeather);
        top.addView(timeBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(top, marginB(18));

        // --- 周历条 ---
        col.addView(buildWeekStrip(), matchWrap());
        col.addView(spacer(14));

        // --- 照片装饰卡（两张叠放，仅排列） ---
        col.addView(buildPhotoRow(), matchWrap());
        col.addView(spacer(14));

        // --- 插件宫格（3 列） ---
        TextView gridLabel = tv("✦  我的插件" + (editMode ? " · 编辑中" : "  （长按进入编辑）"), UiTokens.FONT_VALUE, t.subtext);
        gridLabel.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(gridLabel, marginB(10));
        col.addView(buildPluginGrid(), matchWrap());

        // --- 便签卡 ---
        col.addView(buildNoteCard(), matchWrapTop(16));

        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshClock();
        refreshDeskWeather();
        return sv;
    }

    private View musicCapsule() {
        LinearLayout cap = cardRow(t.card, dp(14));
        cap.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cover = new ImageView(this);
        cover.setImageResource(R.drawable.decor_guidian_butterfly);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int cs = dpI(46);
        cover.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        cover.setBackground(shape(t.primary, UiTokens.RADIUS_SM));
        cap.addView(cover);
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv("CosmicLove", 13, t.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = tv("掌心窗 · 一起听", 10, t.subtext);
        sub.setMaxLines(1); sub.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(title); meta.addView(sub);
        cap.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView prev = tv("◀", 16, t.text); prev.setPadding(dpI(8), 0, dpI(6), 0);
        TextView play = tv("▶", 18, t.text);
        TextView next = tv("▶|", 16, t.text); next.setPadding(dpI(6), 0, dpI(4), 0);
        cap.addView(prev); cap.addView(play); cap.addView(next);
        cap.setOnClickListener(v -> switchPage(PAGE_MUSIC));
        prev.setOnClickListener(v -> toast("上一首（系统媒体会话）"));
        play.setOnClickListener(v -> toast("播放 / 暂停（系统媒体会话）"));
        next.setOnClickListener(v -> toast("下一首（系统媒体会话）"));
        return cap;
    }

    private View buildWeekStrip() {
        LinearLayout box = cardCol(t.card, dp(18));
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
            TextView n = tv(String.valueOf(dayNum), 15, i == dow ? t.primary : t.text);
            n.setGravity(Gravity.CENTER);
            if (i == dow) n.setTypeface(Typeface.DEFAULT_BOLD);
            cell.addView(d); cell.addView(n);
            week.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
        box.addView(week, matchWrapTop(6));
        return box;
    }

    private View buildPhotoRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] imgs = {R.drawable.decor_guard_hug, R.drawable.decor_guidian_rose};
        String[] labels = {"此刻的窗", "想你的花"};
        for (int i = 0; i < imgs.length; i++) {
            LinearLayout card = cardCol(t.cardSoft, dp(14));
            ImageView iv = new ImageView(this);
            iv.setImageResource(imgs[i]);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(84)));
            card.addView(iv);
            card.addView(tv(labels[i], 11, t.text), matchWrapTop(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : dpI(8), 0, i == 0 ? dpI(8) : 0, 0);
            row.addView(card, lp);
        }
        return row;
    }

    private View buildPluginGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        List<DeskPluginStore.Plugin> plugins = DeskPluginStore.all(this);
        int cols = 3;
        for (int i = 0; i < plugins.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols && i + c < plugins.size(); c++) {
                final DeskPluginStore.Plugin p = plugins.get(i + c);
                final int idx = i + c;
                row.addView(pluginCell(p, idx), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            grid.addView(row, marginB(10));
        }
        Button add = new Button(this);
        add.setText(editMode ? "＋ 新增插件" : "＋ 管理插件（进入编辑）");
        add.setTextSize(12);
        add.setBackground(shape(t.primary, UiTokens.RADIUS_ROW));
        add.setTextColor(t.dark ? t.text : Color.WHITE);
        add.setOnClickListener(v -> {
            if (editMode) showAddPluginDialog();
            else { editMode = true; rebuildDesk(); toast("编辑模式：点图标换图标、点箭头移动、点 × 删除"); }
        });
        grid.addView(add, matchWrapTop(6));
        if (editMode) {
            Button save = new Button(this);
            save.setText("✓ 保存并退出编辑");
            save.setTextSize(12);
            save.setBackground(shape(t.primarySoft, UiTokens.RADIUS_ROW));
            save.setTextColor(t.text);
            save.setOnClickListener(v -> {
                editMode = false; rebuildDesk(); toast("已保存");
            });
            Button reset = new Button(this);
            reset.setText("↺ 恢复默认排列");
            reset.setTextSize(12);
            reset.setBackground(shape(t.cardSoft, UiTokens.RADIUS_ROW));
            reset.setTextColor(t.subtext);
            reset.setOnClickListener(v -> {
                DeskPluginStore.reset(this); editMode = false; rebuildDesk(); toast("已恢复默认排列");
            });
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            actions.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            grid.addView(actions, matchWrapTop(6));
        }
        return grid;
    }

    private View pluginCell(final DeskPluginStore.Plugin p, final int idx) {
        LinearLayout cell = cardCol(t.card, dp(16));
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView icon = tv(p.icon, 26, t.primary);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(t.cardSoft, UiTokens.RADIUS_SM));
        icon.setPadding(0, dpI(8), 0, dpI(8));
        cell.addView(icon, new LinearLayout.LayoutParams(dpI(48), dpI(48)));
        TextView name = tv(p.label, 12, t.text);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(name, matchWrapTop(6));
        if (!editMode) {
            cell.setOnClickListener(v -> openPlugin(p));
            cell.setOnLongClickListener(v -> { editMode = true; rebuildDesk(); return true; });
        } else {
            icon.setOnClickListener(v -> cycleIcon(p));
            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            TextView left = tv("◀", 13, t.subtext);
            TextView del = tv("✕", 13, t.danger);
            TextView right = tv("▶", 13, t.subtext);
            left.setPadding(dpI(6), 0, dpI(6), 0); right.setPadding(dpI(6), 0, dpI(6), 0);
            ops.setGravity(Gravity.CENTER);
            left.setOnClickListener(v -> { DeskPluginStore.move(this, idx, idx - 1); rebuildDesk(); });
            right.setOnClickListener(v -> { DeskPluginStore.move(this, idx, idx + 1); rebuildDesk(); });
            del.setOnClickListener(v -> { DeskPluginStore.remove(this, p.id); rebuildDesk(); toast("已删除 " + p.label); });
            ops.addView(left); ops.addView(del); ops.addView(right);
            cell.addView(ops, matchWrapTop(2));
            cell.setOnClickListener(v -> showRenameDialog(p));
        }
        return cell;
    }

    private void cycleIcon(final DeskPluginStore.Plugin p) {
        String[] icons = DeskPluginStore.ICONS;
        int cur = 0;
        for (int i = 0; i < icons.length; i++) if (icons[i].equals(p.icon)) { cur = i; break; }
        String next = icons[(cur + 1) % icons.length];
        DeskPluginStore.update(this, p.id, p.label, next, p.action);
        rebuildDesk();
        toast("图标已换为 " + next + "（继续点可循环）");
    }

    private View buildNoteCard() {
        LinearLayout box = cardCol(t.card, dp(16));
        box.addView(tv("便签 · 今天想说的话", 11, t.subtext));
        box.addView(tv("把今天看见的你，轻轻写下来。点开守护与陪伴，慢慢说给我听。", 13, t.text), matchWrapTop(6));
        box.setOnClickListener(v -> switchPage(PAGE_GUARD));
        return box;
    }

    // ============================ 图2 守护标签 ============================
    private static class GuardItem {
        String group, name, desc, icon, section;
        boolean on;
        String prefKey;
        GuardItem(String g, String n, String d, String ic, String sec, String pref) {
            group = g; name = n; desc = d; icon = ic; section = sec; prefKey = pref;
        }
    }

    private View buildGuardPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgTop);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(18), dpI(30), dpI(18), dpI(96));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(tv("Toujours & jamais et éternellement", 16, t.text));
        titleBox.addView(tv("守护设置页 · 每一项都能点开更改", 11, t.subtext), matchWrapTop(2));
        titleRow.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView avatar = tv("♡", 20, t.text);
        avatar.setBackground(shape(t.primarySoft, 40));
        avatar.setGravity(Gravity.CENTER);
        int av = dpI(40); avatar.setLayoutParams(new LinearLayout.LayoutParams(av, av));
        avatar.setOnClickListener(v -> toast("点击头像可更改陪伴对象（在完整设置里）"));
        titleRow.addView(avatar);
        col.addView(titleRow, marginB(14));

        List<GuardItem> items = guardItems();
        String lastGroup = "";
        for (final GuardItem it : items) {
            if (!it.group.equals(lastGroup)) {
                lastGroup = it.group;
                TextView g = tv(it.group, 11, t.subtext);
                g.setTypeface(Typeface.DEFAULT_BOLD);
                col.addView(g, matchWrapTop(it.group.equals(items.get(0).group) ? 4 : 14));
            }
            col.addView(guardRow(it), matchWrapTop(6));
        }
        Button changeAlbum = new Button(this);
        changeAlbum.setText("更改情感相册");
        changeAlbum.setTextSize(12);
        changeAlbum.setBackground(shape(t.primarySoft, UiTokens.RADIUS_ROW));
        changeAlbum.setTextColor(t.text);
        changeAlbum.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("open_section", "diary_backup");
            startActivity(i);
        });
        col.addView(changeAlbum, matchWrapTop(18));
        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private View guardRow(final GuardItem it) {
        LinearLayout row = cardRow(t.card, dp(16));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = tv(it.icon, 20, t.primary);
        ic.setBackground(shape(t.cardSoft, UiTokens.RADIUS_SM));
        ic.setGravity(Gravity.CENTER);
        int s = dpI(38); ic.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        row.addView(ic);
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        TextView n = tv(it.name, 14, t.text); n.setTypeface(Typeface.DEFAULT_BOLD);
        TextView d = tv(it.desc, 10, t.subtext); d.setMaxLines(2); d.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(n); meta.addView(d);
        meta.setOnClickListener(v -> openGuardEdit(it));
        row.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch sw = new Switch(this);
        it.on = AppPrefs.get(this).getBoolean(it.prefKey, it.on);
        sw.setChecked(it.on);
        sw.setOnCheckedChangeListener((b, on) -> {
            AppPrefs.get(this).edit().putBoolean(it.prefKey, on).apply();
            toast(it.name + (on ? " 已开启" : " 已关闭"));
        });
        row.addView(sw);
        row.setOnClickListener(v -> openGuardEdit(it));
        return row;
    }

    private void openGuardEdit(GuardItem it) {
        Intent i = new Intent(this, PolicySettingsActivity.class);
        i.putExtra("section", it.section);
        startActivity(i);
    }

    private List<GuardItem> guardItems() {
        List<GuardItem> l = new ArrayList<>();
        // 基础守护
        l.add(new GuardItem("基础守护", "屏幕日记", "记录屏幕使用与今日轨迹", "▤", "checkin", "desk_guard_screen_diary"));
        l.add(new GuardItem("基础守护", "外勤报告", "到访与外勤的汇总报告", "⚑", "checkin", "desk_guard_visit_report"));
        l.add(new GuardItem("基础守护", "email", "邮件与通知的提醒汇总", "✉", "reminder", "desk_guard_email"));
        // 情感守护
        l.add(new GuardItem("情感守护", "情绪感知", "依据状态自动产生情绪与想你程度", "♡", "emotion", "desk_guard_emotion"));
        l.add(new GuardItem("情感守护", "五星保护", "高等级保护与主动找用户", "★", "initiative", "desk_guard_fivestar"));
        // 安全守护
        l.add(new GuardItem("安全守护", "应用门禁", "锁定指定 App，到点拦截", "⚑", "appgate", "desk_guard_appgate"));
        l.add(new GuardItem("安全守护", "专注模式", "全机专注、应急放行与留言", "◷", "focus", "desk_guard_focus"));
        // 设备行为
        l.add(new GuardItem("设备行为", "数字日历", "纪念日、节日与倒数日", "▦", "calendar", "desk_guard_calendar"));
        l.add(new GuardItem("设备行为", "防止夜", "睡前息屏与深夜提醒", "☾", "reminder", "desk_guard_night"));
        l.add(new GuardItem("设备行为", "自拍记录", "定时记录此刻的自己", "☻", "reminder", "desk_guard_selfie"));
        l.add(new GuardItem("设备行为", "抓取辅助", "识图读取屏幕文字与状态", "☷", "ocr", "desk_guard_ocr"));
        l.add(new GuardItem("设备行为", "情感分类", "情绪标签与分类管理", "❀", "emotion", "desk_guard_emotion_class"));
        // 结束会话后 / 其他
        l.add(new GuardItem("结束会话后", "天气模块", "当前位置的天气与提醒", "☁", "weather", "desk_guard_weather"));
        l.add(new GuardItem("结束会话后", "保密模式", "隐私与记录的保护", "⚙", "checkin", "desk_guard_private"));
        l.add(new GuardItem("结束会话后", "备份相册", "日记本与相册的导出备份", "▤", "memory", "desk_guard_backup"));
        return l;
    }

    // ============================ 一起听（网易云同款） ============================
    private View musicViewRef;
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
        pages.removeView(sv);
        // 后台取歌，回来渲染
        new Thread(() -> {
            FreeMusic.Result res = FreeMusic.hot(12);
            runOnUiThread(() -> renderMusic(col, loading, res));
        }).start();
    }

    private void renderMusic(LinearLayout col, TextView loading, FreeMusic.Result res) {
        col.removeView(loading);
        // 本机正在播放（主卡片）
        JSONObject media = null;
        try { media = MediaState.collect(this); } catch (Exception ignored) { }
        FreeMusic.Song now = (media != null && media.optBoolean("available"))
                ? FreeMusic.fromMediaState(media)
                : (!res.songs.isEmpty() ? res.songs.get(0) : new FreeMusic.Song());

        LinearLayout nowCard = cardCol(t.card, dp(20));
        nowCard.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView disc = new ImageView(this);
        disc.setImageResource(R.drawable.decor_guard_hug);
        disc.setScaleType(ImageView.ScaleType.CENTER_CROP);
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
        bar.setMax(100);
        bar.setProgress(media != null ? media.optInt("progress_percent", 0) : 18);
        nowCard.addView(bar, matchWrapTop(12));
        // 位置 + 时间
        String where = currentPlaceLabel();
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        TextView meta = tv("◉ " + where + "   ⏱ " + time + " 起一起听", 11, t.subtext);
        meta.setGravity(Gravity.CENTER);
        nowCard.addView(meta, matchWrapTop(10));
        // 参与者头像
        nowCard.addView(buildAvatars(), matchWrapTop(12));
        col.addView(nowCard, matchWrapTop(10));

        if (!res.ok) {
            TextView warn = tv(res.error, 11, t.danger);
            col.addView(warn, matchWrapTop(10));
        } else {
            TextView src = tv("在线曲库 · " + res.source.replace("https://", ""), 10, t.subtext);
            col.addView(src, matchWrapTop(10));
            TextView listTitle = tv("热门歌曲（点选展示）", 13, t.text);
            listTitle.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(listTitle, matchWrapTop(14));
            for (int i = 0; i < res.songs.size(); i++) {
                final FreeMusic.Song s = res.songs.get(i);
                LinearLayout row = cardRow(t.cardSoft, dp(12));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(tv(String.valueOf(i + 1), 12, t.subtext));
                LinearLayout m = new LinearLayout(this);
                m.setOrientation(LinearLayout.VERTICAL);
                String nm = tv(s.name, 13, t.text).getText().toString();
                TextView n = tv(nm, 13, t.text);
                TextView a = tv(s.artist.isEmpty() ? "未知歌手" : s.artist, 10, t.subtext);
                m.addView(n); m.addView(a);
                row.addView(m, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tv("♫", 16, t.primary));
                final FreeMusic.Song picked = s;
                row.setOnClickListener(v -> {
                    songName.setText(picked.name);
                    artist.setText(picked.artist.isEmpty() ? "未知歌手" : picked.artist);
                    bar.setProgress(0);
                    toast("已选：" + picked.name + "（播放跟随系统媒体会话）");
                });
                col.addView(row, marginB(6));
            }
        }
    }

    private View buildAvatars() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String[] names = {AppPrefs.userName(this), AppPrefs.companionName(this), "我", "TA"};
        int[] colors = {t.primary, t.primarySoft, t.cardSoft, t.primarySoft};
        for (int i = 0; i < names.length; i++) {
            TextView a = tv(names[i].isEmpty() ? "?" : names[i].substring(0, 1), 14, t.dark ? t.text : Color.WHITE);
            a.setGravity(Gravity.CENTER);
            a.setBackground(shape(colors[i], 40));
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

    // ============================ 导航 / 工具 ============================
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
        String[] labels = {"桌面", "守护", "一起听", "我的"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_shield, icMusicRes(), R.drawable.ic_settings};
        final int[] pages = {PAGE_DESK, PAGE_GUARD, PAGE_MUSIC, PAGE_CLASSIC};
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this);
            b.setText(icons[i] == 0 ? labels[i] : labels[i]);
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setBackground(t.navItem(i == currentPage));
            b.setTextColor(t.text);
            final int pg = pages[i];
            b.setOnClickListener(v -> switchPage(pg));
            bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return bar;
    }

    private int icMusicRes() { return R.drawable.ic_heart_wave; }

    private void switchPage(int page) {
        if (page == PAGE_CLASSIC) {
            startActivity(new Intent(this, MainActivity.class));
            return;
        }
        currentPage = page;
        for (int i = pages.getChildCount() - 1; i >= 0; i--) pages.removeViewAt(i);
        if (page == PAGE_DESK) {
            deskView = buildDeskPage();
            pages.addView(deskView);
        } else if (page == PAGE_GUARD) {
            guardView = buildGuardPage();
            pages.addView(guardView);
        } else if (page == PAGE_MUSIC) {
            buildMusicPageAsync();
            pages.addView(musicViewRef);
        }
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

    private void rebuildDesk() {
        if (currentPage == PAGE_DESK) switchPage(PAGE_DESK);
    }

    private void openPlugin(DeskPluginStore.Plugin p) {
        String a = p.action;
        try {
            if (a.startsWith("page:")) {
                String pg = a.substring(5);
                if ("music".equals(pg)) switchPage(PAGE_MUSIC);
                else if ("guard".equals(pg)) switchPage(PAGE_GUARD);
                else if ("classic".equals(pg)) switchPage(PAGE_CLASSIC);
                else toast("页面：" + pg);
            } else if (a.startsWith("policy:")) {
                Intent i = new Intent(this, PolicySettingsActivity.class);
                i.putExtra("section", a.substring(7));
                startActivity(i);
            } else if (a.startsWith("app:")) {
                Intent i = getPackageManager().getLaunchIntentForPackage(a.substring(4));
                if (i != null) startActivity(i); else toast("本机未安装该应用");
            } else {
                toast(p.label + " · " + (p.desc.isEmpty() ? "掌心窗插件" : p.desc));
            }
        } catch (Exception e) {
            toast("打开失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private void showAddPluginDialog() {
        final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("新增插件");
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpI(20), dpI(12), dpI(20), dpI(4));
        final android.widget.EditText name = new android.widget.EditText(this);
        name.setHint("插件名称");
        final android.widget.EditText action = new android.widget.EditText(this);
        action.setHint("动作：policy:calendar / page:music / app:包名（可留空）");
        box.addView(name); box.addView(action);
        b.setView(box);
        b.setPositiveButton("添加", (d, w) -> {
            DeskPluginStore.add(this, name.getText().toString(), "✦", action.getText().toString());
            rebuildDesk(); toast("已添加");
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void showRenameDialog(final DeskPluginStore.Plugin p) {
        final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("编辑插件：" + p.label);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpI(20), dpI(12), dpI(20), dpI(4));
        final android.widget.EditText name = new android.widget.EditText(this);
        name.setText(p.label);
        final android.widget.EditText action = new android.widget.EditText(this);
        action.setText(p.action);
        box.addView(name); box.addView(action);
        b.setView(box);
        b.setPositiveButton("保存", (d, w) -> {
            DeskPluginStore.update(this, p.id, name.getText().toString(), p.icon, action.getText().toString());
            rebuildDesk(); toast("已保存");
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void refreshClock() {
        if (deskClock == null) return;
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        deskClock.setText(time);
        deskDate = tv(new SimpleDateFormat("M月d日 · EEEE", Locale.CHINA).format(new Date()), 12, t.subtext);
        deskWeather.setText("☁ " + weatherShort());
    }

    private String weatherShort() {
        try {
            JSONObject cur = WeatherState.currentLocation(this);
            String city = cur == null ? "" : cur.optString("city", "");
            String district = cur == null ? "" : cur.optString("district", "");
            JSONObject live = WeatherLive.cached(this, city, district);
            if (live != null && live.optBoolean("ok")) return live.optInt("temperature", 0) + "° " + live.optString("condition", "");
            double[] loc = NowState.lastKnownLocation(this);
            if (loc != null) return "已定位 · 点守护页天气模块刷新";
        } catch (Exception ignored) { }
        return "--° 点守护页天气模块刷新";
    }

    private void refreshDeskWeather() {
        try {
            JSONObject cur = WeatherState.currentLocation(this);
            if (cur == null) return;
            double lat = cur.optDouble("latitude", 0), lon = cur.optDouble("longitude", 0);
            String city = cur.optString("city", ""), district = cur.optString("district", "");
            if ((lat != 0 || lon != 0) && !WeatherLive.isFresh(this, city, district, 45L * 60L * 1000L)) {
                WeatherLive.refreshAsync(this, city, district, lat, lon, w -> runOnUiThread(this::refreshClock));
            }
        } catch (Exception ignored) { }
    }

    // ---- UI 小工具 ----
    private TextView tv(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s == null ? "" : s);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }
    private int dpI(float v) { return UiTokens.dpI(v); }
    private float dp(float v) { return UiTokens.dp(v); }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
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
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private LinearLayout.LayoutParams matchWrapTop(int dpTop) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dpI(dpTop);
        return lp;
    }
    private LinearLayout.LayoutParams marginB(int dpB) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dpI(dpB);
        return lp;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
