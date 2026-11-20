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
import android.graphics.drawable.Drawable;
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
 * 掌心窗主入口 —— 参考图「横滑」美化布局，内容全部来自掌心窗真实功能。
 *
 * 【四页横滑】个人页 / 相册页 / 动态页 / 旧功能入口页，手势左右滑动，无底部标签栏。
 * 【自由桌面】每页元素（角标/贴图/文字卡）用 FreeDeskLayout 绝对 dp 坐标自由摆放：
 *   单击=打开功能/换图/编辑文字；长按=进入编辑态（拖动位置、拖拽右下角把手缩放尺寸）。
 * 【角标】来自 DeskFeatureStore 的真实可更改功能 + 第四页 OldEntryStore 旧功能入口；
 *   图标正下方居中显示名字（不跑偏）；长按可改名、从相册自定义上传图标、排序、恢复默认；
 *   角标不可删除。主题角标单击=一键换主题；一起看角标单击=共享本机正在看的电影/抖音。
 * 【贴图】纯图片零字符；可拖动（自动避让角标不遮挡）、拖拽缩放、增删（相册页）。
 * 所有摆放/图标/文案落 SharedPreferences，重启不丢。
 */
public class PalmDeskActivity extends Activity {
    private static final int PAGE_PROFILE = 0;
    private static final int PAGE_ALBUM = 1;
    private static final int PAGE_FEED = 2;
    private static final int PAGE_OLD = 3;
    private static final int PAGE_COUNT = 4;
    private int currentPage = PAGE_PROFILE;

    /** 桌面画布固定高度（dp），元素在此范围内自由摆放。 */
    private static final float STAGE_H = 620f;

    private static final Set<String> VALID_SECTIONS = new HashSet<>(Arrays.asList(
            "checkin", "distraction", "discipline", "emotion", "initiative",
            "appgate", "reminder", "schedule", "memory", "ocr", "weather",
            "homemode", "global", "permissions", "debug", "calendar", "focus", "guidian"));

    private UITheme t;
    private int screenW;
    private HorizontalScrollView hsv;
    private LinearLayout track;
    private final View[] pages = new View[PAGE_COUNT];
    private final FreeDeskLayout[] stages = new FreeDeskLayout[PAGE_COUNT];
    private TextView feedClock;
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
        LinearLayout col = column();
        col.addView(pageSettingRow(index));
        FreeDeskLayout stage = new FreeDeskLayout(this, index, deskActions);
        stages[index] = stage;
        // 画布：占满宽度、固定高度，内部元素绝对定位
        FrameLayout stageHolder = new FrameLayout(this);
        stageHolder.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(STAGE_H)));
        col.addView(stageHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(STAGE_H)));
        populateStage(index, stage);
        col.addView(searchBar());
        col.addView(spacer(72));
        sv.addView(col, new ScrollView.LayoutParams(
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
        hsv.scrollTo(currentPage * screenW, 0);
    }

    private void rebuildAll() {
        for (int i = 0; i < PAGE_COUNT; i++) rebuildPage(i);
        hsv.scrollTo(currentPage * screenW, 0);
    }

    private final FreeDeskLayout.Actions deskActions = new FreeDeskLayout.Actions() {
        @Override public void onClick(String kind, String id) {
            if (FreeDeskLayout.KIND_FEATURE.equals(kind)) {
                DeskFeatureStore.Feature f = findFeature(id);
                if (f != null) openFeature(f);
            } else if (FreeDeskLayout.KIND_STICKER.equals(kind)) {
                pickImage(id);
            } else if (FreeDeskLayout.KIND_TEXT.equals(kind)) {
                handleTextClick(id);
            } else if ("old".equals(kind)) {
                OldEntryStore.Entry e = findOld(id);
                if (e != null) openAction(e.action, e.name);
            }
        }
        @Override public void onDelete(String kind, String id) {
            if (FreeDeskLayout.KIND_STICKER.equals(kind)) {
                DeskSkinStore.setSlotUri(PalmDeskActivity.this, id, "");
                int page = DeskSkinStore.pageOfSlot(id);
                if (page < 0) page = currentPage;
                DeskLayoutStore.remove(PalmDeskActivity.this, page, kind, id);
                rebuildPage(page);
                toast("贴图已删除");
            }
        }
        @Override public void onEdit(String kind, String id) {
            if (FreeDeskLayout.KIND_FEATURE.equals(kind)) {
                DeskFeatureStore.Feature f = findFeature(id);
                if (f != null) editFeatureDialog(f);
            } else if ("old".equals(kind)) {
                OldEntryStore.Entry e = findOld(id);
                if (e != null) editOldEntryDialog(e);
            }
        }
        @Override public void onChanged() { }
    };

    // ============================================================
    // 各页元素填充：默认坐标为 dp（x,y,w,h），用户可拖拽缩放覆盖
    // ============================================================
    private void populateStage(int index, FreeDeskLayout stage) {
        if (index == PAGE_PROFILE) populateProfile(stage);
        else if (index == PAGE_ALBUM) populateAlbum(stage);
        else if (index == PAGE_FEED) populateFeed(stage);
        else populateOld(stage);
    }

    private void populateProfile(FreeDeskLayout stage) {
        // 贴图：封面 + 圆头像（纯图零字符）
        addSticker(stage, "cover", 8, 6, 300, 150);
        addSticker(stage, "avatar", 120, 118, 76, 76);
        // 文字卡：名字 / @账号 / 简介 / 地区
        addText(stage, "name", 8, 205, 300, 30);
        addText(stage, "account", 8, 238, 300, 22);
        addText(stage, "bio", 8, 262, 300, 22);
        addText(stage, "region", 8, 288, 300, 22);
        // 功能角标（前 4 项，2×2）
        addFeatureGrid(stage, 0, 4, 2, 8, 322, 74);
        // About me 贴图
        addSticker(stage, "about", 8, 500, 300, 96);
    }

    private void populateAlbum(FreeDeskLayout stage) {
        // 相册页贴图：默认三张，可增删（位置可拖动、避让角标）
        String[] slots = {"photo0", "photo1", "photo2", "dyn0", "dyn1", "dyn2", "dyn3", "dyn4", "dyn5"};
        float[] xs = {8, 108, 208};
        int shown = 0;
        for (String s : slots) {
            if (DeskSkinStore.slotUri(this, s).isEmpty() && !isDefaultAlbumSlot(s)) continue;
            if (shown >= 9) break;
            float x = xs[shown % 3];
            float y = 8 + (shown / 3) * 112f;
            addSticker(stage, s, x, y, 92, 104);
            shown++;
        }
        // 功能角标（4..16，4 列）
        addFeatureGrid(stage, 4, 16, 4, 8, 360, 62);
    }

    private boolean isDefaultAlbumSlot(String s) {
        return s.equals("photo0") || s.equals("photo1") || s.equals("photo2");
    }

    private void populateFeed(FreeDeskLayout stage) {
        // 顶部横幅贴图
        addSticker(stage, "feedbg", 8, 6, 300, 120);
        // 文字卡：标题 / 日期 / 时钟 / 心情 / 便签
        addText(stage, "feedtitle", 8, 134, 300, 26);
        addText(stage, "feeddate", 8, 162, 300, 22);
        addText(stage, "feedclock", 8, 186, 300, 40);
        addText(stage, "status", 8, 232, 300, 30);
        addText(stage, "feednote", 8, 266, 300, 30);
        // 剩余功能角标（16..end，含一起看，4 列）
        List<DeskFeatureStore.Feature> all = DeskFeatureStore.all(this);
        addFeatureGrid(stage, 16, all.size(), 4, 8, 320, 62);
    }

    private void populateOld(FreeDeskLayout stage) {
        // 旧功能入口角标：陪伴 / TA的日记 / 连接设置 / 设置 / 生活 / 守护 / 守护日历 / 归电（4 列）
        List<OldEntryStore.Entry> entries = OldEntryStore.all(this);
        float baseX = 8, baseY = 20, cellW = 74, cellH = 74, gapX = 4, gapY = 14;
        for (int i = 0; i < entries.size(); i++) {
            OldEntryStore.Entry e = entries.get(i);
            int col = i % 4, row = i / 4;
            final float x = baseX + col * (cellW + gapX);
            final float y = baseY + row * (cellH + gapY);
            DeskLayoutStore.Pos def = new DeskLayoutStore.Pos(x, y, cellW, cellH);
            View cell = oldEntryCell(e);
            stage.addItem(cell, "old", e.id, def, false);
        }
        // 说明文字卡（不可删）
        addText(stage, "oldhint", 8, 200, 300, 60);
        // 第四页贴图（可增删/拖动/缩放，独立槽 old0..old5）
        float[] tx = {8, 108, 208};
        int n = 0;
        for (String s : DeskSkinStore.OLD_SLOTS) {
            if (DeskSkinStore.slotUri(this, s).isEmpty()) continue;
            float x = tx[n % 3];
            float y = 274 + (n / 3) * 112f;
            addSticker(stage, s, x, y, 92, 104);
            n++;
        }
    }

    // ============================================================
    // 元素构建：角标 / 贴图 / 文字卡
    // ============================================================
    private void addFeatureGrid(FreeDeskLayout stage, int from, int to, int columns,
                                float baseX, float baseY, float cellW) {
        List<DeskFeatureStore.Feature> all = DeskFeatureStore.all(this);
        float cellH = cellW + 16;
        float gapX = 3, gapY = 6;
        for (int i = from; i < to && i < all.size(); i++) {
            final DeskFeatureStore.Feature f = all.get(i);
            int k = i - from;
            int col = k % columns, row = k / columns;
            float x = baseX + col * (cellW + gapX);
            float y = baseY + row * (cellH + gapY);
            DeskLayoutStore.Pos def = new DeskLayoutStore.Pos(x, y, cellW, cellH);
            stage.addItem(featureCell(f), FreeDeskLayout.KIND_FEATURE, f.id, def, false);
        }
    }

    /** 功能角标：图标在上、名字在正下方居中（不跑偏）。图标可为字符或自定义上传图片。 */
    private View featureCell(final DeskFeatureStore.Feature f) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setBackgroundColor(Color.TRANSPARENT);
        int side = dpI(46);
        View iconView = iconView(FDeskAssetKey(f.id), f.icon, side);
        LinearLayout ip = new LinearLayout(this);
        ip.setGravity(Gravity.CENTER);
        ip.addView(iconView, new LinearLayout.LayoutParams(side, side));
        cell.addView(ip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView lb = tv(f.name, 10.5f, t.subtext);
        lb.setGravity(Gravity.CENTER);
        lb.setSingleLine(true);
        lb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(lb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 点击/长按统一交给 FreeDeskLayout：单击=打开功能，长按=编辑态（拖动/缩放/点编辑按钮弹菜单）
        cell.setClickable(false);
        return cell;
    }

    /**
     * 角标图标：与 UI 同色系的圆角方块 App 图标（纯几何，无 emoji/文字/简笔画）。
     * 优先级：自定义上传 URI > 内置几何图标。永不回退到字符图标。
     * 图标铺满整个圆角方块（padding=0），解决旧版被 13dp 内边距挤到看不清的问题。
     */
    private View iconView(String assetKey, String glyph, int side) {
        SoftAvatarView img = new SoftAvatarView(this);
        img.setCircle(false);
        img.setCornerDp(12);
        img.setFallbackPaddingDp(0);
        img.setColors(t.card, t.line, t.primary);
        String uri = DeskAssetStore.featureIconUri(this, assetKey);
        if (!uri.isEmpty()) {
            img.setImageUri(Uri.parse(uri));
            return img;
        }
        int builtin = DeskIconPack.indexOf(this, assetKey);
        if (builtin < 0) builtin = DeskIconPack.defaultIndexFor(assetKey); // 永不落回字符
        Drawable d = DeskIconPack.drawable(this, builtin);
        if (d != null) img.setFallback(d);
        return img;
    }

    private String FDeskAssetKey(String featureId) { return featureId; }

    private void addSticker(FreeDeskLayout stage, String slot, float x, float y, float w, float h) {
        SoftAvatarView img = new SoftAvatarView(this);
        img.setCircle(slot.equals("avatar"));
        if (!slot.equals("avatar")) img.setCornerDp(UiTokens.RADIUS_CARD);
        img.setColors(t.cardSoft, t.line, t.primary);
        String uri = DeskSkinStore.slotUri(this, slot);
        if (!uri.isEmpty()) img.setImageUri(Uri.parse(uri));
        DeskLayoutStore.Pos def = new DeskLayoutStore.Pos(x, y, w, h);
        stage.addItem(img, FreeDeskLayout.KIND_STICKER, slot, def, true);
    }

    private void addText(FreeDeskLayout stage, String key, float x, float y, float w, float h) {
        TextView v = tv(textFor(key), sizeFor(key), key.equals("feedclock") ? t.text : t.subtext);
        v.setGravity(key.equals("feedclock") ? Gravity.CENTER : Gravity.CENTER_HORIZONTAL);
        if (key.equals("feedclock")) {
            feedClock = v;
            v.setTextSize(30);
            v.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        }
        DeskLayoutStore.Pos def = new DeskLayoutStore.Pos(x, y, w, h);
        stage.addItem(v, FreeDeskLayout.KIND_TEXT, key, def, false);
    }

    private String textFor(String key) {
        switch (key) {
            case "name": return DeskSkinStore.text(this, "name", "掌心窗");
            case "account": return DeskSkinStore.text(this, "account", "@我的掌心窗");
            case "bio": return DeskSkinStore.text(this, "bio", "在这里，记录我们的一天");
            case "region": return "⌖ " + currentPlaceLabel() + "  ·  " + weatherShort();
            case "feedtitle": return DeskSkinStore.text(this, "feedtitle", "掌心窗 · 我们的世界");
            case "feeddate": return new SimpleDateFormat("yyyy年MM月dd日 E", Locale.CHINA).format(new Date());
            case "feedclock": return "";
            case "status": return statusLine();
            case "feednote": {
                String n = DeskSkinStore.text(this, "feednote", "");
                return n.isEmpty() ? "" : "✎  " + n;
            }
            case "oldhint": return "以上入口可回到掌心窗原来的陪伴、日记、连接设置与设置页面。\n长按任意图标可改名、换图标、拖动位置或拖拽缩放。";
            default: return "";
        }
    }

    private float sizeFor(String key) {
        switch (key) {
            case "name": return 20;
            case "feedclock": return 30;
            case "status": return 12;
            default: return 12;
        }
    }

    private void handleTextClick(String key) {
        if ("feedclock".equals(key) || "feeddate".equals(key) || "region".equals(key)
                || "status".equals(key) || "oldhint".equals(key)) {
            toast(textFor(key));
            return;
        }
        String storeKey = key;
        String title;
        switch (key) {
            case "name": title = "编辑名字"; break;
            case "account": title = "编辑账号"; break;
            case "bio": title = "编辑简介"; break;
            case "feedtitle": title = "编辑标题"; break;
            case "feednote": title = "记一句此刻"; break;
            default: title = "编辑文字";
        }
        showTextDialog(storeKey, title, DeskSkinStore.text(this, storeKey, textFor(key)));
    }

    /** 旧功能入口角标：图标在上、名字在正下方居中。 */
    private View oldEntryCell(final OldEntryStore.Entry e) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setBackgroundColor(Color.TRANSPARENT);
        int side = dpI(46);
        View iconView = iconView("old_" + e.id, e.icon, side);
        LinearLayout ip = new LinearLayout(this);
        ip.setGravity(Gravity.CENTER);
        ip.addView(iconView, new LinearLayout.LayoutParams(side, side));
        cell.addView(ip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView lb = tv(e.name, 10.5f, t.subtext);
        lb.setGravity(Gravity.CENTER);
        lb.setSingleLine(true);
        lb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(lb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setClickable(false);
        return cell;
    }

    // ============================================================
    // 查找
    // ============================================================
    private DeskFeatureStore.Feature findFeature(String id) {
        for (DeskFeatureStore.Feature f : DeskFeatureStore.all(this)) if (f.id.equals(id)) return f;
        return null;
    }
    private OldEntryStore.Entry findOld(String id) {
        for (OldEntryStore.Entry e : OldEntryStore.all(this)) if (e.id.equals(id)) return e;
        return null;
    }

    // ============================================================
    // 背景
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

    /** 页设置入口行：右侧设置图标（图片无字符），第 2、3、4 页左侧返回箭头（图片）。 */
    private View pageSettingRow(final int pageIndex) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int iconSide = dpI(20);
        if (pageIndex > 0) {
            TextView back = new TextView(this);
            Drawable bd = iconDrawable(R.drawable.ic_back);
            if (bd != null) bd.setBounds(0, 0, iconSide, iconSide);
            back.setCompoundDrawables(bd, null, null, null);
            back.setPadding(0, 0, dpI(10), 0);
            back.setMinWidth(iconSide);
            back.setMinimumHeight(iconSide);
            back.setOnClickListener(v -> goPage(pageIndex - 1));
            row.addView(back);
        }
        TextView gear = new TextView(this);
        Drawable gd = iconDrawable(R.drawable.ic_settings);
        if (gd != null) gd.setBounds(0, 0, iconSide, iconSide);
        gear.setCompoundDrawables(gd, null, null, null);
        gear.setMinWidth(iconSide);
        gear.setMinimumHeight(iconSide);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.weight = 1; gp.gravity = Gravity.END;
        gear.setOnClickListener(v -> showPageMenu(pageIndex));
        row.addView(gear, gp);
        return row;
    }

    /** 加载内置 drawable，并按当前文字弱色做 tint，保持同色系统一。 */
    private Drawable iconDrawable(int res) {
        try {
            Drawable d = getResources().getDrawable(res, null);
            if (d == null) return null;
            d = d.mutate();
            d.setTint(t.subtext);
            return d;
        } catch (Exception e) { return null; }
    }

    // ============================================================
    // 选图 / 结果
    // ============================================================
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

    private void pickFeatureIcon(final String assetCategory, final String id) {
        pendingIconCategory = assetCategory;
        pendingIconId = id;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, 6000);
        } catch (Exception e) {
            toast("相册没有接住选图：" + ScreenshotService.shortMsg(e));
        }
    }
    private String pendingIconCategory = "";
    private String pendingIconId = "";

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == 6000) {
            Uri uri = DeskAssetStore.pickResult(this, result, data);
            if (uri != null && pendingIconId != null && !pendingIconId.isEmpty()) {
                DeskAssetStore.setFeatureIconUri(this, pendingIconId, uri.toString());
                rebuildAll();
                toast("图标已换成你上传的图片");
            }
            pendingIconId = "";
            return;
        }
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
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add("更换本页背景图");
        opts.add("选纯色背景");
        opts.add("恢复默认背景");
        opts.add("恢复本页摆放");
        if (page == PAGE_ALBUM) opts.add("添加贴图");
        if (page == PAGE_OLD) { opts.add("添加贴图"); opts.add("恢复旧功能入口默认"); }
        final String[] arr = opts.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("第 " + (page + 1) + " 页设置")
                .setItems(arr, (d, w) -> {
                    String s = arr[w];
                    if ("更换本页背景图".equals(s)) pickPageBg(page);
                    else if ("选纯色背景".equals(s)) showBgColorDialog(page);
                    else if ("恢复默认背景".equals(s)) {
                        DeskSkinStore.setPageBgUri(this, page, "");
                        DeskSkinStore.setPageBgColor(this, page, 0);
                        rebuildPage(page);
                        toast("已恢复默认背景");
                    } else if ("恢复本页摆放".equals(s)) {
                        DeskLayoutStore.resetPage(this, page);
                        rebuildPage(page);
                        toast("已恢复本页默认摆放");
                    } else if ("添加贴图".equals(s)) {
                        String empty = page == PAGE_OLD
                                ? DeskSkinStore.firstEmptyOldSlot(this)
                                : DeskSkinStore.firstEmptyAlbumSlot(this);
                        if (empty == null) toast("贴图已达上限（6 张）");
                        else pickImage(empty);
                    } else if ("恢复旧功能入口默认".equals(s)) {
                        OldEntryStore.resetAll(this);
                        DeskLayoutStore.resetPage(this, PAGE_OLD);
                        rebuildPage(PAGE_OLD);
                        toast("旧功能入口已恢复默认");
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
                .setItems(new String[]{"改名", "自定义上传图标", "选内置图标",
                                "前移", "后移", "恢复这一个默认", "清除自定义图标"}, (d, w) -> {
                    if (w == 0) showRenameFeatureDialog(f);
                    else if (w == 1) pickFeatureIcon("feature", f.id);
                    else if (w == 2) showBuiltinIconDialog(f.id);
                    else if (w == 3) moveFeature(f, -1);
                    else if (w == 4) moveFeature(f, 1);
                    else if (w == 5) {
                        DeskFeatureStore.setIcon(this, f.id, f.defIcon);
                        DeskFeatureStore.setName(this, f.id, f.defName);
                        DeskAssetStore.clearFeatureIconUri(this, f.id);
                        DeskIconPack.resetOne(this, f.id);
                        DeskLayoutStore.remove(this, pageOfFeature(f.id), FreeDeskLayout.KIND_FEATURE, f.id);
                        rebuildAll();
                        toast("已恢复默认");
                    } else if (w == 6) {
                        DeskAssetStore.clearFeatureIconUri(this, f.id);
                        rebuildAll();
                        toast("已清除自定义上传图标");
                    }
                }).show();
    }

    private int pageOfFeature(String id) {
        List<DeskFeatureStore.Feature> all = DeskFeatureStore.all(this);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(id)) {
            if (i < 4) return PAGE_PROFILE;
            if (i < 16) return PAGE_ALBUM;
            return PAGE_FEED;
        }
        return PAGE_PROFILE;
    }

    private void editOldEntryDialog(final OldEntryStore.Entry e) {
        new AlertDialog.Builder(this).setTitle("入口：" + e.name)
                .setItems(new String[]{"改名", "自定义上传图标", "选内置图标", "恢复默认", "清除自定义图标"}, (d, w) -> {
                    if (w == 0) {
                        final EditText name = new EditText(this);
                        name.setText(e.name);
                        new AlertDialog.Builder(this).setTitle("重命名：" + e.name).setView(name)
                                .setPositiveButton("保存", (dd, ww) -> {
                                    String n = name.getText().toString().trim();
                                    if (!n.isEmpty()) OldEntryStore.setName(this, e.id, n);
                                    rebuildPage(PAGE_OLD);
                                    toast("已保存");
                                }).setNegativeButton("取消", null).show();
                    } else if (w == 1) pickFeatureIcon("old", e.id);
                    else if (w == 2) showBuiltinIconDialog("old_" + e.id);
                    else if (w == 3) {
                        OldEntryStore.resetOne(this, e.id);
                        DeskIconPack.resetOne(this, "old_" + e.id);
                        DeskLayoutStore.remove(this, PAGE_OLD, "old", e.id);
                        rebuildPage(PAGE_OLD);
                        toast("已恢复默认");
                    } else if (w == 4) {
                        DeskAssetStore.clearFeatureIconUri(this, "old_" + e.id);
                        rebuildPage(PAGE_OLD);
                        toast("已清除自定义上传图标");
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

    /** 从 UI 同色系的几何图标里选一张作为角标图标（全部为图片，无字符图标）。 */
    private void showBuiltinIconDialog(final String assetKey) {
        String[] opts = new String[DeskIconPack.COUNT];
        for (int i = 0; i < DeskIconPack.COUNT; i++) opts[i] = (i + 1) + ". " + DeskIconPack.NAMES[i];
        new AlertDialog.Builder(this).setTitle("选内置图标（" + DeskIconPack.COUNT + " 款同色系）")
                .setItems(opts, (d, w) -> {
                    DeskIconPack.setIndex(this, assetKey, w);
                    rebuildAll();
                    toast("角标图标已更换");
                }).show();
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
            } else if (a.startsWith("old:")) {
                openOldPage(a.substring(4));
            } else toast(label);
        } catch (Exception e) {
            toast("打开失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private void openOldPage(String page) {
        Intent i = new Intent(this, MainActivity.class);
        if ("diary".equals(page)) i.putExtra("diary", true);
        else if ("calendar".equals(page)) i.putExtra("calendar", true);
        else if ("connection".equals(page)) i.putExtra("connection", true);
        else if ("guidian".equals(page)) i.putExtra("guidian", true);
        else if ("see".equals(page) || "gate".equals(page) || "settings".equals(page) || "life".equals(page))
            i.putExtra("tab", page);
        else return;
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
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

    private String statusLine() {
        String n = DeskSkinStore.text(this, "feednote", "");
        return n.isEmpty() ? ("此刻 " + weatherShort() + " · " + currentPlaceLabel()) : n;
    }

    // ============================================================
    // UI 小工具
    // ============================================================
    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(16), dpI(12), dpI(16), dpI(12));
        return col;
    }

    private TextView tv(String s, float size, int color) {
        TextView v = new TextView(this);
        v.setText(s == null ? "" : s);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private View spacer(int dpV) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(dpV)));
        return v;
    }

    private int dpI(float v) { return UiTokens.dpI(v); }
    private float dp(float v) { return UiTokens.dp(v); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
