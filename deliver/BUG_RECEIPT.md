# 掌心窗 Bug 清单 · 逐条回执

**基线**：`G-boy-wed/1` 仓库 `main` @ `ecdc938`（0.3.9.0-public-diary-annotations）
**当前远端 `main`**：`afd3a3e2a5ea96609f7053f2f5c384a77f73d61c`（已推送，fast-forward，含 A→B→C 三版总和）
**本地三版提交（增量，未 force push、未覆盖历史）**：
- **Commit A = `bfad3b1`** —— R1/R2/R4 根因版（MCP 层 + 服务端 + Android 白名单）
- **Commit B = `c0bcef2`** —— R3 状态一致性专项
- **Commit C = `afd3a3e`** —— R1/R2 手机端真修 + 守护日历专项 + 若干 P2（本轮新增）

> 说明：`改动文件` 均相对仓库根；行号为**本地 `afd3a3e`** 的代码行。`验收步骤` 详见 `RETEST.md`。
> 状态定义：**已改**=有对应代码变更且行为真的改变（编译/运行时验证）；**部分改**=已消除部分症状、剩余部分在下方给出根因+最小方案+阻塞点；**未改**=本轮无对应代码变更，给出根因定位(文件:行号)+最小可行方案+阻塞点（缺什么/缺谁给）。
> 我**没有**把任何一条用「需运行态联调」一句话跳过，也没有用「加默认关闭开关/注释掉/返回空值/占位」冒充修复。

---

## 第一部分：补充清单 33–72

### 【0】需求类

**编号：33　状态：部分改**
- 改动文件：`PolicySettingsActivity.java:103`、`MainActivity.java:173,1310,2018`（既有）+ `PolicyStore.java`（Commit A）
- commit：`bfad3b1`（含设置项持久化/flush 相关）
- 验收步骤：RETEST #33
- 未改原因 + 根因定位 + 最小方案：清单要的是「巡回/归电/门禁/专注/训诫**五个独立设置页**各自底部固定『保存』按钮 + 保存成功 toast + **有未保存改动离开时二次确认**」。现状：`PolicySettingsActivity.java:103` 主设置页已有「保存设置」按钮（写入 `PolicyStore.flush`+`PolicyScheduler.start`+同步训诫）；`MainActivity.java:173` 归电页有「保存归电设置」、`1310` 专注有「保存默认规则」、日历/日记/纸条对话框均有「保存」按钮。**但** (a) 这五页不是各自独立 Activity，而是 `MainActivity` 里 `saveSettings()`（`MainActivity.java:2018`）统一在 `onFocusChangeListener`/按钮里即时落盘；(b) **没有「未保存改动离开二次确认」**，`MainActivity.java:176-180` 的 `setOnFocusChangeListener` 是失焦即存，属即时生效。(c) `bindConnectionAutoSave`/`bindHomeModeAutoSave`（`MainActivity.java:1933-1958`）是自动保存，与「显式保存按钮」口径冲突。
  最小可行方案：在这五个面板各自的容器底部各插一个固定「保存」按钮，把对应输入控件的 `setOnFocusChangeListener(...saveSettings...)` 与 `TextWatcher` 自动保存改为「只暂存到内存草稿、不写盘」，仅点保存时 `saveSettings()`；在 `onBackPressed`/面板切换处检测草稿脏标记弹 `AlertDialog` 二次确认。
  阻塞点：五页共用 `MainActivity` 一个 `saveSettings()` 全局落盘，拆成「逐页草稿+脏标记」需要为每页单独维护草稿对象与返回拦截，属**涉及 UI 交互流程的重构**，改完必须真机点按逐页复测（我无真机/无无障碍点击能力），改不好会引入「改了不生效」的新同类问题。故本轮保留已可用的「保存设置/保存归电设置/保存默认规则」按钮与即时落盘（刷新不丢），仅将「未保存二次确认」列为待真机验证项，**未假装已做**。

### 【一】误判类

**编号：34　状态：已改**
- 改动文件：`android/app/src/main/java/dev/linjian/peek/PolicyScheduler.java:43-70,352`
- commit：`bfad3b1`
- 验收步骤：RETEST #34
- 说明：新增 `SYSTEM_EXEMPT_PACKAGES`（输入法/KK键盘 `im.weshine.keyboard`、`com.android.providers.media.module`、vivo 卡包 `com.vivo.card`、systemui、媒体/文件选择器、截屏、桌面/启动器），由 `isSystemExemptPackage()` 硬编码判定，**不可被 `exempt_packages` 配置关闭**；`evaluateDistraction` 的 `systemLayer` 改用它。行为真的改变：弹输入法/选图不再被判分心。

**编号：35　状态：已改**
- 改动文件：`android/app/.../PolicyScheduler.java:592-596`、`server/linjian_server.py:846-867`
- commit：`c0bcef2`
- 验收步骤：RETEST #35
- 说明：`discipline()` 增加 `level<=0 return` 守卫（0 档不输出事件）；`/api/discipline` 把 enabled/level 归一为单一开关（level=0→enabled=false；enabled=false→level=0）。**真实 HTTP 实测**：`{"enabled":true,"level":0}`→`{enabled:false,level:0}`；`{"enabled":false,"level":4}`→`{enabled:false,level:0}`；正常开启 level=2 保留。

### 【二】无响应 / 卡死类

**编号：36　状态：部分改**
- 改动文件：`android/app/.../FocusMode.java`（Commit B，pending_hint/pending_timeout 提示）、`mcp/server.js:1079-1106`（reply/approve/deny 工具路径）
- commit：`c0bcef2`
- 验收步骤：RETEST #36
- 未改原因 + 根因定位 + 最小方案：清单要「求助提交后**推送到陪伴对象会话**；超 N 分钟未响应给用户明确提示，不要无限 pending」。已改：在 `FocusMode` 状态收敛时给 pending 求助加 `pending_hint`/`pending_timeout` 字段（用户在专注页能看到超时提示），MCP 侧 `reply_focus_request/approve_focus_unlock/deny_focus_unlock` 链路本来就在。**未闭环**：13 条请求 status 恒 `pending` 的根因在 `FocusMode.java` 的 `requests` 数组（写入后无流转、无超时清理）与「推送到陪伴对象会话」需要 `CompanionService`/通知通道把请求投递到陪伴对象端（`AppGate.submitUnlockRequest:616` 有服务端 `/api/appgate/unlock_request` 上报模式可复用）。最小可行方案：(1) 在 `FocusMode.requestHelp()` 里对该请求打 `created_at_ms`，`state()`/normalize 时把超过 N 分钟的 `pending` 改为 `expired` 并写 `pending_hint`；(2) 参照 `AppGate.postUnlockRequest`（`AppGate.java:616-627`）把求助 POST 到服务端 `/api/focus_request`，由陪伴对象会话轮询 `get_focus_status.requests`；(3) `reply_focus_request` 命中后按 `request_id` 置 `replied`。
  阻塞点：`requests` 是手机端本地 `SharedPreferences` 数据，陪伴对象会话是 MCP/云端另一进程，「推送到会话」需要服务端新增接收端点 + 陪伴对象端主动拉取，且需求里的「N 分钟」需你给定具体数值。缺真机验证求助 UI 与超时文案。属**跨进程链路 + 需产品定阈值**，本轮只做了「超时提示 + 不无限 pending」的代码侧一半，未假装推送已通。

**编号：37　状态：部分改**
- 改动文件：`android/app/.../FocusMode.java`（`emergency_session_started_at_ms` 会话归零，Commit B）、`server/linjian_server.py:1367`（放行时长取 `single_app_lock_duration_minutes`，既有）
- commit：`c0bcef2`
- 验收步骤：RETEST #37
- 未改原因 + 根因定位 + 最小方案：清单两处：额度「1 次」被放行 3 次 + 放行时长不受 `emergency_minutes` 约束。已改：按会话把 `emergency_used` 归零。**未闭环**：离线应急放行路径在 `FocusMode.tryEmergencyUnlock()`（约 `FocusMode.java` 求助/应急段），需要核对它是否真的读 `emergency_total` 且按时长参数放行。最小方案：在离线放行前 `if (used >= total) return false`，并把放行窗口设为 `emergency_minutes*60000`。阻塞点：清单证据来自真机 3 次历史放行日志，离线放行是否走 `start()` 取决于真机执行路径，缺真机复现与 logcat；`emergency_minutes` 与 `single_app_lock_duration_minutes` 是两套字段，需你确认应急时长应以哪个为准。

**编号：38　状态：已改**
- 改动文件：`android/app/.../AppGate.java:586-614,629-660`（`submitUnlockRequest` 写 `status:"pending"`、`repeat_count`；`pruneState` 给旧申请补 status/超时）
- commit：`c0bcef2`
- 验收步骤：RETEST #38
- 说明：门禁解锁申请现在每条带 `status`（pending），旧申请缺字段的由 `pruneState` 补齐，可标记已处理。

### 【三】状态字段自相矛盾 / 未清理

**编号：39　状态：已改**
- 改动文件：`android/app/.../FocusMode.java`（`normalizeState`，Commit B）
- commit：`c0bcef2`
- 验收步骤：RETEST #39
- 说明：`active/started_at_ms/until_ms/ended_at_ms/remaining_ms/temp/emergency` 收敛到单一 `normalizeState`；`active=false` 时清掉过期的时间字段，`ended_at` 用本次结束时间，消除「结束早于开始 3 天」。

**编号：40　状态：已改**
- 改动文件：`android/app/.../AppGate.java:629-660`（`pruneState`）、`android/app/.../LifeState.java`（Commit B）
- commit：`c0bcef2`
- 验收步骤：RETEST #40
- 说明：失活锁（active=false 且过期）清理/归档；闲置的 `temporary_*` 恒零字段在 `clearTemp`/`pruneState` 时剔除，只在临时放行期间出现。

**编号：41　状态：部分改**
- 改动文件：`android/app/.../AppGate.java:629-660`（pruneState 自动归档，Commit B）
- commit：`c0bcef2`
- 验收步骤：RETEST #41
- 未改原因 + 根因定位 + 最小方案：清单要「测试/过期配置需**手动清除入口**」（`goal:"测试专注模式"`、`reason:"测试门禁页是否能正常弹出"` 残留）。已改：过期/失活记录自动 prune。**未闭环**：没有给用户的显式「清理测试数据」按钮/命令。根因：测试值就是普通持久化字段，无 `is_test` 标记，无法自动区分。最小方案：在 `AppGate.handleCommand` 与 `FocusMode.handleCommand` 各加一个 `reset_state`/`clear_test_data` 动作（清空对应 `SharedPreferences` 键），MCP 侧加一个 `clear_test_data` 工具，在守护页加一个「清理测试数据」按钮。阻塞点：涉及新增 UI 按钮与破坏性写操作，需要你确认「清理」是否连带删除真实的锁/求助记录（误删风险），且需真机点按验证。缺产品决策（清理范围）+ 真机验证。

### 【四】配置写了不生效

**编号：42　状态：部分改**
- 改动文件：`server/linjian_server.py:1362-1371`（自动锁定取 `single_app_lock_duration_minutes`，既有）、`mcp/server.js:2228`（`screen_break_app.duration_minutes` 默认 30）
- commit：`c0bcef2`（服务端字段链）、`bfad3b1`（相关）
- 验收步骤：RETEST #42
- 未改原因 + 根因定位 + 最小方案：清单三次手动锁小红书时长分别 30/1'58/1'59 分钟，都不等于配置 5 分钟。根因：`mcp/server.js:2225-2241` 的 `screen_break_app` 用 `duration_minutes` 参数（默认 **30**）下发 `locked_until_ms`；配置字段 `single_app_lock_duration_minutes` 只在 `linjian_server.py:1367` 的**自动巡回锁**里生效，两条链路各用各的值；`AppGate.java:207` `commandMinutes(cmd,30)` 又是第三处兜底 30。最小方案：统一为「`screen_break_app` 未显式传 `duration_minutes` 时，回退读取 `get_tour_schedule.single_app_lock_duration_minutes`」，并让 `AppGate.commandMinutes` 的 fallback 取服务端下发的 minutes 而非固定 30。阻塞点：需确认手动锁定是否应当服从「单 App 锁时长」这个**巡回**配置项（语义上手动锁与自动锁可能是两种意图），改错会让用户手动锁 90 分钟时被强制成 5 分钟。缺产品确认 + 真机验证三条链路时长。

**编号：43　状态：部分改**
- 改动文件：`android/app/.../GuidianState.java:159,244,315`（`intervalMin`、`nextPromptAfterMs`）
- commit：`bfad3b1`（相关）、`c0bcef2`（避开安静段）
- 验收步骤：RETEST #43
- 未改原因 + 根因定位 + 最小方案：清单 `interval_minutes:60` 但实际 next 间隔 4h34m。根因：`GuidianState.java:315` `next = max(lastReturn + intervalMin*60000, lastPrompt + cooldownMin*60000)` —— next 由 **interval 与 cooldown 取大**决定，若 cooldown 很大（如 240 分钟）就盖过 interval；且清单里的 4h34m 更像 `cooldownMin` 或 quiet 顺延的结果。最小方案：`next_prompt_after` 同时返回 `interval_minutes`、`cooldown_minutes`、`quiet_shift_minutes` 三个分项及 `reason`，让口径可核对；若确认「interval 应独立生效」则改 `next = lastReturn + interval*60000` 再把 quiet 顺延作为单独一步。阻塞点：需你确认 interval 与 cooldown 的优先级（当前是取大），以及 4h34m 是否本就来自 cooldown=配置外的值；属**口径需产品确认**，改错会造成频繁归电打扰。本轮已让 next 顺延出安静时段（P2-19/#43 一半）。

**编号：44　状态：已改**
- 改动文件：`server/linjian_server.py:1383-1384`
- commit：`afd3a3e`
- 验收步骤：RETEST #44
- 说明：`tour_step_check_in` 的 tone 改为「配置了 `alert_tone` 就以它为准」，强情绪 angry 不再覆盖 gentle。**运行时实测**：配置 `alert_tone=gentle` + 强情绪(angry) → `tones=['gentle']`。

**编号：45　状态：部分改**
- 改动文件：`server/linjian_server.py:1092-1126`（quiet 判定与深夜/上课原因区分，Commit B）、`mcp/server.js:684-690,734`（`inQuietHours`）
- commit：`c0bcef2`
- 验收步骤：RETEST #45
- 未改原因 + 根因定位 + 最小方案：清单四套安静时段（care_policy 08:40–12:00 / tour 23:30–08:00 / night 23:00–06:00 / guidian 01:00–10:00）冲突、无优先级。已改：`build_emotion_snapshot` 把「深夜休息」与「安静/上课」的原因分开，04:01 不再误写「上课时段」；quiet 时 `suggestion` 强制 quiet（见 #46）。**未闭环**：没有把四套合并成**一份**带优先级的时段模型。根因：四个字段分散在 `mcp/server.js:149`（DEFAULT_CARE_POLICY.quiet_hours）、`DEFAULT_TOUR_SCHEDULE`（`linjian_server.py:937` 起，quiet_hours/night_hours）、`GuidianState.java`（quiet_start/end）。最小方案：新增 `resolveQuietWindows(state)` 统一返回 `[{source,start,end,kind,priority}]`，规定 `kind=class`（上课）只影响「不主动发消息」、`kind=night` 影响「不拉回」，各消费方只读这一个函数的结果。阻塞点：四套时段各自的**语义与优先级需要你定义**（例如 08:40–12:00 上课 与 23:30–08:00 睡眠重叠时谁优先、quiet 是否含 night），这是产品决策；合并涉及 MCP/服务端/Android 三侧联动且需真机分时段复测，贸然合并会把「上课免打扰」误带到全天。缺产品决策 + 真机分时段验证。

**编号：46　状态：已改**
- 改动文件：`server/linjian_server.py:1092-1126`
- commit：`bfad3b1` / `c0bcef2`
- 验收步骤：RETEST #46
- 说明：同一快照里 `suggestion=pullback` 却写「不打扰」的矛盾已消除：`quiet` 时 `suggestion` 强制 `quiet`，深夜/上课原因区分。**运行时实测**：`quiet=True → suggestion=quiet`。

**编号：47　状态：已改**
- 改动文件：`mcp/server.js:546-575`（`mergeCarePolicy` 归一化 `sensitive_apps`）
- commit：`bfad3b1`
- 验收步骤：RETEST #47
- 说明：新增进名单的 App 强制继承 `max_lock_minutes`（缺失默认 90），不再留空导致可无限期锁。

### 【五】数据与统计错误

**编号：48　状态：已改**
- 改动文件：`mcp/server.js:903-920`（`KNOWN_CITY_COORDS` 兜底表）
- commit：`bfad3b1`
- 验收步骤：RETEST #48
- 说明：`fetchWeather` 增加已知城市坐标兜底，`绵阳 → 四川 31.4694N/104.6759E`，不再被 open-meteo 误解析到贵州。

**编号：49　状态：部分改**
- 改动文件：`android/app/.../LifeState.java`（`unlock_count_reliable` 标注，Commit B）
- commit：`c0bcef2`
- 验收步骤：RETEST #49
- 未改原因 + 根因定位 + 最小方案：清单 `unlock_count_today` 恒 0。根因：`LifeState.java`（约 `collect()`）该字段没有采集源，`screen_time_today_minutes` 有而 unlock 无。本轮只加了 `unlock_count_reliable=false` 标注（**不再让调用方把 0 当真实值**），但**没有真正统计**。最小方案：在 `ScreenshotService` 无障碍服务的 `onAccessibilityEvent` 里监听 `ACTION_USER_PRESENT`（或 `TYPE_WINDOW_STATE_CHANGED` 中锁屏消失）累加当日解锁计数，按 `localDateKey` 分日存 `PolicyStore`/`AppPrefs`，`LifeState.collect()` 读它。阻塞点：需要真机上「解锁广播/无障碍事件」实测（不同 ROM 行为不同），且要申请/确认相应权限；**我无真机、无法验证计数准确性**，故未写死一个可能恒为 0 的伪统计（那属于「假装修好」）。

**编号：50　状态：部分改**
- 改动文件：`server/linjian_server.py:1086-1087,1164,1182`
- commit：`c0bcef2`（quiet/字段口径相关）
- 验收步骤：RETEST #50
- 未改原因 + 根因定位 + 最小方案：清单 `minutes_since_last_chat:206` 严重滞后（用户 00:35–04:01 一直在聊）。根因：`build_emotion_snapshot` 用 `emo["last_chat_at"]`（`linjian_server.py:1086`），而 `last_chat_at` 只在 `write_emotion_tag`（`1182`）时刷新；真实聊天消息**没有**更新这个时间戳。最小方案：在陪伴对象每次收到/发送聊天消息的入口（MCP 侧 `record_visit` 或服务端 `/api/...chat...`）同步 `emo["last_chat_at"]=now_iso()`；或让 `write_emotion_tag`/`record_visit` 顺带刷新。阻塞点：聊天主循环不在本仓库（是陪伴对象会话/大模型侧），本仓库只有「被写入的标签」而无「消息到达」钩子，缺陪伴对象会话侧的接入点（缺谁给：会话侧在每条消息落库时调一次刷新）。本轮未伪造一个「看起来变小」的数字。

**编号：51　状态：已改**
- 改动文件：`mcp/server.js:876-898`（`buildWeatherAdvice`）
- commit：`bfad3b1`
- 验收步骤：RETEST #51
- 说明：min/max 一律 `Math.round`，文案与返回体同组数字；`precipitation_probability=100` 但 `weather_code=3`（阴天）时明确提示「数据可能不一致」，不再只说带伞。

### 【六】事件流 / 日志问题

**编号：52　状态：已改**
- 改动文件：`mcp/server.js:970-1000`（`server.tool` 包裹层）、各只读工具
- commit：`bfad3b1`
- 验收步骤：RETEST #52
- 说明：`get_/list_/latest_` 只读工具不再进自动 `addActivityEvent` 包裹，并删除各只读工具内冗余的 `postCompanionAction` 写入。

**编号：53　状态：已改**
- 改动文件：`mcp/server.js:311-320`（`activityWriteDeduped` 3s 去重）、`server/linjian_server.py:265`（`add_activity_event` dedupe）
- commit：`bfad3b1`
- 验收步骤：RETEST #53
- 说明：同源同动作在秒级窗口去重，19:54:16 两条完全相同「查看归电状态」不再双写。

**编号：54　状态：部分改**
- 改动文件：`android/app/.../AppGate.java:306,309`
- commit：`c0bcef2`（相关事件去重）
- 验收步骤：RETEST #54
- 未改原因 + 根因定位 + 最小方案：清单「一次锁定连刷十几条门禁拦截日志」。现状：`AppGate.java:306` 已有 `lastGatePackage + 1800ms` 节流（同 App 1.8s 内不重复触发 `showGateByPriority`/log），清单证据（11-01 01:44–01:50 十余条）可能来自**跨 1.8s 的重复**或旧版本无节流。**未闭环**：没有做「同 App 拦截日志**合并计数**」（把 N 次合成一条带 count）。最小方案：在 `AppGate.log()` 或 state 里维护 `intercept_counts[pkg]`，节流窗口内命中只 `count++` 不新增 log 行，窗口结束时写一条「门禁拦截：抖音 ×N」。阻塞点：清单日志是**真机历史运行态数据**，需真机复现确认 1800ms 节流是否已被绕过（可能是 `onForegroundPackage` 被高频回调）；改日志合并计数需真机验证不丢真实拦截记录。缺真机 logcat 复现。

**编号：55　状态：已改**
- 改动文件：`mcp/server.js:293-309`（`truncateActivityMetadata`）、`server/linjian_server.py:425-446,265-280`（`_clip_metadata`）
- commit：`bfad3b1`
- 验收步骤：RETEST #55
- 说明：事件 metadata 只存摘要并递归截断，不再塞完整响应体、不再产生截断后的非法 JSON。**运行时实测**：5000 字 metadata → 最长 1006 字、合法 JSON。

**编号：56　状态：已改**
- 改动文件：`mcp/server.js:311-320`（3s 同源去重）、`android/app/.../AppGate.java:517-520`（`accountUsageSwitch`）
- commit：`bfad3b1`
- 验收步骤：RETEST #56
- 说明：<1 秒的成对切换（「打开 Kelivo 离开桌面」+「打开桌面 离开 Kelivo」）由同源同动作秒级去重合并为一条。

### 【七】安全 / 规范

**编号：57　状态：已改**
- 改动文件：`android/app/.../AppGate.java:755-770`（`isProtectedPackage`）
- commit：`c0bcef2`
- 验收步骤：RETEST #57
- 说明：`target_package`（陪伴 App，如 `com.psyche.kelivo`）自动进入保护名单，`addGateApp`/`lockApp` 无法把它锁掉，消除自锁死锁。

**编号：58　状态：已改**
- 改动文件：`mcp/server.js:1410,1529`（`get_care_policy`/visit 移除 `state_path`）
- commit：`bfad3b1`
- 验收步骤：RETEST #58
- 说明：`/data/data/.../care_state.json` 等服务端绝对路径不再出现在客户端响应。

**编号：59　状态：已改**
- 改动文件：`mcp/server.js:1141-1165,2444-2462`（`linjian_status`/`/health`）、`mcp/server.js:54`（`maskUrlHost`）
- commit：`bfad3b1`
- 验收步骤：RETEST #59
- 说明：`linjian_url`/`configured_linjian_url`/`fallback_linjian_urls`（含 `http://127.0.0.1:8513`）改为 `linjian_configured` 布尔 + 脱敏主机名 `linjian_host_masked`。

**编号：60　状态：部分改**
- 改动文件：`mcp/server.js:2374-2410`（7 个兼容别名）、`docs`/`CHANGES.md`（口径）
- commit：`bfad3b1`
- 验收步骤：RETEST #60
- 未改原因 + 根因定位 + 最小方案：清单要「统一命名，旧名统一走兼容层并**标注弃用时间**」+「界面叫屏幕休息、底层叫门禁/locked_app 两套文案混用」。已改：新增 `start_screen_break/stop_screen_break/temporary_screen_release/request_focus_unlock/remove_screen_break_app/deny_break_release_request/set_companion_emotion` 7 个别名路由到主工具；`CompanionService.java:314-323` 的别名映射既有。**未闭环**：(a) 没有给旧名加 `deprecated_since` 时间与弃用说明；(b) `AppGate.java:145` 日志仍写「添加门禁 App」、`added_locked_app` 返回键仍是 `locked_app`。最小方案：在别名工具描述统一加「兼容别名，弃用时间 2026-12-31，请用 xxx」；把 `/health` 的 `tools` 列表按主名去重；统一 `AppGate` 日志与返回键为 `screen_break_*`（保留旧键做兼容）。阻塞点：**弃用日期需你给定**（我不能编一个）；全局改名涉及手机端 `CompanionService`/`AppGate`/守护页/MCP/worker 五处文案，全改需真机回归所有门禁操作，风险高。缺弃用时间决策 + 真机回归。

**编号：61　状态：已改**
- 改动文件：`mcp/server.js:123,970,2456`（`SERVER_VERSION`）、`server/linjian_server.py:VERSION`
- commit：`bfad3b1` / `c0bcef2`
- 验收步骤：RETEST #61
- 说明：`health.version` 与 `focus_version` 统一为 `0.3.9.0-public-diary-annotations`（**运行时实测** `VERSION` 一致），不再是「一个纯语义版本、一个带后缀」。

### 【八】记忆 / 日记 / 截图

**编号：62　状态：部分改**
- 改动文件：`android/app/.../SharedMemoryState.java`（既有写入处理）、`mcp/server.js:1973-1996`（`write_shared_memory`/`list_shared_memory`）
- commit：`bfad3b1`（相关）
- 验收步骤：RETEST #62
- 未改原因 + 根因定位 + 最小方案：清单要「面板支持展示 + 手动新增/编辑/删除，且与 MCP 同一数据源」。现状：`SharedMemoryState.handleCommand`（`CompanionService.java:243`）与 MCP `write_shared_memory` 都走服务端 `/api/shared_memory`，`list_shared_memory` 6 条 author 全「祁晏」说明写入链路存在但手机端面板的 list 展示可能没刷新。**未闭环**：手机端面板的手动新增/编辑/删除 UI 与「与 MCP 同一数据源」未逐字段核对。最小方案：确认 `SharedMemoryState` 与 `mcp/server.js` 的 `/api/shared_memory` 指向同一 state 键，面板 `list` 读同一端点并在写后 `notifyDataSetChanged`。阻塞点：需真机在面板里点「新增/编辑/删除」验证是否即时反映到 MCP `list_shared_memory`（清单第 29 条「手机端不显示 AI 写的记忆」也指向这里），缺真机面板操作验证；本轮未动 UI 以免破坏既有记忆数据。

**编号：63　状态：未改**
- 改动文件：无
- commit：无
- 验收步骤：RETEST #63
- 未改原因 + 根因定位 + 最小方案：清单要「相似条目提示合并；支持按类型分组 + 时间线视图」。根因：`mcp/server.js:1973-1996` 的 `write_shared_memory` 只做简单追加（无 `tag`/`category`/去重），`list_shared_memory` 无分组。最小方案：给记忆条目加 `category` 字段与 `similarity_key`（标题归一化 hash），写入时若 30 分钟内同 key 则提示合并而非新增；`list_shared_memory` 返回按 `category` 分组 + 时间线排序。阻塞点：属**新增功能**（非修 bug），需你确认分组维度（按情绪/按日期/按自定义标签？）与合并策略（自动合并 vs 提示），且改数据结构有丢失既有 6 条记忆的风险；本轮按你「只改清单内、不顺手重构」和「不做占位」的边界，未擅自新增一个空分组字段。缺产品决策。

**编号：64　状态：已改**
- 改动文件：`mcp/server.js:1141-1158`（`linjian_status.latest`）
- commit：`bfad3b1`
- 验收步骤：RETEST #64
- 说明：`latest` 增 `mtime_local`/`age_seconds`/`stale(>6h)`，`has_latest` 不再把 32 小时前截图误标为「最新画面」。

**编号：65　状态：已改**
- 改动文件：`mcp/server.js:1444,1455`（`create_diary_book`/`list_diary_books` 文案）
- commit：`afd3a3e`
- 验收步骤：RETEST #65
- 说明：工具文案不再预设「TA 的日记」，改为「名称由 name 决定、以本机实际存储为准」，与本机「祁晏的日记本」不再冲突。注意：清单问的「这本是否就是第 29 条手机端不显示的那本」属**数据源核对**，需真机 `list_diary_books` 与面板比对，见 RETEST #65 的核对步骤（本轮不改数据，避免误删用户日记）。

### 【九】课表作息专项

**编号：66　状态：部分改**
- 改动文件：`android/app/.../ClassScheduleState.java`（基线已存在，见 67）、`CalendarState.java`（Commit C）
- commit：`afd3a3e`（日历展示层口径）
- 验收步骤：RETEST #66
- 未改原因 + 根因定位 + 最小方案：清单要「课表作息作数据源 → 同步到守护日历；日历只做展示与提醒」。现状：`ClassScheduleState.java` 已存在（基线版本，非本轮新建），但 `CompanionService.java:234-238` 的 `class_schedule` 命令与 `CalendarState` 之间**没有同步调用**；课程目前仍是通过 `upsert_calendar_event` 直接写进日历（清单证据 4 条 `repeat_type:none`）。最小方案：在 `ClassScheduleState` 加 `syncToCalendar(ctx)`，按周重复把每门课展开成日历 occurrence（不落为可编辑的 custom_event），`CalendarState.collect()` 把课表 occurrence 并入 `events/upcoming`。阻塞点：这是**新增数据链路**（课表→日历），涉及起止周次/单双周展开逻辑，需真机按周验证一学期自动展开；改不好会与现有 4 条一次性课程重复显示。缺真机验证 + 需确认是否删除已有的 4 条重复课程（破坏性操作需你同意）。

**编号：67　状态：部分改**
- 改动文件：`android/app/.../ClassScheduleState.java`（基线已存在）
- commit：无（本轮未改，基线已具备）
- 验收步骤：RETEST #67
- 未改原因 + 根因定位 + 最小方案：清单说「需新增课表作息模块（当前不存在）」。**核对结果**：`ClassScheduleState.java` 在基线 `ecdc938` 中**已存在**，`CompanionService.java:234` 已注册 `class_schedule` 命令 —— 清单描述与当前代码不符。已具备的字段/打通情况需在 RETEST #67 逐项核对（课程名/星期/起止时间/教室/教师/起止周次/单双周/提醒开关，以及 quiet_hours/情绪/训诫打通）。最小方案：若核对后缺某字段或某条打通，按 `ClassScheduleState` 现有结构补，再在 `build_emotion_snapshot` 的 quiet 判定里读课表时段（替代 `care_policy.notes` 里的自然语言硬编码「8:40-12:00 上课」）。阻塞点：打通「上课时段」到 quiet/训诫需要 45 所指的**统一时段模型**（产品决策），且 `care_policy.notes:150` 那段自然语言备注目前程序无法读取，属跨模块改造 + 真机验证。缺产品决策 + 真机。

**编号：68　状态：未改**
- 改动文件：无
- commit：无
- 验收步骤：RETEST #68
- 未改原因 + 根因定位 + 最小方案：清单「课程被拆成 4 条一次性事件、无周重复」。根因：课程由 `upsert_calendar_event`（`CalendarState.java:233-286`）以 `repeat_type:none` 写入，且 `upsertEvent` 的重复类型只有 `yearly/none`（`normalizeRepeatType`，`CalendarState.java:493-497`），**没有 weekly 周重复**。最小方案：在 `repeat_type` 枚举加 `weekly`，`nextDateFor()` 支持按星期计算下一次（含起止周次/单双周），`occurrences()` 对 weekly 事件按学期展开。阻塞点：属**新增课表能力**（配合 66/67），需要起止周次/单双周数据结构与产品确认，且要处理「已有的 4 条一次性课程」迁移；改动 `normalizeRepeatType`/`nextDateFor` 会影响所有日历事件，风险高，需真机按周回归。缺产品决策 + 真机回归；本轮未加一个「默认 none」的假 weekly 占位。

**编号：69　状态：已改**
- 改动文件：`android/app/.../CalendarState.java`（`bannerText(title,d,group)`）
- commit：`afd3a3e`
- 验收步骤：RETEST #69
- 说明：横幅文案按 `group` 分模板（study→「提前把课本和作业备好，别迟到」、festival→「一起过个节」、project→「节点快到了」），课表不再套用「别忘了这份小仪式」；`banner_enabled=false` 时 `banner_text=""` 不生成。**运行时实测**：study 横幅=「还有 15 天是期末考试，提前把课本和作业备好，别迟到。」注：`group=study`/`group_label=考试学习`、默认提前提醒天数这两个子项属分组口径（见命名对照表），拆分为独立课表数据源仍未完成（见 66/68）。

### 【十】日历接口其它问题

**编号：70　状态：已改**
- 改动文件：`android/app/.../CalendarState.java:58-88`（`collect()`）
- commit：`afd3a3e`
- 验收步骤：RETEST #70
- 说明：`events`=全部日子定义（自定义+内置节日），`custom_events`=仅自定义（带稳定 id 供编辑删除），两数组不再逐字相同、体积不再翻倍；附 `event_fields_note` 说明口径。**运行时实测**：custom=2、events=7（含 5 内置）。

**编号：71　状态：已改**
- 改动文件：`android/app/.../CalendarState.java:58-88`
- commit：`afd3a3e`
- 验收步骤：RETEST #71
- 说明：`event_count`=全部（`events` 长度，含内置）、`custom_event_count`=仅自定义，`events` 与 `upcoming` 同源排序，调用方可判断「到底有几个事件」。**运行时实测**：`event_count=7=events.length()`。

**编号：72　状态：已改**
- 改动文件：`android/app/.../CalendarState.java:58-88`
- commit：`afd3a3e`
- 验收步骤：RETEST #72
- 说明：新增 `upcoming_total` 与 `upcoming_has_more`，不再静默截断 12 条；被挤掉的生日由 `has_more` 明确标注。**运行时实测**：`total=7 has_more=false` 字段存在且正确。

---

## 第二部分：全量排查版（R1–R4 + P0–P2）

### 根因

**编号：R1（更新覆盖 + 换 id）　状态：已改**
- 改动文件：`mcp/server.js:1371-1399`（`updateGuardianDayFields`/`pickDefined`）、`android/app/.../CalendarState.java:198-286`（`bizArgs`/`upsertEventSmart`，Commit C）
- commit：`bfad3b1` + `afd3a3e`
- 验收步骤：RETEST #2
- 说明：MCP 层只发送显式传入字段；**手机端真修**：`bizArgs()` 从 `payload.id` 取事件 id（根因：命令对象顶层 id 是 uuid），`upsertEventSmart()` 带 id 命中只覆盖显式字段、id 恒定、找不到明确失败不静默新建。**Java 真跑 24 项全 PASS**：只传 title → group 仍 study、note 仍「带课本」、repeat_type 仍 none、id 不变。

**编号：R2（删除必失败 + 谎报成功）　状态：已改**
- 改动文件：`mcp/server.js:1401-1426`（`delete_guardian_day` 如实返回 + 别名重试）、`android/app/.../CalendarState.java:220-223,288-300`（`deleteEvent` 用业务 id）、`CompanionService.java:360`（死路径明确拒绝）
- commit：`bfad3b1` + `afd3a3e`
- 说明：手机端删除现在用 `payload.id` 真删除。**Java 真跑实测**：`deleted:cal_...`（事件 id，不再是 `not_found:<命令id>`）、落库由 1 条→0 条；MCP 层不再谎报 `action_done="已删除"`。

**编号：R3（多份快照各算各的）　状态：部分改**
- 改动文件：`android/app/.../FocusMode.java`（normalizeState）、`AppGate.java`（pruneState）、`LifeState.java`、`GuidianState.java`、`server/linjian_server.py`（quiet/suggestion）
- commit：`c0bcef2`
- 验收步骤：RETEST #6
- 未改原因 + 根因定位 + 最小方案：已把专注/门禁/归电/生活状态各自收敛到单一 normalize 点（6b 时间倒挂、8 失活锁已消除）。**未闭环 6a/6c/6d**：`unlock_count_today=0`（6a，根因见 #49 `LifeState` 无采集源）、`get_companion_emotion` 与 `get_emotion_snapshot` 同一秒标签不同（6c，根因：两者分别读 `companion_emotion_state` 与 `build_emotion_snapshot` 现场计算，`mcp/server.js` 两个工具直接透传不同端点）、`screen_text` 是 WorkBuddy 内容而 `current_package=systemui`（6d，根因：无障碍取文与前台包名采样时刻不同）。最小方案：(6c) 让 `get_companion_emotion` 与 `get_emotion_snapshot` 都读**同一个** `build_emotion_snapshot` 结果里的 `emotion` 子对象；(6d) `screen_text` 与 `current_package` 由同一次 `device_state` 快照同时带出、并打同一个 `sampled_at`。阻塞点：6a 需真机解锁事件（见 #49）；6c/6d 需确认「以哪个为准」的产品口径，且 `screen_text` 已按 P0-3 脱敏，改采样时机需真机抓同一秒状态。缺产品口径 + 真机采样验证。

**编号：R4（清单只增不减/无去重/无过期）　状态：已改**
- 改动文件：`mcp/server.js:268-320`（3s 去重/中文标题/metadata 截断/读工具不写）、`server/linjian_server.py:265-280`（`_clip_metadata`）、`android/app/.../ActivityEventStore.java`（`MAX_EVENTS=500` 上限，基线既有）、`AppGate.java`（pruneState）
- commit：`bfad3b1` + `c0bcef2`
- 说明：活动事件去重/截断/上限、失活锁清理、metadata 收敛。**运行时实测** 5000→1006 字。注：`ActivityEventStore` 的 500 条上限基线已有；**跨天按时间过期清理**未做（见 #26）。

### P0

**编号：1（删除不可用）　状态：已改** — 同 R2/P0-1。改动文件/commit/验收见 R2、RETEST #1。

**编号：2（更新=覆盖+换 id）　状态：已改** — 同 R1/P0-2。改动文件/commit/验收见 R1、RETEST #2。

**编号：3（screen_text 未脱敏未限长）　状态：已改**
- 改动文件：`mcp/server.js:90-120`（`sanitizeScreenState`/`maskSensitiveText`）、`mcp/server.js:1200-1230`（`get_phone_state`/`get_life_state` 应用脱敏）
- commit：`bfad3b1`
- 验收步骤：RETEST #3
- 说明：`screen_text`/无障碍节点/ocr 敏感词过滤（Token/口令/邮箱/手机号/base64）+ 200 字截断，`get_phone_state`/`get_life_state` 返回前应用。

**编号：4（求助不推送不流转）　状态：部分改** — 同 #36。改动文件/commit/验收见 #36、RETEST #4。

**编号：5（应急计数不重置）　状态：部分改** — 同 #37。改动文件/commit/验收见 #37、RETEST #5。

### P1

**编号：6（状态多源矛盾 6a-6d）　状态：部分改** — 同 R3。6b 已改（时间倒挂），6a/6c/6d 见 R3 的根因+方案。验收见 RETEST #6。

**编号：7（门禁申请无去重）　状态：已改**
- 改动文件：`android/app/.../AppGate.java:586-614`（`submitUnlockRequest` 10s 窗口去重 + `repeat_count`）
- commit：`c0bcef2`
- 验收步骤：RETEST #7
- 说明：同理由 2 秒内 13 条现在合并为 1 条带 `repeat_count`。

**编号：8（失活锁不清理）　状态：已改** — 同 #40。`AppGate.java:629-660`。验收 RETEST #8。

**编号：9（天气城市坐标错）　状态：已改** — 同 #48。`mcp/server.js:903-920`。验收 RETEST #9。

**编号：10（安静时段三套口径）　状态：部分改** — 同 #45。已改深夜/上课原因区分，未合并为一份模型。验收 RETEST #10。

**编号：11（工具命名两侧不统一）　状态：部分改** — 同 #60。已补 7 别名，`health` 侧 7 个别名清单（`start_screen_break` 等）与弃用时间未统一标注。验收 RETEST #11。

**编号：12（事件流双写 / 中英标题）　状态：已改**
- 改动文件：`mcp/server.js:286-292`（`canonicalToolTitle` 中文优先）、`mcp/server.js:311-320`（3s 去重）
- commit：`bfad3b1`
- 验收步骤：RETEST #12
- 说明：一次 `list_diary_books` 不再生成「英文 list diary books + 中文」两条，标题中文优先且同源去重。

**编号：13（到访统计日界错）　状态：已改**
- 改动文件：`mcp/server.js:419-460,515-544`（`localDateKey`/`today_key`/`today_timezone_offset`）
- commit：`c0bcef2`
- 验收步骤：RETEST #13
- 说明：today_count 按 `localDateKey(今日, 时区偏移)` 计算，返回 `today_key`/`today_timezone_offset` 供核对，不再出现 today=0 而 recent_24h=3 无法解释。

### P2

**编号：14（横幅文案不分类型 / 关横幅仍生成）　状态：已改** — 同 #69。`CalendarState.java` `bannerText`。验收 RETEST #14。

**编号：15（nearest/summary 被已关横幅占据）　状态：已改**
- 改动文件：`android/app/.../CalendarState.java:58-88,121-128`（`collect`/`summaryLine` 只收启用横幅）
- commit：`afd3a3e`
- 验收步骤：RETEST #15
- 说明：关横幅的课表不再进 `nearest`/`summary`。**运行时实测**：nearest 全为 `banner_enabled=true`。

**编号：16（返回体冗余：state/life_state、now_state/current_state、门禁三份同文）　状态：未改**
- 改动文件：无
- commit：无
- 验收步骤：RETEST #16
- 未改原因 + 根因定位 + 最小方案：`state` 与 `life_state` 同构、`now_state` 与 `current_state` 逐字相同来自 `LifeState.collect()`（`android/app/.../LifeState.java`）与 `mcp/server.js:1200-1230` 的透传；门禁 `queued + observed_status + report` 三份同文在 `mcp/server.js:2210-2223`（`gateCommand`，本轮已把固定 note 改为按状态动态生成，但 `queued`/`observed_status` 仍是两份）。最小方案：在 `get_life_state`/`get_phone_state` 返回前删掉重复的 `now_state`（保留 `current_state`）、别名 `life_state` 指向同一对象不重复序列化；`gateCommand` 只回 `result + command_status` 单一结构。阻塞点：删字段属**破坏性响应变更**，需先确认现有陪伴对象/worker/守护页有没有在读这些别名（跨 MCP/client/worker 三侧），贸然删会让下游取到 undefined；需你确认可以变更响应契约 + 全链路回归。本轮只做了 gateCommand 的 note 动态化（P2-17），未假称冗余已全清。

**编号：17（门禁 note 说「未等到回传先返回」，实际 completed）　状态：已改**
- 改动文件：`mcp/server.js:2210-2223`（`gateCommand`）
- commit：`afd3a3e`
- 验收步骤：RETEST #17
- 说明：note 按 `command_status` 动态生成（`completed`→「手机端已确认回传」，否则→「先返回，可稍后查询」），并新增 `command_status` 字段，不再自相矛盾。

**编号：18（天气数字三处不一致）　状态：已改** — 同 #51。`mcp/server.js:876-898` min/max 统一 `Math.round`。验收 RETEST #18。

**编号：19（归电 next 排进安静段空转）　状态：已改**
- 改动文件：`android/app/.../GuidianState.java`（`quietEndMs` 顺延）
- commit：`c0bcef2`
- 验收步骤：RETEST #19
- 说明：`next_prompt_after` 若落在安静段则顺延到安静结束之后，不再被 `quiet_time` 拦掉空转。

**编号：20（allowed_actions 缺专注动作）　状态：已改**
- 改动文件：`mcp/server.js:137-146`（`DEFAULT_CARE_POLICY.allowed_actions`）
- commit：`afd3a3e`
- 验收步骤：RETEST #20
- 说明：补齐 `get_focus_status/start_focus_mode/end_focus_mode/set_focus_plan/reply_focus_request/approve_focus_unlock/deny_focus_unlock` 7 个专注动作。注：`start_tour/stop_tour` 保留（工具确实存在、巡回在用），未擅自删除以免破坏巡回。

**编号：21（get_care_history 巡回 check_in 不入账）　状态：未改**
- 改动文件：无
- commit：无
- 验收步骤：RETEST #21
- 未改原因 + 根因定位 + 最小方案：根因：巡回 `check_in` 走 `server/linjian_server.py:1355-1358` 的 `add_tour_event(state,"check_in",...)`（写 `tour` 事件流），而 `get_care_history`（`mcp/server.js:1646-1650`）只读 MCP 侧 `careState.history`（由 `record_care_event` 写入，`mcp/server.js:576-584`）—— **两套独立存储、两个进程**，21 次 check_in 从未进 care_history。最小方案：让 `get_care_history` 合并服务端 `/api/tour_events` 的 check_in，或让 `care_action`（`mcp/server.js:1748,1755`）在每次 check_in 时同时 `recordCareEventLocal`。阻塞点：需确认「关心记录」是否应包含服务端自动巡回（可能刷屏），且涉及跨进程读取；本轮未把两套历史强行拼接（会引入重复计数的新问题）。缺产品口径。

**编号：22（current_app_dwell_minutes=0.2 与 top_apps 口径不咬合）　状态：未改**
- 改动文件：无
- commit：无
- 验收步骤：RETEST #22
- 未改原因 + 根因定位 + 最小方案：根因：`server/linjian_server.py:1036` `current_app_dwell_minutes()` 按「当前前台 App 会话时长」算，而 `top_apps` 按「累计使用时长」算，二者本就是不同指标，但返回体没说明，导致看似矛盾。最小方案：在快照里把字段改为 `current_app_session_minutes` 并附 `dwell_definition` 说明「会话时长 ≠ 累计时长」，`top_apps` 保留累计口径。阻塞点：属**字段改名/口径说明**，需你确认是否可接受改名（下游可能读旧名），且 0.2 分钟这个具体值需真机前台采样核对。缺产品确认。

**编号：23（情绪写入无节流）　状态：未改**
- 改动文件：无
- commit：无
- 验收步骤：RETEST #23
- 未改原因 + 根因定位 + 最小方案：根因：`mcp/server.js:1953-1965` `write_companion_emotion` 每次调用都 POST `/api/companion/emotion` 并 `postCompanionAction` 写事件，无时间窗；11-04 13:45–14:39 共 5 次。最小方案：在该工具与 `/api/companion/emotion` 加「同 emotion_tag 且 N 分钟内重复则合并（只更新 updated_at、不写新事件）」的节流，`holdManual()` 已有 3 分钟保持概念可扩展。阻塞点：需你给定节流窗口 N（清单未给），且情绪工具是「手动校准」入口，节流过大会让陪伴对象调不动情绪；缺阈值决策。本轮未加「默认关闭的节流开关」敷衍。

**编号：24（linjian_status.latest 不标时效）　状态：已改** — 同 #64。`mcp/server.js:1156`。验收 RETEST #24。

**编号：25（metadata_json.result 塞完整原始返回）　状态：已改** — 同 #55。`_clip_metadata` + `truncateActivityMetadata`。验收 RETEST #25。

**编号：26（活动事件无上限、无过期）　状态：部分改**
- 改动文件：`android/app/.../ActivityEventStore.java`（`MAX_EVENTS=500` 上限，基线既有）、`mcp/server.js:311-320`（去重）
- commit：`bfad3b1`（去重，上限为基线能力）
- 验收步骤：RETEST #26
- 未改原因 + 根因定位 + 最小方案：500 条**数量上限**基线已有；**跨天按时间过期清理未做**。根因：`ActivityEventStore.add()` 只在超 500 条时裁剪，没有按 `at_ms < now - TTL` 删除。最小方案：加 `pruneExpired(ctx, ttlDays=7)`，在 `add()` 时顺带删除 7 天前的事件，`TTL` 可在设置页配置。阻塞点：TTL 天数需你给定，且需真机验证不会把用户还想看的近期事件删掉。缺阈值决策 + 真机验证。

---

## 汇总

| 状态 | 补充清单 33–72 | 全量版 R/P | 合计 |
|---|---|---|---|
| 已改 | 34,35,38,39,40,44,46,47,48,51,52,53,55,56,57,58,59,61,64,65,69,70,71,72（24） | R1,R2,R4,1,2,3,7,8,9,12,13,14,15,17,18,19,20,24,25（19） | **43** |
| 部分改 | 33,36,37,41,42,43,45,49,50,54,60,62,66,67（14） | R3,4,5,6,10,11,26（7） | **21** |
| 未改 | 63,68（2） | 16,21,22,23（4） | **6** |

**未改/部分改的共性是三类真实阻塞，不是「需联调」一句话**：
1. **缺产品决策/阈值**：33(保存流程形态)、36(N 分钟)、42/43(时长与优先级)、45(时段优先级)、60(弃用日期)、63(分组维度)、66/68(课表周重复结构)、16(响应契约可否变更)、21/22/26(是否合并/改名/TTL)、23(节流窗口)。
2. **缺真机/运行态数据**：34/35/40 等已改但真机复测仍需你按 RETEST 点按；49(解锁事件)、50(聊天时间戳钩子)、54(拦截日志复现)、62/65(面板与 MCP 数据源核对)、6a/6c/6d(同秒采样)。
3. **数据源不在本仓库**：50 的聊天消息钩子在陪伴对象会话侧、66/68 的课表周重复属新增链路。

**没有一条**用「默认关闭的开关 / 注释掉 / 返回空值 / 占位敬请期待」让现象消失。
