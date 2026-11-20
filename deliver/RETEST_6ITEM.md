# 六项 Bug 清单 · 复测清单（真机/真环境）

**版本**：`main` @ `2e651a3`（Commit E，含此前 A/B/C/D 全部修复）
**安装包**：`android/Zhangxinchuang-public-v0.3.9.0.apk`（本轮重新编译签名，v1/v2/v3 Verified）
**前置**：服务端 `server/linjian_server.py` 与 MCP `mcp/server.js` 均更新到本版并重启。

## 1 MCP 可用性（P1）
- [ ] **1a** 不设环境变量，在 mcp 目录放 `.env`（LINJIAN_URL/LINJIAN_TOKEN），重启 MCP：`linjian_status` 的 `config_errors` 为空、`env_file_fallback_used=true`
- [ ] **1b** 故意清空 .env 与环境变量重启：`fix_hint` 给出 4 条具体修复步骤（不是只报 Missing）
- [ ] **1c** 用正确配置后，get_window_whisper / get_companion_actions / get_tour_schedule / get_emotion_snapshot 全部能读回数据

## 2 共同窗语（P2）
- [ ] **2a** 全新库调 `get_window_whisper`：返回默认文案 + `is_default=true` + 说明 note
- [ ] **2b** `set_window_whisper` 写入后再读：内容更新、`is_default=false`
- [ ] **2c** 填错 Token 调用：返回 `ok:false + comapion_state_fetch_failed`（不再是"空窗语+ok:true"）

## 3 行动记录（P3）
- [ ] **3a** 执行一次 set_alarm 或查天气后 `get_companion_actions`：出现该记录
- [ ] **3b** 制造大量 App 切换（>500 条事件）后回读：行动记录不再被挤出
- [ ] **3c** 全新空库调用：返回空列表 + `empty_reason` 解释（而非静默空）

## 4 查岗配置（P2）
- [ ] **4a** 服务端先设置 `force_back_interval_minutes=30`、`alert_tone=strict`
- [ ] **4b** 手机端查岗设置页改任意参数并点「保存设置」
- [ ] **4c** `get_tour_schedule` 回读：30/strict 保留（不被 0/gentle 覆盖），手机改的间隔/冷却/时段已同步

## 5 情绪 Tick（P3，验证 75dd4de 修复）
- [ ] **5a** 断网 ≥1 分钟后恢复：数秒内 `get_emotion_snapshot` 返回新状态
- [ ] **5b** DebugState 出现「网络恢复：立即重算情绪并强制上报」

## 6 设置页保存（P0）
- [ ] **6a** 主动提醒规则抽屉：改阈值/开关 → 点「保存提醒规则」→ 杀进程重启 → 配置仍在
- [ ] **6b** 周期提醒抽屉：改日期/天数 → 点「保存周期提醒」→ 重启 → 仍在
- [ ] **6c** 回家模式抽屉：改目标/阈值 → 点「保存回家模式」→ 重启 → 仍在
- [ ] **6d** 连接设置抽屉：改地址/Token/称呼 → 点「保存连接设置」→ 重启 → 连接正常
- [ ] **6e** 权限与运行抽屉：切横幅开关 → 点「保存权限设置」→ 重启 → 状态一致
- [ ] **6f** 应用门禁总开关：仅点开关（不点按钮）→ 重启 → 状态仍在（点选即存）
- [ ] **6g** 高级调试抽屉：改城市/天气备注 → 点「保存调试字段」→ 重启 → 仍在
- [ ] **6h** 回归：不点保存直接退出提醒抽屉，重进页面 → 显示的是**上次已保存**的值（草稿不误存）

## 回归（防引入新问题）
- [ ] 归电设置/守护日历/天气/包名管理/主题等原合规页面保存行为不变
- [ ] PolicySettingsActivity「保存设置」仍正常：本机生效 + 服务端同步成功
- [ ] 前四轮修复抽查 3–5 条无回退（如农历闰月、锁期满即时解除）
