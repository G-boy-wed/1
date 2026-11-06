# 掌心窗 · 共同记忆 / 天气区县 / 闹钟意图 / 其他小 bug 修复回执（Commit M）

> 核验时间：2026-11-07
> 基线：远端 `a34dd6f`（Commit L）。本批次在此基础上继续修复，未新建 MCP。
> 验证方式：实机网络 curl 复现天气地理编码根因 + `build.sh` 重新编译 APK 并校验签名（v1/v2/v3 Verified）。

---

## 0. 是否新增 MCP？——结论：**不需要新增 MCP**

记忆写入问题的根因不是"缺少 MCP"，而是**已存在的记忆 MCP 写错了存储位置**（写到了服务端 `shared_memory.json` 文件，与手机本机 `SharedMemoryState` 是两份互不相通的数据）。
因此本批次**复用现有"设备命令通道"**（`/api/command` + 手机轮询执行），把记忆工具路由到手机本机执行即可，无需新 MCP。
若日后要支持"离线也能写记忆"，再考虑新增轻量 MCP；当前架构以手机本机为唯一数据源，最稳。

---

## 1. 天气：精准到区县 + 返回正确天气 ✅

**根因（已用 curl 实证）**：Open-Meteo 地理编码对中文「区县 + 城市」查询**返回空结果**：
- `长安区 石家庄` → `{}`（仅 `generationtime_ms`，无 `results`）
- `徐汇区 上海` → `{}`（同上）

之前的实现依赖 `admin2` 回写，查询拿不到坐标自然全失败 → "找不到区县、地址错误"。

**修复（`WeatherLive.java` `fetch`）**：
- 坐标获取：优先试「区县 城市」，拿不到坐标就**退回「城市」单独查询**（实测"石家庄市"稳定返回坐标），坐标按城市级足够（同城市内天气基本一致）。
- 区县展示：**以用户填写的区县名为权威展示值**（`out.district = 用户区县` 或 `admin2` 兜底），不再指望地理编码返回。
- `cardText` 同步显示 `区县·城市`。
- `MainActivity.updateWeatherOverview` 把 `district` 透传给 `cached / refreshAsync / isFresh`，使带区县的天气真正被缓存与刷新（之前只传 city，区县天气永远取不到）。

**效果**：用户在北京·朝阳区 / 石家庄·长安区 等设置区县后，天气卡片显示"长安区·石家庄市 · 晴 · 18℃"，且天气数值为该市正确实况。

---

## 2. 共同记忆：彻底修复"不写入 / 不显示 / 不可改查" ✅

**根因（三处，已全部修复）**：
1. **MCP 写错存储**：`write_shared_memory` 直接 `POST /api/shared_memory` 写**服务端文件**，手机永远看不到 → "记忆不写入[到手机]"。
2. **手机端已有完整面板但读取走服务端镜像**：`PolicySettingsActivity` 的"共同记忆"分区本就有新增/保存/列表/逐条编辑/删除，调用的是手机本机 `SharedMemoryState`（之前我误判为"无面板"，实为 grep 只看定义文件所致，已纠正）。
3. **服务端镜像与手机不同步**：服务端 `/api/shared_memory` 是独立文件，不随手机变化。

**修复**：
- `mcp/server.js`：`write_shared_memory` 改为经**设备命令通道** `postCommand({action:"add_shared_memory", payload:{text,author,explicit:true}})` 并等待手机回执；`search/list` 读服务端镜像。
- `server/linjian_server.py`：`POST /api/shared_memory` 的写入/删除**转发为设备命令**（落到手机本机）；`GET` 返回服务端镜像。
- `CompanionService.uploadStateThrottled`：每次状态上报把手机记忆 `SharedMemoryState.getItems` 镜像到服务端，使 `/api/shared_memory` 与网页/MCP 检索看到手机最新记忆（可改可查）。
- 手机本机写入沿用既有 `commit()` 即时落盘（已核查 `SharedMemoryState` 的 add/edit/remove 全为 `commit`）。

**效果**：AI 说"记住…"→ 经 MCP → 手机本机写入 → 掌心窗"共同记忆"面板实时可见可改可删；手机端写的记忆也会同步到网页/MCP 检索。

---

## 3. 闹钟：仅"用户说帮我定闹钟 + 时间"才调用 ✅

**根因**：手机端 `performAction` 的 `set_alarm` 要求 `explicit` 才执行（正确守门），但 MCP 的 `set_alarm` 工具**从未传入 `explicit`** → 所有经 MCP 定的闹钟都被手机拒回 `alarm_requires_explicit_request`（等于"闹钟永远定不上"）。同时 `care_action` 会把"计划/提醒"类信号**自动**塞进 `set_alarm`，与"仅显式请求"冲突。

**修复（`mcp/server.js`）**：
- `set_alarm` 工具：`payload` 补 `explicit: true`，并等手机回执确认；描述收紧为**仅当用户明确说"帮我定闹钟/定个闹钟/帮我设个闹钟 + 时间"才调用**，普通"提醒我/计划"改用 `send_notification`。
- 从 `care_action` 枚举与信号自动推送清单中**移除 `set_alarm`**，杜绝自动触发。

**效果**：只有用户显式说出"帮我定个明天 7 点的闹钟"时才真正下发到手机设定；其余提醒走通知，不再误定闹钟。

---

## 4. 其他小 bug 排查与核验 ✅

- **聊天软件内不再误弹拉回/情绪/主动聊天**：`PolicyScheduler.evaluate()` 在 `inTarget`（目标聊天 App / 掌心窗自身）且非上课时，跳过查岗/情绪/主动聊天/时长锁/权限提醒，仅保留"离开聊天才算"的分心判定。逻辑正确。
- **弹窗浮于当前 App 之上**：`PullBackOverlay` 使用 `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCH_MODAL` + `FLAG_WATCH_OUTSIDE_TOUCH`，不跳转掌心窗即可覆盖当前 App。
- **手机端写入即时落盘**：核查 `PolicyStore` 用户侧开关/设置写入均为 `commit()`；运行期计数用 `apply()`（无需同步，符合设计）。`SharedMemoryState` 增改删均为 `commit()`。
- **训诫语气随开关变化**：`CompanionEmotionState.tone()` 在训诫开启（strict 或中档及以上）返回 `strict`；`GuidianTheme.accentFor` 映射 danger 红；`GuidianState.disciplineAdjustedPrompt()` 对归电文案加硬话/等待后缀；可编辑文案同样随语气加工，不再一成不变。
- **弹窗类型标识**：所有拉回/分心/情绪/主动聊天/提醒均带 `kind_label`（查岗拉回/分心查岗/情绪感知/主动聊天/提醒），悬浮窗与 Activity 兜底都展示。

---

## 5. 编译与签名验证 ✅

```
bash android/build.sh
→ APK: android/Zhangxinchuang-public-v0.3.9.0.apk (≈1.1M)
→ apksigner verify: v1/v2/v3 Verified
→ 固定公钥 SHA-256: aea75c9b2b5f5c42d56b72d4a69a79a38e1c57f27db021017be8656bc8f002fb
python3 -m py_compile server/linjian_server.py  → OK
node --check mcp/server.js                       → OK
```

交付物：
- `deliver/Zhangxinchuang-public-v0.3.9.0.apk`（重编签名版）
- `deliver/patches/CommitM_*.patch`（本批次 5 文件全部 diff）

---

## 6. 诚实说明（未在本环境完成的部分）

1. **真机验收**：本沙箱无法运行 Android 真机/模拟器，上述为代码级根因修复 + 编译/签名验证 + 网络复现，未做真机点测。建议你在手机上回归：①天气填区县看是否显示"区县·城市+正确天气"；②AI 让"记住…"后到掌心窗"共同记忆"面板查看/编辑/删除；③说"帮我定个X点闹钟"验证真能定上。
2. **第一版 UI 视觉重绘**：你要求"采用掌心窗第一版 UI 设计"。本环境**没有第一版的原稿/标注素材**，无法做像素级视觉还原；本次在功能层已确保"保存按钮、守护页开关带开/关标签与删除/关闭标志"存在并生效（设置页均有显式保存按钮、守护卡片均有真实开关）。如需视觉还原，请提供第一版截图/标注，我再据此重绘。

---

## 7. 代码改动清单

| 文件 | 改动 |
|---|---|
| `android/.../WeatherLive.java` | 地理编码改为城市级取坐标 + 用户区县展示；`cardText` 显示区县 |
| `android/.../MainActivity.java` | 天气刷新透传 `district` |
| `android/.../CompanionService.java` | 状态上报镜像共同记忆到服务端 |
| `mcp/server.js` | 记忆工具改走设备命令通道；闹钟补 `explicit` 并收紧触发 |
| `server/linjian_server.py` | `/api/shared_memory` 写入转发设备命令 + 状态上报同步镜像 |
