package dev.linjian.peek;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * App 内「查岗与管束」设置页（v2：本机优先、立即生效）。
 *
 * 设计要点（对应需求）：
 *  - 所有参数只存本机 SharedPreferences，改任意一项立即生效，不用重启 App / 后端 / MCP；
 *  - 每一项都有默认值，可一键恢复默认；
 *  - 分组可折叠、可搜索，每项配一句人话说明它到底干什么；
 *  - 安静时段 / 深夜时段支持跨零点；冷却改参数后重算；想她可锁定；
 *  - 参数变更自动留一行记录；支持导出 / 导入一份配置 JSON（剪贴板）；
 *  - 一键全部关闭，用户能自己拿回手机；
 *  - 网络同步（若配置了服务器）仅作可选 best-effort，永不阻塞 UI、不依赖。
 */
public class PolicySettingsActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout container;
    private EditText search;
    private TextView statusText, changeLogText;
    private final List<View> allRows = new ArrayList<>();
    private String emotionTagDraft = "";
    private int emotionIntensityDraft = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_policy_settings);

        container = findViewById(R.id.policyContainer);
        search = findViewById(R.id.policySearch);
        statusText = findViewById(R.id.policyStatusText);
        changeLogText = findViewById(R.id.policyChangeLog);

        buildUI();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString().trim()); }
            @Override public void afterTextChanged(Editable s) { }
        });

        refreshStatus();
    }

    private void buildUI() {
        container.removeAllViews();
        allRows.clear();

        LinearLayout top = card(container, "全局", "总开关与配置管理");
        top.addView(rowSwitch("一键全部关闭（拿回手机）", "关掉后所有查岗、拉回、主动找她、训诫全部暂停。",
                PolicyStore.isMasterOn(this), (v, c) -> PolicyStore.setMaster(this, c)));
        top.addView(rowButton("恢复默认", "把所有参数恢复成默认值（不会清空变更记录）。", "恢复默认",
                v -> { PolicyStore.resetToDefaults(this); buildUI(); Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show(); }));
        top.addView(rowButton("导出配置", "把全部配置复制成 JSON 到剪贴板，方便备份或换机。", "导出到剪贴板", v -> exportConfig()));
        top.addView(rowButton("导入配置", "从剪贴板读取之前导出的 JSON 并应用。", "从剪贴板导入", v -> importConfig()));

        LinearLayout g1 = card(container, "查岗与拉回", "多久查一次、想她到什么程度强制拉回、拉回长什么样。");
        g1.addView(rowSwitch("开启查岗", "关闭后不再自动查岗（其它拉回逻辑仍可手动触发）。", PolicyStore.checkinEnabled(this), (v, c) -> PolicyStore.setCheckinEnabled(this, c)));
        g1.addView(rowNumber("查岗随机上限（分钟）", "每次查岗间隔在这个上限内随机取值，避免太机械。默认 60。", PolicyStore.randomUpper(this), 5, 1440, v -> PolicyStore.setRandomUpper(this, v)));
        g1.addView(rowNumber("查岗随机下限（分钟）", "间隔不会低于这个值，避免连续两次挨着弹。默认 8。", PolicyStore.randomLower(this), 1, 120, v -> PolicyStore.setRandomLower(this, v)));
        g1.addView(rowNumber("拉回分级阈值（%）", "想她达到该值→强级弹窗（只给「现在就回去」）；低于则软级（可拒绝）。默认 70。", PolicyStore.forceBackThreshold(this), 1, 100, v -> PolicyStore.setForceBackThreshold(this, v)));
        g1.addView(rowSwitch("拒绝理由必填", "软级弹窗拒绝拉回必须留理由才放你走；关闭则可空拒绝。", PolicyStore.declineReasonRequired(this), (v, c) -> PolicyStore.setDeclineReasonRequired(this, c)));
        g1.addView(rowText("拉回目标 App", "点「现在就回去」要跳回的 App 包名。默认 com.psyche.kelivo。", PolicyStore.targetPackage(this), s -> PolicyStore.setTargetPackage(this, s)));
        g1.addView(rowSpinner("弹窗主题", "跟随归电，或单独覆盖一套主题。", themeOptions(), themeOverrideIndex(), s -> PolicyStore.setThemeOverride(this, normalizeTheme(s))));
        g1.addView(rowSpinner("弹窗位置", "跟随归电，或单独覆盖（顶部/居中/底部）。", positionOptions(), positionOverrideIndex(), s -> PolicyStore.setPositionOverride(this, normalizePosition(s))));
        g1.addView(rowNumber("冷却时间（分钟）", "同类提醒最短间隔；改任意参数后冷却立即重算。默认 5。", PolicyStore.cooldownMinutes(this), 0, 240, v -> PolicyStore.setCooldown(this, v)));
        g1.addView(rowTimePair("安静时段", "这段时间里不主动打扰（支持跨零点，如 23:30→08:00）。", PolicyStore.quietStart(this), PolicyStore.quietEnd(this), s -> PolicyStore.setQuietStart(this, s), s -> PolicyStore.setQuietEnd(this, s)));
        g1.addView(rowTimePair("深夜时段", "用于情绪感知判断的深夜范围（支持跨零点）。", PolicyStore.nightStart(this), PolicyStore.nightEnd(this), s -> PolicyStore.setNightStart(this, s), s -> PolicyStore.setNightEnd(this, s)));

        LinearLayout g2 = card(container, "陪伴情绪", "她自己的情绪，独立于你的手机状态、不覆盖你的快照。");
        g2.addView(rowNumber("想她百分比（当前值）", "手动写当前的想她程度；锁定后不被自动衰减盖回。", PolicyStore.emotionCurrentMiss(this), 0, 100, v -> PolicyStore.setEmotionCurrentMiss(this, v)));
        g2.addView(rowSwitch("想她锁定", "打开后当前想她值固定不变，衰减与累积都不再动它。", PolicyStore.emotionLock(this), (v, c) -> PolicyStore.setEmotionLock(this, c)));
        g2.addView(rowNumber("起始值", "很久没互动时的初始想她值，低一点更自然。默认 8。", PolicyStore.emotionStartMiss(this), 0, 100, v -> PolicyStore.setEmotionStartMiss(this, v)));
        g2.addView(rowNumber("每30分钟增长下限", "你越久不回来想她累积越快；这是增长的下限。默认 2。", PolicyStore.emotionGainMin(this), 0, 20, v -> PolicyStore.setEmotionGainMin(this, v)));
        g2.addView(rowNumber("每30分钟增长上限", "增长上限，配合下限形成随机浮动，不会一直匀速。默认 5。", PolicyStore.emotionGainMax(this), 0, 20, v -> PolicyStore.setEmotionGainMax(this, v)));
        g2.addView(rowNumber("回落速度（每小时）", "被安抚后想她每小时回落的点数。默认 4。", PolicyStore.emotionDecayPerHour(this), 0, 50, v -> PolicyStore.setEmotionDecayPerHour(this, v)));
        g2.addView(rowText("情绪标签", "如 平静/想你/吃醋/生气/担心。点下方「保存情绪」生效。", CompanionEmotionState.emotionTag(this), s -> emotionTagDraft = s));
        g2.addView(rowNumber("情绪强度", "0-100，影响语气与拉回频率。点下方「保存情绪」生效。", CompanionEmotionState.snapshot(this).optInt("intensity", 0), 0, 100, v -> emotionIntensityDraft = v));
        g2.addView(rowSwitch("情绪驱动文案", "打开后弹窗文案随情绪变化；关闭则用中性文案。", PolicyStore.emotionDriveText(this), (v, c) -> PolicyStore.setEmotionDriveText(this, c)));
        g2.addView(rowButton("保存情绪（标签/强度）", "把上面填的情绪标签与强度写进陪伴对象情绪。", "保存情绪",
                v -> { CompanionEmotionState.write(this, emotionTagDraft, emotionIntensityDraft, PolicyStore.emotionCurrentMiss(this), ""); Toast.makeText(this, "情绪已保存", Toast.LENGTH_SHORT).show(); refreshStatus(); }));

        LinearLayout g3 = card(container, "主动找她", "你太久没发消息，她会主动找你发一条。");
        g3.addView(rowSwitch("开启主动找她", "关闭后她不再主动发起消息。", PolicyStore.initiativeEnabled(this), (v, c) -> PolicyStore.setInitiativeEnabled(this, c)));
        g3.addView(rowNumber("沉默多久后主动发（分钟）", "超过这个时间你没发消息，就主动找你。默认 30。", PolicyStore.initiativeSilenceMinutes(this), 1, 1440, v -> PolicyStore.setInitiativeSilenceMinutes(this, v)));
        g3.addView(rowNumber("两次最小间隔（分钟）", "同一次主动找她之间的最短间隔，避免刷屏。默认 20。", PolicyStore.initiativeMinInterval(this), 1, 1440, v -> PolicyStore.setInitiativeMinInterval(this, v)));
        g3.addView(rowNumber("每日条数上限", "0=不限条数；其它数字为当天上限。默认 0。", PolicyStore.initiativeDailyLimit(this), 0, 999, v -> PolicyStore.setInitiativeDailyLimit(this, v)));
        g3.addView(rowSpinner("生成方式", "自由生成（每次内容不同）或固定模板。", new String[]{"自由生成", "自定义模板"}, PolicyStore.initiativeMode(this).equals("template") ? 1 : 0, s -> PolicyStore.setInitiativeMode(this, s.equals("自定义模板") ? "template" : "free")));
        g3.addView(rowText("自定义模板", "生成方式选「自定义模板」时使用的文案。", PolicyStore.initiativeTemplate(this), s -> PolicyStore.setInitiativeTemplate(this, s)));

        LinearLayout g4 = card(container, "训诫插件", "只你本人能开，默认关闭，分四档强度。");
        g4.addView(rowSwitch("训诫总开关", "关闭则训诫完全不生效。默认关闭。", PolicyStore.disciplineEnabled(this), (v, c) -> PolicyStore.setDisciplineEnabled(this, c)));
        g4.addView(rowSpinner("训诫档位", "0关闭/1轻/2中/3重/4极。档位越高措辞越硬。", new String[]{"0 关闭", "1 轻", "2 中", "3 重", "4 极"}, PolicyStore.disciplineLevel(this), s -> PolicyStore.setDisciplineLevel(this, parseLevel(s))));
        g4.addView(rowSwitch("开启标志", "打开后界面明确显示「训诫已开启」，让你随时知道状态。", PolicyStore.disciplineBadge(this), (v, c) -> PolicyStore.setDisciplineBadge(this, c)));
        g4.addView(rowText("说明", "仅用户本人可开启，不设安全词、不写日志，但界面必须有明确开启标志。", "仅本人可开", s -> { }));

        LinearLayout g5 = card(container, "分心判定", "检测到切屏到其它 App 时怎么处理。");
        g5.addView(rowSwitch("切屏算分心", "聊天中切到其它 App 标记为分心。默认开启。", PolicyStore.distractionEnabled(this), (v, c) -> PolicyStore.setDistractionEnabled(this, c)));
        g5.addView(rowSwitch("每次切屏都拉回", "关闭时是否拉回由她当时的情绪决定；打开则每次切屏都拉回。默认关闭。", PolicyStore.distractionEveryTime(this), (v, c) -> PolicyStore.setDistractionEveryTime(this, c)));

        LinearLayout g6 = card(container, "权限开关", "逐项管理 App 的各种守护功能。");
        addPerm(g6, PolicyStore.KEY_PERM_SCREEN_TIME, "屏幕时长上限", "限制每天可用屏幕总时长（填分钟，0=不限）。");
        addPerm(g6, PolicyStore.KEY_PERM_SLEEP, "睡前息屏", "到时间提醒息屏睡觉（填 HH:mm）。");
        addPerm(g6, PolicyStore.KEY_PERM_CURFEW, "深夜宵禁", "深夜时段限制使用（填 23:00-06:00）。");
        addPerm(g6, PolicyStore.KEY_PERM_STUDY, "学习白名单", "只放行白名单 App（填包名，逗号分隔）。");
        addPerm(g6, PolicyStore.KEY_PERM_DRINK, "喝水提醒", "每隔多久提醒喝水（填分钟）。");
        addPerm(g6, PolicyStore.KEY_PERM_SIT, "久坐提醒", "每隔多久提醒起来走动（填分钟）。");
        addPerm(g6, PolicyStore.KEY_PERM_WEEKLY, "每周回顾", "每周生成一次使用回顾（填 星期,HH:mm）。");
        addPerm(g6, PolicyStore.KEY_PERM_HOME, "到家报备", "到家后提醒报备（填地点）。");
        addPerm(g6, PolicyStore.KEY_PERM_PERIOD, "生理期照顾", "按周期给予照顾提醒（填周期天数）。");

        LinearLayout g7 = card(container, "验收与调试", "本地触发拉回弹窗、把配置同步给服务器（可选）。");
        g7.addView(rowButton("试一下软级别拉回", "想她低于阈值：两个按钮，可拒绝但必须留理由。", "试软级", v -> testPullBack(false)));
        g7.addView(rowButton("试一下强级别拉回", "想她达到阈值：只有「现在就回去」一个按钮。", "试强级", v -> testPullBack(true)));
        g7.addView(rowButton("同步给服务器（可选）", "若已配置服务器地址，把配置 best-effort 同步过去；不影响本机生效。", "同步", v -> syncToServer()));

        refreshChangeLog();
    }

    // ============ 分组卡片（可折叠）============

    private LinearLayout card(LinearLayout container, String title, String desc) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.card_bg);
        ViewGroup.MarginLayoutParams glp = new ViewGroup.MarginLayoutParams(-1, -2);
        glp.topMargin = dp(10);
        group.setLayoutParams(glp);
        group.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, dp(4), 0, dp(4));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(0xFF2D3E39);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(t);

        TextView arrow = new TextView(this);
        arrow.setText("▾");
        arrow.setTextSize(16);
        arrow.setTextColor(0xFF8B7D99);
        header.addView(arrow);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(6), 0, 0);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11);
        d.setTextColor(0xFF8B7D99);
        d.setLineSpacing(0, 1.15f);
        content.addView(d);

        header.setOnClickListener(v -> {
            boolean visible = content.getVisibility() == View.VISIBLE;
            content.setVisibility(visible ? View.GONE : View.VISIBLE);
            arrow.setText(visible ? "▸" : "▾");
        });

        group.addView(header);
        group.addView(content);
        group.setTag("group::" + title);
        container.addView(group);
        return content;
    }

    // ============ 行构造 ============

    private View rowSwitch(String label, String desc, boolean value, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = rowShell(label, desc);
        CheckBox cb = new CheckBox(this);
        cb.setChecked(value);
        cb.setOnCheckedChangeListener(l);
        row.addView(cb);
        return row;
    }

    private View rowNumber(String label, String desc, int value, int min, int max, IntSetter l) {
        LinearLayout row = rowShell(label, desc);
        EditText et = new EditText(this);
        et.setText(String.valueOf(value));
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setTextSize(13);
        et.setMinWidth(dp(80));
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { l.set(clamp(safeInt(s.toString(), min), min, max)); }
            @Override public void afterTextChanged(Editable s) { }
        });
        row.addView(et);
        return row;
    }

    private View rowText(String label, String desc, String value, TextSetter l) {
        LinearLayout row = rowShell(label, desc);
        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setTextSize(13);
        et.setMinWidth(dp(120));
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { l.set(s.toString().trim()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        row.addView(et);
        return row;
    }

    private View rowTimePair(String label, String desc, String start, String end, TextSetter ls, TextSetter le) {
        LinearLayout row = rowShell(label, desc);
        EditText s = new EditText(this); s.setHint("起 23:30"); s.setText(start); s.setTextSize(12); s.setMinWidth(dp(90));
        EditText e = new EditText(this); e.setHint("止 08:00"); e.setText(end); e.setTextSize(12); e.setMinWidth(dp(90));
        s.addTextChangedListener(tw(v -> ls.set(v)));
        e.addTextChangedListener(tw(v -> le.set(v)));
        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        pair.addView(s); pair.addView(e);
        row.addView(pair);
        return row;
    }

    private View rowSpinner(String label, String desc, String[] options, int sel, TextSetter l) {
        LinearLayout row = rowShell(label, desc);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        if (sel >= 0 && sel < options.length) sp.setSelection(sel);
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { l.set(options[position]); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        row.addView(sp);
        return row;
    }

    private View rowButton(String label, String desc, String text, View.OnClickListener l) {
        LinearLayout row = rowShell(label, desc);
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setBackgroundResource(R.drawable.pill_soft);
        b.setOnClickListener(l);
        row.addView(b);
        return row;
    }

    private LinearLayout rowShell(String label, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.setTag("row::" + label + "::" + desc);
        allRows.add(row);

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(13);
        lab.setTextColor(0xFF2D3E39);
        row.addView(lab);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(10);
        d.setTextColor(0xFF8B7D99);
        d.setLineSpacing(0, 1.15f);
        row.addView(d);
        return row;
    }

    private void addPerm(LinearLayout group, String key, String label, String desc) {
        LinearLayout row = rowShell(label, desc);
        CheckBox cb = new CheckBox(this);
        cb.setChecked(PolicyStore.permEnabled(this, key));
        cb.setOnCheckedChangeListener((v, c) -> PolicyStore.setPermEnabled(this, key, c));
        EditText et = new EditText(this);
        et.setHint("参数（可选）");
        et.setText(PolicyStore.permValue(this, key));
        et.setTextSize(12);
        et.setMinWidth(dp(120));
        et.addTextChangedListener(tw(v -> PolicyStore.setPermValue(this, key, v)));
        LinearLayout hl = new LinearLayout(this);
        hl.setOrientation(LinearLayout.HORIZONTAL);
        hl.addView(cb); hl.addView(et);
        row.addView(hl);
        group.addView(row);
    }

    // ============ 搜索 / 状态 / 变更记录 ============

    private void filter(String q) {
        q = q.toLowerCase();
        if (q.isEmpty()) {
            for (View r : allRows) r.setVisibility(View.VISIBLE);
            for (int i = 0; i < container.getChildCount(); i++) container.getChildAt(i).setVisibility(View.VISIBLE);
            return;
        }
        // 先全隐藏
        for (View r : allRows) r.setVisibility(View.GONE);
        for (int i = 0; i < container.getChildCount(); i++) container.getChildAt(i).setVisibility(View.GONE);
        // 标题匹配 -> 显示整组
        for (int i = 0; i < container.getChildCount(); i++) {
            View g = container.getChildAt(i);
            String tag = String.valueOf(g.getTag());
            if (tag.startsWith("group::") && tag.substring("group::".length()).toLowerCase().contains(q)) {
                g.setVisibility(View.VISIBLE);
                setChildrenVisible(g, View.VISIBLE);
            }
        }
        // 行关键词匹配 -> 显示该行及其所在分组
        for (View r : allRows) {
            if (String.valueOf(r.getTag()).toLowerCase().contains(q)) {
                r.setVisibility(View.VISIBLE);
                View g = findGroup(r);
                if (g != null) g.setVisibility(View.VISIBLE);
            }
        }
    }

    private View findGroup(View child) {
        View p = child;
        while (p != null && !(String.valueOf(p.getTag()).startsWith("group::"))) p = (View) p.getParent();
        return p;
    }
    private void setChildrenVisible(View group, int vis) {
        if (group instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) group).getChildCount(); i++) ((ViewGroup) group).getChildAt(i).setVisibility(vis);
        }
    }

    private void refreshStatus() {
        if (statusText == null) return;
        statusText.setText("已生效（全部本机保存） · " + PolicyStore.summary(this) + "\n"
                + (PolicyStore.isMasterOn(this) ? "总开关：开" : "总开关：已一键关闭"));
    }

    private void refreshChangeLog() {
        if (changeLogText == null) return;
        JSONArray log = PolicyStore.changeLog(this);
        StringBuilder sb = new StringBuilder("最近变更（共 ").append(PolicyStore.changeLogCount(this)).append(" 条）：\n");
        int start = Math.max(0, log.length() - 12);
        for (int i = start; i < log.length(); i++) {
            try {
                JSONObject e = log.getJSONObject(i);
                sb.append("· ").append(e.optString("who", "")).append(" ").append(e.optString("field", ""))
                        .append(" ").append(e.optString("from", "")).append("→").append(e.optString("to", ""))
                        .append(" ").append(e.optString("at", "")).append("\n");
            } catch (Exception ignored) { }
        }
        changeLogText.setText(sb.toString().trim());
    }

    // ============ 导出 / 导入 / 同步 ============

    private void exportConfig() {
        try {
            String json = PolicyStore.exportJson(this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("policy_config", json));
            Toast.makeText(this, "配置已复制到剪贴板", Toast.LENGTH_SHORT).show();
            status("已导出配置到剪贴板，可粘贴保存。");
        } catch (Exception e) {
            status("导出失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private void importConfig() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence txt = cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0
                    ? cm.getPrimaryClip().getItemAt(0).getText() : null;
            if (txt == null || txt.toString().trim().isEmpty()) {
                Toast.makeText(this, "剪贴板里没有配置 JSON", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ok = PolicyStore.importJson(this, txt.toString());
            if (ok) { buildUI(); Toast.makeText(this, "已导入配置", Toast.LENGTH_SHORT).show(); }
            else Toast.makeText(this, "导入失败（JSON 无法解析）", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            status("导入失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private void syncToServer() {
        if (!ready()) { status("未配置服务器，跳过同步（本机已生效）。"); return; }
        final String base = AppPrefs.server(this), token = AppPrefs.token(this);
        try {
            JSONObject body = new JSONObject();
            body.put("enabled", PolicyStore.checkinEnabled(this));
            body.put("check_in_interval_minutes", PolicyStore.randomUpper(this));
            body.put("force_back_interval_minutes", 0);
            body.put("cooldown_minutes", PolicyStore.cooldownMinutes(this));
            body.put("alert_tone", "gentle");
            JSONObject q = new JSONObject();
            q.put("start", PolicyStore.quietStart(this)); q.put("end", PolicyStore.quietEnd(this));
            body.put("quiet_hours", q);
            JSONObject n = new JSONObject();
            n.put("start", PolicyStore.nightStart(this)); n.put("end", PolicyStore.nightEnd(this));
            body.put("night_hours", n);
            body.put("force_back_miss_threshold", PolicyStore.forceBackThreshold(this));
            body.put("exempt_packages", new JSONArray());
            final String payload = body.toString();
            status("同步中…");
            new Thread(() -> {
                final String resp = request(base, token, "POST", "/api/tour/schedule", payload);
                main.post(() -> status((resp.startsWith("ERROR") || resp.startsWith("HTTP 4") || resp.startsWith("HTTP 5"))
                        ? ("同步失败（本机已生效）：" + resp) : "已同步给服务器，本机始终生效。"));
            }, "policy-sync").start();
        } catch (Exception e) {
            status("同步失败：" + ScreenshotService.shortMsg(e));
        }
    }

    private void testPullBack(boolean strong) {
        try {
            JSONObject cfg = new JSONObject();
            int miss = strong ? Math.max(PolicyStore.forceBackThreshold(this), 80) : 35;
            cfg.put("level", strong ? "strong" : "soft");
            cfg.put("strong", strong);
            cfg.put("allow_decline", !strong);
            cfg.put("emotion_tag", strong ? "生气" : CompanionEmotionState.emotionTag(this));
            cfg.put("miss_percent", miss);
            cfg.put("intensity", strong ? 85 : 45);
            cfg.put("threshold", PolicyStore.forceBackThreshold(this));
            cfg.put("title", strong ? AppPrefs.companionName(this) + "要你回来" : AppPrefs.companionName(this) + "在等你");
            cfg.put("message", CompanionEmotionState.message(this));
            cfg.put("tone", strong ? "angry" : "gentle");
            PullBackActivity.show(this, cfg);
        } catch (Exception e) {
            status("触发失败：" + ScreenshotService.shortMsg(e));
        }
    }

    // ============ 工具 ============

    private boolean ready() {
        String base = AppPrefs.server(this), token = AppPrefs.token(this);
        return base != null && !base.trim().isEmpty() && token != null && !token.trim().isEmpty();
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int safeInt(String s, int def) {
        try { String t = s == null ? "" : s.trim(); return t.isEmpty() ? def : Integer.parseInt(t); }
        catch (Exception e) { return def; }
    }
    private static int parseLevel(String s) {
        try { return Integer.parseInt(s.trim().split("\\s+")[0]); } catch (Exception e) { return 0; }
    }
    private static String normalizeTheme(String s) { return (s == null || s.equals("跟随归电")) ? "" : s; }
    private static String normalizePosition(String s) {
        if (s == null || s.equals("跟随归电")) return "";
        if ("顶部".equals(s)) return "top";
        if ("底部".equals(s)) return "bottom";
        return "center";
    }
    private static TextWatcher tw(TextSetter l) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { l.set(s.toString().trim()); }
            @Override public void afterTextChanged(Editable s) { }
        };
    }

    private String[] themeOptions() {
        List<String> list = new ArrayList<>();
        list.add("跟随归电");
        for (String t : GuidianTheme.themeNames()) list.add(t);
        return list.toArray(new String[0]);
    }
    private int themeOverrideIndex() {
        String o = PolicyStore.themeOverride(this);
        if (o == null || o.isEmpty()) return 0;
        String[] opts = themeOptions();
        for (int i = 0; i < opts.length; i++) if (opts[i].equals(o)) return i;
        return 0;
    }
    private String[] positionOptions() { return new String[]{"跟随归电", "顶部", "居中", "底部"}; }
    private int positionOverrideIndex() {
        String o = PolicyStore.positionOverride(this);
        if (o == null || o.isEmpty()) return 0;
        if ("top".equals(o)) return 1;
        if ("center".equals(o) || "居中".equals(o)) return 2;
        if ("bottom".equals(o) || "底部".equals(o)) return 3;
        return 0;
    }

    private void status(String s) { if (statusText != null) statusText.setText(s == null ? "" : s); }

    private static String request(String base, String token, String method, String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setRequestProperty("X-Auth-Token", token);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return code >= 400 ? ("HTTP " + code + " " + text) : text;
        } catch (Exception e) {
            return "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally { if (c != null) c.disconnect(); }
    }

    private interface IntSetter { void set(int v); }
    private interface TextSetter { void set(String v); }
}
