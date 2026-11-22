# 交付记录 · 训诫双卡池修正 + 锁App 3分钟 + 天气修复

日期：2026-11-22
包名：`dev.linjian.peek`
版本：v0.3.9.0
APK：`android/Zhangxinchuang-public-v0.3.9.0.apk`
SHA-256：`d2c49f2221a1abcdc045f9e5663e0e7dec8c119ec3734446b475da335d1a5380`

## 背景
上一版把"温柔提醒池/严厉管束池"合并成单一随机池，与用户要求（两个独立卡池、由用户选择抽取）相反。本次纠正，并一并处理锁App时长与天气隐患。

## 本次改动

### ① 训诫：恢复双卡池选择（核心修正）
- **两个独立卡池**：温柔提醒池(gentle)、严厉管束池(strict)数据分开保存，服务端 `discipline_phrases.json` 本就分两池，无需重建数据。
- **选择方式（按用户确认）**：在**手机设置页**用"话术卡池"选项固定选一个；MCP `set_discipline_mode` 工具恢复，用来切同一个卡池（单一配置源 `KEY_DISCIPLINE_POOL`）。
- **取话术**：触发训诫时**只从当前选中池**随机取整句，`last_phrase` 按池分开记、避免连续重复。
- **不显示数字档位、不加固定标签后缀**：弹窗文案就是话术池里的整句。
- 手机端：`PolicyStore` 新增 `disciplinePool/setDisciplinePool` + 默认 gentle + 迁移补默认；`DisciplineController` 用 `chosenPool()` 替代 `mergedPool()`；设置页加"话术卡池"选择；`syncDisciplineToServer` 带上 pool。
- 服务端：`mcp_engine.py` 恢复 `set_discipline_mode` handler（SPECIAL 映射 + `_t_discipline_mode`）；`linjian_server.py` 恢复白名单、`apply_discipline_update` 支持 pool、默认结构带 pool；`mcp_tools.json` 恢复工具定义（120 个）；`verify_mcp_schema.py`、`worker.js` 同步。
- 命令下发 action：`set_discipline_mode`，payload `{pool, mode}`。

### ② 训诫锁 App 固定 3 分钟
- `DisciplineController.trigger()` 锁 App 时长由 30 分钟改为 `PolicyStore.DISC_LOCK_MINUTES = 3`。
- 触发条件不变：情绪强度 ≥80（`DISC_STRONG_INTENSITY`）才锁 App + 弹窗不可拒绝；不足 80 只弹窗、可拒绝。

### ③ 天气两个隐患修复（服务端文字提醒链路）
- **超时重试**：`fetch_weather` 的 forecast 请求由 1 次改为最多 3 次（网络抖动是偶发 `fetch_failed` 主因）；地理编码也带 2 次重试。
- **区县后缀兜底**：城市名带 `市/区/县/旗/州/盟` 后缀查不到时，剥离后缀再查（`朝阳区→朝阳`、`海淀区→海淀`）。
- 实测：朝阳区、海淀区、浦东新区、绵阳、海淀均 `ok=True` 并返回温度；带坐标查询正常。
- 手机端天气（`WeatherLive` 用 uapis.cn 区县实况 + GPS 坐标）本就正常，本次未改。
- **已知局限**：无坐标时"朝阳区"可能被 Open-Meteo 解析为重庆朝阳（全国多个同名区）——但至少能返回并标注地区，手机弹窗走 GPS 坐标不受影响。

## 验证
- Java 编译通过；APK 签名 v1/v2/v3 全过（`Signer #1` 固定公钥摘要 `aea75c9b…`）。
- MCP schema 校验：工具数 120，`PERFECT: ZERO_ERRORS_ALL_RESOLVED`。
- `set_discipline_mode` 定义/SPECIAL/白名单三处齐全；`get_discipline_mode` 档位工具已删。
- dex 检查：旧标识 `disc_level/disc_mode/mergedPool` 全为 0；新标识 `disciplinePool/DISC_LOCK_MINUTES/last_phrase_` 已编入。
- 端到端冒烟：两池数据独立、切池正确下发、中英文卡池名识别、非法值拒绝；天气带后缀查询与坐标查询均通过。

## 待用户确认
- 是否上传 GitHub（提交 + 推 main + 替换 Release APK）。
