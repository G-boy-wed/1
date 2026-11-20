package dev.linjian.peek;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
 * 双人共同观看 / 吐槽界面（对应 MCP watch_party_* 工具）。
 *
 * 参考“双人一起刷视频 / 共同看电影”设计：
 *  - 顶部影片卡（封面/标题/博主）+ 进度条 + 播放暂停；
 *  - 双方头像、位置、时间同屏显示（“在一起”感）；
 *  - 弹幕飘屏 + 表情互动行；
 *  - 吐槽输入框，发出后本机落地并镜像服务端，MCP 可读、对方可见。
 * 所有数据来自 WatchPartyState（与服务端双向同步），空态给出明确引导。
 */
public class WatchPartyActivity extends Activity {

    private UITheme t;
    private Handler ui;
    private Timer ticker;
    private LinearLayout root, danmakuList, reactionRow;
    private TextView mediaTitle, mediaAuthor, mediaCover, progressText, memberInfo, partnerInfo, emptyHint;
    private SeekBar seekBar;
    private EditText input;
    private boolean userSeeking = false;

    private static final String[] EMOJIS = {"❤️", "👍", "👏", "😂", "😮", "😢", "🔥", "🌹"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = UITheme.current(this);
        ui = new Handler(Looper.getMainLooper());
        build();
        refresh();
        // 每 1.5s 对齐房间状态（进度/新弹幕/新表情），实现双人“同步”观感。
        ticker = new Timer();
        ticker.schedule(new TimerTask() {
            @Override public void run() { ui.post(() -> refresh()); }
        }, 1500, 1500);
    }

    @Override protected void onDestroy() {
        try { if (ticker != null) ticker.cancel(); } catch (Exception ignored) { }
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
        title.setText("一起看 · 双人同步观看");
        title.setTextSize(22);
        title.setTextColor(t.text);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        emptyHint = new TextView(this);
        emptyHint.setTextSize(13);
        emptyHint.setTextColor(t.subtext);
        emptyHint.setPadding(0, dp(6), 0, dp(14));
        root.addView(emptyHint);

        // —— 影片卡 ——
        LinearLayout hero = UiKit.glassCard(this, 18);
        root.addView(hero, lp(dp(16)));
        mediaCover = new TextView(this);
        mediaCover.setText("▣");
        mediaCover.setTextSize(64);
        mediaCover.setGravity(Gravity.CENTER);
        mediaCover.setTextColor(t.primary);
        mediaCover.setBackground(t.soft(14));
        hero.addView(mediaCover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        mediaTitle = new TextView(this);
        mediaTitle.setTextSize(18);
        mediaTitle.setTextColor(t.text);
        mediaTitle.getPaint().setFakeBoldText(true);
        mediaTitle.setPadding(dp(4), dp(12), dp(4), 0);
        hero.addView(mediaTitle);
        mediaAuthor = new TextView(this);
        mediaAuthor.setTextSize(13);
        mediaAuthor.setTextColor(t.subtext);
        mediaAuthor.setPadding(dp(4), dp(2), dp(4), dp(8));
        hero.addView(mediaAuthor);

        // —— 进度条 ——
        progressText = new TextView(this);
        progressText.setTextSize(12);
        progressText.setTextColor(t.subtext);
        hero.addView(progressText);
        seekBar = new SeekBar(this);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                long ms = (long) (s.getProgress() / 100.0 * WatchPartyState.room(WatchPartyActivity.this).optLong("duration_ms", 0));
                sendServer("watch_party_sync", "progress_ms", ms);
            }
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
        });
        hero.addView(seekBar, lp(0));

        // —— 播放控制 ——
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);
        ctrl.setPadding(0, dp(8), 0, dp(8));
        hero.addView(ctrl, lp(0));
        Button playBtn = UiKit.whitePill(this, "▶ 播放");
        playBtn.setOnClickListener(v -> sendServer("watch_party_sync", "playing", true));
        ctrl.addView(playBtn, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
        Button pauseBtn = UiKit.whitePill(this, "❚❚ 暂停");
        pauseBtn.setOnClickListener(v -> sendServer("watch_party_sync", "playing", false));
        ctrl.addView(pauseBtn, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // —— 弹幕飘屏区 ——
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

        // —— 表情互动行 ——
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

        Button end = UiKit.whitePill(this, "结束一起看");
        end.setOnClickListener(v -> {
            WatchPartyState.end(this);
            sendServer("watch_party_end", "", "");
            finish();
        });
        root.addView(end, lp(dp(6)));
    }

    private LinearLayout.LayoutParams lp(int top) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        emptyHint.setText(active ? "● 正在同步观看，进度与吐槽实时对齐" : "○ 尚未发起一起看：可让陪伴对象用 MCP 发起，或点下方按钮开始本机房间");
        JSONObject media = room.optJSONObject("media");
        if (media == null) media = new JSONObject();
        String cover = media.optString("cover", "");
        mediaCover.setText(cover != null && !cover.isEmpty() ? cover.substring(0, Math.min(2, cover.length())) : "▣");
        mediaTitle.setText(active && !media.optString("title", "").isEmpty() ? media.optString("title") : "还没有影片");
        mediaAuthor.setText(media.optString("author", "").isEmpty() ? "等待发起…" : ("博主 / 来源：" + media.optString("author")));

        long prog = room.optLong("progress_ms", 0), dur = room.optLong("duration_ms", 0);
        int pct = dur > 0 ? (int) (prog * 100 / dur) : 0;
        if (!userSeeking) seekBar.setProgress(Math.max(0, Math.min(100, pct)));
        progressText.setText(fmt(prog) + " / " + fmt(dur) + "  (" + pct + "%)");

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

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", s / 60, s % 60);
    }
}
