# 手机端交互闭环修复回执（Commit G = `8619e2c`）

**基线**：`80dcf80`（Commit F 之后）→ **`8619e2c`**，3 文件 +253/−6，已推送 GitHub `main`（fast-forward）。

## 逐条回执

### 1. 守护界面内所有标签点开无保存选项 ✅ 已改
- **根因**：`PolicySettingsActivity.buildSectionBanner()`（单分区模式导航条）只有「全部设置」「返回守护」两个按钮；而带「保存设置」的「全局」卡在单分区模式下被 `activeSection` 过滤不渲染 → 从守护页任一标签进入的设置页**整页没有保存按钮**。
- **改动**：`buildSectionBanner()` 新增「保存设置」按钮，行为与全局卡完全一致（`PolicyStore.flush` 同步落盘 → `PolicyScheduler.start` 幂等重启调度器 → `syncDisciplineToServer` 训诫同步服务端 → Toast 确认）。
- **验收**：守护页 → 任一标签（如「查岗拉回」）→ 顶部导航条出现「保存设置」→ 改一个数值 → 点保存 → 提示「设置已保存并生效」→ 杀掉 App 重开，数值仍在。

### 2. 课表作息没办法删除已有信息和更改 ✅ 已改
- **根因**：`PolicySettingsActivity` schedule 卡只有「录入/导入示例/查看」，`ClassScheduleState.removeEntry/clearAll` 后端能力齐全但**没有任何 UI 入口**；「查看」只是只读文本弹窗。
- **改动**：
  - `ClassScheduleState.clearAll()` 新增（同步 commit 落盘 + 调试记录）；
  - `buildScheduleList()` 全量列出课程，每条带「修改」「删除」；
  - `editClassEntryDialog(original)` 录入与修改共用对话框（修改时预填原值）；保存时**先按旧键删除再写入新值**，避免改时间/科目后旧条目残留；
  - `confirmClearSchedule()`「清空课表」带确认与条数提示。
- **验收**：守护页 → 课表作息 → 列表每条点「删除」即消失（带确认）；点「修改」弹预填对话框，改时间后保存，列表只剩新的一条；「清空课表」后列表为空。

### 3. 日历写入后无法进行删除更改取消 ✅ 已改
- **根因**：编辑/删除功能其实存在，但**藏在「点中具体某天」的选中详情卡里**——用户点「＋」写入后，若不知道要点日历格子，就找不到任何修改/删除入口；已过去的日子还要翻月才能找到。
- **改动**：`MainActivity.buildGuardianCalendarDetailPage()` 日历详情页底部新增常驻**「全部日子管理」面板**：`fillAllEventsList()` 全量列出自定义日子（标题/日期/农历标记/分组/每年重复/备注），每条带「修改」「删除」；列表随 `updateGuardianCalendarView()` 同源刷新，保存/删除后月历与列表同时更新。原选中日期卡的编辑/删除保留。
- **验收**：守护页 → 守护日历 → 添加一个日子 → 底部「全部日子管理」立即出现该条 → 点「修改」改标题保存，月历与列表同步更新；点「删除」确认后从两处消失。

### 4. 手机端共同记忆无法查看和写入和更改 ✅ 已改
- **根因**：写入路径存在（新增+保存），但「查看」只是 `pretty()` 只读文本弹窗（**仅前 20 条**），无编辑/删除入口 → 实际体验为"能写不能管"。
- **改动**：memory 卡新增 `buildMemoryList()` 全量列出记忆（作者+内容+时间），每条带「修改」（弹预填输入框 → `SharedMemoryState.edit`）与「删除」（确认 → `SharedMemoryState.remove`）；空态给出写入引导。
- **验收**：守护页 → 共同记忆 → 新增并保存一条 → 列表立即出现；点「修改」改内容保存，立即生效；点「删除」确认后消失；重启 App 后仍一致（SharedPreferences 持久化）。

## 验证记录
- Android 完整 `build.sh` 通过，APK v1/v2/v3 签名全部 Verified。
- patch：`CommitG_INTERACT_80dcf80_to_8619e2c.patch` 在干净基线 `git apply --check` OK。
- 状态类（ClassScheduleState/SharedMemoryState/CalendarState）的 CRUD 后端在上一轮已存在且经命令链路验证，本轮只补 UI 闭环，未改数据存储格式。

## 如实说明
- 日历「内置节日」（元旦/春节/七夕/中秋/元宵）不可编辑删除——属系统内置，仍显示在月历；如需可隐藏/自定义内置节日，请明确需求后我再加。
- 共同记忆与课表只存本机（与此前架构一致）；「双向实时生效」指 MCP/AI 命令链路写入同一份本机存储，不经服务器中转。
