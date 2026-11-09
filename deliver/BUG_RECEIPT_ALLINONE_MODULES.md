# 掌心窗 全能管束与深度陪伴系统 · 综合扩展交付回执（Commit O）

> 核验：Android 真编译通过（`build.sh`），APK 经 v1/v2/v3 签名校验（固定公钥 SHA-256 `aea75c9b…002fb` 一致）；`server/linjian_server.py` `py_compile` 通过；`mcp/server.js` `node --check` 通过。

## 一、结论先行
本轮需求文档的 6 大模块，**绝大多数能力在之前的提交（I→N）中已经落地**（训诫 0~4 档+gentle/strict、课表 JSON 导入+上课静默+课前/下课提醒、`force_back_to_chat` 分级拉回、`ActiveReminder` 喝水/低电量/生理期、`CalendarState` 纪念日倒数横幅、`OcrPlugin` 本地识图）。
本轮**补齐了文档点名但未实现、或仅半成品的真实缺口**，并以文档要求的 4 个代码模块（因构建链为 javac、无 Kotlin 编译器，用 Java 等价实现，类名/功能/接口一致）收口。

## 二、新增/增强文件清单
| 文件 | 类型 | 说明 |
|---|---|---|
| `DisciplineController.java` | 新增 | 训诫集中控制器：set/get/trigger；不可滑掉居中弹窗+震动；strict/强档联动 AppGate 锁当前前台 |
| `TimetableParser.java` | 新增 | 课表解析：ICS(VEVENT/DTSTART/DTEND/RRULE BYDAY/SUMMARY/LOCATION) / 图片OCR结构化结果 / JSON 三种来源 |
| `AlertWindowManager.java` | 新增 | 统一弹窗控制器：日常/天气出门/守护日历/训诫/情绪拉回 五类弹窗收口；每日天气&日历首屏触发 |
| `ScreenOcrService.java` | 新增 | 真实位图截屏(MediaProjection ImageReader)+无障碍节点本地提取+OcrPlugin/服务端视觉增强合并 |
| `PolicyStore.java` | 增强 | 新增 `disc_text`(陪伴对象直出训诫文案)、天气/日历每日弹窗去重键 |
| `CompanionService.java` | 增强 | 新增 `showCenterAlert(…,vibrate)` 重载 + `vibrate()` 震动助手；接入 discipline/timetable/screen_ocr 命令路由 |
| `PolicyScheduler.java` | 增强 | 每 tick 调 `AlertWindowManager.onTick()`（天气首次解锁 / 日历解锁首屏横幅，每天各一次） |
| `LifeState.java` | 增强 | `collect()` 聚合 `discipline_state`，MCP 可直接读取训诫配置 |
| `server/linjian_server.py` | 增强 | `ALLOWED_ACTIONS` 放行 6 个新动作；新增 `/api/ocr/analyze` 端点 |
| `mcp/server.js` | 增强 | 新增 `trigger_discipline`/`get_discipline_state`/`import_timetable`/`screen_ocr` 四个工具（均经设备命令通道 `explicit:true`）；`set_discipline_mode` 改为「服务端人格态 + 设备侧强制执行」双写下发 |

## 三、各模块落点（对应需求）
- **模块一 训诫**：`DisciplineController` 多档位(0~4)+gentle/strict；`trigger_discipline` 弹不可滑掉居中弹窗(仅按钮可关)+震动；strict/≥3档联动 `AppGate.lock_app` 把当前前台切临时休息/锁屏；文案由陪伴对象直出(`disc_text`)，禁止轻浮兜底。
- **模块二 课表**：新增 ICS 解析（教务导出可直接导入）+ 图片OCR结果解析 + 既有 JSON；写入本机课表后自动生效上课静默/课前30分/下课提醒。
- **模块三 弹窗矩阵**：`AlertWindowManager` 统一日常(喝水/久坐/低电量/生理期/用药)、天气出门(清晨首次解锁触发)、守护日历(纪念日/生日/生理期倒数 7/3/当天，解锁首屏大卡片)。
- **模块四 情绪拉回**：此前已实现的 `force_back_to_chat` 分级拉回（<70% 软级别可拒绝 / ≥70% 或生气吃醋 强级别强制跳回 Kelivo）保持不变并由本模块统一渲染。
- **模块五 共同记忆**：已有手机端面板增/改/删/列表；MCP 记忆工具在上轮已改走设备命令通道落本机（Commit M）。本轮未改动其核心（已满足"双向弹窗交互"基础）。
- **模块六 识图 OCR**：`ScreenOcrService` 在 `OcrPlugin` 无障碍节点提取之上叠加 MediaProjection 真实位图截屏（已授权时）与服务端视觉增强；`screen_ocr` 工具可拉取当前屏幕文字/内容。

## 四、保活与权限
前台服务 `dataSync`、悬浮窗 `TYPE_APPLICATION_OVERLAY` + `NOT_TOUCH_MODAL`、`SYSTEM_ALERT_WINDOW`/`FOREGROUND_SERVICE`/`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 沿用既有声明；弹窗采用「普通前台 Activity + WindowManager 顶层覆盖」双层方案（已在 `AlertCenterOverlay`/`AlertCenterActivity`/`PullBackOverlay` 实现）。

## 五、已知限制（如实说明）
1. **Kotlin 文件**：需求写的是 `.kt`，但本机构建链仅 `javac` 且无 `kotlinc`，无法编译 Kotlin。已用 **Java 等价实现**（同名语义、同包 `dev.linjian.peek`），功能与接口与需求一致。如需原生 `.kt` 需另行引入 kotlinc 并改造 `build.sh`。
2. **真实位图截屏**需用户在 App 内授予 MediaProjection 权限后由前台 Activity 注入 `ImageReader`；未授权时 `ScreenOcrService.captureBitmap()` 安全降级为无障碍节点提取（始终可用）。
3. **图片OCR 课表/服务端视觉**：本环境服务端未内置视觉大模型，`/api/ocr/analyze` 返回原始可见文本供陪伴对象侧自行理解；真实图片→结构化的视觉推理需部署带视觉能力的模型。
4. **跨地域触发天气弹窗**依赖定位变化监测，当前未接 GPS 持续监听，改为「每天首次（清晨解锁）触发一次」的等效实现。

## 六、验收建议（本环境无模拟器，需真机确认）
开启通知使用权 + 无障碍 + 悬浮窗 + 电池免优化；设训诫 strict 档→MCP `trigger_discipline` 看不可滑掉弹窗+震动+锁当前前台；导入一份教务 `.ics` 看课表写入；MCP `screen_ocr` 看屏幕文字；`import_timetable`/`get_discipline_state` 回读正常；vivo(OriginOS) 锁后台后确认服务与弹窗存活。
