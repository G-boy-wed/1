package dev.linjian.peek;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 一起看 —— 共享「我手机上正在看的抖音/电影」给对方（不是单独开播放器上传视频）。
 *
 * 用户点「同意共享本机正在看的」后，本页每 1.5s 从 MediaState.collect() 读取
 * 本机真实媒体会话（抖音/快手/B站/腾讯视频…的标题、作者、包名、进度、时长、播放状态），
 * 写入一起看房间并镜像服务端；对方与 MCP（watch_party_*）读到的是我当下真实在看的内容。
 * 同时保留：弹幕吐槽、表情互动、双方位置/头像/时间同屏。
 */
public class WatchPartyActivity extends Activity {

    private UITheme t;
    private Handler ui;
    private Timer ticker;
    private LinearLayout root, danmakuList, reactionRow;
    private TextView mediaTitle, mediaAuthor, mediaApp, progressText, memberInfo, partnerInfo, emptyHint, shareBtn;
    private SeekBar seekBar;
    private EditText input;
    private boolean userSeeking = false;
    // [U7-FIX] sharing 在 UI 线程改写、Timer 后台线程读取，声明 volatile 保证跨线程可见性。
    private volatile boolean sharing = false;

    private static final String[] EMOJIS = {"❤️", "👍", "👏", "😂", "😮", "😢", "🔥", "🌹"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = UITheme.current(this);
        ui = new Handler(Looper.getMainLooper());
        sharing = WatchPartyState.room(this).optBoolean("from_live", false)
                && WatchPartyState.room(this).optBoolean("active", false);
        build();
        refresh();
        // 每 1.5s：若已同意共享，抓本机真实媒体同步进房间；再对齐房间 UI（进度/新弹幕/新表情）。
        ticker = new Timer();
        ticker.schedule(new TimerTask() {
            @Override public void run() {
                if (sharing) {
                    try {
                        JSONObject live = MediaState.collect(WatchPartyActivity.this);
                        if (WatchPartyState.applyLiveMedia(WatchPartyActivity.this, live)) {
                            // 即时把真实媒体镜像到服务端，MCP get_watch_party 能读到我此刻在看什么。
                            pushSnapshot();
                        }
                    } catch (Exception ignored) { }
                }
                // 拉取服务端房间：对方（TA/机端）发的弹幕/表情/位置能显示在我这页。
                pullRemote();
                ui.post(() -> refresh());
            }
        }, 1200, 1500);
    }

    @Override protected void onDestroy() {
        try { if (ticker != null) ticker.cancel(); } catch (Exception ignored) { }
        // [U6-FIX] 清理 Timer 已 post 但未执行的 refresh 回调，避免销毁后仍执行一次。
        try { if (ui != null) ui.removeCallbacksAndMessages(null); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bgTop);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bgTop);
        int pad = dp(18);
        root.setPadding(pad, dp(28), pad, dp(28));
        sv.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        TextView back = new TextView(this);
        back.setText("‹ 返回掌心窗");
        back.setTextSize(14);
        back.setTextColor(t.primary);
        back.setPadding(0, 0, 0, dp(14));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        TextView title = new TextView(this);
        title.setText("一起看 · 共享我正在看的");
        title.setTextSize(22);
        title.setTextColor(t.text);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        // —— 同意共享开关（核心）——
        shareBtn = UiKit.primaryPill(this, sharing ? "● 已同意共享本机正在看的（点此停止）" : "同意共享本机正在看的抖音/电影");
        shareBtn.setTextSize(14);
        shareBtn.setPadding(dp(14), dp(12), dp(14), dp(12));
        shareBtn.setOnClickListener(v -> toggleShare());
        root.addView(shareBtn, lp(dp(12)));

        emptyHint = new TextView(this);
        emptyHint.setTextSize(13);
        emptyHint.setTextColor(t.subtext);
        emptyHint.setPadding(0, dp(2), 0, dp(12));
        root.addView(emptyHint);

        // —— 影片卡（显示本机真实媒体）——
        LinearLayout hero = UiKit.glassCard(this, 18);
        root.addView(hero, lp(dp(6)));
        mediaApp = new TextView(this);
        mediaApp.setTextSize(13);
        mediaApp.setTextColor(t.primary);
        mediaApp.setPadding(dp(4), dp(2), dp(4), dp(2));
        hero.addView(mediaApp);

        mediaTitle = new TextView(this);
        mediaTitle.setTextSize(18);
        mediaTitle.setTextColor(t.text);
        mediaTitle.getPaint().setFakeBoldText(true);
        mediaTitle.setPadding(dp(4), dp(6), dp(4), 0);
        hero.addView(mediaTitle);
        mediaAuthor = new TextView(this);
        mediaAuthor.setTextSize(13);
        mediaAuthor.setTextColor(t.subtext);
        mediaAuthor.setPadding(dp(4), dp(2), dp(4), dp(8));
        hero.addView(mediaAuthor);

        progressText = new TextView(this);
        progressText.setTextSize(12);
        progressText.setTextColor(t.subtext);
        hero.addView(progressText);
        seekBar = new SeekBar(this);
        seekBar.setEnabled(false); // 只读展示本机真实进度（内容在我手机的抖音/电影里，不在本页播放）
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { userSeeking = false; }
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
        });
        hero.addView(seekBar, lp(0));

        // —— 双方信息（位置/头像/时间）——
        LinearLayout duo = new LinearLayout(this);
        duo.setOrientation(LinearLayout.HORIZONTAL);
        duo.setWeightSum(2);
        memberInfo = new TextView(this);
        partnerInfo = new TextView(this);
        memberInfo.setTextSize(13);
        partnerInfo.setTextSize(13);
        memberInfo.setTextColor(t.text);
        partnerInfo.setTextColor(t.text);
        memberInfo.setBackground(t.soft(12));
        partnerInfo.setBackground(t.soft(12));
        memberInfo.setPadding(dp(12), dp(12), dp(12), dp(12));
        partnerInfo.setPadding(dp(12), dp(12), dp(12), dp(12));
        duo.addView(memberInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        duo.addView(partnerInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(duo, lp(dp(10)));

        // —— 弹幕区 ——
        TextView dTitle = new TextView(this);
        dTitle.setText("弹幕 · 吐槽");
        dTitle.setTextSize(15);
        dTitle.setTextColor(t.text);
        dTitle.getPaint().setFakeBoldText(true);
        dTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(dTitle);
        danmakuList = new LinearLayout(this);
        danmakuList.setOrientation(LinearLayout.VERTICAL);
        danmakuList.setBackground(t.soft(12));
        danmakuList.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(danmakuList, lp(0));

        // —— 表情互动 ——
        reactionRow = new LinearLayout(this);
        reactionRow.setOrientation(LinearLayout.HORIZONTAL);
        reactionRow.setGravity(Gravity.CENTER);
        root.addView(reactionRow, lp(dp(10)));
        for (String em : EMOJIS) {
            TextView e = new TextView(this);
            e.setText(em);
            e.setTextSize(22);
            e.setPadding(dp(8), dp(6), dp(8), dp(6));
            final String emo = em;
            e.setOnClickListener(v -> {
                WatchPartyState.addReaction(this, emo, "");
                sendServer("watch_party_reaction", "emoji", emo);
                refresh();
            });
            reactionRow.addView(e);
        }

        // —— 吐槽输入 ——
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("发条弹幕吐槽…");
        input.setTextSize(14);
        input.setTextColor(t.text);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(t.soft(10));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button send = UiKit.primaryPill(this, "发送");
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) { UiKit.toast(this, "先写点什么"); return; }
            WatchPartyState.addDanmaku(this, text, "");
            sendServer("watch_party_danmaku", "text", text);
            input.setText("");
            refresh();
        });
        inputRow.addView(send, new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(inputRow, lp(dp(12)));
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    /** 切换「同意共享本机正在看的」。 */
    private void toggleShare() {
        sharing = !sharing;
        if (sharing) {
            WatchPartyState.enableLive(this);
            JSONObject live = MediaState.collect(this);
            boolean got = WatchPartyState.applyLiveMedia(this, live);
            sendServer("watch_party_start", "", null);
            pushSnapshot();
            UiKit.toast(this, got ? "已同意共享：对方现在能看到你正在看的" : "已同意共享：打开抖音/电影后自动同步");
        } else {
            WatchPartyState.end(this);
            sendServer("watch_party_end", "", null);
            UiKit.toast(this, "已停止共享");
        }
        refresh();
    }

    /** 立即把房间快照（含本机真实媒体）上报到服务端，MCP / 对方可读。 */
    private void pushSnapshot() {
        try {
            String base = AppPrefs.server(this), token = AppPrefs.token(this);
            if (base == null || base.trim().isEmpty()) return;
            CompanionService.handleWatchPartyLocal(this, base, token, new JSONObject());
        } catch (Exception ignored) { }
    }

    /** 从服务端拉取房间（含对方弹幕/表情/位置），合并进本机；本机真实媒体进度不被覆盖。 */
    private void pullRemote() {
        try {
            String base = AppPrefs.server(this), token = AppPrefs.token(this);
            if (base == null || base.trim().isEmpty()) return;
            String body = CompanionService.getJson(base.replaceAll("/+$", "") + "/api/watch_party", token);
            if (body == null || body.trim().isEmpty()) return;
            JSONObject resp = new JSONObject(body);
            JSONObject wp = resp.optJSONObject("watch_party");
            if (wp != null) WatchPartyState.mergeRemote(this, wp);
        } catch (Exception ignored) { }
    }

    /** 把本机操作写成命令入队，由 CompanionService 上报服务端（MCP 可读、对方可见）。 */
    private void sendServer(String action, String key, Object value) {
        try {
            String base = AppPrefs.server(this), token = AppPrefs.token(this);
            if (base == null || base.trim().isEmpty()) return;
            JSONObject payload = new JSONObject();
            payload.put("action", action);
            payload.put("device_id", AppPrefs.device(this));
            if (key != null && !key.isEmpty() && value != null) payload.put(key, value);
            if (value instanceof Long) payload.put("progress_ms", value);
            CompanionService.handleWatchPartyLocal(this, base, token, payload);
        } catch (Exception ignored) { }
    }

    private void refresh() {
        if (isFinishing() || isDestroyed()) return;
        JSONObject room = WatchPartyState.room(this);
        boolean active = room.optBoolean("active", false);
        // [FIX] 用房间状态回写本机共享态：对方/机端结束共享(active=false)时，本页不再停留在旧的
        // 「已同意共享」；只有本机 live 共享(from_live=true)才算我方同意，避免对方单独开房间误触发。
        boolean fromLive = room.optBoolean("from_live", false);
        if (!active || !fromLive) sharing = false;
        else sharing = true;
        shareBtn.setText(sharing ? "● 已同意共享本机正在看的（点此停止）" : "同意共享本机正在看的抖音/电影");

        JSONObject media = room.optJSONObject("media");
        if (media == null) media = new JSONObject();
        String app = media.optString("app", "");
        mediaApp.setText(sharing && !app.isEmpty() ? "▶ 来自 " + app : (sharing ? "▶ 本机媒体" : ""));
        String title = media.optString("title", "");
        mediaTitle.setText(sharing && !title.isEmpty() ? title : (sharing ? "等待本机播放…" : "还没同意共享"));
        String author = media.optString("author", "");
        mediaAuthor.setText(sharing && !author.isEmpty() ? ("作者 / 来源：" + author) : (sharing ? "打开抖音/电影后自动同步" : "点上方按钮，把你正在看的分享给 TA"));

        emptyHint.setText(sharing
                ? (room.optBoolean("available", true) ? "● 正在共享你手机真实播放的内容，进度与吐槽实时同步" : "○ 已同意共享，但本机暂无正在播放的抖音/电影——打开后会自动同步")
                : "○ 共享你正在看的抖音/电影，对方就能和你一起看（不用上传视频）");

        long prog = optLong(room, "progress_ms"), dur = optLong(room, "duration_ms");
        int pct = dur > 0 ? (int) (prog * 100 / dur) : 0;
        if (!userSeeking) seekBar.setProgress(Math.max(0, Math.min(100, pct)));
        progressText.setText(fmt(prog) + " / " + fmt(dur) + (dur > 0 ? "  (" + pct + "%)" : ""));

        JSONObject mem = room.optJSONObject("member");
        JSONObject par = room.optJSONObject("partner");
        memberInfo.setText("我\n" + (mem != null && !mem.optString("avatar", "").isEmpty() ? mem.optString("avatar") + " " : "🙂 ")
                + (mem != null && !mem.optString("position", "").isEmpty() ? mem.optString("position") : "本机")
                + "\n" + (mem != null && !mem.optString("time", "").isEmpty() ? mem.optString("time") : WatchPartyState.nowTime()));
        partnerInfo.setText("TA\n" + (par != null && !par.optString("avatar", "").isEmpty() ? par.optString("avatar") + " " : "💛 ")
                + (par != null && !par.optString("position", "").isEmpty() ? par.optString("position") : "对方")
                + "\n" + (par != null && !par.optString("time", "").isEmpty() ? par.optString("time") : WatchPartyState.nowTime()));

        danmakuList.removeAllViews();
        JSONArray arr = room.optJSONArray("danmaku");
        if (arr == null || arr.length() == 0) {
            TextView e = new TextView(this);
            e.setText("还没有弹幕，来发第一句吐槽吧～");
            e.setTextSize(12); e.setTextColor(t.subtext);
            danmakuList.addView(e);
        } else {
            int from = Math.max(0, arr.length() - 30);
            for (int i = from; i < arr.length(); i++) {
                JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                TextView line = new TextView(this);
                line.setText(d.optString("author", "我") + "：" + d.optString("text", ""));
                line.setTextSize(13); line.setTextColor(t.text);
                line.setPadding(0, dp(3), 0, dp(3));
                danmakuList.addView(line);
            }
        }
    }

    private static long optLong(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", s / 60, s % 60);
    }
}
