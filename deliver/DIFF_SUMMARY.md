# Diff 摘要（commit 号 + 各文件改了什么）

基线：`ecdc938`（远端 `main`）　本地 HEAD：`afd3a3e`　全量：**10 文件，+818 / −92**

## Commit A = `bfad3b1`　R1/R2/R4 根因版（4 文件，+433 / −43）

| 文件 | 改动 |
|---|---|
| `mcp/server.js` (+257/−40) | R1 `update_guardian_day` 只发显式字段(`pickDefined`)、R2 `delete_guardian_day` 如实返回+别名重试；只读工具不写事件流(#52)、3s 去重(#53)、`canonicalToolTitle` 中文优先(#12)、`truncateActivityMetadata`(#55)；隐私 `sanitizeScreenState`/`maskSensitiveText`(P0-3)；7 个工具别名(#60)；`SERVER_VERSION`(#61)；移除 `state_path`/回环地址(#58/#59)；`sensitive_apps` 默认 `max_lock_minutes`(#47)；天气城市坐标兜底(#48)、降水矛盾提示(#51)、温度 `Math.round`(#18)；`linjian_status.latest` 时效(#64) |
| `server/linjian_server.py` (+40/−2) | `VERSION` 对齐(#61)、`_clip_metadata` 递归截断(#55)、quiet/suggestion 一致(#46) |
| `android/.../PolicyScheduler.java` (+42/−1) | `SYSTEM_EXEMPT_PACKAGES` 系统级分心白名单(#34) |
| `CHANGES.md` (+94) | 本轮改动清单 |

## Commit B = `c0bcef2`　R3 状态一致性专项（8 文件，+226 / −18）

| 文件 | 改动 |
|---|---|
| `android/.../AppGate.java` (+73/−5) | `submitUnlockRequest` 10s 去重+`status`/`repeat_count`(#7/#38)、`pruneState` 失活锁清理+剔除 `temporary_*`(#8/#40)、陪伴 App 自动进保护名单(#57) |
| `android/.../FocusMode.java` (+71/−9) | `normalizeState` 时间字段收敛(#39/6b)、求助 `pending_hint/timeout`(#36)、应急计数按会话归零(#37) |
| `android/.../GuidianState.java` (+23/−1) | `next_prompt_after` 顺延出安静段(#19/#43) |
| `android/.../LifeState.java` (+6/−1) | `unlock_count_reliable` 标注(6a/#49) |
| `android/.../PolicyScheduler.java` (+3) | 训诫 `level<=0` 守卫(#35) |
| `mcp/server.js` (+14/−1) | 到访 `today_key`/时区(#13)、天气数字/quiet 口径 |
| `server/linjian_server.py` (+8/−1) | 训诫 enabled/level 归一(#35)、quiet 措辞 |
| `CHANGES.md` (+28) | R3 说明 |

## Commit C = `afd3a3e`　R1/R2 手机端真修 + 日历专项（5 文件，+160 / −32，本轮）

| 文件 | 改动 |
|---|---|
| `android/.../CalendarState.java` (+107/−25) | **真修 R1/R2**：新增 `bizArgs()`（从 `payload.id` 取事件 id，解决命令 uuid 顶替）、`upsertEventSmart()`（部分更新/id 恒定/找不到明确失败）、`handleCommand` 删除用业务 id；P2-14 `bannerText` 按 group 分模板 + 关横幅不生成；P2-15 `nearest`/`summaryLine` 只收启用横幅；#70/#71 `events`=全部/`custom_events`=仅自定义+计数口径；#72 `upcoming_total`/`upcoming_has_more` |
| `android/.../CompanionService.java` (+1/−1) | `performAction` 旧日历死路径改明确拒绝（不再构造缺 id 的 cmd） |
| `mcp/server.js` (+13/−4) | `allowed_actions` 补 7 个专注动作(#20)、`gateCommand` note 按 `command_status` 动态生成(#17)、日记本命名文案(#65) |
| `server/linjian_server.py` (+5/−1) | `tour_step_check_in` 配置 `alert_tone` 优先(#44) |
| `CHANGES.md` (+34) | 第五轮说明 |

## 查证记录（本机实跑，非「理论上」）

| 项 | 命令 | 结果 |
|---|---|---|
| Android 真编译 | `cd android && bash build.sh` | 45 个 Java 文件 javac **0 error**；APK v1/v2/v3 签名 `Verified:true`；产物 `Zhangxinchuang-public-v0.3.9.0.apk` 1.1MB |
| JS 语法 | `node --check mcp/server.js` / `worker.js` | 均 OK |
| Python 语法 | `python3.11 -m py_compile server/linjian_server.py` | OK |
| **Java 运行时** | `java -cp out:lib/json.jar TestMain`（官方 org.json + stub 真跑 CalendarState） | **PASS=24 FAIL=0**：删除真删(1→0)、更新不换 id/不覆盖、课表横幅分类型、events 口径、total/has_more |
| **Python 运行时** | 直调模块函数 | `#44 tones=['gentle']`、metadata 5000→1006、VERSION 一致、情绪方向、quiet/suggestion 一致，7 项全过 |
| **HTTP 实测** | `POST /api/discipline` | level=0→enabled=false；enabled=false→level=0；level=2 保留（#35 归一） |

## 推送状态（重要）
- `git push origin main` → **成功（fast-forward）**：`ecdc938..afd3a3e  main -> main`
- 远端 `main` HEAD = `afd3a3e2a5ea96609f7053f2f5c384a77f73d61c`（已更新）
- 本次推送为**总和版**：远端 `main` 的 HEAD `afd3a3e` 是 A(`bfad3b1`)→B(`c0bcef2`)→C(`afd3a3e`) 的快进终点，三版改动**全部已在 `main` 上**。
- **未 force push、未覆盖任何历史、未带走别人改动**；三版均为本地增量提交，仅以 fast-forward 合入。
- PAT 仅在临时凭据注入下使用，未写入文件或 git config。

## patch 完整性验证（在干净基线上实跑）
```
git archive ecdc938 → 干净基线 → git apply --check：
  CommitA patch: OK
  CommitB patch: OK (在 A 之上)
  CommitC patch: OK (在 A+B 之上)
  ALL_vs_remote_main.patch: OK (干净基线上单独应用)
应用 ALL patch 后与本地 HEAD 逐文件 diff 比对：
  mcp/server.js / linjian_server.py / CalendarState.java / AppGate.java —— 全部一致
```
结论：交付的 4 个 patch 均可用，可直接 `git apply` 还原出与本地 `afd3a3e` 完全一致的代码。
