package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件系统分类与架构整理（对应 PRD P1.3）。
 *
 * 把所有扩展能力统一归类管理，杜绝硬编码：
 *  - 管束查岗类：查岗拉回、应用门禁、应用使用时长自动锁、专注模式；
 *  - 情绪感知类：陪伴对象情绪、主动找她、训诫插件；
 *  - 日程作息类：课表插件、守护日历、周期提醒；
 *  - 系统控制类：归电(回家)、夜间息屏、通知提醒；
 *  - 记忆与视觉类：共同记忆、识图插件、守护主页视觉。
 *
 * 每个插件有统一的元数据（id / 分类 / 名称 / 说明 / 是否持久化 / 是否可双向修改），
 * 设置页与命令路由都从这里取，不再把分类散落在各处 if/else 里。
 */
public final class PluginRegistry {

    public static final String CAT_GUARD = "管束查岗类";
    public static final String CAT_EMOTION = "情绪感知类";
    public static final String CAT_SCHEDULE = "日程作息类";
    public static final String CAT_SYSTEM = "系统控制类";
    public static final String CAT_MEMORY = "记忆与视觉类";

    private PluginRegistry() { }

    public static class Plugin {
        public final String id;
        public final String category;
        public final String name;
        public final String desc;
        public final boolean persist;     // 是否独立持久化配置
        public final boolean twoWay;      // 是否支持手机端与 MCP 双向修改实时生效
        Plugin(String id, String category, String name, String desc, boolean persist, boolean twoWay) {
            this.id = id; this.category = category; this.name = name; this.desc = desc;
            this.persist = persist; this.twoWay = twoWay;
        }
    }

    /** 返回全部已登记插件（单一事实来源；新增能力在此登记，UI 与路由自动同步）。 */
    public static List<Plugin> all() {
        List<Plugin> list = new ArrayList<>();
        // 管束查岗类
        list.add(new Plugin("checkin_pullback", CAT_GUARD, "查岗拉回", "随机查岗 + 分级拉回，想她达阈值强拉回。", true, true));
        list.add(new Plugin("app_gate", CAT_GUARD, "应用门禁", "锁定指定 App，到点或触发时拦截。", true, true));
        list.add(new Plugin("usage_auto_lock", CAT_GUARD, "使用时长自动锁", "连续使用超 N 分钟自动锁该 App M 分钟。", true, true));
        list.add(new Plugin("focus_mode", CAT_GUARD, "专注模式", "全机专注、应急放行与留言。", true, true));
        // 情绪感知类
        list.add(new Plugin("companion_emotion", CAT_EMOTION, "陪伴对象情绪", "TA 自己的情绪标签/强度/想她百分比。", true, true));
        list.add(new Plugin("initiative", CAT_EMOTION, "主动找她", "沉默超阈值主动发起消息。", true, true));
        list.add(new Plugin("discipline", CAT_EMOTION, "训诫插件", "SM 小圈管束风格，本人开启、四档强度。", true, true));
        // 日程作息类
        list.add(new Plugin("class_schedule", CAT_SCHEDULE, "课表插件", "录入课表，到点判定上课、静默守护与提醒。", true, true));
        list.add(new Plugin("guard_calendar", CAT_SCHEDULE, "守护日历", "纪念日、节日与倒数日。", true, true));
        list.add(new Plugin("cycle_reminder", CAT_SCHEDULE, "周期提醒", "生理期等周期关怀提醒。", true, true));
        // 系统控制类
        list.add(new Plugin("guidian", CAT_SYSTEM, "归电回家", "回到掌心窗陪伴页的联动与补弹。", true, true));
        list.add(new Plugin("night_lock", CAT_SYSTEM, "夜间息屏", "睡前息屏与深夜宵禁。", true, true));
        list.add(new Plugin("notifications", CAT_SYSTEM, "通知提醒", "喝水、久坐、屏幕时长等提醒。", true, true));
        // 记忆与视觉类
        list.add(new Plugin("shared_memory", CAT_MEMORY, "共同记忆", "双方可增改删、双向同步的记忆面板。", true, true));
        list.add(new Plugin("ocr", CAT_MEMORY, "识图插件", "本地结构化提取屏幕文字与状态。", true, false));
        list.add(new Plugin("guard_visual", CAT_MEMORY, "守护视觉", "守护主页与弹窗的视觉主题。", true, true));
        return list;
    }

    /** 按分类聚合，便于设置页分组展示。 */
    public static JSONObject categorizedJson() {
        JSONObject out = new JSONObject();
        try {
            String[] cats = {CAT_GUARD, CAT_EMOTION, CAT_SCHEDULE, CAT_SYSTEM, CAT_MEMORY};
            for (String cat : cats) {
                JSONArray arr = new JSONArray();
                for (Plugin p : all()) {
                    if (!cat.equals(p.category)) continue;
                    JSONObject o = new JSONObject();
                    o.put("id", p.id); o.put("name", p.name); o.put("desc", p.desc);
                    o.put("persist", p.persist); o.put("two_way", p.twoWay);
                    arr.put(o);
                }
                out.put(cat, arr);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
