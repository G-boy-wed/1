# 掌心窗 · 角标开关 + 一起看修复 交付回执（v2026-11-21）

## 本轮需求与落地

| 需求 | 落地 |
|---|---|
| 彻底删掉识图模式 | 桌面角标 `ocr`（识图感知）从 `DeskFeatureStore.DEFS` 移除。`policy:ocr` 定义已清除（DEX 确认 CLEAR）。**保留**设置页/底层 OCR 能力——它是课表图片导入（TimetableParser.importFromImageOcr）依赖的真实功能，桌面入口已彻底删除。 |
| 第三页全局/权限角标去除 | `global`、`permissions` 两个角标同时从 DEFS 移除。角标总数 17 → 14。 |
| 每个角标加可控开关 | 新建 `DeskFeatureToggleStore`，每个功能角标右上角独立开关（绿=开/灰=关）。点击切换、置灰图标、关闭后点功能提示"已关闭"不误打开。状态持久化 + 上报服务端；机端 MCP `set_desk_toggle` 可远程控制，`get_desk_state` 可读 enabled。 |
| 一起看不能正常运行 | 修复两个真实断点（详见下），E2E 实测通过。 |

## 一起看：根因与修复

**根因 1（弹幕/表情被吞）**：手机端每 1.5s 把整个房间状态上报，`LocalState.watchparty_update` 用手机本地的 `danmaku`/`reactions` 数组**整体覆盖**服务端状态。机端（MCP）刚发的吐槽、表情、位置被紧随其后的手机 snapshot 用空数组清掉，表现为"发了没反应 / 状态不更新"。

**根因 2（对方内容不显示）**：`WatchPartyActivity` 只读本地房间，从不拉取服务端，对方的弹幕/表情/位置永远不显示。

**修复**：
- 服务端 `watchparty_update` 改为**按时间指纹去重合并**：弹幕/表情不再被空数组覆盖，双向合并（local+remote）；`member`/`partner` 逐字段合并，空串不覆盖已有值。
- 手机端新增 `WatchPartyState.mergeRemote()` + `WatchPartyActivity.pullRemote()`，定时从 `/api/watch_party` 拉取房间并合并进本地，本机真实媒体进度保持优先。
- `CompanionService.getJson()` 新增带 token 的 GET 能力供拉取使用。

**E2E 实测**（本地跑 McpEngine）：
- 机端加 2 条弹幕 → 手机空弹幕 snapshot 覆盖 → **仍有 2 条**（不再被吞）
- 手机再加 1 条 → **变 3 条**（双向合并成功）
- `set_desk_toggle{id:watchparty,enabled:false}` → 正确入队 `desk_set_toggle` 命令

## 分页去 index 依赖（关键防回归）

删除 3 个角标后，原 `addFeatureGrid` 的 `0-4 / 4-16 / 16+` 分段会让一起看（index 16→13）掉进相册页甚至丢失。已重构为**显式 id→页映射**（`defaultFeaturePage`）：
- 个人页：查岗/情绪/主动找/训诫
- 动态页：一起看（恒定，不受增删影响）
- 相册页：其余角标

`addFeatureGrid` 改为按 `belongsOn`（归属页）渲染，跨页移动依然生效。

## 文件清单

**Android（Java）**
- `DeskFeatureStore.java`：删 ocr/global/permissions（14 项）
- `PalmDeskActivity.java`：显式 id→页映射、addFeatureGrid 按归属页渲染、featureCell 加开关控件、点击关闭态提示
- `DeskFeatureToggleStore.java`（新）：角标开关持久化 + 快照
- `DeskCommand.java`：desk_set_toggle、get_state 含 enabled、reset 含开关
- `CompanionService.java`：命令分发（startsWith desk_）、状态上报加 desk_toggles/desk_badges、getJson()
- `WatchPartyState.java`：mergeRemote() 双向合并
- `WatchPartyActivity.java`：pullRemote() 定时拉取

**Server（Python）**
- `mcp_tools.json`：set_desk_badge 描述去已删角标、get_desk_state 描述补 enabled、新增 set_desk_toggle（工具 132 → 133）
- `mcp_engine.py`：set_desk_toggle handler+注册、watchparty_update 智能合并
- `linjian_server.py`：McpBackend.desk_state()

## 验证

- `bash android/build.sh`：编译通过，v1/v2/v3 验签全 Verified
- DEX：DeskFeatureToggleStore / desk_set_toggle / mergeRemote / pullRemote 全部命中；policy:ocr/global/permissions 已 CLEAR
- `py_compile` mcp_engine + linjian_server：通过
- `verify_mcp_schema.py --static`：MISSING_ITEMS_COUNT=0，ZERO_ERRORS
- MCP E2E：弹幕合并 + 开关工具入队，全部通过

## GitHub 同步（2026-11-21）

- main ref：`014806c42a62ec6049876b4e68c5dc11251eff07`（tree `b6d796ce`，与本地 HEAD 完全一致；经 REST Git Data API 增量推送，git 协议超时）
- Release `v0.3.9.0` APK 已覆盖，asset id `772982821`，三方 SHA-256 一致
- 下载：https://github.com/G-boy-wed/1/releases/download/v0.3.9.0/Zhangxinchuang-public-v0.3.9.0.apk

## APK

`android/Zhangxinchuang-public-v0.3.9.0.apk`（2,441,047 字节）
SHA-256：`c05e29b9112fe962bf19d74d79142bb224cee8cb66917a6d1c8ac839910bbf89`
