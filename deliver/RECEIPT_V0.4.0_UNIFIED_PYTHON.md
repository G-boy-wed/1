# 掌心窗 v0.4.0「一次性推倒重构」交付回执

> 核心目标：**彻底抛弃 Node.js 混血架构，全盘并入 Python(8513) 单进程闭环**。
> 交付提交：见 git log（本文件同批推送）。

---

## 一、架构与通信（彻底干掉 Node 8787 与多进程撕裂）

| 项 | 之前 | 现在 |
|---|---|---|
| MCP 服务 | `mcp/server.js`（Node，8787，express + MCP SDK + zod） | **删除整个 `mcp/` 目录**，全部工具用 Python 重写 |
| 后端服务 | `server/linjian_server.py`（8513） | 同文件，**单进程**同时承载 REST + MCP + SQLite |
| 进程数 | 2（Node + Python），端口 8787 + 8513 | **1 个 Python 进程、仅 8513** |
| 依赖 | npm/pnpm、`MODULE_NOT_FOUND` 风险 | **纯 Python 标准库**，零第三方依赖 |
| 跨域 | 无 CORS、预检 404 | 全局 CORS 头 + `OPTIONS 204` 预检 |

**新增/改动文件**
- `server/mcp_engine.py`（新，纯标准库 MCP 引擎）：MCP JSON-RPC 协议 `initialize / tools/list / tools/call / ping / resources / prompts`，支持 **Streamable HTTP(`POST /mcp`)、SSE(`GET /sse`+`POST /messages`)、stdio(`--mcp-stdio`)** 三种传输。
- `server/mcp_tools.json`（新）：从原 2780 行 Node 逐工具提取的 **123 个工具定义**（name / description / inputSchema / 行为标记），运行时加载。
- `server/linjian_server.py`：挂载 MCP 路由、抽出模块级 `enqueue_command()`（HTTP 与 MCP **共用同一份入队逻辑，同进程直连 state，无 HTTP 自转发**）、新增 `McpBackend` 适配器、`--port` / `--mcp-stdio` 入口、全局 CORS。
- 天气/到访/关心策略/时间/截图/活动/窗语/陪伴动作等原 Node 重实现逻辑，全部用 Python 等价重写（含「坐标优先」防漂移）。

**统一 Base URL**：`start.sh` / `render.yaml` 均改为**单服务、单端口 8513**；`AppPrefs.cleanServer()` 自动把误填的旧 MCP 地址(`:8787`、`/mcp`、`/sse`、`/messages`)纠正到统一 8513 根，消除 404/CORS；手机端本就只连一个 `server_url`，从不依赖 MCP 端口。（注：真机/云端域名保留用户自填主机，不硬编码 `127.0.0.1`，否则手机会连到自己。）

**实测**：`start.sh` 启动后 `ps` 仅 1 个 `python3 linjian_server.py`、8787 无监听；MCP `initialize`/`tools/list`(123)/`tools/call`、stdio、SSE 全部通过。

---

## 二、共同记忆（Shared Memory）全功能落地

- **SQLite 内嵌**：`State._init_shared_memory_db()` 建表 `shared_memory(id, author, text, timestamp)` + 时间索引，幂等；`_migrate_shared_memory_into_db()` 把旧 JSON 镜像一次性迁入。
- **3 个接口**（`X-Auth-Token` 鉴权）：
  - `POST /api/shared_memory/write` — 立即落库，返回 `persisted:true`；同时下发 `add_shared_memory` 设备命令做双向同步。
  - `GET  /api/shared_memory/list` — 按 `timestamp DESC` 倒序列全部。
  - `POST /api/shared_memory/search` — 关键词检索（text/author LIKE）。
- **`life_state.shared_memory` 聚合**：改为从 SQLite 读（含最新条目），手机未携带记忆时也正确返回，卡片可渲染。
- **MCP 工具** `write_shared_memory / search_shared_memory / list_shared_memory` 直连 SQLite。
- 修复历史 bug：`add_shared_memory`/`remove_shared_memory` 之前不在 `ALLOWED_ACTIONS` 白名单会被静默转成 `noop`——已补入。
- **实测**：write→list→search→life_state 聚合全通过；`write` 后命令可被 `/api/poll` 取到。

---

## 三、弹窗与拉回（告别「切回软件才跳」）

Android 端悬浮窗「骑脸」此前已实现（`TYPE_APPLICATION_OVERLAY` + `MATCH_PARENT` + `FLAG_LAYOUT_IN_SCREEN`），本轮补关键兜底：
- **`fullScreenIntent` 双重兜底**：`CompanionService.showReminderNotification()` 增加 `setFullScreenIntent(fullPi, true)` + `IMPORTANCE_HIGH` 通道。Android 10+ 后台 `startActivity` 被拦截时，系统仍以全屏/悬浮横幅强制展示。
- **弹窗/拉回联动通知**：`showCenterAlert()` 与 `PullBackActivity.show()` 在悬浮窗不可用/失败降级到 Activity 时，**额外补发一条 fullScreenIntent 横幅通知**，无论用户在刷什么 App 都能骑脸。
- **权限引导**（新 `OverlayPermission.java`）：实时检测「悬浮窗权限 / 后台弹出界面(AppOps 探测，未知不谎报) / 电池优化白名单」，`showGuide()` 一键跳转授权；`MainActivity` 的「打开悬浮窗权限」按钮升级为完整引导弹窗。

---

## 四、界面、死锁与辅助 Bug 清理

- **日记重命名多候选报错**：`DiaryState.renameBook()` 新增 `book_name` 兜底 + 按 `new_name` 反查唯一匹配 + 只有一本自动选中，`handleCommand` 传入 `book_name/book_title`，不再轻易返回 `book_not_found_or_ambiguous`。
- **页边纸条白名单校验失败**：`addAnnotation()` 新增 `normalizeAnnotationStyle()`/`normalizeCreatedBy()`，兼容别名/大小写/未知值，归一到合法枚举，绝不因参数格式拒写。
- **门禁/屏幕休息后台保活**：计时本就以绝对时间戳 `locked_until_ms` 存储（杀进程重启后比较仍准确）；真实缺口是「被杀期间无前台切换事件→无触发点」。新增 `AppGate.recheckActiveLocks()`，由 `SchedulerWatchdogReceiver`(AlarmManager 15 分钟穿透 Doze) 与 `BootCompletedReceiver` 调用，按时间戳复查并补锁；看门狗同时确保 `CompanionService` 前台服务存活。
- **start.sh**：重写为唯一可靠的单进程启动（自动生成 token 到 `server/.env`、`pkill` 自匹配修复、`--port 8513`、`curl /health` 自检）。

---

## 五、编译/实测凭据

- Python：`py_compile linjian_server.py mcp_engine.py` ✅；`mcp_tools.json` 合法（123 工具）。
- MCP：`initialize` / `tools/list`(123) / `tools/call`（本地类、命令类、读状态类、天气坐标优先、日记/门禁/专注命令）/ stdio / SSE ✅。
- REST：`/health`、`/mcp`、`/sse`、`/messages`、3 个记忆接口、CORS 预检(204)、命令入队可被 `/api/poll` 取走 ✅。
- Android：`build.sh` → aapt2→javac→d8→zipalign→apksigner，新增 `OverlayPermission.java` 及全部 Java 改动编译通过，**v1/v2/v3 Verified**。
- 单进程：`start.sh` 启动后仅 1 个 Python(8513)、8787 无服务、无 Node 项目进程 ✅。

---

## 六、如实标注的限制

1. **MCP 客户端实测**：本环境有完整 MCP 协议实现并逐方法自测通过，但**无真实 AI 客户端/WorkBuddy 连线环境**；切换部署时把 MCP 地址由旧 `:8787/mcp` 改为新 `:8513/mcp`（或 `/sse`、或 command 方式用 `--mcp-stdio`）即可，若某客户端对 Streamable HTTP 头有差异可反馈，我再适配。
2. **真机 ROM 行为**：悬浮窗骑脸、后台弹出界面权限、国产 ROM 保活、fullScreenIntent 生效情况需在 vivo/小米/OPPO 真机实测（本环境无模拟器/真机）；代码已按 Android 10+ 后台限制做了双重兜底。
3. **图片识图**：`analyze_image`/`screen_ocr` 返回可见文本/OCR 摘要，视觉推理仍需在后端接入带视觉能力模型。
4. **Kotlin**：构建链仅 javac（无 kotlinc），Android 改动均为 Java（历史上亦如此），非原生 `.kt`。
5. **APK 未入仓库**：`*.apk` 被 `.gitignore` 忽略；最新签名包在 `android/Zhangxinchuang-public-v0.3.9.0.apk`（本地可用）。
