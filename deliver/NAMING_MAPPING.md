# 命名 / 口径统一对照表

> 涉及命名或口径统一的改动，列出「旧 → 新」，便于逐项核对下游是否还有旧口径残留（你的要求「修完一条不得引入新的同类问题」）。

## 1. 工具 / 动作命名（#60、P1-11）

| 旧名 / 别名 | 主名（本轮起统一） | 处理 | 状态 |
|---|---|---|---|
| `start_screen_break` / `screen_break` | `screen_break_app` | `CompanionService.java:314` 别名归一 | 已统一（可用） |
| `stop_screen_break` | `end_screen_break` | `CompanionService.java:315` | 已统一 |
| `temporary_screen_release` | `temporary_screen_break_release` | `CompanionService.java:316` | 已统一 |
| `deny_break_release_request` | `deny_screen_break_release_request` | `CompanionService.java:318` | 已统一 |
| `get_screen_break_state` | `get_lock_state`（内部） | `CompanionService.java:319` | 已统一 |
| `add_screen_break_app` 返回 `added_locked_app` | 应统一 `added_screen_break_app` | `AppGate.java:145` 仍写「添加门禁 App」 | **未改**：需弃用日期+全链路改名回归，见回执 #60 |
| 「门禁 / locked_app」 | 「屏幕休息 / screen_break」 | MCP 工具名已用「屏幕休息」，底层键/日志仍「门禁」 | **部分改**：文案混用残留，见 #60 |
| health 侧 `add/delete/upsert_calendar_event` | MCP 侧 `add/update/delete_guardian_day` | 已加 7 个兼容别名（`mcp/server.js:2374`） | 部分改：旧名未标弃用时间 |

## 2. 日历事件字段口径（#70/#71/#72，Commit C，`CalendarState.java:58-88`）

| 字段 | 旧口径 | 新口径 |
|---|---|---|
| `custom_events` | 与 `events` 完全相同（同一数组两遍） | **仅**自定义事件（带稳定 id，供编辑/删除） |
| `events` | 仅自定义（同 custom_events） | **全部**日子定义（自定义 + 内置节日） |
| `event_count` | = custom_events.length()（只算自定义） | = events.length()（含内置） |
| `custom_event_count` | = custom_events.length()（与上重复） | 仅自定义条数 |
| `upcoming` | 静默截断 12 条，无提示 | 保留 12 条预览 + 新增 `upcoming_total` / `upcoming_has_more` |
| （新增）`event_fields_note` | 无 | 明确三者差异的文字说明 |
| `nearest` / `active_banners` / `summary` | 含已关横幅的课表 | 只收 `banner_enabled=true` 的日子（P2-15） |

## 3. 横幅文案模板（P2-14/#69，`CalendarState.java` bannerText）

| 事件类型(group) | 旧文案 | 新文案 |
|---|---|---|
| study（课表/考试） | 「还有 N 天是…，别忘了这份小仪式。」 | 「还有 N 天是…，提前把课本和作业备好，别迟到。」 |
| festival（节日） | 同上（小仪式） | 「…，一起过个节，我记着呢。」 |
| project | 同上（小仪式） | 「…，节点快到了，提前收个尾。」 |
| user / companion | 同上（小仪式） | 「…，这个日子只属于你，我陪你过。」 |
| our_days（纪念日） | 「…别忘了这份小仪式。」 | 保留（本就是纪念日语义） |
| `banner_enabled=false` | 仍生成 banner_text | `banner_text=""` 不生成 |

## 4. 情绪方向 / 触发口径（前轮统一，本次核对无残留）

| 旧 | 新 |
|---|---|
| 识别「用户」的情绪 | 「你（陪伴对象）对用户」的情绪 |
| 交给模型判断 | `direction=companion_to_user` + `source=device_low_level_code`，底层代码触发 |
| 「想她」 | 「想你」 |

## 5. 版本号（#61）

| 位置 | 旧 | 新 |
|---|---|---|
| `linjian_status.health.version` | `0.3.9.0` | `0.3.9.0-public-diary-annotations` |
| `get_focus_status.focus_version` | `0.3.9.0-public-diary-annotations` | 不变 |
| `mcp/server.js` | 硬编码多处 | 统一常量 `SERVER_VERSION`（`:123`） |
| `server/linjian_server.py` `VERSION` | `0.3.9.0` | `0.3.9.0-public-diary-annotations` |

## 6. 日记本命名（#65）

| 位置 | 旧 | 新 |
|---|---|---|
| `create_diary_book` 描述 | 「创建一本『TA 的日记』」 | 「名称由 name 决定，不预设书名」 |
| `list_diary_books` 描述 | 「查看本机『TA 的日记』本列表」 | 「书名以本机实际存储为准」 |
| 本机实际 | 「祁晏的日记本」 | 不变（未改数据，避免误删） |

## 7. 训诫开关（#35）

| 旧 | 新 |
|---|---|
| `enabled` 与 `level` 两个独立状态源，0 档仍输出事件 | 单一开关：`level<=0 ⇔ enabled=false`；`PolicyScheduler:595` level≤0 直接 return，不产出「训诫(0档)」 |

## 8. 安静时段原因措辞（#45/#46，部分改）

| 场景 | 旧 | 新 |
|---|---|---|
| 04:01（深夜，不在上课时段） | 「安静/上课时段，先安静陪着你，不打扰」 | 区分「深夜休息」/「安静·上课」两类原因 |
| quiet 时 suggestion | 可能 =pullback 却写不打扰 | 强制 `quiet`，reason 与之一致 |

> 注：四套时段（care_policy 08:40–12:00 / tour 23:30–08:00 / night 23:00–06:00 / guidian 01:00–10:00）**尚未合并为一份模型**，见回执 #45 的产品决策阻塞点。

## 9. 隐私字段（P0-3）

| 字段 | 旧 | 新 |
|---|---|---|
| `screen_text`/无障碍节点/ocr | 回传全文（含 WorkBuddy 页面、PAT 字样） | 敏感词过滤（token/口令/邮箱/手机号/base64 掩码）+ 200 字截断 + `privacy_note` |
| `state_path` | `/data/data/.../care_state.json` | 移除（#58） |
| `linjian_url` / `configured_linjian_url` | `http://127.0.0.1:8513` | `linjian_configured` 布尔 + `linjian_host_masked`（#59） |

## 10. 事件流字段（R4）

| 旧 | 新 |
|---|---|
| 每次 `get_*` 都写 `status_check` | 只读工具不写事件流（#52） |
| 中英双标题（如「list diary books」+ 中文） | `canonicalToolTitle` 中文优先 + 3s 同源去重（#12/#53） |
| `metadata_json.result` 塞完整响应体、截断成非法 JSON | `truncateActivityMetadata` + `_clip_metadata` 递归截断、合法 JSON（#55/#25，实测 5000→1006） |
| `latest` 只有 `has_latest:true` | 增 `mtime_local`/`age_seconds`/`stale`（#64/#24） |
