# 掌心窗 Bug 修复 · 交付包索引

**生成时间**：2026-11-05　**基线**：`G-boy-wed/1` `main` @ `ecdc938`（0.3.9.0-public-diary-annotations）
**提交链**：A=`bfad3b1`（R1/R2/R4）→ B=`c0bcef2`（R3）→ C=`afd3a3e`（R1/R2 手机端真修+日历专项）→ **D=`75dd4de`（九项新清单：巡回查岗/门禁/日历/归电 8 条 bug）**

## 一、这个包里有什么

| 文件/目录 | 内容 |
|---|---|
| `README.md` | 本索引 + 凭据需求 + 快速开始 |
| `BUG_RECEIPT.md` | **逐条回执**：覆盖补充清单 33–72 与全量版 R1–R4/P0–P2 全部编号，每条「编号/状态/改动文件/commit/验收步骤/未改原因+根因定位(文件:行号)+最小方案+阻塞点」 |
| `RETEST.md` | **复测清单**：每条 bug 的验证步骤与预期结果（已改项 + 部分改/未改项的现状确认） |
| `NAMING_MAPPING.md` | **命名/口径新旧对照表**（工具别名、日历字段、横幅模板、版本、隐私字段等 10 类） |
| `DIFF_SUMMARY.md` | **三版 commit 号 + 每个文件改了什么 + diff 统计 + 查证记录 + 推送状态** |
| `ROLLBACK.md` | **可回滚说明**：改了哪些文件、5 种回退场景的具体命令 |
| `DEVICE_LOG_NOTE.md` | **真机问题说明**：如实说明本环境无真机/无 logcat、给出采集方法、列权限/后台相关线索（未编造堆栈） |
| `改动总览与失败点.md` | **本次新增**：上传版本说明（总和版）+ 改动清单（已改 43/部分改 21 逐条）+ 失败点（未改 6 + 未闭环，含根因文件:行号+最小方案+阻塞点） |
| `BUG_RECEIPT_9BUG.md` | **九项新清单逐条回执**（Commit D）：模块 1–4 共 8 条 bug，每条「根因/改动/commit/验收步骤/残余风险」，8/8 已改、0 未改 |
| `RETEST_9BUG.md` | **九项新清单复测清单**：准备项 + 8 条 bug 的真机复测步骤 + 回归防回退项 |
| `BUG_RECEIPT_6ITEM.md` | **六项清单逐条回执**（Commit E）：MCP 可用性/窗语/行动记录/查岗配置/情绪 Tick/设置页保存(P0)，6/6 已改 |
| `RETEST_6ITEM.md` | **六项清单复测清单**：MCP 真环境复测 + 设置页 6 抽屉保存复测 + 回归项 |
| `patches/` | 6 个 patch：`CommitA_...`、`CommitB_...`、`CommitC_...`、`ALL_vs_remote_main.patch`、`CommitD_9BUG_...patch`、`CommitE_6ITEM_...patch` |
| `changed_files/` | 10 个改动文件的快照（保持仓库目录结构，可直接覆盖） |
| `Zhangxinchuang-public-v0.3.9.0.apk` | 本机真编译 + v1/v2/v3 签名的 APK（1.1MB） |
| `CHANGES.md`（仓库根，也在 changed_files） | 追加第五轮改动说明 |

## 二、结论速览

- **43 项已改**（行为真的变了，含前两版遗漏的 **R1/R2 手机端真修**：删除不再 `not_found:命令id`、更新不再换 id/覆盖）
- **21 项部分改**、**6 项未改**，每条都给了根因定位(文件:行号)+最小可行方案+阻塞点，**没有一条用「需联调」一句话跳过**
- 阻塞点集中在三类：**缺产品决策/阈值**（如时段优先级、弃用日期、节流窗口、课表周重复）、**缺真机运行态数据**（解锁计数、拦截日志复现、面板与 MCP 数据源核对）、**数据源不在本仓库**（聊天时间戳钩子在陪伴会话侧）
- 未用「默认关闭开关 / 注释掉 / 返回空值 / 占位」冒充修复；未 force push；未覆盖历史；未带走别人改动

## 三、快速开始

```bash
# A. 直接覆盖改动文件
cp -r changed_files/* <你的仓库根>/

# B. 或用 patch（干净基线上按顺序）
git apply patches/CommitA_R1R2R4.patch
git apply patches/CommitB_R3.patch
git apply patches/CommitC_R1R2_phone_calendar.patch
# 或一次性：
git apply patches/ALL_vs_remote_main.patch

# C. 重新构建 / 重启
cd android && ANDROID_HOME=<sdk> bash build.sh
# 重启 mcp/server.js 与 server/linjian_server.py
```

## 四、推送与凭据（重要）

**推送已成功（fast-forward，未 force push、未覆盖历史）**：
```
$ git push origin main
To https://github.com/G-boy-wed/1.git
   ecdc938..afd3a3e  main -> main
远端 main HEAD = afd3a3e2a5ea96609f7053f2f5c384a77f73d61c  # 已更新
```
> 本次推送的是**总和版**：远端 `main` 的 HEAD `afd3a3e` 是 A(`bfad3b1`)→B(`c0bcef2`)→C(`afd3a3e`) 的快进终点，**三版改动全部已在 `main` 上**，并非只推了某一版。三版分开仅为独立审查/回滚粒度。详见 `改动总览与失败点.md` 第一节。

**PAT 使用说明**：本次推送由你提供的 PAT 在临时凭据注入下完成，**PAT 未写入任何文件或 git config，推送后即丢弃**。
**安全提醒**：此前对话里出现过的 PAT 建议尽快在 GitHub 撤销/轮换。

**patch 已验证**：在 `git archive ecdc938` 导出的干净基线上，A/B/C 逐版 `git apply --check` 全部 OK、全量 patch 可单独应用，且应用结果与远端 `afd3a3e` 逐文件一致（见 `DIFF_SUMMARY.md`）。若需本地另起分支验证，可 `git apply patches/ALL_vs_remote_main.patch`。

## 五、验收口径

以你按 `RETEST.md` 的「验收步骤」逐条复测通过为准；未通过的一律计为未完成。
部分改/未改项如需我继续补齐，请给出对应的产品决策/阈值（见 `BUG_RECEIPT.md` 各条「阻塞点」），我按增量提交方式补，**不覆盖现有三版历史**。

---

## 追加（Commit F/G/H/I 各轮交付，均已推送 GitHub main）

| 文件 | 说明 |
|---|---|
| `UI_AUDIT.md` | 全量改动 UI 审查（Commit F） |
| `BUG_RECEIPT_INTERACT.md` / `RETEST_INTERACT.md` | 四项交互问题回执与复测（Commit G） |
| `BUG_RECEIPT_POPUP_BG_MCP.md` / `RETEST_POPUP_BG_MCP.md` | 弹窗互斥/后台保活/MCP 策略链路回执与复测（Commit H） |
| `BUG_RECEIPT_SWITCH_KIND.md` / `RETEST_SWITCH_KIND.md` | **守护页全标签真实开关 / 分心查岗误计修复 / 弹窗类型徽标 / 主动聊天弹窗化** 回执与复测（Commit I = `01a3d6b`，本轮） |
| `patches/CommitF_UI_...patch`、`CommitG_INTERACT_...patch`、`CommitH_POPUP_BG_MCP_...patch`、`CommitI_SWITCH_KIND_3ddfb26_to_01a3d6b.patch` | 各轮增量 patch（Commit I 已在干净基线 `3ddfb26` 通过 `git apply --check`） |
| `Zhangxinchuang-public-v0.3.9.0.apk` | Commit I 构建（SHA-256 `88dbb137…988a`），v1/v2/v3 签名 Verified |

提交链（全部 fast-forward，无 force push）：
C=`afd3a3e` → D=`75dd4de` → E=`2e651a3` → F=`80dcf80` → G=`8619e2c` → H=`3ddfb26` → **I=`01a3d6b`（远端 HEAD）**

---

## 追加（Commit J = `5f171e0`，本轮：门禁监测/训诫语气/后台自主）

| 文件 | 说明 |
|---|---|
| `BUG_RECEIPT_GATE_DISC_BACKEND.md` | 应用门禁写入即监测 / 训诫语气升级 / 后台自主运行 回执（含根因） |
| `RETEST_GATE_DISC_BACKEND.md` | 真机复测清单（A 门禁/B 训诫/C 后台/D 清理） |
| `patches/CommitJ_GATE_DISC_BACKEND_01a3d6b_to_5f171e0.patch` | 增量 patch（基线 `01a3d6b` 通过 `git apply --check`） |

提交链（全部 fast-forward，无 force push）：
C=`afd3a3e` → D=`75dd4de` → E=`2e651a3` → F=`80dcf80` → G=`8619e2c` → H=`3ddfb26` → I=`01a3d6b` → **J=`5f171e0`（远端 HEAD）**

---

## 追加（Commit K = `42f5a7f`，本轮：分心误弹/天气定制/训诫严厉/上课静默/弹窗悬浮）
| 文件 | 说明 |
|---|---|
| `BUG_RECEIPT_DISTRACT_WEATHER_DISC_CLASS.md` | 分心误弹/天气定制/训诫严厉/上课静默/弹窗悬浮 回执（含根因） |
| `RETEST_DISTRACT_WEATHER_DISC_CLASS.md` | 真机复测清单（A 分心/B 天气/C 训诫/D 悬浮/E 落盘/F 课前/G 上课） |
| `patches/CommitK_DISTRACT_WEATHER_DISC_CLASS_5f171e0_to_42f5a7f.patch` | 增量 patch（基线 `5f171e0` 通过 `git apply --check`） |

提交链（全部 fast-forward，无 force push）：
C=`afd3a3e` → D=`75dd4de` → E=`2e651a3` → F=`80dcf80` → G=`8619e2c` → H=`3ddfb26` → I=`01a3d6b` → J=`5f171e0` → **K=`42f5a7f`（远端 HEAD）**

---

## 追加（Commit L = `a34dd6f`，本轮：弹窗判定/闹钟意图/记忆落盘/天气区县/训诫语气/写入门控）
| 文件 | 说明 |
|---|---|
| `BUG_RECEIPT_POPUP_ALARM_MEMORY_WEATHER_DISC.md` | 9 项诉求逐条回执（含根因） |
| `RETEST_POPUP_ALARM_MEMORY_WEATHER_DISC.md` | 真机复测清单（A–G） |
| `patches/CommitL_POPUP_ALARM_MEMORY_WEATHER_DISC_42f5a7f_to_a34dd6f.patch` | 增量 patch（基线 `42f5a7f` 通过 `git apply --check`） |

提交链（全部 fast-forward，无 force push）：
…→ K=`42f5a7f` → **L=`a34dd6f`（远端 HEAD）**
