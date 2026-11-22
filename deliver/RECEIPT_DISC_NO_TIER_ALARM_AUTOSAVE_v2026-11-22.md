# 交付记录：训诫去档位 + 闹钟自动保存 + 全 MCP 可调用排查（v2026-11-22）

## 一、用户确认的决策（逐条提问后执行）
| # | 决策点 | 用户选择 |
|---|--------|----------|
| 1 | 闹钟拉起方式 | 无障碍自动点保存（失败保留手动兜底） |
| 2 | 其他 MCP 含义 | 排查全部 MCP 都可调 |
| 3 | 训诫档位提示 | 连档位配置也删（彻底删干净） |
| 4 | 话术池合并 | 保留两池、取用时混合抽 |
| 5 | 删档位后 set_discipline_mode 工具 | 删掉 |
| 6 | 锁 App 判定 | 情绪强度达阈值锁，阈值 80 |
| 7 | 弹窗能否拒绝 | 两项都按强度（≥80 锁 App + 弹窗不可拒绝） |
| 8 | 锁 App 时序 bug | 要修 |

## 二、手机端改动（Android，已编译验签通过）
- **PolicyStore.java**：删除 `KEY_DISCIPLINE_LEVEL/MODE`、`DEF_DISC_LEVEL/MODE`、迁移与 reset 写入、`disciplineLevel/Mode/setXxx/disciplineForceStrong`；新增 `DISC_STRONG_INTENSITY=80` 与 `disciplineStrongNow()`（enabled && 情绪强度≥80）。
- **DisciplineController.java**（重写）：
  - 话术池温柔+严厉两池保留，取用时 `mergedPool()` 合并随机整句，避免连续重复；
  - `config()` 去掉 level/mode/tone_badge/tone_label，改为 text/phrase_count/strong 等；
  - `resolveDisciplineText()` 从合并池取，兜底文案去掉温柔/严厉字眼；
  - `trigger()`：先抓真实前台 App（排除掌心窗自身）再弹窗 → 修时序 bug；强度≥80 才锁 App；去档位落款；
  - 删除 `set_discipline_mode` 命令分支。
- **AlertWindowManager / GuidianState / CompanionsEmotionState / PolicyScheduler / PolicySync / PolicyCommand / PolicySettingsActivity / CompanionService**：所有档位引用改为强度≥80 口径；设置页删除档位/语气两个 Spinner，改为强度说明；陪伴话术尾句去档位数字与【严厉管束】标签。
- **CompanionService.setAlarm()**：拉起系统时钟后调用 `ScreenshotService.requestAutoSaveAlarm()`。
- **ScreenshotService.java**：新增 `[ALARM-AUTO-SAVE]`——拉起时钟后 1.2/2.6/4.5/7s 共 4 次延迟尝试，仅当前台为时钟类 App（含 clock/deskclock/时钟/alarm）时按候选词（保存/确定/完成/添加/OK/Save…）`tapText` 点击；成功即取消后续；失败写日志、保留手动兜底。
- 编译结果：Java 编译通过；APK 签名 v1/v2/v3 全通过；签名证书 SHA-256 仍为 `aea75c9b…f002fb`。

## 三、服务端改动（Python + 云 worker，静态校验通过）
- **mcp_engine.py**：删 `get_discipline_mode` READ 映射、`set_discipline_mode` SPECIAL 映射与 `_t_discipline_mode` 方法。
- **linjian_server.py**：ALLOWED_ACTIONS 删 `set_discipline_mode`；`apply_discipline_update` 不再写 mode/level、改用 `strong_intensity_threshold`、去掉 level 反推 enabled；兜底话术改单句中性；默认结构改 `{enabled, strong_intensity_threshold:80, custom_text}`（兼容旧字段读取）。
- **mcp_tools.json**：删除 `set_discipline_mode`、`get_discipline_mode` 两个工具（121→119）；更新话术池与 trigger/get_discipline_state 描述为“混合取用、无档位”。
- **verify_mcp_schema.py**：白名单删 `get_discipline_mode`。
- **cloudflare-worker/worker.js**：删两个工具定义与 case 处理。
- 校验：`py_compile` 全通过；`node --check` 通过；`verify_mcp_schema.py` → **119 工具，PERFECT: ZERO_ERRORS_ALL_RESOLVED**。

## 四、全 MCP 可调用排查
- 静态核对 119 个工具的实现路径（SPECIAL / READ_TOOLS / 通用命令 + ACTION_OVERRIDE），无幽灵工具；
- 下发 action 经 override 后全部在 ALLOWED_ACTIONS 白名单内（3 个疑似项经核实为 READ/SPECIAL 内部读取键，非下发 action，非 bug）；
- 端到端 tools/list 返回 119 工具，initialize OK。

## 五、产物
- APK：`android/Zhangxinchuang-public-v0.3.9.0.apk`
- SHA-256：`dc5ac068261eea2e8623b3318e7e8ad8399d0b1c02bb48b2a2ba61a0990599ec`
- update.json（根 + server）已同步新摘要与本次更新说明。

## 六、说明
- 闹钟自动保存受安卓系统限制与各家时钟界面差异影响，不保证 100% 成功；失败时停在时钟页由用户手动保存。
- 历史版本 changelog 条目按惯例保留（属历史记录），本次改动以新增条目形式记录。
