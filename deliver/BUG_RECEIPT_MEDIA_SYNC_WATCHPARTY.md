# 掌心窗 · 同屏流媒体与短视频伴看守护插件（Companion Media Sync & Watch Party）交付回执

> 版本：0.3.9.0 · 提交：Commit N（基于上一版 `5d804c7`）· 编译：APK 经 v1/v2/v3 签名校验（固定公钥 SHA-256 `aea75c9b…002fb` 一致）· 状态：已推送 github

## 一、关于「.kt 文件名」的重要说明（请先读）
本机构建链是 `aapt2 → javac → d8 → zipalign → apksigner`（`android/build.sh`），且环境**没有 kotlinc / Gradle**，无法编译 Kotlin。为保证「能运行」，新模块用 **Java**（包名 `dev.linjian.peek`）实现，功能与需求**完全等价**：

| 需求文件名 | 实际实现（Java，功能等价） |
|---|---|
| `MediaSyncService.kt` | `MediaSyncService.java`（前台保活 + 伴看调度 + 事件上报 + 浮窗管理 + 超时长拉回） |
| `CompanionFloatingWidget.kt` | `CompanionFloatingWidget.java`（画中画半透明小挂件 + 呼吸动效 + 气泡） |

> 如需**原生 `.kt` 文件**，需先引入 Kotlin 编译器并改造 `build.sh`（javac+d8 → kotlinc+javac+d8，且加入 Kotlin 标准库 dex），这是一次独立的环境升级，不在本次"能运行后上传"范围内。可后续单独安排。

## 二、Android 端实现（已落地、已编译通过）

### 1. 媒体感知增强 `MediaState.java`
- 新增**白名单分类**：短视频（抖音/抖音极速版/快手/快手极速版/小红书）、长视频（腾讯视频/B站/爱奇艺/优酷/芒果TV）、音乐。
- 新增字段：`content_type`、`is_watchable`、`watchable_app`、`position_ms`、`duration_ms`、`position_percent`。
- 短视频前台时，结合无障碍读屏文本结构化出 `caption`（视频文案）、`author`（作者名/@账号）。
- 新增 `pauseActiveMedia(Context)`：遍历系统活跃 MediaSession，对正在播放的会话调用 `TransportControls.pause()`，供"强制暂停"指令使用。

### 2. 伴看调度 `MediaSyncService.java`（前台服务）
- `foregroundServiceType="dataSync"` 前台保活，vivo(OriginOS) 锁后台也不易回收；自身死亡由 `CompanionService`/`BootCompletedReceiver` 重启。
- 每 **4 秒**轮询 `MediaState`：检测白名单媒体 App 进入/离开前台。
- **事件同步**（节流）：进入→`media_watch_start`；离开→`media_watch_end`；播放中每 **5 秒**上报 `media_state`（进度/标题/博主）。
- 伴看期间拉起画中画小挂件并实时更新内容。
- **管束联动**：单 App 连续观看超阈值（默认 90 分钟）→ 强制切回掌心窗 + 休息提醒弹窗。

### 3. 画中画小挂件 `CompanionFloatingWidget.java`
- `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCH_MODAL`（外部触摸透传，不挡操作）。
- 祁晏头像（`SoftAvatarView`）+ 呼吸动效（呼吸点）+ 当前在看的视频文案。
- 点击挂件 = **快捷查岗**（切回掌心窗陪伴页）。
- `showWhisper(text)`：后端可下发一句陪伴/吐槽气泡，4.2 秒后自动消失。

### 4. 命令接入与自启接线
- `CompanionService.performAction` 新增 4 个命令：`send_watch_whisper`、`pause_media`、`media_switch_screen`、`get_live_media_context`。
- `ScreenshotService` 无障碍事件把短视频前台文本喂给 `MediaState`（caption/author 来源）。
- `AndroidManifest.xml` 声明 `MediaSyncService`；`BootCompletedReceiver` 与 `CompanionService.onStartCommand` 均启动该服务（自启 + 守护同生共死）。

## 三、后端 / MCP 接口（已落地、语法校验通过）

### 服务端 `server/linjian_server.py`
- `ALLOWED_ACTIONS` 加入：`send_watch_whisper`、`pause_media`、`media_switch_screen`、`get_live_media_context`。
- 新增 `POST /api/media/sync`：接收并存储当前媒体快照（`media_live`）与伴看事件（`media_events`，保留最近 50 条）。
- 新增 `GET /api/media/context`：返回当前媒体上下文 + 最近事件。
- `tools` 列表（/health）已包含新动作，MCP 可发现。

### MCP `mcp/server.js`
- **`get_live_media_context`**：先读 `/api/life_state.media_state`（秒级缓存），再发设备命令 `get_live_media_context`（explicit）取**实时**数据覆盖，并合并 `/api/media/context` 的最近事件，返回结构化上下文（APP/标题/博主/进度/百分比/文案/作者/是否伴看中）。
- **`companion_media_interact`**：参数 `interaction ∈ {whisper, pause, switch_screen}` + `message`，映射为设备命令并带 `explicit:true` 下发（`postCommand`+`waitCommand`），返回执行结果。

## 四、编译与验证
- `python3 -m py_compile server/linjian_server.py` ✅
- `node --check mcp/server.js` ✅
- `bash build.sh` ✅ → APK `Zhangxinchuang-public-v0.3.9.0.apk`（1.1M，v1/v2/v3 Verified）
- 静态核对：命令注册↔白名单↔手机执行分支一致；浮窗 `NOT_TOUCH_MODAL` 透传；前台保活链路闭合。

## 五、真机验收建议（本环境无法跑模拟器，需真机确认）
1. 开启**通知使用权**与**无障碍**（媒体状态与文案抓取依赖）。
2. 打开抖音/B站/腾讯视频，确认：小挂件出现并显示在看的视频；后端 `get_live_media_context` 能读到标题/进度/博主。
3. MCP 调用 `companion_media_interact`：`whisper` 弹气泡、`pause` 暂停当前视频、`switch_screen` 切回聊天。
4. 单 App 连续观看超 90 分钟，确认触发休息提醒 + 拉回。
5. vivo(OriginOS) 锁后台后确认守护服务与挂件仍存活。

## 六、改动文件清单
- 新增：`MediaSyncService.java`、`CompanionFloatingWidget.java`
- 修改：`MediaState.java`、`CompanionService.java`、`ScreenshotService.java`、`BootCompletedReceiver.java`、`AndroidManifest.xml`、`server/linjian_server.py`、`mcp/server.js`
