# 掌心窗 修复需求单 v2026-11-14-01 交付回执

- **交付对象**：WorkBuddy
- **范围**：`server` + `android`。`ui` 全站美化规范（THEME / NAV / PAGE-01~16 / COMPONENT / MOTION / ASSET）经范围对齐后作为独立阶段延后，本轮不交付。
- **状态**：BE-01 ~ BE-14 全部实现、双端核验通过，已推送 `G-boy-wed/1` 的 `main`，签名 APK、Release 资产与 `update.json` 哈希同步更新。
- **工程实况**：需求单中以 `.kt` 标注的文件在实际工程中均为 `.java`（57 个 Java、0 个 Kotlin）。已按真实类名映射实现，例如 `CommandPoll.kt → CompanionService.java`、`MediaSessionBridge.kt → MediaState.java`、`DisciplineStore.kt → DisciplineController.java / PolicyStore.java`。

## 一、14 项后端修复对照

| ID | 症状 | 修复要点 | 主要文件 |
|---|---|---|---|
| BE-01 | `set_alarm` 命令停在 dispatched，闹钟不响 | poll 白名单纳入 `set_alarm`；3 次无回执写 `failed` + `failed_reason` 并记 `care_history`；Android 去掉 `!explicit` 一律拒绝，改用 `setExactAndAllowWhileIdle(RTC_WAKEUP)`，S+ 校验 `SCHEDULE_EXACT_ALARM`；新增 `AlarmReceiver` 与 Manifest 权限 | `linjian_server.py`、`CompanionService.java`、`AlarmReceiver.java`、`AndroidManifest.xml` |
| BE-02 | emotion 落盘冻结，`intensity=0` 但 `raw_intensity=55`，`updated_at` 停 9 天 | `snapshot()` 从同一 `SharedPreferences` 键同源读取 `intensity / miss_percent / raw_*`；`updated_at` 写当前时间；超过 10 分钟标记 `stale=true` + `stale_ms` | `CompanionEmotionState.java` |
| BE-03 | `force_back` 恒为 soft，strong 从未触发 | `miss_percent >= threshold` 判 strong，strong 只渲染「回到软件」；soft 拒绝理由 `trim().length() >= 2` 才启用提交按钮，点击时二次校验 | `PullBackCard.java` |
| BE-04 | 配置 30min 却在 10min 触发查岗 | 改用 `SystemClock.elapsedRealtime()` 单调时钟 + `lastCheckInElapsed`；触发前二次校验 `elapsed - last >= interval`，触发后重置 | `PolicyScheduler.java` |
| BE-05 | `quiet_hours=null` 与 `quiet_start/quiet_end` 双字段并存 | 服务端 `DEFAULT_CARE_POLICY` 改为双标量字段 + `quiet_window()`；`/api/tour/schedule` 迁移旧 `quiet_hours` 并 `pop`；Android 序列化/读取只认双字段 | `linjian_server.py`、`PolicySync.java`、`PolicySettingsActivity.java` |
| BE-06 | focus mode `enabled=true, active=false` 且 until 过期未清 | `FocusMode.normalizeState()` 在 `!active` 时清 `until_ms / until_local` 并置 `enabled=false`；新增 `reconcile()`，App 启动调用 | `FocusMode.java`、`MainActivity.java` |
| BE-07 | `unlock_count_today` 恒为 0 | 广播 `ACTION_USER_PRESENT` 按自然日计数；`LifeState.readUsage()` 兜底改为 `KEYGUARD_HIDDEN → SCREEN_INTERACTIVE` 事件配对去重 | `UnlockTracker.java`、`LifeState.java` |
| BE-08 | `list_screen_break_apps` 返回 `[]`，但 `app_gate` 有数据 | MCP `SPECIAL` 补两个工具 handler，共用 `_read_gate_apps()`：主源 `app_gate.available_apps` → 回退 `gate_apps` → 实时查询 → `source="empty"`；Android `AppGate.config()` 上报 `available_apps`；命令侧映射 `list_screen_break_apps → list_lockable_apps` | `mcp_engine.py`、`AppGate.java`、`CompanionService.java` |
| BE-09 | 1 分钟内十余次 `app_open` 抖动，分心虚增误触训诫 | `ActivityEventStore` 加 800ms debounce（`SETTLE_MS=800`）+ 同包取消 pending；`PolicyScheduler.isSystemExemptPackage()` 提为 public 共享，补 `com.vivo.upslide / com.vivo.interactionpool / com.kelivo / im.weshine.keyboard` 等豁免；`evaluateDistraction()` 提前 `return` 系统层（修复「算出 excluded 却未使用」根因） | `ActivityEventStore.java`、`PolicyScheduler.java` |
| BE-10 | `get_phone_state` 的 `battery/network=null`，而 usage snapshot 有 88% | 新增 `read_device_telemetry()`，`build_usage_snapshot()` 与 MCP `_read_state` 同源取 `battery_percent / network_type / charging`；无值返回 `null`（`JSONObject.NULL`），不返回 `0 / -1 / ""` | `linjian_server.py`、`mcp_engine.py`、`LifeState.java` |
| BE-11 | 通知使用权已授权但 `available=false`、`has_session=false`，读不到进度 | `queryActiveSessions()` 返回 `SessionProbe{best,count,ok}`；`collect()` 增 `has_session / listener_connected / active_session_count / can_pause`；`available` 以监听已连接 + 会话探测为准；新增 `current_position / total_duration / progress_percent`（无值 `null`）；短视频用读屏 caption 兜底标题；权限页加「重新连接」按钮 + `requestRebind()` | `MediaState.java`、`MediaNotificationService.java`、`MainActivity.java`、`activity_main.xml` |
| BE-12 | `last_events=[]`，enter/leave/timeout 全缺失 | 服务端新增事件别名规范化、`record_media_session_event()`、共享 `build_media_context()`；事件写 `media_events` 与 `activity_events(source=media)`；以 `last_heartbeat` 判定超时（默认 120s，可配）；Android `MediaSyncService` 生成 enter/leave/timeout 并上报 `session_timeout_seconds` | `linjian_server.py`、`mcp_engine.py`、`MediaSyncService.java`、`AppPrefs.java`、`ActivityEventStore.java` |
| BE-13 | 无会话时 whisper / pause / switch_screen 静默入队 | 服务端与 Android 双层返回 `NO_SESSION`（带 hint）；pause 先 `canPauseActiveMedia()` 否则 `PAUSE_UNSUPPORTED`；switch_screen 成功返回 `target_page="companion_chat"` | `mcp_engine.py`、`CompanionService.java` |
| BE-14 | `text_is_fallback=true`、`custom_text=""`，自定义文案丢失 | 新增 `apply_discipline_update()` / `discipline_view()` / `resolve_discipline_text()`，`_load_discipline()` 默认带 `custom_text`；REST GET/POST 与 MCP `discipline_state/update` 全走同一对函数；Android 支持 `text / custom_text / discipline_text` 别名，空串显式清空并回到兜底 | `linjian_server.py`、`mcp_engine.py`、`DisciplineController.java`、`PolicyStore.java` |

## 二、构建健壮性改动（本轮附带）

- `android/build.sh`：编译源码时排除 `*/io/flutter/*`。Flutter 工具链会在 `app/src/main/java` 生成 `io/flutter/plugins/GeneratedPluginRegistrant.java`，它依赖本工程不存在的 `io.flutter.*`，被 `find -name "*.java"` 无差别纳入会导致 `javac` 报 `package does not exist`。
- `.gitignore`：补充忽略 `android/app/src/main/java/io/flutter/`，与既有「Flutter 本地生成，不应进仓库」规则对齐。

## 三、核验结果

| 项目 | 命令 / 手段 | 结果 |
|---|---|---|
| Python 语法 | `python3 -m py_compile server/linjian_server.py server/mcp_engine.py` | 通过 |
| MCP Schema | `python3 server/verify_mcp_schema.py` | `MISSING_ITEMS_COUNT = 0`、`PERFECT: ZERO_ERRORS_ALL_RESOLVED` |
| 服务端加固回归 | `python3 server/verify_server_hard` | `PASS=18 FAIL=0`、`PERFECT: SERVER_HARDENING_PASSED` |
| 服务端 E2E | 真实 HTTP + `/mcp` 端到端脚本（BE-10/11/12/13/14） | `ALL E2E PASS`（电量两接口一致、媒体事件可见、`source=media` 事件落库、无会话 `NO_SESSION`、自定义文案重进仍在） |
| Java 静态检查 | 全量 `.java` 括号配平、`activity_main.xml` well-formed | 配平差值 0、XML 合法 |
| Android 真实构建 | `ANDROID_HOME=/root/android-sdk bash android/build.sh` | 58 个 Java 编译通过 → DEX → 打包 → 对齐 → 签名 → 验签全通过 |
| 签名校验 | `apksigner verify --verbose --print-certs` | v1/v2/v3 = true；证书 SHA-256 `aea75c9b2b5f5c42d56b72d4a69a79a38e1c57f27db021017be8656bc8f002fb` |
| APK 内容 | `unzip -p … classes.dex \| strings` | 含 `AlarmReceiver`、`MediaState$SessionProbe`、`media_session_timeout_seconds`，确认是本轮代码产物 |

## 四、APK 与下载

| 项 | 值 |
|---|---|
| 文件 | `android/Zhangxinchuang-public-v0.3.9.0.apk` |
| 包名 / 版本 | `dev.linjian.peek` / `0.3.9.0`（versionCode `30900`），minSdk 23、targetSdk 34 |
| SHA-256 | `e81765383c60d5d8ed7378dce96bd0ae681414aae6ab7ca5ae473d30434f5548` |
| Release 下载 | https://github.com/G-boy-wed/1/releases/download/v0.3.9.0/Zhangxinchuang-public-v0.3.9.0.apk |
| 仓库直链 | https://raw.githubusercontent.com/G-boy-wed/1/main/android/Zhangxinchuang-public-v0.3.9.0.apk |
| App 内更新 | `update.json` 的 `apk_url` 指向上述 Release，`sha256` 已同步为本产物值 |

CI 工作流 `.github/workflows/android-debug.yml` 在 push 到 `android/**` 时自动构建并上传 `zhangxinchuang-public-debug-apk` artifact。

## 五、备注

- 全站 UI 美化规范（16 页视觉重构）按范围对齐结论延后，作为独立阶段交付。
- 历史 `deliver/` 下的其他 `BUG_RECEIPT_*.md`、`RETEST_*.md` 为更早迭代的交付物，本轮未改动、未纳入本次提交。
- 公开版签名固定使用 `android/signing/zhangxinchuang-public-release.p12`，与私人版证书分离，升级覆盖不会失败。
