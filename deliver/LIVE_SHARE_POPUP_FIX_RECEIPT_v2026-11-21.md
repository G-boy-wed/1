# 掌心窗交付回执 · 一起看共享真实媒体 + 召回弹窗修复（v2026-11-21）

## 一、「一起看」重构：共享我正在看的抖音/电影（不再是独立播放器/上传视频）

按用户要求彻底改方向：**一起看 = 我在手机端同意后，对方/机器端就能和我一起看我手机上真实正在播放的内容**，不单独开播放器、不上传视频。

### 手机端
- `WatchPartyActivity` 顶部新增醒目的「**同意共享本机正在看的抖音/电影**」开关按钮，点击即授权。
- 同意后每 1.5s 从 `MediaState.collect()` 读取本机真实媒体会话（MediaSession + 通知监听 + 读屏兜底）：
  - 抖音/快手/小红书/B站/腾讯视频/爱奇艺/优酷/芒果TV 等的**标题、作者、App 名、包名、播放进度、总时长、播放状态**；
  - 短视频不暴露标题时，用读屏文案（caption）兜底，保证标题非空。
- 影片卡显示真实内容（「▶ 来自 抖音」+ 真实标题/作者），进度条只读跟随真实进度；再点按钮即停止共享。
- 房间快照实时镜像服务端（`/api/device/state` 的 `watch_party` 字段），带 `source=live_media`。

### 服务端 / MCP
- `watch_party_start` **不传任何媒体参数时自动读取手机真实媒体会话**（`self.ctx.media_context`），带回真实标题/作者/包名/进度/时长/播放状态，`live_media_used=true`；手机没在播放时给出明确引导而非伪造影片。
- `get_watch_party` 对来自真实媒体的房间**实时再读最新进度**——对方看到的是我「此刻」的进度，不是发起瞬间的快照。
- 6 个工具（start/get/sync/danmaku/reaction/end）全部保留，工具总数 129；工具描述已改为"共享本机正在看的，不用上传视频"。
- 完整链路已闭环：手机同意共享 → 真实媒体/弹幕/进度实时上报 → 服务端字段级合并 → LocalState → MCP 可读。

## 二、修复召回弹窗误弹（明明在聊天软件却弹召回）

### 根因
1. `ScreenshotService.onAccessibilityEvent` 对**所有无障碍事件类型**无条件覆盖 `currentPackage`——在微信聊天时，输入法弹起、页面滚动等事件把前台包名污染成 `com.tencent.qqpinyin` 等，令调度器误判"不在聊天软件"。
2. 判定只认单一 `targetPackage`（默认 Kelivo），微信/QQ 等真实聊天 App 不在其中，被当成"分心/该召回"。
3. 服务端 `exempt_packages` 默认空，巡回强拉回/锁应用不豁免聊天软件。

### 修复（双端）
| 位置 | 改动 |
|---|---|
| `ScreenshotService` | 仅 `TYPE_WINDOW_STATE_CHANGED` 才更新 `currentPackage`，且过滤输入法/系统面板等瞬时窗口 |
| `PolicyScheduler` | 新增聊天 App 白名单 `CHAT_FOREGROUND_PACKAGES`（微信/QQ/TIM/企微/钉钉/飞书/WhatsApp/Telegram 等）+ `isChatForeground()`；查岗/情绪/主动/分心入口全部兜底：在聊天软件里一律不弹召回 |
| `AppGate` | 主流聊天 App 纳入保护名单，不再被锁/召回 |
| `linjian_server.py` | 新增 `CHAT_FOREGROUND_PACKAGES` + `is_chat_foreground_pkg()`（含分身/国际版后缀识别）；`is_package_exempt`、force_back、单 App 超时锁三处均豁免聊天软件 |

## 三、验证
- `bash android/build.sh` 编译通过，APK v1/v2/v3 验签通过，启动页 `.PalmDeskActivity`。
- dex 校验：`applyLiveMedia / enableLive / CHAT_FOREGROUND_PACKAGES / isChatForeground / live_media` 在位；旧独立播放器文案（还没有影片/等待发起/上传视频/新影片）计数 0。
- 服务端 `py_compile` 全通过；`verify_mcp_schema --static` = `PERFECT: ZERO_ERRORS_ALL_RESOLVED`（129 工具）。
- MCP 端到端：无参数 `watch_party_start` 成功带入手机真实媒体（海边日落绝美延时/旅行君/抖音/进度），get 读到真实进度，弹幕/表情/结束正常，命令正确入队。
- 聊天豁免单测：微信/QQ/分身/WhatsApp = 豁免；抖音/空 = 不豁免（抖音是视频软件，不属于聊天场景）。
- APK sha256：`d47504f86857f7cde23758a92671300756f20e4df46e500e47940e8d54067277`
