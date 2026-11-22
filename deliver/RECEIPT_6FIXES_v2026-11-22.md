# 掌心窗 6 项修复回执 v2026-11-22

## 修复清单（全部经逐条确认后实施）

### 1. 闹钟改为拉起系统时钟 App
- `CompanionService.setAlarm()` 从「本 App AlarmReceiver + setExactAndAllowWhileIdle 自己响铃」改为发 `AlarmClock.ACTION_SET_ALARM` 拉起本机时钟 App 新建闹钟页（`EXTRA_SKIP_UI=false`，由用户在系统时钟保存）。
- 掌心窗不再自己响铃；闹钟出现在系统闹钟列表。
- 保留 `set_alarm` 命令 action。

### 2. 训诫改为 MCP 动态话术池（核心：语气本身区分，非结尾加词）
- 服务端 `LocalState` 新增 `discipline_phrases.json`：`gentle`/`strict` 两个话术池，整句话语气区分。
- 新增 4 个 MCP 工具：`add_discipline_phrase` / `remove_discipline_phrase` / `list_discipline_phrases` / `clear_discipline_phrases`。
- 写入时下发 `sync_discipline_phrases` 到手机端镜像（离线可用）。
- `DisciplineController.resolveDisciplineText()` 改为从对应池随机取整句；**档位≥4 强制用严厉池**（解决"选最高档仍最温柔"）；池空才用中性兜底，不硬编码温柔/严厉话术。
- **移除自定义训诫文案**：删除 `KEY_DISCIPLINE_TEXT`、`disciplineText()/setDisciplineText()`、`set_discipline_mode` 的 `text` 参数。

### 3. 天气 4 处修复（最正确区县天气+提醒）
- 弹窗链路 `AlertWindowManager.showWeatherAlert` 读取已保存 GPS 坐标，走 `WeatherLive.ensureFresh(...,lat,lon)` 坐标级精确查询（与卡片链路一致），解决"保存了定位弹窗仍按地名查"的主因。
- 删除编造的 `precipitation_probability=80`，带伞只依据真实天气现象（雨/雪）。
- `admin2` 统一为区县名；`cardText` 优先用完整 `geo_display`（省·市·区县）。
- 缓存 key 纳入经纬度（0.01° 粒度），定位移动后及时刷新。

### 4. 弹窗不再拉起掌心窗
- `AndroidManifest.xml` 给 `AlertCenterActivity` / `ReminderActivity` / `PullBackActivity` / `GuidianActivity` 加独立 `taskAffinity=":popup"`，`FLAG_ACTIVITY_NEW_TASK` 不再把 MainActivity 主 task 带到前台。

### 5. 中心弹窗 8-60 分钟随机 + 互斥
- `PolicyStore` 默认间隔恢复 8-60 分钟（`DEF_RANDOM_LOWER=8`/`UPPER=60`，迁移 v3 覆盖历史 35/5）。
- 取消想你倍率对间隔下限的突破。
- `PopupMutex` 新增全局闸门 `canTriggerCenterAlert/markCenterAlertTriggered`，查岗与情绪弹窗共用，任意弹窗后下一次最早 8-60 分钟后，保证不同时弹多个。

### 6. 一起看彻底删除
- Android：删 `WatchPartyActivity.java`/`WatchPartyState.java`/`FreeMusic.java`（一起听死类）；删 MainActivity 开关+入口；删 CompanionService 命令分支/状态镜像/getJson/handleWatchPartyLocal；删 Manifest 注册；清理注释。
- 媒体三兄弟 `MediaState`/`MediaSyncService`/`MediaNotificationService` 保留（独立功能）。
- MCP：删 6 个 watch_party 工具 + SPECIAL 映射 + handler + LocalState 存储；删白名单 5 项、`/api/watch_party` 端点、状态合并。
- focus 残留：删 `linjian_server.py` 的 `focus_tools` 标记 + dart 侧 8 个 focus 工具白名单。
- 文档：删除 5 份专门一起看文档；24 份综合文档只删一起看段落；README/CHANGES/docs 清理专注历史段落；两个 update.json 清 changelog 相关行。
- "今日专注"卡片文案改为"屏幕使用"（功能保留）。

## 验证结果

| 项目 | 结果 |
|---|---|
| Java 编译 / APK 签名 | 通过，v1/v2/v3 验签，公钥摘要 `8f89e9f8…` 一致 |
| dex 残留类 | 无 WatchParty/FreeMusic/FocusLock/PalmDesk |
| dex 修复特征 | `android.intent.action.SET_ALARM`、`sync_discipline_phrases`、"屏幕使用" 均在 |
| MCP 静态校验 | PERFECT: ZERO_ERRORS_ALL_RESOLVED，121 工具无幽灵/无死键 |
| 话术池端到端 | 严厉/温柔话术分别写入对应池，下发手机成功 |
| 一起看 | 代码/工具/白名单/文档全库无残留 |
| 最新 APK SHA-256 | `459514c8915e1cc155dcf6e84993734ccc21fc63146f611436d86bc8559a899c` |
