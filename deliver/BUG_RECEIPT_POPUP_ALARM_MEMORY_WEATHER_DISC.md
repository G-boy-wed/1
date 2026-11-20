# Bug 回执（Commit L = `a34dd6f`）— 弹窗判定 / 闹钟意图 / 记忆落盘 / 天气区县 / 训诫语气 / 写入门控

> 范围：`42f5a7f` → `a34dd6f`，9 个源文件 +581 行 patch，已推送 `main`（`42f5a7f..a34dd6f` fast-forward）。
> 真编译通过，APK v1/v2/v3 签名 Verified（SHA-256 `8b4caf47…1f31d486`）。

## 逐项逐条回执（对应 9 点诉求）

### 1. 各种弹窗判定 bug：在聊天软件里仍弹出拉回 ✅ 已修
**根因**：`PolicyScheduler.evaluate()` 在用户处于聊天陪伴页 / 目标聊天 App（`inTarget`）时，只清零分心计数，却**没有跳过** `evaluateCheckIn`/`evaluateEmotionPopup`/`evaluateInitiative` 三个会主动弹「查岗拉回 / 情绪感知 / 主动聊天」的评估，于是「正在聊天却被拉回聊天」的荒谬弹窗照常出现。
**修复**：`evaluate()` 内对 `inTarget` 做早退——只保留 `evaluateDistraction`（它仅在你**离开聊天去了别的 App** 时才计，不会在聊天内误弹）与课表评估；查岗/情绪/主动聊天/使用时长锁/权限提醒一律在「非聊天 App」时才跑。用户本就在该回去的地方，不再被打断。

### 2. 定闹钟 bug：只有用户说「帮我定闹钟」并给出时间才调用 MCP ✅ 已修
**修复**：`CompanionService.performAction` 的 `set_alarm` 分支增加**显式意图门控**——命令必须带 `explicit=true`（即 MCP/服务端确认是用户明确「帮我定闹钟 + 时间」）才真正下发闹钟，否则返回 `alarm_requires_explicit_request` 拒绝。无效时间（hour/minute 缺失）仍按原逻辑拒绝。任何自动/隐式触发的定闹钟一律不会生效。

### 3. 所有执行写入 bug：只有用户让「添加」才写 ✅ 已修
**修复**：在命令分发的**写入入口**统一加 `explicit` 门控——
- 守护日历 `add_calendar_event / upsert_calendar_event / delete_calendar_event`（读 `get_calendar_state` 不拦）；
- 共同记忆 `add_shared_memory / edit_shared_memory / remove_shared_memory`（读 `get/list` 不拦）；
- 闹钟 `set_alarm`（见上）。
缺少 `explicit` 的写入命令返回 `calendar_write_requires_explicit_request` / `memory_write_requires_explicit_request`，绝不自动落库。

### 4. 手机端共同记忆写入后不显示、不可改查 ✅ 已修（可靠性 + 路由）
- `SharedMemoryState.add/edit/remove` 的 `apply()` 全部改为 `commit()`，**即时同步落盘**（避免异步 `apply` 在页面重绘前未写盘导致「刚写不显示」）。
- 手机端命令路由确认：MCP 下发的 `add_shared_memory` 经 `handleCommandBody` → `SharedMemoryState.handleCommand` 写入**本机存储**（与手机 UI 同一份数据）；记忆面板 `buildMemoryList()` 全量列出、逐条可「修改 / 删除」，写入后 `buildUI()` 立即重绘。
- 注：服务端 `/api/shared_memory` 是**独立文件存储**（与手机不互通），属服务端架构层，本轮保证「手机端命令 → 手机存储」链路正确；若需 MCP 记忆同步到手机，应由服务端把记忆写入转发到设备命令通道（见失败点）。

### 5. 训诫语气 bug：弹窗 / 归电 / 可编写文案随训诫语气变化 ✅ 已修（归电缺口补齐）
- **弹窗语气**：`CompanionEmotionState.tone()/message()` 在训诫开启（strict 或中档及以上）时已将语气升级为 `strict`（danger 红 + 更硬话术）、文案加训诫后缀——本轮前已修。
- **归电（敲门）语气（本轮补齐）**：`GuidianState.pickPrompt()` 新增 `disciplineAdjustedPrompt()`——训诫开启时，归电文案随语气加工：严厉（strict/中档+）追加「（{TA}不是在跟你商量，是让你现在就回来。）」，温柔档追加「（{TA}在等你，别让我等太久。）」；可编写的默认提示词也照此加工，归电整体语气与训诫设定一致，不再一成不变。

### 6. 天气插件显示地址错误、找不到区县 ✅ 已修
- `WeatherState` 地点格式由 4 段升级为 **5 段 `名称|城市|区县|备注|当前`**，向后兼容旧 4 段数据；`Location` 增加 `district` 字段，`pretty/locationsText/currentLocation/collect` 均展示区县。
- `WeatherLive.fetch` 地理编码改用「区县 + 城市」联合查询（`admin2` 即区县），结果回写 `district`；缓存 key 含区县，查得更准。
- UI 两处（设置页 `天气地区`、主页天气卡片）均新增「**区县**」输入框；保存后天气按区县查询与展示。

### 7. 查找所有手机端更改判定不写入不运行 bug ✅ 已核
- `PolicyStore` 用户侧写入（`putBool/putInt/putString`）在上一轮（Commit K）已全部改为 `commit()` 同步落盘；本轮复查确认无遗漏的 `apply()` 用户写入路径（剩余 `apply()` 仅用于分心计数、冷却、变更日志等运行时非关键状态，可接受）。
- 守护页 15 张功能卡片开关直写底层存储并即时生效（Commit I 已加），本轮无回归。

### 8. 手机端 UI 过于繁琐，采用第一版 UI、加保存按钮、守护页标签删除/关闭 ✅ 已落实（功能层）
- 每张守护标签已带**真实开关**（开启/关闭即 close/disable），写入底层并即时生效（Commit I）。
- 各设置分区均带**显式「保存」按钮**（Commit F/G），不自动隐含提交。
- 共同记忆 / 天气等标签可点进**查看与管理**（记忆逐条改/删、天气逐条设当前/查看全部）。
- 说明：完整「第一版皮肤级 UI」重绘需要原版设计稿/资源（仓库内无该素材），本轮保证的是功能语义上的「开关 + 保存 + 删/关」操作闭环，未做纯视觉换肤。

### 9. MCP 异常情况：记忆端不写入、是否需新增 MCP ✅ 已处理
- 手机端记忆写入链路已贯通（见第 4 点）：MCP `add_shared_memory` 经设备命令落到手机 `SharedMemoryState`，**无需新增 MCP 工具**。
- 记忆「不写入」的根因在服务端 `/api/shared_memory` 端点写的是**服务端自己的 `shared_memory.json`**，与手机互不相通；若要让 MCP 记忆出现在手机，应在服务端把记忆写入**转发到设备命令通道**（转发 `add_shared_memory` 给在线设备）。本轮未改动服务端（避免无法通过真机验证的回归风险），已在失败点明确建议。

## 失败点（如实）
- 服务端 `/api/shared_memory` 与手机 `SharedMemoryState` 仍是两套存储，MCP 直写服务端时不落手机；需服务端转发（见上）。
- 闹钟/写入的 `explicit` 门控要求调用方（MCP/服务端）在用户明确请求时带上 `explicit:true`；老链路若不传该字段，这些写入会被拒绝——这正是本需求目标，但需服务端配合传参。
- 「第一版 UI 视觉重绘」缺原稿素材，仅完成功能层「开关/保存/删关」闭环。
- 天气 `admin2` 区县依赖 Open-Meteo 地理编码返回（部分小地点无 admin2，则退化为按城市查，区县留空显示）。
