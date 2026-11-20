package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 掌心窗主入口 —— 参考图「三页横滑」美化布局，内容全部来自掌心窗真实插件。
 *
 * 【布局严格照图，内容不用图里的假软件名】
 *  - 个人页：封面大卡(可贴图) + 圆头像(可贴图) + 名字/@账号/简介/地区(可改) +
 *            2×2 真实插件宫格 + About me 卡(可贴图) + 搜索栏。
 *  - 相册页：返回/更多 + N Photo Selected + 3 张照片墙(可贴图) + 4 好友头像(可贴图) +
 *            2×2 真实插件宫格 + New List 卡 + 搜索栏。
 *  - 动态页：点状背景卡 + 头像(可贴图) + 标题/日期 + 对话气泡 + 输入框 +
 *            真实插件宫格 + 搜索栏。
 *  三页共性：每页可自定义背景(图/纯色)，底部 4 图标 Dock（真实插件，可点开/可改）。
 *
 * 【数据唯一来源】DeskPluginStore（20 个真实插件，去重后按顺序无重复铺满三页 + Dock）。
 * 每个图标：点按=打开使用；长按=改名/换图标/改动作/移动/删除。皮肤存 DeskSkinStore。
 *
 * 【无旧 UI / 无假数据】不跳旧 MainActivity；天气/时间/位置取真实状态；深链走白名单。
 */
public class PalmDeskActivity extends Activity {
    private static final int PAGE_PROFILE = 0;
    private static final int PAGE_ALBUM = 1;
    private static final int PAGE_FEED = 2;
    private static final int PAGE_COUNT = 3;
    private int currentPage = PAGE_PROFILE;

    private static final Set<String> VALID_SECTIONS = new HashSet<>(Arrays.asList(
            "checkin", "distraction", "discipline", "emotion", "initiative",
            "appgate", "reminder", "schedule", "memory", "ocr", "weather",
            "homemode", "global", "permissions", "debug", "calendar", "focus", "guidian"));

    private UITheme t;
    private int screenW;
    private HorizontalScrollView hsv;
    private LinearLayout track;
    private final View[] pages = new View[PAGE_COUNT];
    private LinearLayout dock;
    private TextView feedClock, feedDate;
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
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        screenW = dm.widthPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bgTop);

        hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PAGE_COUNT; i++) {
            View page = buildPage(i);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(screenW, ViewGroup.LayoutParams.MATCH_PARENT);
            page.setLayoutParams(pp);
            pages[i] = page;
            track.addView(page);
        }
        hsv.addView(track, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hsv.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP
                    || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) snapPage();
            return false;
        });
        root.addView(hsv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dock = buildDock();
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.gravity = Gravity.BOTTOM;
        root.addView(dock, dp);

        setContentView(root);
        clock.post(clockTick);
        refreshDeskWeather();
    }

    @Override protected void onDestroy() {
        clock.removeCallbacks(clockTick);
        super.onDestroy();
    }

    /** 横滑结束吸附到最近一页。 */
    private void snapPage() {
        int page = Math.round(hsv.getScrollX() / (float) Math.max(1, screenW));
        page = Math.max(0, Math.min(PAGE_COUNT - 1, page));
        currentPage = page;
        hsv.smoothScrollTo(page * screenW, 0);
    }

    private void goPage(int page) {
        page = Math.max(0, Math.min(PAGE_COUNT - 1, page));
        currentPage = page;
        hsv.smoothScrollTo(page * screenW, 0);
    }

    private View buildPage(int index) {
        FrameLayout shell = new FrameLayout(this);
        shell.addView(new SkinBg(this, index), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.TRANSPARENT);
        sv.setFillViewport(true);
        LinearLayout content;
        if (index == PAGE_PROFILE) content = buildProfilePage();
        else if (index == PAGE_ALBUM) content = buildAlbumPage();
        else content = buildFeedPage();
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return shell;
    }

    /** 重建某一页（编辑/换图后调用）。 */
    private void rebuildPage(int index) {
        if (index < 0 || index >= PAGE_COUNT) return;
        View fresh = buildPage(index);
        fresh.setLayoutParams(new LinearLayout.LayoutParams(screenW, ViewGroup.LayoutParams.MATCH_PARENT));
        track.removeViewAt(index);
        track.addView(fresh, index);
        pages[index] = fresh;
    }

    private void rebuildAll() {
        for (int i = 0; i < PAGE_COUNT; i++) rebuildPage(i);
        rebuildDock();
        hsv.scrollTo(currentPage * screenW, 0);
    }

    // ============================================================
    // 背景：默认柔绿渐变 + 星点；可被用户自定义为图片或纯色
    // ============================================================
    private class SkinBg extends View {
        private final int page;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bgBitmap;
        private final float[][] stars = {
                {0.08f,0.11f,3},{0.21f,0.19f,2},{0.34f,0.08f,2.4f},{0.49f,0.15f,2},
                {0.63f,0.09f,3},{0.78f,0.21f,2},{0.91f,0.13f,2.4f},{0.14f,0.38f,2},
                {0.44f,0.31f,2.2f},{0.58f,0.44f,2.6f},{0.87f,0.36f,2},{0.29f,0.56f,2.4f},
                {0.71f,0.60f,2},{0.10f,0.68f,2},{0.94f,0.66f,2.4f},{0.52f,0.72f,2}
        };
        SkinBg(Context c, int page) {
            super(c);
            this.page = page;
            String uri = DeskSkinStore.pageBgUri(c, page);
            if (!uri.isEmpty()) {
                try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
                    bgBitmap = BitmapFactory.decodeStream(in);
                } catch (Exception ignored) { bgBitmap = null; }
            }
        }
        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int custom = DeskSkinStore.pageBgColor(PalmDeskActivity.this, page);
            if (custom != 0) {
                cv.drawColor(custom);
            } else {
                int center = UiTokens.blend(t.bgTop, t.dark ? 0xFF2C352E : 0xFFE3EFDE, t.dark ? 0.30f : 0.60f);
                RadialGradient rg = new RadialGradient(w * 0.42f, h * 0.26f, Math.max(w, h) * 0.95f,
                        center, t.bgTop, Shader.TileMode.CLAMP);
                paint.setShader(rg);
                cv.drawRect(0, 0, w, h, paint);
                paint.setShader(null);
                paint.setColor(t.dark ? 0x44FFFFFF : 0x99FFFFFF);
                for (float[] s : stars) cv.drawCircle(s[0] * w, s[1] * h, dp(s[2]), paint);
            }
            if (bgBitmap != null && !bgBitmap.isRecycled()) {
                float scale = Math.max((float) w / bgBitmap.getWidth(), (float) h / bgBitmap.getHeight());
                float bw = bgBitmap.getWidth() * scale, bh = bgBitmap.getHeight() * scale;
                cv.drawBitmap(bgBitmap, null,
                        new android.graphics.RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), paint);
                paint.setColor(t.dark ? 0x99000000 : 0x59FFFFFF);
                cv.drawRect(0, 0, w, h, paint);
            }
        }
    }

    // ============================================================
    // 屏 1：个人页
    // ============================================================
    private LinearLayout buildProfilePage() {
        LinearLayout col = column();
        // 封面大卡（可贴图）
        FrameLayout cover = new FrameLayout(this);
        cover.setLayoutParams(matchWrap());
        SoftAvatarView coverImg = sticker(DeskSkinStore.S_COVER, false, UiTokens.RADIUS_CARD, t.cardSoft, t.line);
        cover.addView(coverImg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(180)));
        // 圆形头像（可贴图），压在封面下沿
        SoftAvatarView avatar = sticker(DeskSkinStore.S_AVATAR, true, 0, t.card, t.line);
        FrameLayout.LayoutParams avp = new FrameLayout.LayoutParams(dpI(84), dpI(84));
        avp.gravity = Gravity.CENTER_HORIZONTAL;
        avp.topMargin = dpI(138);
        cover.addView(avatar, avp);
        col.addView(cover, marginB(14));

        // 信息卡：名字 / @账号 / 简介 / 地区
        LinearLayout info = cardCol(t.card, dpI(18));
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        String name = DeskSkinStore.text(this, "name", "掌心窗");
        String account = DeskSkinStore.text(this, "account", "@" + "我的掌心窗");
        String bio = DeskSkinStore.text(this, "bio", "在这里，记录我们的一天");
        info.addView(editTextRow(name, 20, t.text, "name", "编辑名字"));
        info.addView(editTextRow(account, 13, t.subtext, "account", "编辑账号"), matchWrapTop(3));
        info.addView(editTextRow(bio, 12, t.subtext, "bio", "编辑简介"), matchWrapTop(6));
        info.addView(editTextRow("⌖ " + currentPlaceLabel() + "  ·  " + weatherShort(),
                12, t.subtext, "region", "编辑地区"), matchWrapTop(6));
        col.addView(info, marginB(14));

        // 2×2 真实插件宫格
        col.addView(pluginGrid(4, 8, 2), marginB(14));

        // About me 卡（可贴图）
        FrameLayout about = new FrameLayout(this);
        about.setLayoutParams(matchWrap());
        SoftAvatarView aboutImg = sticker(DeskSkinStore.S_ABOUT, false, UiTokens.RADIUS_CARD, t.cardSoft, t.line);
        about.addView(aboutImg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(110)));
        TextView aboutLb = tv("About me  ·  长按可换图", 12, t.text);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.BOTTOM | Gravity.START;
        alp.leftMargin = dpI(16); alp.bottomMargin = dpI(12);
        about.addView(aboutLb, alp);
        col.addView(about, marginB(10));

        col.addView(searchBar());
        col.addView(spacer(96));
        return col;
    }

    // ============================================================
    // 屏 2：相册页
    // ============================================================
    private LinearLayout buildAlbumPage() {
        LinearLayout col = column();
        // 顶部返回 / 更多
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = tv("‹", 26, t.text);
        back.setPadding(dpI(4), 0, dpI(14), 0);
        back.setOnClickListener(v -> goPage(PAGE_PROFILE));
        TextView more = tv("···", 20, t.text);
        more.setOnClickListener(v -> showPageMenu(PAGE_ALBUM));
        LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mrp.weight = 1; mrp.gravity = Gravity.END;
        top.addView(back);
        top.addView(more, mrp);
        col.addView(top, marginB(10));

        // 1 Photo Selected 卡
        LinearLayout sel = cardCol(t.card, dpI(16));
        LinearLayout selRow = new LinearLayout(this);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        selRow.setGravity(Gravity.CENTER_VERTICAL);
        SoftAvatarView selThumb = sticker(DeskSkinStore.S_PHOTO0, false, UiTokens.RADIUS_SM, t.cardSoft, t.line);
        selRow.addView(selThumb, new LinearLayout.LayoutParams(dpI(40), dpI(40)));
        LinearLayout selText = new LinearLayout(this);
        selText.setOrientation(LinearLayout.VERTICAL);
        selText.addView(tv(countPhotos() + " Photo Selected", 13, t.text));
        selText.addView(tv("点照片或头像可更换贴图  ›", 11, t.subtext));
        selRow.addView(selText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = tv("×", 18, t.subtext);
        close.setOnClickListener(v -> clearPhotos());
        selRow.addView(close);
        sel.addView(selRow);
        col.addView(sel, marginB(12));

        // 3 张照片墙（可贴图）
        LinearLayout wall = new LinearLayout(this);
        wall.setOrientation(LinearLayout.HORIZONTAL);
        wall.setWeightSum(3);
        String[] photoSlots = {DeskSkinStore.S_PHOTO0, DeskSkinStore.S_PHOTO1, DeskSkinStore.S_PHOTO2};
        for (int i = 0; i < 3; i++) {
            SoftAvatarView ph = sticker(photoSlots[i], false, UiTokens.RADIUS_ROW, t.cardSoft, t.line);
            LinearLayout.LayoutParams lp = weight1();
            lp.height = dpI(120);
            lp.rightMargin = i < 2 ? dpI(8) : 0;
            wall.addView(ph, lp);
        }
        col.addView(wall, marginB(14));

        // 4 好友头像（可贴图）
        LinearLayout friends = new LinearLayout(this);
        friends.setOrientation(LinearLayout.HORIZONTAL);
        friends.setWeightSum(4);
        String[] friendSlots = {DeskSkinStore.S_FRIEND0, DeskSkinStore.S_FRIEND1,
                DeskSkinStore.S_FRIEND2, DeskSkinStore.S_FRIEND3};
        String[] friendNames = {"自己", "伴侣", "家人", "好友"};
        for (int i = 0; i < 4; i++) {
            LinearLayout f = new LinearLayout(this);
            f.setOrientation(LinearLayout.VERTICAL);
            f.setGravity(Gravity.CENTER_HORIZONTAL);
            SoftAvatarView av = sticker(friendSlots[i], true, 0, t.card, t.line);
            f.addView(av, new LinearLayout.LayoutParams(dpI(52), dpI(52)));
            f.addView(tv(friendNames[i], 10, t.subtext), matchWrapTop(4));
            LinearLayout.LayoutParams fp = weight1();
            friends.addView(f, fp);
        }
        col.addView(friends, marginB(14));

        // 2×2 真实插件宫格
        col.addView(pluginGrid(8, 12, 2), marginB(14));

        // New List 卡（课表/守护日历真实入口）
        LinearLayout nl = cardCol(t.card, dpI(16));
        nl.addView(tv("New List", 13, t.text));
        nl.addView(tv("Templates  ·  课表 / 守护日历", 11, t.subtext), matchWrapTop(3));
        nl.setOnClickListener(v -> openSection("schedule"));
        col.addView(nl, marginB(10));

        col.addView(searchBar());
        col.addView(spacer(96));
        return col;
    }

    // ============================================================
    // 屏 3：动态页
    // ============================================================
    private LinearLayout buildFeedPage() {
        LinearLayout col = column();
        // 点状背景卡（可贴图 / 可换背景）
        FrameLayout hero = new FrameLayout(this);
        hero.setLayoutParams(matchWrap());
        SoftAvatarView heroBg = sticker(DeskSkinStore.S_FEEDBG, false, UiTokens.RADIUS_CARD, t.cardSoft, t.line);
        hero.addView(heroBg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(150)));
        LinearLayout heroInner = new LinearLayout(this);
        heroInner.setOrientation(LinearLayout.HORIZONTAL);
        heroInner.setGravity(Gravity.CENTER_VERTICAL);
        heroInner.setPadding(dpI(14), dpI(18), dpI(14), dpI(14));
        SoftAvatarView heroAv = sticker(DeskSkinStore.S_FEEDAVATAR, false, UiTokens.RADIUS_SM, t.card, t.line);
        heroInner.addView(heroAv, new LinearLayout.LayoutParams(dpI(44), dpI(44)));
        LinearLayout heroTxt = new LinearLayout(this);
        heroTxt.setOrientation(LinearLayout.VERTICAL);
        String title = DeskSkinStore.text(this, "feedtitle", "掌心窗 · 我们的世界");
        heroTxt.addView(editTextRow(title, 14, t.text, "feedtitle", "编辑标题"));
        feedDate = tv(new SimpleDateFormat("yyyy年MM月dd日 E", Locale.CHINA).format(new Date()), 10, t.subtext);
        heroTxt.addView(feedDate, matchWrapTop(2));
        feedClock = tv("", 30, t.text);
        feedClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        heroTxt.addView(feedClock, matchWrapTop(6));
        heroInner.addView(heroTxt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hero.addView(heroInner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(hero, marginB(12));

        // 对话气泡（真实状态，不用假情话）
        LinearLayout bubbles = new LinearLayout(this);
        bubbles.setOrientation(LinearLayout.VERTICAL);
        bubbles.addView(bubble("♡  " + statusLine()));
        LinearLayout inp = new LinearLayout(this);
        inp.setOrientation(LinearLayout.HORIZONTAL);
        inp.setGravity(Gravity.CENTER_VERTICAL);
        inp.setBackground(t.soft(UiTokens.RADIUS_ROW));
        inp.setPadding(dpI(12), dpI(8), dpI(12), dpI(8));
        final EditText input = new EditText(this);
        input.setHint("记一句此刻…");
        input.setTextSize(12);
        input.setTextColor(t.text);
        input.setBackground(null);
        input.setSingleLine(true);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        inp.addView(input, ip);
        TextView send = tv("↑", 18, t.primary);
        send.setOnClickListener(v -> {
            String s = input.getText().toString().trim();
            if (s.isEmpty()) { toast("先写点什么"); return; }
            DeskSkinStore.setText(this, "feednote", s);
            input.setText("");
            rebuildPage(PAGE_FEED);
            toast("已记下");
        });
        inp.addView(send);
        bubbles.addView(inp, matchWrapTop(10));
        String note = DeskSkinStore.text(this, "feednote", "");
        if (!note.isEmpty()) bubbles.addView(bubble("✎  " + note));
        col.addView(bubbles, marginB(14));

        // 真实插件宫格（剩余全部插件）
        col.addView(pluginGrid(12, 99, 4), marginB(14));

        col.addView(searchBar());
        col.addView(spacer(96));
        return col;
    }

    /** 第 3 页可编辑文案点击行（名字/账号/简介/地区/标题）。 */
    private TextView editTextRow(String value, float size, int color, final String key, final String title) {
        TextView v = tv(value, size, color);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setOnClickListener(x -> showTextDialog(key, title, DeskSkinStore.text(this, key, value)));
        return v;
    }

    private View bubble(String text) {
        TextView v = tv(text, 12, t.text);
        v.setBackground(t.soft(UiTokens.RADIUS_ROW));
        v.setPadding(dpI(14), dpI(10), dpI(14), dpI(10));
        return v;
    }

    private String statusLine() {
        String n = DeskSkinStore.text(this, "feednote", "");
        return n.isEmpty() ? ("此刻 " + weatherShort() + " · " + currentPlaceLabel()) : n;
    }

    // ============================================================
    // 真实插件宫格：从 DeskPluginStore 按区间取，无重复
    // ============================================================
    private View pluginGrid(int from, int to, int columns) {
        List<DeskPluginStore.Plugin> all = DeskPluginStore.all(this);
        List<DeskPluginStore.Plugin> slice = new ArrayList<>();
        for (int i = from; i < to && i < all.size(); i++) slice.add(all.get(i));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        if (slice.isEmpty()) {
            grid.addView(tv("（暂无插件，长按底部图标可新增）", 12, t.subtext));
            return grid;
        }
        int rows = (slice.size() + columns - 1) / columns;
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setWeightSum(columns);
            for (int c = 0; c < columns && idx < slice.size(); c++, idx++) {
                row.addView(pluginCell(slice.get(idx)), weight1());
            }
            grid.addView(row, matchWrapTop(r == 0 ? 0 : 12));
        }
        return grid;
    }

    private View pluginCell(final DeskPluginStore.Plugin p) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(t.soft(UiTokens.RADIUS_ROW));
        box.setLayoutParams(new LinearLayout.LayoutParams(dpI(54), dpI(54)));
        box.addView(tv(p.icon, 22, t.text));
        TextView lb = tv(p.label, 11, t.subtext);
        lb.setSingleLine(true);
        lb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(box);
        cell.addView(lb, matchWrapTop(4));
        cell.setOnClickListener(v -> openPlugin(p));
        cell.setOnLongClickListener(v -> { editPluginDialog(p); return true; });
        UiKit.pressScale(cell);
        return cell;
    }

    // ============================================================
    // 底部 Dock：真实插件（前 4 个），可点开 / 长按编辑
    // ============================================================
    private LinearLayout buildDock() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackground(t.navBar());
        int pad = dpI(12);
        bar.setPadding(pad, dpI(8), pad, dpI(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dpI(14), 0, dpI(14), dpI(12));
        bar.setLayoutParams(lp);
        fillDock(bar);
        return bar;
    }

    private void fillDock(LinearLayout bar) {
        bar.removeAllViews();
        List<DeskPluginStore.Plugin> all = DeskPluginStore.all(this);
        int[] pick = {0, 1, 2, 3};
        for (int i = 0; i < 4; i++) {
            final DeskPluginStore.Plugin p = i < all.size() ? all.get(pick[i]) : null;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout island = new LinearLayout(this);
            island.setGravity(Gravity.CENTER);
            island.setBackground(t.navItem(p != null && p.id.equals(
                    pluginIdAt(currentPage * 4))));
            island.setLayoutParams(new LinearLayout.LayoutParams(dpI(56), dpI(40)));
            island.addView(tv(p == null ? "✦" : p.icon, 18, t.text));
            item.addView(island);
            TextView lb = tv(p == null ? "插件" : p.label, 9, t.subtext);
            item.addView(lb, matchWrapTop(2));
            final LinearLayout finalBar = bar;
            item.setOnClickListener(v -> {
                if (p != null) openPlugin(p); else addPluginDialog();
            });
            item.setOnLongClickListener(v -> {
                if (p != null) editPluginDialog(p); else showPageMenu(currentPage);
                return true;
            });
            bar.addView(item, weight1());
        }
    }

    private void rebuildDock() { fillDock(dock); }

    private String pluginIdAt(int index) {
        List<DeskPluginStore.Plugin> all = DeskPluginStore.all(this);
        return index < all.size() ? all.get(index).id : "";
    }

    // ============================================================
    // 贴图：点击换图 / 长按清除
    // ============================================================
    private SoftAvatarView sticker(String slot, boolean circle, float corner, int fill, int stroke) {
        SoftAvatarView v = new SoftAvatarView(this);
        v.setCircle(circle);
        if (!circle) v.setCornerDp(corner);
        v.setColors(fill, stroke, t.primary);
        String uri = DeskSkinStore.slotUri(this, slot);
        if (!uri.isEmpty()) v.setImageUri(Uri.parse(uri));
        bindSticker(v, slot);
        return v;
    }

    private void bindSticker(SoftAvatarView v, final String slot) {
        v.setOnClickListener(x -> pickImage(slot));
        v.setOnLongClickListener(x -> {
            new AlertDialog.Builder(this)
                    .setTitle("这个贴图位")
                    .setItems(new String[]{"从相册更换", "清除图片"}, (d, w) -> {
                        if (w == 0) pickImage(slot);
                        else { DeskSkinStore.setSlotUri(this, slot, ""); rebuildAll(); toast("已清除"); }
                    }).show();
            return true;
        });
    }

    private void pickImage(String slot) {
        int idx = Arrays.asList(DeskSkinStore.PICK_SLOTS).indexOf(slot);
        if (idx < 0) idx = 0;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, DeskSkinStore.REQ_PICK_BASE + idx);
        } catch (Exception e) {
            toast("相册没有接住选图：" + ScreenshotService.shortMsg(e));
        }
    }

    private void pickPageBg(int page) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, DeskSkinStore.REQ_BG_BASE + page);
        } catch (Exception e) {
            toast("相册没有接住选图：" + ScreenshotService.shortMsg(e));
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        Uri uri = DeskSkinStore.persistPick(this, result, data);
        if (uri == null) return;
        if (req >= DeskSkinStore.REQ_BG_BASE && req < DeskSkinStore.REQ_BG_BASE + PAGE_COUNT) {
            int page = req - DeskSkinStore.REQ_BG_BASE;
            DeskSkinStore.setPageBgUri(this, page, uri.toString());
            rebuildPage(page);
            toast("第 " + (page + 1) + " 页背景已更换");
            return;
        }
        if (req >= DeskSkinStore.REQ_PICK_BASE
                && req < DeskSkinStore.REQ_PICK_BASE + DeskSkinStore.PICK_SLOTS.length) {
            String slot = DeskSkinStore.PICK_SLOTS[req - DeskSkinStore.REQ_PICK_BASE];
            DeskSkinStore.setSlotUri(this, slot, uri.toString());
            rebuildAll();
            toast("贴图已更换");
        }
    }

    private int countPhotos() {
        int n = 0;
        for (String s : new String[]{DeskSkinStore.S_PHOTO0, DeskSkinStore.S_PHOTO1, DeskSkinStore.S_PHOTO2})
            if (!DeskSkinStore.slotUri(this, s).isEmpty()) n++;
        return n;
    }

    private void clearPhotos() {
        DeskSkinStore.setSlotUri(this, DeskSkinStore.S_PHOTO0, "");
        DeskSkinStore.setSlotUri(this, DeskSkinStore.S_PHOTO1, "");
        DeskSkinStore.setSlotUri(this, DeskSkinStore.S_PHOTO2, "");
        rebuildPage(PAGE_ALBUM);
        toast("照片墙已清空");
    }

    // ============================================================
    // 菜单 / 编辑
    // ============================================================
    private void showPageMenu(final int page) {
        new AlertDialog.Builder(this).setTitle("第 " + (page + 1) + " 页设置")
                .setItems(new String[]{"更换本页背景图", "选纯色背景", "恢复默认背景", "新增插件", "恢复默认插件"},
                        (d, w) -> {
                            if (w == 0) pickPageBg(page);
                            else if (w == 1) showBgColorDialog(page);
                            else if (w == 2) {
                                DeskSkinStore.setPageBgUri(this, page, "");
                                DeskSkinStore.setPageBgColor(this, page, 0);
                                rebuildPage(page);
                                toast("已恢复默认背景");
                            } else if (w == 3) addPluginDialog();
                            else if (w == 4) {
                                DeskPluginStore.reset(this);
                                rebuildAll();
                                toast("插件已恢复默认");
                            }
                        }).show();
    }

    private void showBgColorDialog(final int page) {
        final String[] names = {"米白", "柔绿", "淡蓝", "藕粉", "浅灰", "夜色"};
        final int[] colors = {0xFFF6F4EE, 0xFFE6EFE4, 0xFFE5EEF4, 0xFFF3E7EC, 0xFFEDEDED, 0xFF232A26};
        new AlertDialog.Builder(this).setTitle("选择背景纯色")
                .setItems(names, (d, w) -> {
                    DeskSkinStore.setPageBgUri(this, page, "");
                    DeskSkinStore.setPageBgColor(this, page, colors[w]);
                    rebuildPage(page);
                    toast("背景已设为" + names[w]);
                }).show();
    }

    private void showTextDialog(final String key, String title, String current) {
        final EditText input = new EditText(this);
        input.setText(current);
        input.setSelection(input.getText().length());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    DeskSkinStore.setText(this, key, input.getText().toString().trim());
                    rebuildAll();
                    toast("已保存");
                })
                .setNegativeButton("取消", null).show();
    }

    private void editPluginDialog(final DeskPluginStore.Plugin p) {
        new AlertDialog.Builder(this).setTitle("插件：" + p.label)
                .setItems(new String[]{"改名 / 动作", "换图标", "前移", "后移", "删除"}, (d, w) -> {
                    if (w == 0) showRenameDialog(p);
                    else if (w == 1) cycleIcon(p);
                    else if (w == 2) movePlugin(p, -1);
                    else if (w == 3) movePlugin(p, 1);
                    else if (w == 4) {
                        DeskPluginStore.remove(this, p.id);
                        rebuildAll();
                        toast("已删除 " + p.label);
                    }
                }).show();
    }

    private void movePlugin(DeskPluginStore.Plugin p, int delta) {
        List<DeskPluginStore.Plugin> all = DeskPluginStore.all(this);
        int from = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(p.id)) { from = i; break; }
        if (from < 0) return;
        int to = from + delta;
        if (to < 0 || to >= all.size()) { toast("已经到边了"); return; }
        DeskPluginStore.move(this, from, to);
        rebuildAll();
    }

    private void cycleIcon(final DeskPluginStore.Plugin p) {
        String[] icons = DeskPluginStore.ICONS;
        int cur = 0;
        for (int i = 0; i < icons.length; i++) if (icons[i].equals(p.icon)) { cur = i; break; }
        String next = icons[(cur + 1) % icons.length];
        DeskPluginStore.update(this, p.id, p.label, next, p.action);
        rebuildAll();
        toast("图标已换为 " + next + "（继续点可循环）");
    }

    private void showRenameDialog(final DeskPluginStore.Plugin p) {
        final EditText name = new EditText(this);
        name.setText(p.label);
        final EditText action = new EditText(this);
        action.setText(p.action);
        action.setHint("policy:calendar / page / app:包名 / none");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpI(20), dpI(12), dpI(20), dpI(4));
        box.addView(name);
        box.addView(action);
        new AlertDialog.Builder(this).setTitle("编辑插件：" + p.label).setView(box)
                .setPositiveButton("保存", (dd, ww) -> {
                    DeskPluginStore.update(this, p.id, name.getText().toString(), p.icon,
                            action.getText().toString());
                    rebuildAll();
                    toast("已保存");
                })
                .setNegativeButton("取消", null).show();
    }

    private void addPluginDialog() {
        final EditText name = new EditText(this);
        name.setHint("插件名称");
        final EditText action = new EditText(this);
        action.setHint("动作：policy:xxx / app:包名（可留空）");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dpI(20), dpI(12), dpI(20), dpI(4));
        box.addView(name);
        box.addView(action);
        new AlertDialog.Builder(this).setTitle("新增插件").setView(box)
                .setPositiveButton("添加", (dd, ww) -> {
                    DeskPluginStore.add(this, name.getText().toString(), "✦", action.getText().toString());
                    rebuildAll();
                    toast("已添加");
                })
                .setNegativeButton("取消", null).show();
    }

    private void openPlugin(DeskPluginStore.Plugin p) { openAction(p.action, p.label); }

    private void openAction(String a, String label) {
        try {
            if (a == null || a.isEmpty() || "none".equals(a)) {
                toast(label + " · 长按可编辑");
            } else if (a.startsWith("page:")) {
                String pg = a.substring(5);
                if ("profile".equals(pg) || "world".equals(pg)) goPage(PAGE_PROFILE);
                else if ("album".equals(pg)) goPage(PAGE_ALBUM);
                else if ("feed".equals(pg) || "terminal".equals(pg)) goPage(PAGE_FEED);
                else openSection(null);
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

    private void openSection(String section) {
        Intent i = new Intent(this, PolicySettingsActivity.class);
        if (section != null && VALID_SECTIONS.contains(section)) i.putExtra("section", section);
        try { startActivity(i); }
        catch (Exception e) { toast("打开设置失败：" + ScreenshotService.shortMsg(e)); }
    }

    private void showSearchDialog() {
        List<DeskPluginStore.Plugin> all = DeskPluginStore.all(this);
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).icon + "  " + all.get(i).label;
        new AlertDialog.Builder(this).setTitle("打开插件")
                .setItems(names, (d, w) -> openPlugin(all.get(w))).show();
    }

    // ============================================================
    // 搜索栏 / 时钟 / 天气
    // ============================================================
    private View searchBar() {
        TextView s = tv("⌕  搜索", 12, t.subtext);
        s.setGravity(Gravity.CENTER);
        s.setBackground(t.soft(UiTokens.RADIUS_ROW));
        s.setPadding(dpI(16), dpI(8), dpI(16), dpI(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpI(140),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        s.setLayoutParams(lp);
        s.setOnClickListener(v -> showSearchDialog());
        return s;
    }

    private void refreshClock() {
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        if (feedClock != null) feedClock.setText(time);
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

    private void refreshDeskWeather() {
        try {
            JSONObject cur = WeatherState.currentLocation(this);
            if (cur == null) return;
            double lat = cur.optDouble("latitude", 0), lon = cur.optDouble("longitude", 0);
            String city = cur.optString("city", ""), district = cur.optString("district", "");
            if ((lat != 0 || lon != 0) && !WeatherLive.isFresh(this, city, district, 45L * 60L * 1000L))
                WeatherLive.refreshAsync(this, city, district, lat, lon, w -> runOnUiThread(() -> rebuildPage(PAGE_PROFILE)));
        } catch (Exception ignored) { }
    }

    // ============================================================
    // UI 小工具
    // ============================================================
    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(16), dpI(16), dpI(16), dpI(16));
        return col;
    }

    private TextView tv(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s == null ? "" : s);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout cardCol(int color, float pad) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(t.card(UiTokens.RADIUS_CARD, UiTokens.STROKE_DP));
        c.setPadding((int) pad, (int) pad, (int) pad, (int) pad);
        return c;
    }

    private View spacer(int dpV) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(dpV)));
        return v;
    }

    private int dpI(float v) { return UiTokens.dpI(v); }
    private float dp(float v) { return UiTokens.dp(v); }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
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
