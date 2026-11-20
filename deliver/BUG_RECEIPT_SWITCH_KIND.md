# 守护页开关 / 分心查岗误计 / 弹窗类型标识 修复回执（Commit I = `01a3d6b`）

**基线**：`3ddfb26`（Commit H 之后，已确认推送成功）→ **`01a3d6b`**，16 文件 +403/−47，已推送 GitHub `main`（fast-forward：`3ddfb26..01a3d6b`）。

## 逐条回执

### 1. 守护内所有标签新增开启/关闭，且可实际操作 ✅ 已改
守护页（`MainActivity.buildGuardMagazine`）每张功能卡片右侧新增真实生效的 `Switch`：
写入的是各能力**底层存储**（与设置页、MCP `set_policy` 同一份数据），切换即时生效并 Toast 确认；
点开关不会误触卡片跳转，点卡片其余区域仍进对应设置页。

| 卡片 | 开关写入 | 关闭时的实际效果 |
|---|---|---|
| 查岗拉回 | `PolicyStore.setCheckinEnabled` | 不再随机查岗 |
| 分心强拉回 | `PolicyStore.setDistractionEnabled` | 不计分心、不拉回 |
| 训诫 | `PolicyStore.setDisciplineEnabled` | 不产生任何训诫事件 |
| 情绪感知 | `PolicyStore.setEmotionPopupEnabled` | 想你达标不再自动弹窗 |
| 主动找用户 | `PolicyStore.setInitiativeEnabled` | 不再主动发「主动聊天」弹窗 |
| 应用门禁 | `AppGate.setEnabled`（新增） | 门禁判定暂停，锁着的 App 不再拦 |
| 主动提醒 | `PolicyStore.setAllPermEnabled`（新增，9 项权限键逐项真实写入） | 屏幕/睡前/宵禁/白名单/喝水/久坐/每周/到家/生理期提醒全部停发 |
| 专注模式 | `FocusMode.toggleFromGuard`（新增） | 解除全机锁定并置 disabled；打开按默认 30 分钟开始托管 |
| 守护日历 | `CalendarState.setEnabled`（新增，`AppPrefs.KEY_GUARD_CALENDAR_ENABLED`） | 横幅与临近提醒停发（`collect()` 的 nearest/active_banners 为空、summary 标注已关闭），数据保留 |
| 课表作息 | `ClassScheduleState.setEnabled`（新增） | 不再判定上课、上下课提醒停发、上课免打扰随之失效 |
| 共同记忆 | `SharedMemoryState.setEnabled`（新增） | 只读保护：本机与 MCP 的新增/编辑/删除一律拒绝，已有记忆不删、可查看 |
| 识图感知 | `PolicyStore.setOcrEnabled` | 本地与 MCP 都不可读屏 |
| 天气地区 | `AppPrefs.KEY_RULE_WEATHER`（新增键） | 不再发天气提醒 |
| 回家模式 | `AppPrefs.KEY_HOME_MODE_ENABLED` | 回家弹窗停发 |
| 归电设置 | `GuidianState.KEY_ENABLED`（已有键，首次接到开关上） | 归电不再敲门 |
| 插件分类 | 纯总览页，无运行时行为，不加开关（如实说明，不凑数） | — |

以上 7 个新开关（应用门禁/课表/日历/共同记忆/天气/回家/归电）已同步纳入 `PolicyCommand`
MCP 策略目录（`app_gate_enabled` / `class_schedule_enabled` / `guard_calendar_enabled` /
`shared_memory_enabled` / `weather_reminder_enabled` / `home_mode_enabled` / `guidian_enabled`），
MCP `get_policy`/`set_policy` 与手机端开关读写同一份底层存储。
- **验收**：守护页逐个关掉开关 → 对应能力立即停（如关「课表作息」后上课期间查岗不再被免打扰压制、上下课提醒停发）；重开即恢复；MCP `set_policy {"class_schedule_enabled":false}` 与卡片开关状态一致。

### 2. 分心查岗误计修复：点进聊天没开始聊天、切出不再计分心 ✅ 已改
- **根因**：`PolicyScheduler.evaluateDistraction` 把「处于聊天 App」一律视为聊天中，
  只要切出去→切回来就累计来回；用户只是点进聊天看一眼（没开始聊天）就切出，照样累计分心并弹拉回。
- **改动**：新增「真正开始聊天」判定（聊天会话跟踪）：
  - 在聊天界面（掌心窗陪伴页或目标 App）**连续停留 ≥20 秒**才算「开始聊天」，此后切出才计一次分心/来回；
  - 只是点进聊天看一眼（<20 秒）就切出：**不计分心、清空待配对状态与来回累计**；
  - 计过的「切出」切回聊天后视作同一聊天会话延续（切回即刻算聊天中，来回判定不变）；
  - 「每次切屏都拉回 / 累计超阈值」旧逻辑同样加「已开始聊天」前置，未聊天路过不再误弹。
- 设置页「分心查岗」说明文案同步更新。
- **验收**：桌面点进聊天 5 秒→切到微信→切回聊天：调试日志无「检测到切屏分心」、来回不累计；
  在聊天里真实停留 30 秒后切出→切回 2 次：弹「分心查岗」拉回（与此前行为一致）。

### 3. 每个弹窗标明是什么弹窗 ✅ 已改
- 分级拉回卡片（`PullBackCard`）顶部新增**类型徽标**（accent 色圆角 pill），按 `kind_label` 显示，
  未提供时按 `kind`/`from` 推断，兜底「守护提醒」：
  - 查岗拉回 → 「查岗拉回」（本机查岗 + 服务端 `force_back_to_chat`）
  - 分心查岗 → 「分心查岗」（切出切回 / 每次切屏 / 累计超阈值，三种来源同标）
  - 情绪感知 → 「情绪感知」（想你达标自动弹窗）
  - 主动聊天 → 「主动聊天」（主动找用户，见第 4 条）
- 居中弹窗（`AlertCenterActivity.bind` + `AlertCenterOverlay.show`）卡片最上方新增同样式徽标：
  服务端/MCP 下发 `show_center_alert` 时可用 `kind`/`alert_kind` 标明类型，默认「陪伴消息」。
- 提醒类通知：`PolicyScheduler.notify()` 标题统一加「提醒 · 」前缀（标题本身已含「提醒」不重复加）。
- **验收**：任意通道弹窗左上角可见类型徽标；MCP 下发 `show_center_alert {kind:"主动聊天"}` → 徽标显示「主动聊天」。

### 4. 弹窗必须在正在使用的 APP 上显示，不要跳转掌心窗后显示 ✅ 已确认 + 修掉最后的跳转点
- **现状确认**：拉回/居中弹窗两条通道本就是 `TYPE_APPLICATION_OVERLAY` 悬浮窗优先，
  盖在当前前台 App 之上，不切前台；仅锁屏（系统限制）走透明 Activity，仍在当前屏上。
- **修掉最后的跳转点**：主动找用户原先**静默直接打开目标 App**（等于强制跳走）。
  现改为「弹窗化」：在当前 App 之上弹「主动聊天」软弹窗（可拒绝、按钮「去找 TA / 晚点再说」），
  用户点「去找 TA」才跳目标 App，不再不打招呼就抢前台。
- **验收**：在第三方 App 内触发主动找用户 → 当前 App 不切换，弹窗浮在上层并标「主动聊天」；
  点「去找 TA」才跳转；拒绝则留痕不跳转。

## 验证记录
- Android 完整 `build.sh` 通过，APK `Zhangxinchuang-public-v0.3.9.0.apk`（SHA-256 `88dbb137…988a`）v1/v2/v3 签名 Verified。
- patch：`deliver/patches/CommitI_SWITCH_KIND_3ddfb26_to_01a3d6b.patch`（917 行）在 `3ddfb26` 干净基线可 `git apply --check`。
- 推送凭据：`3ddfb26..01a3d6b main -> main`，远端 HEAD = `01a3d6b`。

## 失败点 / 如实说明
- 无编译失败遗留。两点设计取舍如实说明：
  1. 「插件分类」是纯总览入口，没有可关停的运行时行为，未配开关（不造假开关）；
  2. 「开始聊天」用 20 秒停留作为可操作判定（本机无聊天正文权限，不读内容），阈值常量
     `MIN_CHAT_STAY_MS` 集中在 `PolicyScheduler`，后续可调。
