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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
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
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 掌心窗主入口 —— 参考图「三页横滑」美化布局，内容全部来自掌心窗真实功能。
 *
 * 【图标位 = 掌心窗真实可更改功能】查岗/情绪/主动/训诫/分心/门禁/提醒/课表/
 * 记忆/识图/天气/回家/全局/权限/调试/主题，共 16 项，全部来自 DeskFeatureStore，
 * 绝不出现「消息/图片/联系人/便签」这类无关系统应用；去重、无重复铺满三页。
 * 每个角标：点按=打开对应设置进行更改；长按=改名/换图标/移动。
 *
 * 【贴图位零字符】封面/头像/照片墙/好友头像/动态背景/动态头像等可换图区域
 * 只显示纯图片，上面不叠加任何文字；换图/清除靠点击与长按菜单完成。
 *
 * 【三页横滑】个人页 / 相册页 / 动态页，手势左右滑动，无底部标签栏。
 * 每页背景可换图或纯色；名字/账号/简介/地区/标题为独立文字卡可点击编辑。
 * 皮肤与角标自定义全部落 SharedPreferences，重启不丢。
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
            page.setLayoutParams(new LinearLayout.LayoutParams(screenW, ViewGroup.LayoutParams.MATCH_PARENT));
            pages[i] = page;
            track.addView(page);
        }
        hsv.addView(track, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hsv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) snapPage();
            return false;
        });
        root.addView(hsv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        clock.post(clockTick);
        refreshDeskWeather();
    }

    @Override protected void onDestroy() {
        clock.removeCallbacks(clockTick);
        super.onDestroy();
    }

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
        // 右上角：页设置（换背景/纯色/恢复），无文字图标
        col.addView(pageSettingRow(false));

        // 封面大卡（纯贴图，零字符）+ 圆头像（纯贴图，零字符）
        FrameLayout cover = new FrameLayout(this);
        cover.setLayoutParams(matchWrap());
        SoftAvatarView coverImg = sticker(DeskSkinStore.S_COVER, false, UiTokens.RADIUS_CARD, t.cardSoft, t.line);
        cover.addView(coverImg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(180)));
        SoftAvatarView avatar = sticker(DeskSkinStore.S_AVATAR, true, 0, t.card, t.line);
        FrameLayout.LayoutParams avp = new FrameLayout.LayoutParams(dpI(84), dpI(84));
        avp.gravity = Gravity.CENTER_HORIZONTAL;
        avp.topMargin = dpI(138);
        cover.addView(avatar, avp);
        col.addView(cover, marginB(14));

        // 信息卡：名字 / @账号 / 简介 / 地区（文字卡，非贴图位）
        LinearLayout info = cardCol(t.card, dpI(18));
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        String name = DeskSkinStore.text(this, "name", "掌心窗");
        String account = DeskSkinStore.text(this, "account", "@我的掌心窗");
        String bio = DeskSkinStore.text(this, "bio", "在这里，记录我们的一天");
        info.addView(editTextRow(name, 20, t.text, "name", "编辑名字"));
        info.addView(editTextRow(account, 13, t.subtext, "account", "编辑账号"), matchWrapTop(3));
        info.addView(editTextRow(bio, 12, t.subtext, "bio", "编辑简介"), matchWrapTop(6));
        info.addView(editTextRow("⌖ " + currentPlaceLabel() + "  ·  " + weatherShort(),
                12, t.subtext, "region", "编辑地区"), matchWrapTop(6));
        col.addView(info, marginB(14));

        // 真实功能角标（2×2）
        col.addView(featureGrid(0, 4, 2), marginB(14));

        // About me（纯贴图，零字符）
        SoftAvatarView aboutImg = sticker(DeskSkinStore.S_ABOUT, false, UiTokens.RADIUS_CARD, t.cardSoft, t.line);
        col.addView(aboutImg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(110)));

        col.addView(searchBar());
        col.addView(spacer(96));
        return col;
    }

    // ============================================================
    // 屏 2：相册页
    // ============================================================
    private LinearLayout buildAlbumPage() {
        LinearLayout col = column();
        col.addView(pageSettingRow(true));

        // 3 张照片墙（纯贴图，零字符）
        LinearLayout wall = new LinearLayout(this);
        wall.setOrientation(LinearLayout.HORIZONTAL);
        wall.setWeightSum(3);
        String[] photoSlots = {DeskSkinStore.S_PHOTO0, DeskSkinStore.S_PHOTO1, DeskSkinStore.S_PHOTO2};
        for (int i = 0; i < 3; i++) {
            SoftAvatarView ph = sticker(photoSlots[i], false, UiTokens.RADIUS_ROW, t.cardSoft, t.line);
            LinearLayout.LayoutParams lp = weight1();
            lp.height = dpI(130);
            lp.rightMargin = i < 2 ? dpI(8) : 0;
            wall.addView(ph, lp);
        }
        col.addView(wall, marginB(14));

        // 4 好友头像（纯贴图，零字符）
        LinearLayout friends = new LinearLayout(this);
        friends.setOrientation(LinearLayout.HORIZONTAL);
        friends.setWeightSum(4);
        String[] friendSlots = {DeskSkinStore.S_FRIEND0, DeskSkinStore.S_FRIEND1,
                DeskSkinStore.S_FRIEND2, DeskSkinStore.S_FRIEND3};
        for (int i = 0; i < 4; i++) {
            SoftAvatarView av = sticker(friendSlots[i], true, 0, t.card, t.line);
            LinearLayout.LayoutParams fp = weight1();
            fp.height = dpI(58);
            friends.addView(av, fp);
        }
        col.addView(friends, marginB(16));

        // 真实功能角标（2×2）
        col.addView(featureGrid(4, 8, 2), marginB(14));

        // 真实功能角标（2×2）
        col.addView(featureGrid(8, 12, 2), marginB(14));

        col.addView(searchBar());
        col.addView(spacer(96));
        return col;
    }

    // ============================================================
    // 屏 3：动态页
    // ============================================================
    private LinearLayout buildFeedPage() {
        LinearLayout col = column();
        col.addView(pageSettingRow(false));

        // 顶部贴图横幅（纯贴图，零字符）
        SoftAvatarView heroBg = sticker(DeskSkinStore.S_FEEDBG, false, UiTokens.RADIUS_CARD, t.cardSoft, t.line);
        col.addView(heroBg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(140)));

        // 文字信息卡（非贴图位）：头像 + 标题/日期/时钟
        LinearLayout infoCard = cardCol(t.card, dpI(14));
        infoCard.setOrientation(LinearLayout.HORIZONTAL);
        infoCard.setGravity(Gravity.CENTER_VERTICAL);
        SoftAvatarView heroAv = sticker(DeskSkinStore.S_FEEDAVATAR, false, UiTokens.RADIUS_SM, t.card, t.line);
        infoCard.addView(heroAv, new LinearLayout.LayoutParams(dpI(48), dpI(48)));
        LinearLayout heroTxt = new LinearLayout(this);
        heroTxt.setOrientation(LinearLayout.VERTICAL);
        String title = DeskSkinStore.text(this, "feedtitle", "掌心窗 · 我们的世界");
        heroTxt.addView(editTextRow(title, 14, t.text, "feedtitle", "编辑标题"));
        feedDate = tv(new SimpleDateFormat("yyyy年MM月dd日 E", Locale.CHINA).format(new Date()), 10, t.subtext);
        heroTxt.addView(feedDate, matchWrapTop(2));
        feedClock = tv("", 28, t.text);
        feedClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        heroTxt.addView(feedClock, matchWrapTop(4));
        infoCard.addView(heroTxt, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(infoCard, marginB(14));

        // 对话气泡（文字卡）+ 输入框（非贴图位）
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
        inp.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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

        // 真实功能角标（剩余 4 项，4 列）
        col.addView(featureGrid(12, 16, 4), marginB(14));

        col.addView(searchBar());
        col.addView(spacer(96));
        return col;
    }

    /** 页设置入口行：左侧返回/标题，右侧 ⚙ 图标（纯图标无文字）。 */
    private View pageSettingRow(boolean showBack) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (showBack) {
            TextView back = tv("‹", 24, t.text);
            back.setPadding(dpI(2), 0, dpI(10), 0);
            back.setOnClickListener(v -> goPage(PAGE_PROFILE));
            row.addView(back);
        }
        TextView gear = tv("⚙", 18, t.subtext);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.weight = 1; gp.gravity = Gravity.END;
        gear.setOnClickListener(v -> showPageMenu(currentPage));
        row.addView(gear, gp);
        return row;
    }

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
    // 真实功能角标：来自 DeskFeatureStore，无重复铺满三页
    // ============================================================
    private View featureGrid(int from, int to, int columns) {
        List<DeskFeatureStore.Feature> all = DeskFeatureStore.all(this);
        List<DeskFeatureStore.Feature> slice = new java.util.ArrayList<>();
        for (int i = from; i < to && i < all.size(); i++) slice.add(all.get(i));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        if (slice.isEmpty()) {
            grid.addView(tv("（暂无功能项）", 12, t.subtext));
            return grid;
        }
        int rows = (slice.size() + columns - 1) / columns;
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setWeightSum(columns);
            for (int c = 0; c < columns && idx < slice.size(); c++, idx++) {
                row.addView(featureCell(slice.get(idx)), weight1());
            }
            grid.addView(row, matchWrapTop(r == 0 ? 0 : 12));
        }
        return grid;
    }

    private View featureCell(final DeskFeatureStore.Feature f) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(t.soft(UiTokens.RADIUS_ROW));
        box.setLayoutParams(new LinearLayout.LayoutParams(dpI(54), dpI(54)));
        box.addView(tv(f.icon, 22, t.text));
        TextView lb = tv(f.name, 11, t.subtext);
        lb.setSingleLine(true);
        lb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(box);
        cell.addView(lb, matchWrapTop(4));
        cell.setOnClickListener(v -> openFeature(f));
        cell.setOnLongClickListener(v -> { editFeatureDialog(f); return true; });
        UiKit.pressScale(cell);
        return cell;
    }

    // ============================================================
    // 贴图：点击换图 / 长按更换或清除（贴图位本身零字符）
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
                    .setTitle("更换这张图片")
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
            toast("图片已更换");
        }
    }

    // ============================================================
    // 菜单 / 编辑
    // ============================================================
    private void showPageMenu(final int page) {
        new AlertDialog.Builder(this).setTitle("第 " + (page + 1) + " 页设置")
                .setItems(new String[]{"更换本页背景图", "选纯色背景", "恢复默认背景", "恢复默认角标"},
                        (d, w) -> {
                            if (w == 0) pickPageBg(page);
                            else if (w == 1) showBgColorDialog(page);
                            else if (w == 2) {
                                DeskSkinStore.setPageBgUri(this, page, "");
                                DeskSkinStore.setPageBgColor(this, page, 0);
                                rebuildPage(page);
                                toast("已恢复默认背景");
                            } else if (w == 3) {
                                DeskFeatureStore.reset(this);
                                rebuildAll();
                                toast("角标已恢复默认");
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

    private void editFeatureDialog(final DeskFeatureStore.Feature f) {
        new AlertDialog.Builder(this).setTitle("功能：" + f.name)
                .setItems(new String[]{"改名", "换图标", "前移", "后移", "恢复这一个默认"}, (d, w) -> {
                    if (w == 0) showRenameFeatureDialog(f);
                    else if (w == 1) cycleIcon(f);
                    else if (w == 2) moveFeature(f, -1);
                    else if (w == 3) moveFeature(f, 1);
                    else if (w == 4) {
                        DeskFeatureStore.setIcon(this, f.id, f.defIcon);
                        DeskFeatureStore.setName(this, f.id, f.defName);
                        rebuildAll();
                        toast("已恢复默认");
                    }
                }).show();
    }

    private void moveFeature(DeskFeatureStore.Feature f, int delta) {
        List<DeskFeatureStore.Feature> all = DeskFeatureStore.all(this);
        int from = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(f.id)) { from = i; break; }
        if (from < 0) return;
        int to = from + delta;
        if (to < 0 || to >= all.size()) { toast("已经到边了"); return; }
        DeskFeatureStore.move(this, from, to);
        rebuildAll();
    }

    private void cycleIcon(final DeskFeatureStore.Feature f) {
        String[] icons = DeskFeatureStore.ICONS;
        int cur = 0;
        for (int i = 0; i < icons.length; i++) if (icons[i].equals(f.icon)) { cur = i; break; }
        String next = icons[(cur + 1) % icons.length];
        DeskFeatureStore.setIcon(this, f.id, next);
        rebuildAll();
        toast("图标已换为 " + next + "（继续点可循环）");
    }

    private void showRenameFeatureDialog(final DeskFeatureStore.Feature f) {
        final EditText name = new EditText(this);
        name.setText(f.name);
        new AlertDialog.Builder(this).setTitle("重命名：" + f.name).setView(name)
                .setPositiveButton("保存", (dd, ww) -> {
                    String n = name.getText().toString().trim();
                    if (!n.isEmpty()) DeskFeatureStore.setName(this, f.id, n);
                    rebuildAll();
                    toast("已保存");
                })
                .setNegativeButton("取消", null).show();
    }

    private void openFeature(DeskFeatureStore.Feature f) { openAction(f.action, f.name); }

    private void openAction(String a, String label) {
        try {
            if (a == null || a.isEmpty() || "none".equals(a)) {
                toast(label + " · 长按可编辑");
            } else if (a.startsWith("theme:")) {
                cycleTheme();
            } else if (a.startsWith("policy:")) {
                openSection(a.substring(7));
            } else if ("watch:open".equals(a)) {
                startActivity(new Intent(this, WatchPartyActivity.class));
            } else toast(label);
        } catch (Exception e) {
            toast("打开失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private void cycleTheme() {
        String[] names = UITheme.themeNames();
        String cur = UITheme.currentBaseName(this);
        int idx = 0;
        for (int i = 0; i < names.length; i++) if (names[i].equals(cur)) { idx = i; break; }
        String next = names[(idx + 1) % names.length];
        UITheme.setTheme(this, next);
        t = UITheme.current(this);
        rebuildAll();
        toast("主题已切换为" + next);
    }

    private void openSection(String section) {
        Intent i = new Intent(this, PolicySettingsActivity.class);
        if (section != null && VALID_SECTIONS.contains(section)) i.putExtra("section", section);
        try { startActivity(i); }
        catch (Exception e) { toast("打开设置失败：" + ScreenshotService.shortMsg(e)); }
    }

    private void showSearchDialog() {
        List<DeskFeatureStore.Feature> all = DeskFeatureStore.all(this);
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).icon + "  " + all.get(i).name;
        new AlertDialog.Builder(this).setTitle("掌心窗功能")
                .setItems(names, (d, w) -> openFeature(all.get(w))).show();
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
