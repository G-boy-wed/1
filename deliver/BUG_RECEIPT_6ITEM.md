# 六项 Bug 清单 · 逐条回执（Commit E = `2e651a3`）

**基线**：`main` @ `75dd4de`（九项清单 Commit D 已完成）
**本版**：`main` @ `2e651a3dde76f355f9b8478d9d779b74fc38de98`（快进提交，未 force push）
**改动**：5 个文件，+168 / −8（`git show 2e651a3 --stat`）

---

## 1. MCP 后端可用性（linjian_status 报缺 LINJIAN_URL/LINJIAN_TOKEN）— ✅ 已改
- **根因**：`mcp/server.js` 只从环境变量读配置（第 10/64 行）；Render 面板、本地 npx、部分 MCP 客户端没法方便地设置环境变量 → 配置缺失时 `linjianFetch` 直接 throw（794-795 行），所有后端读取工具全挂。工具本身能如实报错，但缺一个**不需要改部署面板的兜底配置途径**。
- **改动**：
  - `mcp/server.js` 头部新增零依赖 `loadEnvFileFallback()`：启动时若 `LINJIAN_URL`/`LINJIAN_TOKEN`/`LINJIAN_DEFAULT_DEVICE` 环境变量缺失，自动从 `mcp/.env`（或 `LINJIAN_ENV_FILE` 指定路径、或 cwd 下 `.env`）加载缺的键；**已有环境变量优先，文件只补缺、不覆盖**。支持 `#` 注释与引号包裹。
  - `linjian_status` 返回体新增 `env_file_fallback_used` / `env_file_fallback_keys` / **`fix_hint`**（缺配置时给出 4 条可执行修复步骤，含 .env 文件用法），不再只甩一句 Missing env。
- **commit**：`2e651a3`（文件：`mcp/server.js`）
- **验证**：Node 独立运行时测试——缺 3 键时从 .env 全部补齐（引号剥离正确）、二次验证已有环境变量不被文件覆盖，PASS。
- **验收步骤**：①在 MCP 服务部署处，不改环境变量，放一个 `mcp/.env`（内容 `LINJIAN_URL=...`、`LINJIAN_TOKEN=...`），重启 MCP；②调 `linjian_status`：`config_errors` 应为空、`env_file_fallback_used=true`；③拔掉 .env 再重启：`fix_hint` 应给出具体修复步骤。
- **如实说明**：如果 URL/Token 本身填错（而不是缺失），`linjian_status` 会显示 `health.ok=false`——那是连接问题不是代码问题，`fix_hint` 会引导先确认地址与令牌一致。

## 2. 共同窗语读取为空/仅默认值（get_window_whisper）— ✅ 已改
- **根因**：`mcp/server.js` `get_window_whisper` 直接 `textResult({ok:true, whisper: data?.whisper || {}})`——**后端 401/网络失败时把失败掩蔽成"空窗语"且报 ok:true**，陪伴对象无从区分"真没窗语"和"读取失败"。服务端本身有默认文案（"把今天，轻轻收进窗里。"）并持久化到 companion.json，"仅有默认值"实际是**从未有人写过窗语**，但旧返回体不告诉你这一点。
- **改动**：①`data.ok === false` 时如实返回 `ok:false + comapion_state_fetch_failed + 修复引导`；②成功时新增 `is_default` 字段：内容还是初始默认文案时为 true，并附 note 说明"可用 set_window_whisper 写入新内容"。
- **commit**：`2e651a3`
- **验收步骤**：①正常部署下调 `get_window_whisper`：返回默认文案且 `is_default=true`；②用 `set_window_whisper` 写入新内容后再读：`is_default=false`、内容/作者/版本正确；③故意填错 Token 调用：应返回 `ok:false` 而非空窗语。

## 3. 活动事件记录缺失（get_companion_actions）— ✅ 已改
- **根因**（服务端真实缺陷）：`server/linjian_server.py` `/api/companion/state` 处理器在**最近 500 条**（`ACTIVITY_EVENT_LIMIT`）统一事件里过滤 `companion/assistant` 来源与 `command/notification/weather/calendar/status_check` 类型——被无障碍/前台事件刷满时，陪伴动作记录会被整体挤出窗口，**即使发生过动作也返回空**。另外 MCP 端读取失败同样被掩蔽成空列表。
- **改动**：①服务端扫描窗口扩到 `COMPANION_ACTION_SCAN_LIMIT=2000`（新常量，`linjian_server.py:33`）；②MCP 端 `get_companion_actions` 如实上报失败（ok:false + 修复引导），空列表时返回 `empty_reason` 说明空的原因与如何生成一条验证记录。
- **commit**：`2e651a3`（文件：`server/linjian_server.py`、`mcp/server.js`）
- **验证**：Python 运行时实测——造 900 条噪音事件 + 3 条 companion 事件，新窗口下 3 条全部找到（旧 500 窗口会被挤掉），PASS。
- **验收步骤**：①让 AI 执行一次 `set_alarm`/查天气；②调 `get_companion_actions`：应出现该记录；③在大量 App 切换后回读：记录不再丢失。
- **如实说明**：若服务端**从未发生过任何陪伴动作**（刚部署/刚清库），返回空是事实而非 Bug——现在会带 `empty_reason` 解释。

## 4. 巡回查岗调度器配置丢失/异常值（get_tour_schedule）— ✅ 已改
- **根因**（手机端真实缺陷）：`PolicySettingsActivity.syncToServer()`（旧 684-689 行）**硬编码** `force_back_interval_minutes=0`、`alert_tone="gentle"`、`exempt_packages=[]`——每次在手机端保存设置，都会用这三个假值覆盖服务端已保存的巡回配置 → `get_tour_schedule` 读到 0/默认值。而服务端 `/api/tour/schedule` 本身是"只更新 body 里出现的键"的合并语义，缺省键保留原值。
- **改动**：`syncToServer()` 移除三个硬编码字段，只上送本机真实存在的配置（enabled、check_in_interval_minutes、cooldown_minutes、quiet_hours、night_hours、force_back_miss_threshold）；服务端缺省键自动保留。
- **commit**：`2e651a3`（文件：`PolicySettingsActivity.java`）
- **验收步骤**：①在 MCP 侧用 `set_tour_schedule`（或 POST /api/tour/schedule）设置 `force_back_interval_minutes=30`、`alert_tone="strict"`；②手机端打开查岗设置页改任意参数并点「保存设置」；③`get_tour_schedule` 回读：30/strict 不再被抹成 0/gentle，同时手机改的间隔/冷却已同步。

## 5. 情感感知 Tick 滞后（get_emotion_snapshot）— ✅ 已改（上一版 Commit D，本轮核实）
- **状态**：已在 **`75dd4de`** 修复并随上一轮交付。`CompanionService.java` 新增 `registerNetworkRecovery()`（ConnectivityManager 回调：`onLost` 记标记、`onAvailable` 触发 `onNetworkReconnected()` → 立即 `EmotionPerception.perceive()` 重算 + `uploadStateThrottled(..., force=true)` 强制上报，`onDestroy` 反注册）。
- **commit**：`75dd4de`（本轮无新改动，仅核实代码在位）
- **验收步骤**：断网 ≥1 分钟 → 恢复；数秒内 `get_emotion_snapshot` 返回新状态（非下一 Tick），DebugState 出现「网络恢复：立即重算情绪并强制上报」。

## 6. 设置页保存行为（P0）— ✅ 已改（6 个缺陷抽屉全补齐）
- **审计结论**（全量审计 16 处设置界面）：`PolicySettingsActivity`、归电设置、天气、日历、包名、主题、专注规则等 **10 处原本合规**（显式保存按钮/点选即存 + apply/commit 落盘）；**6 处不合规**，全部在 `MainActivity`：
  | 抽屉 | 原缺陷 |
  |---|---|
  | 主动提醒规则 | 无保存按钮、**无任何监听器**，仅靠"刷新生活总览/启停服务"等无关动作顺带落盘，重启即丢 |
  | 周期提醒 | 同上 |
  | 应用门禁总开关 | 开关无监听、无按钮，仅被顺带写入 |
  | 权限与运行（横幅开关） | 同上 |
  | 连接设置 | 只有 TextWatcher/失焦自动保存，无显式保存按钮 |
  | 回家模式 | 同上 |
  | （轻微）高级调试遗留城市/天气字段 | 无按钮，仅顺带保存 |
- **改动**（`MainActivity.java` + `activity_main.xml`，沿用项目既有「行级即时写 + 页级显式保存按钮」范式）：
  1. 新增 6 个显式「保存」按钮（XML）：保存提醒规则 / 保存周期提醒 / 保存回家模式 / 保存连接设置 / 保存权限设置 / 保存调试字段；
  2. 新增 4 个专用保存方法 `saveReminderSettingsOnly / saveCycleSettingsOnly / savePermissionSettingsOnly / saveDebugFieldsOnly`（`commit()` 同步落盘），不再依赖无关动作；
  3. 新增 `bindInstantPersistToggles()`：8 个勾选开关（主动提醒/低电量/屏幕/喝水/休息/周期/横幅/门禁总开关）**点选即 apply 落盘**，与保存按钮双保险；
  4. `bindExplicitSaveButtons()` 统一绑定，保存后 Toast 明示「重启后仍生效」。
- **commit**：`2e651a3`
- **验收步骤**：①进每个抽屉改值 → 点显式保存按钮 → 杀掉 App 重启 → 配置仍在；②不点保存直接改开关（勾选类）→ 重启 → 开关状态仍在（点选即存）；③连上服务器改 Token → 点「保存连接设置」→ 重启后连接正常。
- **未动项（如实）**：`PolicySettingsActivity` 训诫分组「说明」一行回调为空实现——该字段是纯说明性文本，不承载配置；如需可编辑保存请明确，我再加。

---

## 汇总

| 编号 | 状态 | 主要改动文件 |
|---|---|---|
| 1 MCP 可用性 | ✅ 已改 | `mcp/server.js`（.env 兜底 + fix_hint） |
| 2 窗语读取 | ✅ 已改 | `mcp/server.js`（失败如实上报 + is_default） |
| 3 行动记录缺失 | ✅ 已改 | `server/linjian_server.py`（扫描 2000）、`mcp/server.js`（empty_reason） |
| 4 查岗配置丢失 | ✅ 已改 | `PolicySettingsActivity.java`（去硬编码覆盖） |
| 5 情绪 Tick 滞后 | ✅ 已改（`75dd4de`） | `CompanionService.java`（本轮核实） |
| 6 设置页保存（P0） | ✅ 已改 | `MainActivity.java`、`activity_main.xml`（6 抽屉按钮 + 8 开关即时落盘） |

**6/6 已改，0 未改，0 假修。** 全部改动：Android 真编译通过（APK v1/v2/v3 Verified）、Python/Node 语法 + 运行时测试 PASS（.env 兜底、扫描窗口、开关即时写）。
