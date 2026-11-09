# 掌心窗 v0.4.1 五模块合并修复 · 交付回执

基线：`a19c3ce`（v0.4.0 单进程 Python 重构）。本轮 13 个源码文件改动（12 改 + 1 新增），提交后推送 GitHub。

> 说明：需求示例为 Kotlin，本项目手机端是 **Java/Android**，且构建链仅有 `javac` 无 `kotlinc`，故全部按 **Java 等价实现**（类名 / 方法 / 行为一一对应），未引入 Kotlin 源文件。

---

## 一、逐条回执

### Bug#1 训诫模块：语气不显示 / 文案未下发 ✅

**根因**：`DisciplineController.config()` 把 `PolicyStore.disciplineText()` 原样吐出，用户未自定义时就是空串；`trigger()` 的旧 `defaultText()` 不分 gentle / strict 用同一句话；弹窗标题固定「训诫」，前端拿不到任何语气标识。

**改动**（`DisciplineController.java` / `AlertWindowManager.java`）：
- 新增 `resolveDisciplineText(ctx, customReason)`：有自定义文案用它（reason 追加一行），否则按 mode 兜底 —— `strict` → 「顾钰，立刻把手机放下。不是在跟你商量，现在回我身边来。」；`gentle` → 「宝宝乖，先不看了好不好？眼睛该酸了，过来抱一下。」；reason 非空时以 `【原因】` 开头。称呼取 `AppPrefs.userName()`，空则回退「顾钰」。
- 新增 `toneBadge(ctx)` → `【严厉管束】` / `【温柔提醒】`，弹窗标题改为 `badge + " 掌心窗管束"`，`kindLabel` 带「严厉 / 温柔」。
- `config()` 的 `text` 改为兜底后的有效文案（**永不再为空**），另加 `custom_text`、`tone_badge`、`tone_label`、`text_is_fallback` 供前端渲染语气标签。
- **strict 档位额外联动**：原仅 `level>=3` 才锁前台，现改为 `strictMode || forceStrong`，即严厉语气必触发「弹窗 + 震动 + 门禁把当前前台非白名单 App 切入休息页」。
- `AlertWindowManager.showDiscipline()` 同步走同一套文案 / 标签，避免两处弹窗长得不一样。

**验证**：APK 编译通过；服务端 `/api/life_state` 模拟上报验证 `discipline_state.text` 非空、`tone_badge` = `【严厉管束】` 正确透传。

### Bug#2 天气：缺「使用当前定位」+ 中文地名误匹配 ✅

**根因**：地点编辑页只有手输框，天气完全走 Open-Meteo 名称地理编码；「北川羌族自治县」这类中文长地名会被模糊匹配到贵州遵义（27.5385, 106.439），而本机定位其实已有精准坐标（31.596, 104.458）。

**改动**（`WeatherLive.java` / `WeatherState.java` / `NowState.java` / `MainActivity.java` / `activity_main.xml`）：
- **坐标优先**：`WeatherLive.fetchByCoords(lat, lon, ...)` 直接按经纬度请求 forecast，**零名称地理编码**，从根上杜绝省市漂移；`refreshAsync` 新增 6 参重载，坐标非零时优先走它。
- **「使用当前定位」按钮**：布局新增 `useCurrentLocationButton`；`NowState.lastKnownLocation(ctx)` 取本机最后已知坐标；点击后 `WeatherState.saveLocationCoords()` 落盘、强制缓存失效、立即按坐标刷新天气，并回显解析结果。
- **坐标持久化**：`WeatherState` 地点格式扩为 7 段 `名称|城市|区县|备注|当前|lat|lon`，`readLocations` 兼容旧 3/4/5 段；`currentLocation` / `locationsJson` / `current_weather_location` 带出坐标；手改城市自动清除旧坐标；`collect()` 中设备坐标优先于 geocoding 坐标回填。
- **中文地名容错**：`normalizeCityName()` 逐级剥离「省/市/区/县/自治州/自治县/羌族自治县」等后缀取核心城市名，且兼容需求给的正则截断语义；检索顺序改为「核心城市名 → 区县+城市 → 全名」。
- **已知易错地名坐标兜底**：内置北川 / 北川县 / 北川羌族自治县 / 绵阳 → 四川绵阳真实坐标，命中即跳过 geocoding。
- **二次确认**：天气结果新增 `geo_display`（省+市 / 当前定位 lat,lon）、`geo_source`、`geo_query_used`，前端 `weatherGeoConfirmText` 展示解析结果供用户确认。

**验证**（等价逻辑实测）：`北川羌族自治县 → 检索词「北川」+ 命中四川绵阳 31.596,104.458`；`北川县 → 北川`；`绵阳市 → 绵阳`；`长安区 → 长安`。均不再漂移到外省。APK 编译通过。

### Bug#3 解锁次数统计不可靠 ✅

**根因**：`unlock_count_today` 完全依赖 `UsageStatsManager` 的 `KEYGUARD_HIDDEN / SCREEN_INTERACTIVE` 事件推断，国产 ROM 常不给这些事件 → 常年 0、`unlock_count_reliable=false`。

**改动**（新增 `UnlockTracker.java` / `CompanionService.java` / `LifeState.java`）：
- 新增 `UnlockTracker`：在常驻前台服务 `CompanionService.onStartCommand` 动态注册 `ACTION_USER_PRESENT` + `ACTION_SCREEN_ON`（`onDestroy` 注销）；`USER_PRESENT` 按自然日累加解锁次数并标 `reliable=true`，`SCREEN_ON` 只记亮屏不计数（避免双计）；数据 `commit()` 落 SharedPreferences，进程被杀重启可恢复。
- 前台服务本身已 `START_STICKY` + `startForeground`，保证广播长期在线（需求第 4 点）。
- `LifeState.collect()` 优先采用广播统计覆盖 UsageStats 推断，新增 `unlock_count_source` 标明来源。

**验证**：APK 编译通过；服务端 `/api/life_state` 验证 `unlock_count_today=7 / reliable=true` 正常透传。真机广播触发需用户实测。

### Bug#4 媒体监听权限引导缺失 ✅

**根因**：`media_state` 未授权时只返回「通知使用权未开启」，虽在调试抽屉有个按钮，但守护设置页没有醒目提示与直达入口。

**改动**（`MainActivity.java` / `MediaState.java`）：
- 守护页顶部新增醒目警示块（仅未授权时可见）：「⚠ 媒体监听未开启 · 伴看 / 正在播放 / 媒体状态都不可用 → 点此一键授权」，点击直达 `ACTION_NOTIFICATION_LISTENER_SETTINGS`；`updateUI()` 实时刷新可见性。
- 已有 `notificationListenerButton` 文本改为「⚠ 媒体监听未开启 · 点此一键授权」。
- `MediaState.collect()` 未授权时新增 `needs_permission` / `grant_action` / `action_hint`，前端可直接渲染提示与入口。

**验证**：APK 编译通过。

### Bug#5 门禁 / 求助重复提交与历史堆积 ✅

**根因**：拦截页狂点产生几毫秒内多条重复申请；`expired` 记录只标记不删除，状态 JSON 无上限膨胀。

**改动**：
- **手机端 3 秒防抖**：`AppGate.submitUnlockRequest()` 与 `FocusMode.createRequest()` 各加 `lastSubmitTimeMs` 全局 3 秒防抖（保留原有同 App/同理由 10 秒合并）。
- **手机端记录清理**：`AppGate.pruneState()` 与 `FocusMode.normalizeState()` 在标记 expired 后，删除 24 小时以上的 expired 记录，并把 `requests` 数组上限收敛到 20 条。
- **服务端定时清理**（`server/linjian_server.py`）：新增 `cleanup_expired_requests()` —— 超时 pending 标记 expired、24h 以上 expired 删除、列表上限 50；`state_cleanup_loop()` 每 5 分钟常驻执行（独立于查岗调度开关）；`POST /api/appgate/unlock_request` 写入前清理 + **服务端 3 秒防抖兜底**；`GET /api/appgate/unlock_requests` 与 `McpBackend.unlock_requests()` 读前清理；新增 `unlock_requests_lock` 保护并发。

**验证**（实跑）：连发 3 次同 App 申请 → 第 1 次入库，第 2/3 次 `deduplicated:true`，`count` 保持 1；注入 25h 前 expired 后 GET → 已被清理；单元自测「24h 清理 + 50 条上限 + ISO 时间回退」全通过。

---

## 二、真编译凭据

```
=== Compiling Java ===   （唯一 warning 为 -source 11 系统模块路径提示，非错误）
=== Creating DEX === / === Building APK ===
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
APK: /workspace/android/Zhangxinchuang-public-v0.3.9.0.apk  (1.1M)
BUILD_EXIT=0
```

- `python3 -m py_compile server/linjian_server.py server/mcp_engine.py` → OK
- `mcp_tools.json` 仍为 123 个工具，MCP `tools/list` 实跑返回 123

## 三、交付物

| 类型 | 文件 |
|---|---|
| 新增 | `android/app/src/main/java/dev/linjian/peek/UnlockTracker.java` |
| 修改 | `DisciplineController.java`、`AlertWindowManager.java`、`WeatherLive.java`、`WeatherState.java`、`NowState.java`、`CompanionService.java`、`LifeState.java`、`MainActivity.java`、`MediaState.java`、`AppGate.java`、`FocusMode.java`、`activity_main.xml`、`server/linjian_server.py` |
| 文档 | `deliver/RECEIPT_V0.4.1_FIVE_FIXES.md` |
| 签名包 | `android/Zhangxinchuang-public-v0.3.9.0.apk`（仓库 `.gitignore` 忽略 `*.apk`，不入库） |

## 四、推送凭据

沿用用户提供的 GitHub PAT，以 `git -c "url.https://x-access-token:<PAT>@github.com/.insteadOf=..."` **一次性注入**，不写入 `git config`、不落盘、提交内不含任何 token。

## 五、实测汇总（本环境可验证部分）

| 项 | 结果 |
|---|---|
| Python 语法 / MCP 引擎 | ✅ |
| 过期清理单元自测（24h清理 / 50上限 / ISO回退） | ✅ `['c','d']` / `50` / `1001ms` |
| 服务端 3 秒防抖（连发3次） | ✅ 仅 1 条入库 |
| GET 时清理 25h 前 expired | ✅ 已清理 |
| health / MCP tools/list(123) / 记忆三接口 / life_state 聚合 / CORS 204 | ✅ 全部回归通过 |
| 状态透传（discipline text / tone_badge / 解锁数 / 天气坐标） | ✅ |
| Android 全量编译签名 | ✅ v1/v2/v3 Verified |
| 中文地名归一化等价逻辑 | ✅ 北川→四川绵阳真实坐标 |

## 六、限制与后续

1. **真机验收**：解锁广播（`ACTION_USER_PRESENT`）、「使用当前定位」的真实定位权限与坐标、媒体通知使用权授权跳转、训诫 strict 联动门禁的实机观感，本环境无真机/模拟器，需你实测。
2. **Kotlin**：本轮仍为 Java 等价实现（构建链无 `kotlinc`）。如需原生 `.kt`，需另引 Kotlin 编译器改造 `build.sh`。
3. **天气兜底表**：目前内置北川 / 绵阳等已知易错地名；其他中文长地名若仍出现 geocoding 错配，优先用「使用当前定位」写坐标（坐标路径零 geocoding），或告知我补进兜底表。
4. APK 因 `.gitignore` 未入库，最新签名包在本地 `/workspace/android/Zhangxinchuang-public-v0.3.9.0.apk`。
