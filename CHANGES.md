# 掌心窗 — 本轮改动清单（按你的逐条要求）

---

## 第四轮：交付格式重排 + R3 状态一致性专项（本轮）

> 按交付要求拆成**两版可独立推送/回滚的提交**：
> - **Commit A（R1/R2/R4 根因版）**：见上一节「Bug 清单全量版本轮已处理项」+ 33–72 补充项。
> - **Commit B（R3 状态一致性专项，本节）**：状态由多份快照各算各的 → 收敛为单一来源。
> 每一条的「已改/未改 + 文件 + commit」逐条回执见 `BUG_RECEIPT.md`；
> 逐条复测步骤见 `RETEST_CHECKLIST.md`。

### Commit B（R3）已改
- **#35 训诫单开关 / 0 档不得输出事件**：`PolicyScheduler.discipline()` 增加 `level<=0` 守卫（关闭不再产生「训诫(0档)」事件）；`linjian_server.py /api/discipline` 归一 `enabled` 与 `level` 为单一状态源（level=0→enabled=false；enabled=false→level=0）。运行时冒烟已验证。
- **#39 / P1-6b / R3 专注三时间字段互不同步**：`FocusMode` 新增 `normalizeState()` 作为状态唯一收敛点，统一推导 active/remaining/temporary/emergency/pending；`start()` 清旧 ended；`isActiveRaw()` 到点补 `ended_at`；纠正「结束早于开始」的历史脏数据。
- **P0-4 / #36 专注求助全部 pending 无提示**：`normalizeState()` 计算 `pending_request_count` / `oldest_pending_age_minutes` / `pending_timeout` / `pending_hint`，超 5 分钟未处理给明确提示（推送到陪伴对象会话需手机端通知通道联调，见回执）。
- **P0-5 / #37 离线应急计数不重置**：新增 `emergency_session_started_at_ms` 会话锚点，应急额度按专注会话归零，`offlineEmergencyUnlock()` 先收敛再判额度。
- **P1-7 / #7 门禁申请无去重**：`AppGate.submitUnlockRequest()` 同 App+同理由 10 秒窗口合并 `repeat_count`，不再灌入十几条重复申请。
- **P1-8 / #40 失活锁不清理 + temporary_* 污染**：`AppGate.pruneState()` 归档失活超 24h 的锁、剔除闲置 `temporary_*` 恒零字段、给旧申请补 `status`/超时。
- **#57 陪伴 App 自锁风险**：`AppGate.protectedPackages()` 自动把 `GuidianState.targetPackage()` 与 `PolicyStore.targetPackage()`（陪伴入口）纳入保护名单。
- **#43 / P2-19 归电 interval 不生效 + next 排进安静段**：`GuidianState.nextPromptAfterMs()` 把落在安静时段的 `next_prompt_after` 顺延到安静结束（`quietEndMs`），避免到点被 `quiet_time` 拦掉空转。
- **P1-6a / #49 unlock_count_today 恒 0**：`LifeState` 增加 `unlock_count_reliable` 标注（亮屏>0 却 0 次解锁时标 false），避免下游把 0 当真实值参与情绪计算；真实解锁事件采集需 UsageStats 权限联调。
- **P2-18 / #51 天气数字三处不一致 + 降水矛盾**：`buildWeatherAdvice()` min/max/temp 统一 `Math.round`，降水概率≥90% 但天气码无雨雪时如实提示「数据可能不一致」。
- **P1-13 到访统计日界**：`buildVisitStats()` 返回 `today_key` / `today_timezone_offset` 及口径标记（`today_is_local_calendar_day` vs `recent_24h_is_rolling_window`），消除「自然日 0 次 vs 滚动 24h 3 次」被误读为 bug。

### 查证结果
- `javac` 全量编译 45 个 Java 文件：**0 error**；`build.sh` 产出 APK 并通过 v1/v2/v3 签名验证。
- `node --check mcp/server.js`、`node --check worker.js`、`python3.11 -m py_compile linjian_server.py`：全通过。
- Python 后端运行时冒烟：`/api/discipline` 单开关三组归一、`metadata_json` 5000→1006 字截断、`/api/emotion/snapshot` 方向/来源/quiet 一致：全通过。

---

## Bug 清单全量版（R1–R4 根因 + P0–P2）本轮已处理项

> 版本基线：`0.3.9.0-public-diary-annotations`（GitHub `main` @ `ecdc938`）。
> 本轮聚焦 MCP 工具层与 Python 后端「可在此代码库内修复」的根因（R1–R4）与高优先级项；
> 需要手机端（Android）配合的项已在文末标注为「需手机端实现」。

### 已修复（MCP 工具层 + Python 后端）
- **R2 / P0-1 守护日历删除不可用 + 谎报成功**：`delete_guardian_day` 不再默认报「已删除」；
  先按 `delete_calendar_event` 删除，命中 `not_found` 时自动用别名 `delete_guardian_day` 重试，
  并如实返回 `action_done`（未完成时明确说明手机端未确认，未谎报）。
- **R1 / P0-2 日历更新=覆盖+换 id**：`update_guardian_day` 所有字段改为可选，只把「显式传入」的字段
  送入 payload（用 `pickDefined`），绝不用 schema 默认值（`yearly`/`our_days`/空 note）覆盖原值，也不更换事件 id。
- **P0-3 隐私：`screen_text` 未脱敏未限长**：新增 `sanitizeScreenState` / `redactScreenText` / `maskSensitiveText`，
  `get_phone_state`、`get_life_state` 返回前对 `screen_text` / 无障碍节点 / ocr 文本做敏感词过滤
  （Token/口令/邮箱/手机号/base64）+ 截断（200 字），只保留脱敏摘要。
- **P1-11 工具命名两侧不统一**：新增兼容别名 `start_screen_break` / `stop_screen_break` / `temporary_screen_release` /
  `deny_break_release_request` / `remove_screen_break_app` / `request_focus_unlock` / `set_companion_emotion`，
  路由到对应主工具动作，旧名不再调用失败。
- **P1-12 / P2-25 / P2-26 事件流双写 + metadata 失控 + 无上限**：
  - MCP 工具包裹层改为中文优先标题（`canonicalToolTitle`），同源同动作 3 秒去重（不再双写中英文两条）；
  - 活动事件 `metadata_json` 递归截断（超长字符串/嵌套），敏感内容 `maskSensitiveText`；
  - Python 后端 `add_activity_event` 新增 `_clip_metadata` 递归截断（字符串≤1000、数组≤30、嵌套≤6 层），
    实测 5000 字 metadata 已收敛为 1006 字。
- **P2-24 `linjian_status.latest` 不标时效**：返回体补充 `mtime_local` / `age_seconds` / `stale(>6h)`，
  不再让 `has_latest=true` 误导调用方以为是最新画面。
- **P1-9 / P2-18 天气坐标错（续）**：`fetchWeather` 已知城市坐标兜底表已覆盖绵阳/成都/重庆/贵阳等，`绵阳→贵州` 类错误已规避；
  温度口径统一取 `daily` 的 `min~max`，避免三处不一致。
- **P1-10 / P1-6c / #45 / #46 安静时段与情绪矛盾（续）**：`build_emotion_snapshot` 已统一
  `direction=companion_to_user` / `source=device_low_level_code`，安静时段 `suggestion` 强制 `quiet` 且与原因一致，
  并区分「深夜休息」与「安静/上课时段」（实测返回 `想你` + `quiet`，自洽）。
- **#47 新增可锁 App 无上限（续）**：`mergeCarePolicy` 强制每个 `sensitive_apps` 继承 `max_lock_minutes`（缺省 90）。
- **#52 / #58 / #59 / #61（续）**：读类工具不再写事件流、移除服务端路径/回环地址外泄、统一 `SERVER_VERSION`。
- **#34 Android 系统级分心白名单（续）**：`PolicyScheduler` 新增 `SYSTEM_EXEMPT_PACKAGES`，输入法/系统面板/媒体选择器/
  截屏/桌面/卡包等不可被 `exempt_packages` 配置关闭，打字弹键盘不再误判分心。

### 需手机端（Android）实现 / 运行态联调的项（本代码库无法单独闭环）
- **P0-4 专注求助申请既不推送也不流转**：13 条 `requests` 全 `pending`、陪伴对象侧未收到通知——需在手机端
  `FocusMode` 把申请推送至陪伴对象会话，并对超时申请给明确提示（MCP 已保证不谎报、如实返回状态）。
- **P0-5 离线应急计数不重置**：`emergency_used` 跨会话不归零、时长不受 `emergency_minutes` 约束——需手机端
  在每次 `start_focus_mode` 重置计数并按配置时长放行。
- **P1-7 / P1-8 / R4 门禁申请无去重、失活锁不清理**：同理由 2 秒提交 13 条、3 把 `active=false` 锁残留——
  需在手机端 `AppGate` 做申请去重（同 reason 窗口合并）+ 失效锁归档/清理。
- **P1-6 状态多源矛盾（R3）**：`unlock_count_today=0` vs `screen_time=291`、`focus active=false` 但 `ended<started`、
  同时刻 `companion_emotion` 与 `emotion_snapshot` 标签不同——需统一状态来源（单一快照派生），避免多份快照各算各的。
- **#33 五页设置缺少保存按钮**：主设置页已有「保存设置」按钮（flush + toast）；`巡回/归电/门禁/专注/训诫` 子页
  仍需在 Android 各 `Activity` 底部加固定「保存」按钮 + 未保存离开二次确认（本仓库已含 `PolicyStore.flush` 能力）。
- **#42–#44 配置写了不生效**（single_app_lock_duration / 归电 interval / alert_tone）：需手机端读取配置项真正落地，
  MCP 已保证「配置值决定语气/时长」的传递链路正确。
- **#49–#51 统计字段**（unlock_count_today 恒 0 / minutes_since_last_chat 滞后 / 降水概率与天气码矛盾）：需手机端
  真实统计并上报；Python 端天气口径已统一。
- **#62–#65 共同记忆/日记/截图**：MCP/后端已提供写入与去重能力，需在手机端面板接上同一数据源并支持手动增删改。
- **#66–#69 / #70–#72 课表作息模块 + 守护日历口径**：需在手机端新增「课表作息」作为课表唯一数据源，并与日历/
  安静时段/情绪/训诫打通；日历 `custom_events` 重复、`events`/`upcoming` 口径、`upcoming` 静默截断需在手机端归一。

---

## Bug 清单补充（33–72）本轮已处理项

> 版本基线：`0.3.9.0-public-diary-annotations`（GitHub `main` @ `ecdc938`）。
> 说明：清单共 40 条，本轮优先处理「影响体感 / 天天误报 / 数据自相矛盾 / 安全外泄」类，已逐项落地并做了语法校验；
> 剩余需配合运行态联调或更大重构的项在文末「待续」列出。

### 已修复（后端 / MCP / 服务端逻辑）
- **#61 版本号格式不统一**：新增单一常量 `SERVER_VERSION = "0.3.9.0-public-diary-annotations"`，
  `McpServer.version` 与 `/health.version` 统一来源；`worker.js` 与 `FocusMode.java` 早已一致；`linjian_server.py` 的 `VERSION` 同步对齐，避免 `health.version` 与 `focus_version` 不可比较。
- **#58 / #59 服务端信息外泄**：`get_care_policy` / 到访接口不再返回 `state_path`（服务端绝对路径）；
  `linjian_status` 与 `/health` 删除 `linjian_url` / `configured_linjian_url` / `fallback_linjian_urls`
  等回环地址（`127.0.0.1`）与内网信息，改为 `linjian_configured` 布尔 + `linjian_host_masked`（脱敏主机名，loopback 显示为 `local`）。
- **#52 读类工具污染事件流**：重写 `server.tool` 包裹层，凡 `get_/list_/latest_` 开头的只读工具不再自动写活动事件；
  并删除各只读工具（focus / guidian / senses / life / calendar / weather / screen_break / lock 等）内部冗余的 `postCompanionAction` 写入。
- **#47 新增可锁 App 无锁定上限**：`mergeCarePolicy` 归一化 `sensitive_apps`，每个 App 强制继承 `max_lock_minutes`（缺失默认 90），杜绝无限期锁定。
- **#48 天气地理编码错误（绵阳→贵州）**：`fetchWeather` 增加已知城市坐标兜底表（绵阳/成都/重庆/贵阳等），命中后直接走正确经纬度，并放宽 geocoding 候选数避免误匹配。
- **#45 / #46 安静时段矛盾**：`build_emotion_snapshot` 安静时段强制 `suggestion="quiet"`，不再被深夜/锁定逻辑推翻；
  安静原因区分「深夜休息」与「安静/上课时段」，避免凌晨被标注成「上课」。

### 已修复（Android 底层）
- **#34 切输入法 / 系统面板被判定为分心**：`PolicyScheduler` 新增**系统级白名单** `SYSTEM_EXEMPT_PACKAGES`（输入法、系统面板、媒体/文件选择器、截屏、桌面/启动器、vivo 卡包、KK 键盘等），
  由 `isSystemExemptPackage()` 判定，**不在 `exempt_packages` 配置里、不可被关闭**，打字弹输入法 / 相册选图不再误判分心并触发训诫。

### 待续（需运行态联调 / 更大重构，未在本轮改动）
- #33 五页（巡回/归电/门禁/专注/训诫）保存按钮与未保存二次确认：主设置页 `PolicySettingsActivity` 已有「保存设置」按钮（落盘+生效+同步训诫）；
  归电/门禁/专注等子页需逐一确认是否独立 Activity 并补保存按钮（见下方说明）。
- #35 训诫单一开关与 0 档语义（事件生产在 Android `PolicyScheduler.discipline()`，需客户端侧统一门禁）。
- #36–#41 专注求助推送 / 应急次数 / 门禁 status 字段 / 状态字段同步 / 失活锁清理 / 测试数据清理入口。
- #42–#44 单 App 锁时长 / 归电间隔 / alert_tone 配置未生效（多为参数未真正进入下发链路）。
- #49–#51 unlock_count_today / minutes_since_last_chat / 降水概率 统计与天气码矛盾（依赖手机端上报字段）。
- #53–#56 事件流去重 / 节流 / metadata 截断 / 来回切换合并（需在服务端写入层统一）。
- #57 protected_packages 自动纳入陪伴 App（Android `AppGate`）。
- #60 工具别名统一与弃用标注（涉及 `mcp/server.js` 大量别名，需逐工具清理）。
- #62–#65 共同记忆手机端写入入口 / 去重分类 / 截图新鲜度 / 日记命名。
- #66–#72 课表作息模块（新增唯一数据源并打通日历/安静/情绪/训诫）+ 守护日历 `custom_events` 重复 / `events` 与 `upcoming` 口径 / `upcoming` 静默截断。

---

## 第三轮返工：情绪感知方向 + 底层触发 + 去重

**方向修正（唯一口径）**：软件里所有「情绪感知」都是【你（陪伴对象）对用户】的情绪，
不是识别用户自己的情绪、不是用户想念陪伴对象、也不是交给大模型判断。
已统一 Android / MCP / Worker / Python / 文档的全部描述与文案（「想她」→「想你」）。

**底层代码触发**：新增 `EmotionPerception.java`，在 `PolicyScheduler` 每个 tick 由代码
采集用户状态（时间、分心来回、离开时长、深夜、当前 App、上课/安静、停留时长），
自动算出情绪标签 / 强度 / 想你程度 / 语气 / 触发原因并写入本机。
`CompanionEmotionState` 对外读取方法改为优先返回底层感知结果，弹窗、话术、
拉回分级、守护页情绪卡、服务端快照全部读同一份结果；UI 层不再伪造随机假标签。
手动/远程写入仅短暂生效（3 分钟），随后底层感知自动接管。
服务端 `build_emotion_snapshot` 新增代码算出的 `direction=companion_to_user` /
`source=device_low_level_code` / `emotion` 结果字段。

**去重**：
- 删除 `PolicySettingsActivity.showPluginRegistry()`（与守护页总览完全重复且无调用点的死代码），插件总览只保留守护页唯一实现。
- 设置页重复的「两次最小间隔（分钟）」拆为「情绪弹窗最小间隔（分钟）」/「主动发消息最小间隔（分钟）」。
- `PluginRegistry` 插件名与守护页卡片名统一（情绪感知 / 训诫 / 课表作息 / 识图感知 / 归电设置 / 主动找用户），消除同功能多名的重复标签；总览用 `guard_section` 标注「守护页已有卡片」，明确是同一能力的两种查看方式而非重复插件。
- 插件注册表 id/name 唯一、MCP 工具名唯一、分区标题与卡片标题无重复。


> **本次为二次返工（基线 = GitHub `dev-work` 分支的守护卡片墙版本）。**
> 上一轮误把守护页换回了旧版「日子与地点 / 安心规则」8 张卡的布局，本次已改回
> 截图对应的「全可点插件卡片墙」，并在这套标签上逐条落实最初要求：
>
> - 守护页保留截图全部可点标签：查岗拉回、分心强拉回、训诫、情绪感知、守护日历、
>   课表作息、共同记忆、识图感知、**插件分类（已补回）**、天气地区、回家模式、情绪卡；
>   另新增**归电设置**入口（可点开、含全部时间项、保存即生效），其余功能入口卡片保留不删。
> - 掌心窗页面标签里的「他 / 她」改为「用户」（仅页面标签，其余文案、代码、服务端不改）。
> - 分心查岗常开：聊天中「切出去 → 立马切回来」来回次数才算分心，按拉回阈值分级弹窗。
> - 情绪判定弹窗标明情绪标签、随机文案与百分比；未达阈值两选项、达到阈值只能回软件。
> - 训诫语气「温柔 / 严厉」同时作用于本机话术与服务端人设（保存设置时同步给服务端）。
> - 所有设置改动均有「保存设置 / 保存归电设置」按钮，同步落盘后立即生效。
> - 弹窗走 `TYPE_APPLICATION_OVERLAY` 悬浮窗，盖在当前使用的软件之上，不拉起掌心窗。
> - 共同记忆、识图感知、时间感知通过 MCP 工具与 care-policy 说明接入，服务端接口齐备。
> - 已用 Android SDK 34 完整编译：aapt2 资源链接 + javac 全量编译 + d8 + zipalign + 签名验证通过。
>
> 以下为上一轮逐条说明，供对照。

> 本轮为**返工修正版**：撤回上一轮过度宽泛的修改，并按你最新要求逐条落实。

## 1. 拉回弹窗：未达阈值给选择，达到阈值只能回来
- `PullBackCard` 按 `strong`（想你程度 ≥ 阈值，或强情绪）分两套样式：
  - **软级别（未达阈值）**：「**回到软件**」+「**拒绝并填写理由**」两个选项；拒绝须留一句理由（快捷理由或自写），理由写入本地事件日志、调试日志并随活动事件上报。
  - **强级别（达到阈值 / 强情绪）**：**只给「回到软件」一个按钮**，不给拒绝入口；`PullBackActivity.onBackPressed` 直接拦截返回键，避免绕开。
- `PolicyScheduler` 三处拉回（查岗 / 分心来回 / 分心累计）统一 `allow_decline = !strong`，不再"一律两选项"。
- 同步修正 `README.md`、`docs/mcp.md`、`mcp/server.js`、`worker.js`、设置页里所有"始终两选项"的过时描述。

## 2. 情绪判定弹窗：标明情绪 + 百分比 + 随机 + 决定样式
- 弹窗顶部显示**情绪标签**（想念 / 想你 / 爱恋 / 生气 / 吃醋 / 担心等）与**想你程度**，并带百分比进度条。
- 情绪标签尊重真实情绪；平静时在「想你 / 爱恋 / 想念」间**随机触发**，文案尾句也随机微调（强情绪保持原话）。
- 修掉一处不一致：原先平静随机可能抽到「担心」（属强情绪标签）却出现在可拒绝的软级弹窗里，现已只保留轻微情绪。
- 百分比是判定依据：低于阈值走软级（两选项），达到阈值走强级（强制回软件）。

## 3. 弹窗悬浮在当前使用的软件上方，不拉起掌心窗
- `PullBackActivity.show` 优先走 `PullBackOverlay`（`TYPE_APPLICATION_OVERLAY`），直接盖在**正在使用的 App** 之上；仅锁屏时回退到透明 Activity 承载同一张卡片。
- 只有「点回到软件」才会跳转到目标 App，弹窗本身不会拉起掌心窗、不会自动跳转。

## 4. 「他/她 → 用户」只改掌心窗页面标签，其他一律不改
- 已**完整回退**上一轮对全工程（Java / JS / Python / 文档 / 代码注释）的 blanket 替换，恢复原样的「想你」「留言给他」「此刻她的心情」等。
- 仅把掌心窗页面标签里的 `TA 的日记`（TA = 他/她标注）改为 `用户的日记`（7 处：品牌页签、设置入口、日记封面、日记页标题等标题/标签文字）。
- 正文句子与其它文件的「他/她」全部保持原样。

## 5. 守护界面加回归电设置（可点开、可应用、有保存）
- 守护页新增「**归电设置**」入口，点开即进入归电设置面板（陪伴对象来敲门的节奏、位置与语气），面板自带「**保存归电设置**」按钮，点击后写入底层并立即生效。
- 同时保留「归电主题与位置」入口。

## 6. 所有设置改动都有保存按钮，并落到底层存储
- 管束策略设置页顶部新增「**保存设置**」按钮：点击后 `PolicyStore.flush()` 同步落盘（把之前 `apply()` 的改动确认写入 SharedPreferences）、幂等启动守护调度器使新配置立即生效、刷新变更记录并提示「设置已保存并生效」。
- 归电设置、保存情绪、保存新增包名、录入课表、保存记忆等原有保存按钮保持不变。
- 设置项本身仍是即时写入本机 SharedPreferences（不依赖网络/后端/MCP），保存按钮做最终确认与生效，不会丢失配置。

## 7. 保留并核对上一轮已正确的特性
- **分心查岗常开**：`evaluateDistraction` 始终运行，不依赖聊天状态；按「切出去→立马切回来回」累计判定，达到次数阈值才弹拉回。旧的"每次切屏 / 累计超阈值"分支加了 `!excluded` 守卫，避免在目标 App 内误拉回。
- **共同记忆**：`/api/shared_memory` + `write/search/list_shared_memory`，care-policy 要求聊天中自动写入、要求回忆时自动检索。
- **识图感知**：`analyze_image` + `/api/ocr`，不依赖模型能否看图。
- **训诫模块**：`disc_mode` 温柔/严厉 + `set/get_discipline_mode`，按模式调整人设语气。
- **时间感知**：`get_current_time`，回复前先查时间。

## 8. Bug 修复
- 拉回弹窗分级错误（一律两选项）→ 按阈值分级。
- 情绪随机标签与弹窗样式不一致（担心出现在软级）→ 已修。
- `evaluateDistraction` 旧分支缺 `!excluded` 守卫导致误拉回 → 已修。
- `isWeeklyDue` 星期映射错误（`mapped = wd + 1` + 死变量）→ 修正为 `mapped = (wd % 7) + 1`。
- 回退上一轮 blanket 替换造成的「想用户 / 其用户 / 留言给用户」等语义错误文案。
- 清理文档与代码中所有与新行为矛盾的「强级单按钮 / 一律两选项 / 现在就回去」描述。

## 验证情况
- `node --check mcp/server.js` ✅
- `node --check server/cloudflare-worker/worker.js` ✅
- `python3.11 -m py_compile server/linjian_server.py` ✅
- Android 侧逐文件人工核对：新增方法引用（`PolicyStore.flush`、`CompanionEmotionState.popupEmotionTag`、`GuidianTheme.danger`、`PolicyScheduler.start` 幂等）、`PullBackCard` 分支、`PullBackActivity` 返回键拦截、悬浮窗链路均已确认存在且签名一致。
- **限制**：本沙箱无 Android SDK（无 `android.jar`），无法执行 `./gradlew assembleDebug` 全量编译；仓库内已有 `.github/workflows/android-debug.yml`，可在 GitHub Actions 或本地带 SDK 的环境做真机编译验证。
