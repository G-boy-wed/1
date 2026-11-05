package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.ViewParent;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
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
 *  - 安静时段 / 深夜时段支持跨零点；冷却改参数后重算；想你可锁定；
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
    private String addGateDraft = "";
    private String memoryDraft = "";
    // 守护界面卡片深链：sectionKey -> 对应分组视图
    private final java.util.Map<String, View> sectionGroups = new java.util.HashMap<>();
    private String weatherNameDraft = "", weatherCityDraft = "", weatherNoteDraft = "";
    // 单分区模式：从守护页某个标签点进来时，只渲染/修改这个分区；null=全部设置（总页）
    private String activeSection = null;

    public static final String EXTRA_SECTION = "section";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_policy_settings);

        container = findViewById(R.id.policyContainer);
        search = findViewById(R.id.policySearch);
        statusText = findViewById(R.id.policyStatusText);
        changeLogText = findViewById(R.id.policyChangeLog);

        String section = getIntent() != null ? getIntent().getStringExtra(EXTRA_SECTION) : null;
        activeSection = (section == null || section.isEmpty()) ? null : section;
        buildUI();
        // 单分区模式下页面只含目标分组，从顶部即可看到，无需滚动定位

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
        sectionGroups.clear();
        if (activeSection != null && !activeSection.isEmpty()) buildSectionBanner(activeSection);

        LinearLayout top = card(container, "全局", "总开关与配置管理", "global");
        top.addView(rowButton("保存设置", "把本页所有改动确认写入本机底层存储，并让守护调度器立即按新配置运行；训诫开关与语气模式同步给服务端，陪伴对象读到的与本机一致。点保存后设置持久生效、不会丢失。", "保存设置", v -> {
            PolicyStore.flush(this);        // 同步落盘，确认改动都在底层配置里
            PolicyScheduler.start(this);    // 幂等：已在跑则无副作用，未跑则按新配置启动
            syncDisciplineToServer();       // 让 get_discipline_mode 与 App 里的严厉 / 温柔保持一致
            Toast.makeText(this, "设置已保存并生效", Toast.LENGTH_SHORT).show();
        }));
        top.addView(rowSwitch("一键全部关闭（拿回手机）", "关掉后所有查岗、拉回、主动找用户、训诫全部暂停。",
                PolicyStore.isMasterOn(this), (v, c) -> PolicyStore.setMaster(this, c)));
        top.addView(rowButton("恢复默认", "把所有参数恢复成默认值（不会清空变更记录）。", "恢复默认",
                v -> { PolicyStore.resetToDefaults(this); buildUI(); Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show(); }));
        top.addView(rowButton("导出配置", "把全部配置复制成 JSON 到剪贴板，方便备份或换机。", "导出到剪贴板", v -> exportConfig()));
        top.addView(rowButton("导入配置", "从剪贴板读取之前导出的 JSON 并应用。", "从剪贴板导入", v -> importConfig()));

        // —— 查岗与管束：本页只聚焦「查岗」本身，其它能力都在守护界面卡片直达 ——
        LinearLayout g1 = card(container, "查岗", "多久查一次、想你到什么程度强制拉回、拉回长什么样。", "checkin");
        g1.addView(rowSwitch("开启查岗", "关闭后不再自动查岗（其它拉回逻辑仍可手动触发）。", PolicyStore.checkinEnabled(this), (v, c) -> PolicyStore.setCheckinEnabled(this, c)));
        g1.addView(rowNumber("查岗随机上限（分钟）", "每次查岗间隔在这个上限内随机取值，避免太机械。默认 60。", PolicyStore.randomUpper(this), 5, 1440, v -> PolicyStore.setRandomUpper(this, v)));
        g1.addView(rowNumber("查岗随机下限（分钟）", "间隔不会低于这个值，避免连续两次挨着弹。默认 8。", PolicyStore.randomLower(this), 1, 120, v -> PolicyStore.setRandomLower(this, v)));
        g1.addView(rowNumber("拉回分级阈值（%）", "想你达到该值 → 强级弹窗（只能「回到软件」）；低于则软级（「回到软件 / 拒绝并填写理由」两选项）。默认 70。", PolicyStore.forceBackThreshold(this), 1, 100, v -> PolicyStore.setForceBackThreshold(this, v)));
        g1.addView(rowSwitch("拒绝理由必填", "软级弹窗拒绝拉回必须留理由才放你走；关闭则可空拒绝。", PolicyStore.declineReasonRequired(this), (v, c) -> PolicyStore.setDeclineReasonRequired(this, c)));
        g1.addView(rowText("拉回目标 App", "点「回到软件」要跳回的 App 包名。默认 com.psyche.kelivo。", PolicyStore.targetPackage(this), s -> PolicyStore.setTargetPackage(this, s)));
        g1.addView(rowSpinner("弹窗主题", "跟随归电，或单独覆盖一套主题。", themeOptions(), themeOverrideIndex(), s -> PolicyStore.setThemeOverride(this, normalizeTheme(s))));
        g1.addView(rowSpinner("弹窗位置", "跟随归电，或单独覆盖（顶部/居中/底部）。", positionOptions(), positionOverrideIndex(), s -> PolicyStore.setPositionOverride(this, normalizePosition(s))));
        g1.addView(rowNumber("冷却时间（分钟）", "同类提醒最短间隔；改任意参数后冷却立即重算。默认 5。", PolicyStore.cooldownMinutes(this), 0, 240, v -> PolicyStore.setCooldown(this, v)));
        g1.addView(rowTimePair("安静时段", "这段时间里不主动打扰（支持跨零点，如 23:30→08:00）。", PolicyStore.quietStart(this), PolicyStore.quietEnd(this), s -> PolicyStore.setQuietStart(this, s), s -> PolicyStore.setQuietEnd(this, s)));
        g1.addView(rowTimePair("深夜时段", "用于情绪感知判断的深夜范围（支持跨零点）。", PolicyStore.nightStart(this), PolicyStore.nightEnd(this), s -> PolicyStore.setNightStart(this, s), s -> PolicyStore.setNightEnd(this, s)));

        LinearLayout g2 = card(container, "情绪感知", "你（陪伴对象）对用户的情绪：由底层代码依据用户状态（时间、分心、离开时长等）自动算出，不交给模型判断。", "emotion");
        g2.addView(rowNumber("想你程度（当前值）", "她（机）当前想你的程度；锁定后不被自动衰减盖回。", PolicyStore.emotionCurrentMiss(this), 0, 100, v -> PolicyStore.setEmotionCurrentMiss(this, v)));
        g2.addView(rowSwitch("想你锁定", "打开后她想你的当前值固定不变，衰减与累积都不再动它。", PolicyStore.emotionLock(this), (v, c) -> PolicyStore.setEmotionLock(this, c)));
        g2.addView(rowNumber("平静起始值", "很久没互动时她对你的初始想念值，低一点更自然。默认 8。", PolicyStore.emotionStartMiss(this), 0, 100, v -> PolicyStore.setEmotionStartMiss(this, v)));
        g2.addView(rowNumber("每30分钟增长下限", "你越久不回来，她想你累积越快；这是增长的下限。默认 2。", PolicyStore.emotionGainMin(this), 0, 20, v -> PolicyStore.setEmotionGainMin(this, v)));
        g2.addView(rowNumber("每30分钟增长上限", "增长上限，配合下限形成随机浮动，不会一直匀速。默认 5。", PolicyStore.emotionGainMax(this), 0, 20, v -> PolicyStore.setEmotionGainMax(this, v)));
        g2.addView(rowNumber("回落速度（每小时）", "被安抚后想你回落速度（每小时点数）。默认 4。", PolicyStore.emotionDecayPerHour(this), 0, 50, v -> PolicyStore.setEmotionDecayPerHour(this, v)));
        g2.addView(rowText("情绪标签（你对用户的）", "如 平静/想你/爱恋/吃醋/生气/担心。底层感知会自动刷新；手动保存后短暂生效。点下方「保存情绪」。", CompanionEmotionState.emotionTag(this), s -> emotionTagDraft = s));
        g2.addView(rowNumber("情绪强度（0-100）", "影响语气与拉回频率。点下方「保存情绪」生效。", CompanionEmotionState.snapshot(this).optInt("intensity", 0), 0, 100, v -> emotionIntensityDraft = v));
        g2.addView(rowSwitch("情绪驱动文案（随机）", "打开后弹窗文案随情绪随机生成，每次都不一样；关闭则用中性文案。", PolicyStore.emotionDriveText(this), (v, c) -> PolicyStore.setEmotionDriveText(this, c)));
        g2.addView(rowButton("保存情绪（标签/强度）", "把上面填的情绪标签与强度写入本机；底层感知会在短暂保持后自动接管。", "保存情绪",
                v -> { CompanionEmotionState.write(this, emotionTagDraft, emotionIntensityDraft, PolicyStore.emotionCurrentMiss(this), ""); Toast.makeText(this, "情绪已保存", Toast.LENGTH_SHORT).show(); refreshStatus(); }));
        g2.addView(rowSwitch("情绪达标自动弹窗", "你对用户的想你程度达到阈值时自动弹出情绪判定弹窗（标明情绪标签与百分比）；未达拉回阈值给两选项，达到阈值只能回到软件。独立于查岗。",
                PolicyStore.emotionPopupEnabled(this), (v, c) -> PolicyStore.setEmotionPopupEnabled(this, c)));
        g2.addView(rowNumber("触发阈值（想你 %）", "想你程度达到该百分比就自动弹窗。默认 70。", PolicyStore.emotionPopupThreshold(this), 1, 100, v -> PolicyStore.setEmotionPopupThreshold(this, v)));
        g2.addView(rowNumber("情绪弹窗最小间隔（分钟）", "相邻两次情绪弹窗的最短间隔，避免反复打扰。默认 30。", PolicyStore.emotionPopupIntervalMin(this), 1, 1440, v -> PolicyStore.setEmotionPopupIntervalMin(this, v)));

        LinearLayout g3 = card(container, "主动找用户", "你太久没发消息，她会主动找你发一条。", "initiative");
        g3.addView(rowSwitch("开启主动找用户", "关闭后她不再主动发起消息。", PolicyStore.initiativeEnabled(this), (v, c) -> PolicyStore.setInitiativeEnabled(this, c)));
        g3.addView(rowNumber("沉默多久后主动发（分钟）", "超过这个时间你没发消息，就主动找你。默认 30。", PolicyStore.initiativeSilenceMinutes(this), 1, 1440, v -> PolicyStore.setInitiativeSilenceMinutes(this, v)));
        g3.addView(rowNumber("主动发消息最小间隔（分钟）", "同一次主动找用户之间的最短间隔，避免刷屏。默认 20。", PolicyStore.initiativeMinInterval(this), 1, 1440, v -> PolicyStore.setInitiativeMinInterval(this, v)));
        g3.addView(rowNumber("每日条数上限", "0=不限条数；其它数字为当天上限。默认 0。", PolicyStore.initiativeDailyLimit(this), 0, 999, v -> PolicyStore.setInitiativeDailyLimit(this, v)));
        g3.addView(rowSpinner("生成方式", "自由生成（每次内容不同）或固定模板。", new String[]{"自由生成", "自定义模板"}, PolicyStore.initiativeMode(this).equals("template") ? 1 : 0, s -> PolicyStore.setInitiativeMode(this, s.equals("自定义模板") ? "template" : "free")));
        g3.addView(rowText("自定义模板", "生成方式选「自定义模板」时使用的文案。", PolicyStore.initiativeTemplate(this), s -> PolicyStore.setInitiativeTemplate(this, s)));

        LinearLayout g4 = card(container, "训诫插件", "SM 小圈管束风格；仅你本人能开，默认关闭，分四档强度。", "discipline");
        g4.addView(rowSwitch("训诫总开关", "关闭则训诫完全不生效。默认关闭。仅用户本人可在手机端主动开启。", PolicyStore.disciplineEnabled(this), (v, c) -> PolicyStore.setDisciplineEnabled(this, c)));
        g4.addView(rowSpinner("训诫档位", "0关闭/1轻/2中/3重/4极。档位越高措辞越硬。", new String[]{"0 关闭", "1 轻", "2 中", "3 重", "4 极"}, PolicyStore.disciplineLevel(this), s -> PolicyStore.setDisciplineLevel(this, parseLevel(s))));
        g4.addView(rowSpinner("训诫语气模式", "温柔 / 严厉：接入小圈管束设定与话语，开启后人设语气按所选模式变化；写入底层并作用于陪伴话术。", new String[]{"温柔", "严厉"}, "strict".equals(PolicyStore.disciplineMode(this)) ? 1 : 0, s -> PolicyStore.setDisciplineMode(this, s.equals("严厉") ? "strict" : "gentle")));
        g4.addView(rowSwitch("强视觉警示标志", "打开后界面明确显示「训诫已开启」警示，让你随时知道状态。", PolicyStore.disciplineBadge(this), (v, c) -> PolicyStore.setDisciplineBadge(this, c)));
        g4.addView(rowText("说明", "SM 小圈训诫管束风格：仅本人可开、不设安全词、不写日志，但界面必须有明确开启标志。", "仅本人可开", s -> { }));

        LinearLayout g5 = card(container, "分心查岗（常开）", "分心检测始终开启，不只在聊天时。定义：聊天中切出去到其它 App，又立马切回来，来回几次算分心；未达拉回阈值给两选项，达到阈值只能回到软件。", "distraction");
        g5.addView(rowSwitch("切屏算分心", "常开检测：聊天中切到其它 App 标记为分心。默认开启。", PolicyStore.distractionEnabled(this), (v, c) -> PolicyStore.setDistractionEnabled(this, c)));
        g5.addView(rowSwitch("切出切回算分心", "按「切出去 → 立马切回来」的来回次数判定分心。默认开启。", PolicyStore.distractionLoopEnabled(this), (v, c) -> PolicyStore.setDistractionLoopEnabled(this, c)));
        g5.addView(rowNumber("分心来回次数", "切出切回累计达到该次数弹一次拉回弹窗。默认 2。", PolicyStore.distractionLoopTimes(this), 1, 99, v -> PolicyStore.setDistractionLoopTimes(this, v)));
        g5.addView(rowNumber("来回时间窗口（秒）", "切出后多少秒内切回来才算一次来回，超时重新计。默认 90。", PolicyStore.distractionLoopWindowSec(this), 5, 3600, v -> PolicyStore.setDistractionLoopWindowSec(this, v)));
        g5.addView(rowSwitch("每次切屏都拉回", "关闭时是否拉回由她当时的情绪决定；打开则每次切屏都拉回。默认关闭。", PolicyStore.distractionEveryTime(this), (v, c) -> PolicyStore.setDistractionEveryTime(this, c)));
        g5.addView(rowNumber("累计分心阈值（次）", "连续分心达到该次数弹一次拉回弹窗（是否强制由拉回阈值决定）。默认 5。", PolicyStore.distractionThreshold(this), 1, 999, v -> PolicyStore.setDistractionThreshold(this, v)));
        g5.addView(rowSwitch("超阈值自动提醒", "分心累计达到阈值时弹一次拉回弹窗（不再无理由强制）。默认开启。", PolicyStore.distractionAutoPullback(this), (v, c) -> PolicyStore.setDistractionAutoPullback(this, c)));

        // —— 应用门禁：锁 App 时长 + 自主添加受限 App 合并到一处 ——
        LinearLayout gApp = card(container, "应用门禁", "锁 App 时长、自主添加受限应用，集中在此管理。", "appgate");
        gApp.addView(rowSwitch("连续使用自动锁", "开启后监控目标 App（或指定包名）的连续使用时长，超阈值自动锁。", PolicyStore.usageLockEnabled(this), (v, c) -> PolicyStore.setUsageLockEnabled(this, c)));
        gApp.addView(rowNumber("连续使用阈值（分钟）", "连续使用达到该分钟数触发锁定。默认 45。", PolicyStore.usageLockMinutes(this), 1, 1440, v -> PolicyStore.setUsageLockMinutes(this, v)));
        gApp.addView(rowNumber("自动锁定时长（分钟）", "触发后锁定该 App 多少分钟。默认 15。", PolicyStore.usageLockDuration(this), 1, 1440, v -> PolicyStore.setUsageLockDuration(this, v)));
        gApp.addView(rowText("监控包名（逗号分隔）", "留空=只监控目标 App；也可填多个包名，如 com.tencent.mm,com.ss.android.ugc.aweme。", PolicyStore.usageLockPackages(this), s -> PolicyStore.setUsageLockPackages(this, s)));
        gApp.addView(rowButton("查看当前使用状态", "显示当前前台 App 与连续使用时长。", "查看", v -> showUsageStatus()));
        gApp.addView(rowText("新增受限 App", "填「别名|包名」一行，如 微博|com.sina.weibo，点下方保存。", "", s -> addGateDraft = s));
        gApp.addView(rowButton("保存新增", "把上面填的包名加入受限列表。", "保存", v -> { saveGateDraft(); }));
        gApp.addView(buildGateAppList(gApp));

        // —— 主动提醒：把分散的各类提醒集中到此处，去掉重复项 ——
        LinearLayout gRem = card(container, "主动提醒", "电量、屏幕、喝水、休息、周期等提醒集中管理。", "reminder");
        gRem.addView(rowAppBool("总开关", "关闭后所有主动提醒都暂停。", AppPrefs.KEY_ACTIVE_REMINDERS, true));
        gRem.addView(rowAppBool("低电量提醒", "电量低于阈值时提醒充电。", AppPrefs.KEY_RULE_BATTERY, true));
        gRem.addView(rowAppInt("低电量阈值（%）", "电量低于该百分比触发提醒。默认 20。", AppPrefs.KEY_BATTERY_THRESHOLD, 20, 5, 80));
        gRem.addView(rowAppBool("屏幕时间提醒", "连续使用达到阈值分钟提醒休息。默认开启。", AppPrefs.KEY_RULE_SCREEN, true));
        gRem.addView(rowAppInt("屏幕时间阈值（分钟）", "连续使用达到该分钟数提醒。默认 240。", AppPrefs.KEY_SCREEN_THRESHOLD_MIN, 240, 10, 1440));
        gRem.addView(rowAppBool("喝水提醒", "按间隔提醒喝水。默认关闭。", AppPrefs.KEY_RULE_WATER, false));
        gRem.addView(rowAppInt("喝水间隔（分钟）", "每隔多少分钟提醒喝水。默认 120。", AppPrefs.KEY_WATER_INTERVAL_MIN, 120, 30, 720));
        gRem.addView(rowAppBool("休息提醒", "按间隔提醒起来活动。默认开启。", AppPrefs.KEY_RULE_REST, true));
        gRem.addView(rowAppInt("休息间隔（分钟）", "每隔多少分钟提醒休息。默认 90。", AppPrefs.KEY_REST_INTERVAL_MIN, 90, 30, 720));
        gRem.addView(rowAppBool("周期提醒（生理期关怀）", "按周期提前提醒，给予照顾。默认关闭。", AppPrefs.KEY_CYCLE_ENABLED, false));
        gRem.addView(rowAppText("上次开始日期", "例如 2026-07-09。", AppPrefs.KEY_LAST_PERIOD_START, ""));
        gRem.addView(rowAppInt("周期长度（天）", "两次开始之间的天数。默认 30。", AppPrefs.KEY_CYCLE_LENGTH, 30, 15, 60));
        gRem.addView(rowAppInt("经期天数", "持续天数。默认 6。", AppPrefs.KEY_PERIOD_LENGTH, 6, 1, 14));
        gRem.addView(rowAppInt("提前提醒（天）", "提前多少天提醒。默认 3。", AppPrefs.KEY_CYCLE_REMIND_BEFORE, 3, 0, 14));

        // —— 其它权限开关：去掉与「主动提醒」重复的喝水/久坐项 ——
        LinearLayout g6 = card(container, "其它权限开关", "逐项管理 App 的其余守护功能。", "permissions");
        addPerm(g6, PolicyStore.KEY_PERM_SCREEN_TIME, "屏幕时长上限", "限制每天可用屏幕总时长（填分钟，0=不限）。");
        addPerm(g6, PolicyStore.KEY_PERM_SLEEP, "睡前息屏", "到时间提醒息屏睡觉（填 HH:mm）。");
        addPerm(g6, PolicyStore.KEY_PERM_CURFEW, "深夜宵禁", "深夜时段限制使用（填 23:00-06:00）。");
        addPerm(g6, PolicyStore.KEY_PERM_STUDY, "学习白名单", "只放行白名单 App（填包名，逗号分隔）。");
        addPerm(g6, PolicyStore.KEY_PERM_WEEKLY, "每周回顾", "每周生成一次使用回顾（填 星期,HH:mm）。");
        addPerm(g6, PolicyStore.KEY_PERM_HOME, "到家报备", "到家后提醒报备（填地点）。");
        addPerm(g6, PolicyStore.KEY_PERM_PERIOD, "生理期照顾", "按周期给予照顾提醒（填周期天数）。");

        LinearLayout g11 = card(container, "课表作息", "录入课表，到点自动判定上课、上课静默守护、课前/下课提醒。", "schedule");
        g11.addView(rowButton("录入一节新课", "填 星期/节次/起止/科目/教室 后保存。", "录入", v -> editClassEntryDialog(null)));
        g11.addView(rowButton("导入示例课表", "写入几条示例课表用于验证。", "导入示例", v -> importSampleSchedule()));
        g11.addView(rowButton("查看课表", "列出全部课表与当前是否上课中。", "查看", v -> showClassSchedule()));
        // 修复「课表作息没办法删除已有信息和更改」：列出全部课程，每条可修改/删除。
        g11.addView(buildScheduleList());
        g11.addView(rowButton("清空全部课表", "一次删除所有课程条目（会先确认）。", "清空课表", v -> confirmClearSchedule()));

        LinearLayout g12 = card(container, "共同记忆", "双方可增/改/删、双向修改实时生效的记忆面板。", "memory");
        g12.addView(rowText("新增记忆", "写下一条共同记忆，点下方保存。", "", s -> memoryDraft = s));
        g12.addView(rowButton("保存记忆", "把上面写的记忆加入共同记忆。", "保存", v -> saveMemory()));
        // 修复「共同记忆无法查看和写入和更改」：全量列出记忆，每条可编辑/删除。
        g12.addView(buildMemoryList());

        LinearLayout gOcr = card(container, "识图感知", "用读屏节点树从当前屏幕读取可见文字与状态；可随时关闭，关后本地与 MCP 都无法调用。", "ocr");
        gOcr.addView(rowSwitch("开启识图感知", "关闭后不再读取屏幕文字，MCP 与本地都无法调用识图。默认开启。", PolicyStore.ocrEnabled(this), (v, c) -> PolicyStore.setOcrEnabled(this, c)));
        gOcr.addView(rowButton("读取当前屏幕文字", "需要无障碍权限；读取后展示提取到的文字。", "读取", v -> runOcr()));

        LinearLayout gWeather = card(container, "天气地区", "家与常用地点，天气提醒按地区给出。", "weather");
        JSONObject wl = WeatherState.currentLocation(this);
        weatherNameDraft = wl.optString("name", "家");
        weatherCityDraft = wl.optString("city", "");
        weatherNoteDraft = wl.optString("note", "");
        gWeather.addView(rowText("当前地点名", "这个地点的名字，例如 家 / 学校。", weatherNameDraft, s -> weatherNameDraft = s));
        gWeather.addView(rowText("城市", "用于查询天气的城市，例如 石家庄。", weatherCityDraft, s -> weatherCityDraft = s));
        gWeather.addView(rowText("备注", "例如 风大 / 容易下雨。", weatherNoteDraft, s -> weatherNoteDraft = s));
        gWeather.addView(rowButton("保存当前地点", "保存上面的地点信息，并设为当前地点。", "保存", v -> {
            WeatherState.saveLocation(this, weatherNameDraft.isEmpty() ? "家" : weatherNameDraft, weatherCityDraft, weatherNoteDraft, true);
            Toast.makeText(this, "已保存地点", Toast.LENGTH_SHORT).show(); buildUI();
        }));
        gWeather.addView(rowButton("查看全部地点", "列出所有已保存地点。", "查看", v -> showWeatherLocations()));

        LinearLayout gHome = card(container, "回家模式", "回到熟悉的环境时，自动回到目标 App。", "homemode");
        gHome.addView(rowAppBool("开启回家模式", "打开后监控是否在常用地点，回到就拉回。", AppPrefs.KEY_HOME_MODE_ENABLED, false));
        gHome.addView(rowAppBool("强制回目标 APP", "不论情绪，回到常用地点立即强拉回。", AppPrefs.KEY_HOME_MODE_FORCE, false));
        gHome.addView(rowAppText("目标 APP（每行 名称｜包名）", "回家模式要守的 App，例如 小红书|com.xingin.xhs。", AppPrefs.KEY_TARGET_APPS, ""));
        gHome.addView(rowAppInt("阈值（分钟）", "离开常用 App 超过该分钟数触发。默认 10。", AppPrefs.KEY_HOME_THRESHOLD_MIN, 10, 1, 240));
        gHome.addView(rowAppInt("冷却（分钟）", "两次提醒之间的最短间隔。默认 5。", AppPrefs.KEY_HOME_COOLDOWN_MIN, 5, 1, 240));
        gHome.addView(rowAppText("返回目标（名称或包名）", "拉回时跳回的 App。", AppPrefs.KEY_HOME_TARGET_PACKAGE, ""));
        gHome.addView(rowButton("查看状态", "显示当前回家模式配置与状态。", "查看", v -> showHomeModeStatus()));

        LinearLayout g7 = card(container, "验收与调试", "本地触发拉回弹窗、把配置同步给服务器（可选）。", "debug");
        g7.addView(rowButton("试一下软级别拉回", "想你低于阈值：两个按钮，可拒绝但必须留理由。", "试软级", v -> testPullBack(false)));
        g7.addView(rowButton("试一下强级别拉回", "想你达到阈值：只有「现在就回去」一个按钮。", "试强级", v -> testPullBack(true)));
        g7.addView(rowButton("同步给服务器（可选）", "若已配置服务器地址，把配置 best-effort 同步过去；不影响本机生效。", "同步", v -> syncToServer()));

        refreshChangeLog();
    }

    // ============ 分组卡片（可折叠）============

    private LinearLayout card(LinearLayout container, String title, String desc, String sectionKey) {
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
        // 单分区模式：只把目标分区挂进容器；其余分组照常构建（后续 addView 不会报错），但不显示、不注册深链
        boolean shown = activeSection == null || activeSection.isEmpty() || activeSection.equals(sectionKey);
        if (shown && sectionKey != null && !sectionKey.isEmpty()) sectionGroups.put(sectionKey, group);
        if (shown) container.addView(group);
        return content;
    }

    // ============ 单分区导航条 ============

    /** 分区 key -> 守护页标签上的中文名，用于单分区标题。 */
    private static String sectionTitle(String key) {
        if (key == null) return "全部设置";
        switch (key) {
            case "checkin": return "查岗拉回";
            case "distraction": return "分心强拉回";
            case "discipline": return "训诫插件";
            case "emotion": return "情绪感知";
            case "initiative": return "主动找用户";
            case "appgate": return "应用门禁";
            case "reminder": return "主动提醒";
            case "schedule": return "课表作息";
            case "memory": return "共同记忆";
            case "ocr": return "识图感知";
            case "weather": return "天气地区";
            case "homemode": return "回家模式";
            case "global": return "全局";
            case "permissions": return "其它权限开关";
            case "debug": return "验收与调试";
            default: return key;
        }
    }

    /** 单分区模式顶部的导航条：标明当前分区，可一键回到全部设置或返回守护界面。 */
    private void buildSectionBanner(String key) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.card_bg);
        bar.setPadding(dp(12), dp(10), dp(8), dp(10));
        bar.setTag("banner::" + key);

        TextView t = new TextView(this);
        t.setText("「" + sectionTitle(key) + "」设置");
        t.setTextSize(15);
        t.setTextColor(0xFF2D3E39);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(t);

        Button all = new Button(this);
        all.setText("全部设置");
        all.setTextSize(11);
        all.setBackgroundResource(R.drawable.pill_soft);
        all.setOnClickListener(v -> { activeSection = null; buildUI(); });
        bar.addView(all);

        // 修复「守护界面标签点开无保存选项」：单分区模式下原「全局」卡里的保存按钮
        // 会被分区过滤隐藏，这里在导航条上补一个显式保存，行为与全局卡的保存完全一致。
        Button save = new Button(this);
        save.setText("保存设置");
        save.setTextSize(11);
        save.setBackgroundResource(R.drawable.pill_soft);
        save.setOnClickListener(v -> {
            PolicyStore.flush(this);        // 同步落盘
            PolicyScheduler.start(this);    // 幂等重启调度器，新配置立即生效
            syncDisciplineToServer();       // 训诫开关/语气同步给服务端
            Toast.makeText(this, "设置已保存并生效", Toast.LENGTH_SHORT).show();
        });
        bar.addView(save);

        Button back = new Button(this);
        back.setText("返回守护");
        back.setTextSize(11);
        back.setBackgroundResource(R.drawable.pill_soft);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(-1, -2);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(6);
        container.addView(bar, lp);
    }

    // ============ AppPrefs 直读写（提醒 / 回家模式等）============

    private View rowAppBool(String label, String desc, String key, boolean def) {
        return rowSwitch(label, desc, AppPrefs.get(this).getBoolean(key, def),
                (v, c) -> AppPrefs.get(this).edit().putBoolean(key, c).apply());
    }
    private View rowAppInt(String label, String desc, String key, int def, int min, int max) {
        return rowNumber(label, desc, AppPrefs.get(this).getInt(key, def), min, max,
                v -> AppPrefs.get(this).edit().putInt(key, v).apply());
    }
    private View rowAppText(String label, String desc, String key, String def) {
        return rowText(label, desc, AppPrefs.get(this).getString(key, def == null ? "" : def),
                s -> AppPrefs.get(this).edit().putString(key, s).apply());
    }

    // ============ 守护界面深链：展开并滚动到指定分区 ============

    private void focusSection(String key) {
        View g = sectionGroups.get(key);
        if (g == null) return;
        if (g instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) g;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof LinearLayout && c.getVisibility() != View.VISIBLE) c.setVisibility(View.VISIBLE);
            }
        }
        // 容器（policyContainer）的父级是 policyRoot，再外层才是 ScrollView；向上找真实的 ScrollView
        ViewParent p = container.getParent();
        while (p != null && !(p instanceof ScrollView)) p = p.getParent();
        if (!(p instanceof ScrollView)) return;
        final ScrollView sv = (ScrollView) p;
        // g.getTop() 是相对 container 的偏移；加上 container 在 ScrollView 内容里的偏移才是绝对位置
        final int top = container.getTop() + g.getTop();
        sv.post(() -> sv.scrollTo(0, top));
    }

    // ============ 识图 / 天气 / 回家模式 详情 ============

    private void runOcr() {
        if (!PolicyStore.ocrEnabled(this)) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("识图感知已关闭").setMessage("当前已关闭识图感知。打开上方「开启识图感知」开关后即可读取屏幕文字。").setPositiveButton("知道了", null).show();
            return;
        }
        try {
            JSONObject r = OcrPlugin.extractLocal(this);
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("当前屏幕文字").setMessage(r == null ? "未读到内容（请确认已开启无障碍权限）" : r.toString()).setPositiveButton("知道了", null).show();
        } catch (Exception e) { status("识图失败：" + ScreenshotService.shortMsg(e)); }
    }

    private void showWeatherLocations() {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("全部地点").setMessage(WeatherState.pretty(this)).setPositiveButton("知道了", null).show();
        } catch (Exception e) { status("读取失败：" + ScreenshotService.shortMsg(e)); }
    }

    private void showHomeModeStatus() {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("回家模式状态").setMessage(
                    "开启：" + (AppPrefs.get(this).getBoolean(AppPrefs.KEY_HOME_MODE_ENABLED, false) ? "是" : "否") + "\n"
                            + "强制回目标：" + (AppPrefs.get(this).getBoolean(AppPrefs.KEY_HOME_MODE_FORCE, false) ? "是" : "否") + "\n"
                            + "监视包名：" + AppPrefs.homeWatchPackages(this) + "\n"
                            + "返回目标：" + AppPrefs.homeTargetPackage(this))
                    .setPositiveButton("知道了", null).show();
        } catch (Exception e) { status("读取失败：" + ScreenshotService.shortMsg(e)); }
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
        // 顶部导航条始终保留；其余容器子项按搜索词决定显隐
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (String.valueOf(child.getTag()).startsWith("banner::")) continue;
            child.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
        }
        for (View r : allRows) r.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
        if (q.isEmpty()) return;
        // 标题匹配 -> 显示整组，并把该组的所有行一并显示（修复：原逻辑只显示组头，行仍是 GONE）
        for (int i = 0; i < container.getChildCount(); i++) {
            View g = container.getChildAt(i);
            String tag = String.valueOf(g.getTag());
            if (tag.startsWith("group::") && tag.substring("group::".length()).toLowerCase().contains(q)) {
                g.setVisibility(View.VISIBLE);
                setChildrenVisible(g, View.VISIBLE);
                for (View r : allRows) if (findGroup(r) == g) r.setVisibility(View.VISIBLE);
            }
        }
        // 行关键词匹配 -> 显示该行及其所在分组
        for (View r : allRows) {
            if (String.valueOf(r.getTag()).toLowerCase().contains(q)) {
                r.setVisibility(View.VISIBLE);
                View g = findGroup(r);
                if (g != null) { g.setVisibility(View.VISIBLE); setChildrenVisible(g, View.VISIBLE); }
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

    /**
     * 把本机训诫开关与语气模式同步给服务端，使陪伴对象通过 get_discipline_mode
     * 读到的状态与 App 设置页一致（否则 AI 侧会一直沿用服务端默认语气）。
     * 网络不可用 / 未配置服务器时静默跳过，本机设置依旧生效，不弹错误、不跳转。
     */
    private void syncDisciplineToServer() {
        try {
            final String base = AppPrefs.server(this), token = AppPrefs.token(this);
            if (base == null || base.trim().isEmpty()) return;
            JSONObject body = new JSONObject();
            body.put("enabled", PolicyStore.disciplineEnabled(this));
            body.put("mode", PolicyStore.disciplineMode(this));
            body.put("level", PolicyStore.disciplineLevel(this));
            final String payload = body.toString();
            new Thread(() -> request(base, token, "POST", "/api/discipline", payload), "discipline-sync").start();
        } catch (Exception ignored) { }
    }

    private void syncToServer() {
        if (!ready()) { status("未配置服务器，跳过同步（本机已生效）。"); return; }
        final String base = AppPrefs.server(this), token = AppPrefs.token(this);
        try {
            JSONObject body = new JSONObject();
            // Bug「get_tour_schedule 全为默认值/异常值」：旧代码把 force_back_interval_minutes=0、
            // alert_tone="gentle"、exempt_packages=[] 硬编码进同步体，每次保存都会用假值覆盖
            // 服务端已保存的巡回配置。本机没有这些项的独立配置（弹窗语气/豁免列表由服务端与
            // 管束设定管理），因此这三项**不上送**——服务端 /api/tour/schedule 只更新 body 里
            // 出现的键，缺省键保留原值，不会再被抹掉。
            body.put("enabled", PolicyStore.checkinEnabled(this));
            body.put("check_in_interval_minutes", PolicyStore.randomUpper(this));
            body.put("cooldown_minutes", PolicyStore.cooldownMinutes(this));
            JSONObject q = new JSONObject();
            q.put("start", PolicyStore.quietStart(this)); q.put("end", PolicyStore.quietEnd(this));
            body.put("quiet_hours", q);
            JSONObject n = new JSONObject();
            n.put("start", PolicyStore.nightStart(this)); n.put("end", PolicyStore.nightEnd(this));
            body.put("night_hours", n);
            body.put("force_back_miss_threshold", PolicyStore.forceBackThreshold(this));
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

    // ============ 包名管理 / 课表 / 共同记忆 / 插件 / 使用时长 ============

    private void saveGateDraft() {
        try {
            String t = addGateDraft == null ? "" : addGateDraft.trim();
            if (t.isEmpty()) { Toast.makeText(this, "请先填写 别名|包名", Toast.LENGTH_SHORT).show(); return; }
            String[] p = t.split("\\|", 2);
            if (p.length != 2 || !AppPrefs.isPackageLike(p[1].trim())) {
                Toast.makeText(this, "格式应为 别名|包名，如 微博|com.sina.weibo", Toast.LENGTH_LONG).show(); return;
            }
            AppGate.addGateApp(this, p[0].trim(), p[1].trim());
            Toast.makeText(this, "已添加受限 App：" + p[0].trim(), Toast.LENGTH_SHORT).show();
            addGateDraft = ""; buildUI();
        } catch (Exception e) { status("添加失败：" + ScreenshotService.shortMsg(e)); }
    }

    /** 构建一个列出当前受限 App、每行可移除的视图。 */
    private LinearLayout buildGateAppList(LinearLayout group) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        java.util.Map<String, String> apps = AppGate.gateApps(this);
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无受限 App（内置默认：小红书、抖音）");
            empty.setTextSize(11); empty.setTextColor(0xFF8B7D99);
            list.addView(empty);
        }
        for (final java.util.Map.Entry<String, String> e : apps.entrySet()) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView lab = new TextView(this);
            lab.setText(e.getKey() + "  →  " + e.getValue());
            lab.setTextSize(12); lab.setTextColor(0xFF2D3E39);
            lab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(lab);
            Button rm = new Button(this);
            rm.setText("移除"); rm.setTextSize(11); rm.setBackgroundResource(R.drawable.pill_danger);
            rm.setOnClickListener(v -> { AppGate.removeGateApp(this, e.getValue()); Toast.makeText(this, "已移除 " + e.getKey(), Toast.LENGTH_SHORT).show(); buildUI(); });
            row.addView(rm);
            list.addView(row);
        }
        return list;
    }

    private void showUsageStatus() {
        try {
            String pkg = ScreenshotService.currentPackage();
            String label = AppGate.labelOf(this, pkg);
            boolean watched = PolicyStore.usageLockEnabled(this)
                    && (PolicyStore.usageLockPackages(this).trim().isEmpty()
                        ? PolicyStore.targetPackage(this).equals(pkg)
                        : PolicyStore.usageLockPackages(this).contains(pkg));
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("当前使用状态");
            b.setMessage("前台 App：" + label + "\n包名：" + pkg + "\n是否在自动锁监控中：" + (watched ? "是" : "否")
                    + "\n（连续使用时长由本地调度器实时统计，达到阈值即自动锁）");
            b.setPositiveButton("知道了", null).show();
        } catch (Exception e) { status("读取失败：" + ScreenshotService.shortMsg(e)); }
    }

    /** 录入新课（无预填）。 */
    private void addClassEntry() { editClassEntryDialog(null); }

    /**
     * 录入/修改一节课程。original 为 null 时是新增；否则预填原值，
     * 保存时先按旧键（星期+开始+科目）删除原条目，再写入新值，
     * 避免改了时间或科目后旧条目残留成两条。
     */
    private void editClassEntryDialog(final JSONObject original) {
        final EditText input = new EditText(this);
        input.setHint("周几 起-止 科目@教室，如 1 09:00-09:45 高数@教三201");
        input.setTextSize(13);
        String prefill = "";
        if (original != null) {
            prefill = original.optInt("weekday", 1) + " " + original.optString("start", "")
                    + "-" + original.optString("end", "") + " " + original.optString("subject", "");
            String room = original.optString("room", "");
            if (!room.isEmpty()) prefill += "@" + room;
        }
        input.setText(prefill);
        input.setSelection(prefill.length());
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(original == null ? "录入课表" : "修改课程").setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        String t = input.getText().toString().trim();
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})\\s+(.+?)(?:@(.+))?$").matcher(t);
                        if (!m.matches()) { Toast.makeText(this, "格式不对，参考：1 09:00-09:45 高数@教三201", Toast.LENGTH_LONG).show(); return; }
                        JSONObject e = new JSONObject();
                        e.put("weekday", Integer.parseInt(m.group(1)));
                        e.put("start", m.group(2));
                        e.put("end", m.group(3));
                        e.put("subject", m.group(4).trim());
                        e.put("room", m.group(5) == null ? "" : m.group(5).trim());
                        if (original != null) {
                            // 修改：先删旧键再存新值，防止改时间/科目后旧条目残留
                            ClassScheduleState.removeEntry(this, original.optInt("weekday", 1),
                                    original.optString("start", ""), original.optString("subject", ""));
                        }
                        ClassScheduleState.upsertEntry(this, e);
                        Toast.makeText(this, original == null ? "已保存课程" : "已更新课程", Toast.LENGTH_SHORT).show();
                        buildUI();
                    } catch (Exception ex) { status("保存失败：" + ScreenshotService.shortMsg(ex)); }
                })
                .setNegativeButton("取消", null).show();
    }

    /** 课表管理列表：全部课程逐条展示，每条可修改/删除。 */
    private LinearLayout buildScheduleList() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        org.json.JSONArray arr = ClassScheduleState.getEntries(this);
        if (arr.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无课程。点上方「录入」添加，导入示例也可以。");
            empty.setTextSize(11); empty.setTextColor(0xFF8B7D99);
            list.addView(empty);
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final int wd = o.optInt("weekday", 1);
            final String start = o.optString("start", ""), subject = o.optString("subject", "");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView lab = new TextView(this);
            String txt = ClassScheduleState.weekdayName(wd) + " " + start + "-" + o.optString("end", "") + " " + subject;
            if (!o.optString("room", "").isEmpty()) txt += " @" + o.optString("room", "");
            lab.setText(txt);
            lab.setTextSize(12); lab.setTextColor(0xFF2D3E39);
            lab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(lab);
            Button edit = new Button(this);
            edit.setText("修改"); edit.setTextSize(11); edit.setBackgroundResource(R.drawable.pill_soft);
            edit.setOnClickListener(v -> editClassEntryDialog(o));
            row.addView(edit);
            Button rm = new Button(this);
            rm.setText("删除"); rm.setTextSize(11); rm.setBackgroundResource(R.drawable.pill_danger);
            rm.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("删除这节课程？")
                    .setMessage("「" + ClassScheduleState.weekdayName(wd) + " " + start + " " + subject + "」会从课表移除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d2, w2) -> {
                        ClassScheduleState.removeEntry(this, wd, start, subject);
                        Toast.makeText(this, "已删除课程", Toast.LENGTH_SHORT).show();
                        buildUI();
                    }).show());
            row.addView(rm);
            list.addView(row);
        }
        return list;
    }

    /** 清空课表（带确认）。 */
    private void confirmClearSchedule() {
        int count = ClassScheduleState.getEntries(this).length();
        if (count == 0) { Toast.makeText(this, "课表本来就是空的", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this)
                .setTitle("清空全部课表？")
                .setMessage("共 " + count + " 节课程会全部删除，此操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    if (ClassScheduleState.clearAll(this)) { Toast.makeText(this, "课表已清空", Toast.LENGTH_SHORT).show(); buildUI(); }
                    else status("清空失败，请重试");
                }).show();
    }

    private void importSampleSchedule() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            arr.put(new JSONObject().put("weekday", 1).put("start", "09:00").put("end", "09:45").put("subject", "高等数学").put("room", "教三201"));
            arr.put(new JSONObject().put("weekday", 1).put("start", "10:00").put("end", "10:45").put("subject", "大学英语").put("room", "教二105"));
            arr.put(new JSONObject().put("weekday", 3).put("start", "14:00").put("end", "15:30").put("subject", "数据结构").put("room", "实验楼B"));
            arr.put(new JSONObject().put("weekday", 5).put("start", "08:00").put("end", "09:35").put("subject", "线性代数").put("room", "教一302"));
            ClassScheduleState.importEntries(this, arr);
            Toast.makeText(this, "已导入示例课表", Toast.LENGTH_SHORT).show();
            buildUI();
        } catch (Exception e) { status("导入失败：" + ScreenshotService.shortMsg(e)); }
    }

    private void showClassSchedule() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("课表").setMessage(ClassScheduleState.pretty(this)).setPositiveButton("知道了", null).show();
    }

    private void saveMemory() {
        try {
            String t = memoryDraft == null ? "" : memoryDraft.trim();
            if (t.isEmpty()) { Toast.makeText(this, "请先填写记忆内容", Toast.LENGTH_SHORT).show(); return; }
            SharedMemoryState.add(this, t, AppPrefs.userName(this));
            Toast.makeText(this, "已保存记忆", Toast.LENGTH_SHORT).show();
            memoryDraft = ""; buildUI();
        } catch (Exception e) { status("保存失败：" + ScreenshotService.shortMsg(e)); }
    }

    private void showSharedMemory() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("共同记忆").setMessage(SharedMemoryState.pretty(this)).setPositiveButton("知道了", null).show();
    }

    /** 共同记忆管理列表：全部条目逐条展示（作者+内容），每条可编辑/删除。 */
    private LinearLayout buildMemoryList() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        org.json.JSONArray arr = SharedMemoryState.getItems(this);
        if (arr.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("共同记忆为空。在上面写下第一条记忆，点「保存」即可。");
            empty.setTextSize(11); empty.setTextColor(0xFF8B7D99);
            list.addView(empty);
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String id = o.optString("id", "");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView lab = new TextView(this);
            String txt = "[" + o.optString("author", "") + "] " + o.optString("text", "");
            String at = o.optString("created_at", "");
            if (!at.isEmpty()) txt += "  (" + at + ")";
            lab.setText(txt);
            lab.setTextSize(12); lab.setTextColor(0xFF2D3E39);
            lab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(lab);
            Button edit = new Button(this);
            edit.setText("修改"); edit.setTextSize(11); edit.setBackgroundResource(R.drawable.pill_soft);
            edit.setOnClickListener(v -> {
                if (id.isEmpty()) { Toast.makeText(this, "这条记忆缺少 id，无法编辑", Toast.LENGTH_SHORT).show(); return; }
                final EditText input = new EditText(this);
                input.setText(o.optString("text", ""));
                input.setTextSize(13);
                new AlertDialog.Builder(this)
                        .setTitle("修改记忆")
                        .setView(input)
                        .setPositiveButton("保存", (d2, w2) -> {
                            String t = input.getText().toString().trim();
                            if (t.isEmpty()) { Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show(); return; }
                            boolean ok = SharedMemoryState.edit(this, id, t);
                            Toast.makeText(this, ok ? "已更新记忆" : "更新失败（未找到该条）", Toast.LENGTH_SHORT).show();
                            if (ok) buildUI();
                        })
                        .setNegativeButton("取消", null).show();
            });
            row.addView(edit);
            Button rm = new Button(this);
            rm.setText("删除"); rm.setTextSize(11); rm.setBackgroundResource(R.drawable.pill_danger);
            rm.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("删除这条记忆？")
                    .setMessage("「" + o.optString("text", "") + "」会从共同记忆移除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d2, w2) -> {
                        boolean ok = SharedMemoryState.remove(this, id);
                        Toast.makeText(this, ok ? "已删除记忆" : "删除失败（未找到该条）", Toast.LENGTH_SHORT).show();
                        if (ok) buildUI();
                    }).show());
            row.addView(rm);
            list.addView(row);
        }
        return list;
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
