# 弹窗互斥 / 后台保活 / MCP 策略链路修复回执（Commit H = `3ddfb26`）

**基线**：`8619e2c`（Commit G 之后）→ **`3ddfb26`**，13 文件 +309/−15，已推送 GitHub `main`（fast-forward）。

## 逐条回执

### 1. 弹窗重复冲突：每次只展示一个 ✅ 已改
- **根因**：悬浮窗有两条互相独立的通道（`PullBackOverlay` 分级拉回 / `AlertCenterOverlay` 居中弹窗），各自持有自己的视图引用，互不知晓——会同时叠在屏幕上；且悬浮窗 `TYPE_APPLICATION_OVERLAY` 层级**高于 Activity**，Activity 兜底通道启动时若已有悬浮窗在屏，会出现「悬浮窗 + Activity」双重展示。
- **改动**：新增 `PopupMutex` 全局弹窗互斥仲裁器（last-write-wins）：
  - 两条悬浮窗通道 `doAdd/show` 前调 `beforeShow(ownKey)`——先移除另一通道的悬浮窗再上屏；
  - 两个 Activity 兜底（`PullBackActivity`/`AlertCenterActivity`）`onCreate` 同样先 `beforeShow` 清掉在屏悬浮窗，`onDestroy` 释放登记；
  - 规则：后到的弹窗替换先到的，任何时刻屏幕上最多一个弹窗。
- **验收**：触发查岗拉回后紧接着触发居中弹窗（或反之）→ 旧弹窗立即消失、只见新弹窗；悬浮窗通道失败走 Activity 兜底时同样只有一个。

### 2. 弹窗显示在正在使用的 APP 上 ✅ 已确认 + 加固
- **现状确认**：两条通道本来就是 `TYPE_APPLICATION_OVERLAY` 悬浮窗优先，天然盖在当前前台 App 之上，不拉起掌心窗（`PullBackActivity.show` 注释与实现一致）。
- **加固**：互斥保证 Activity 兜底只在悬浮窗确实不可用（无权限/添加失败）时启用，不再出现"有权限却走 Activity 抢前台"的路径；锁屏场景保留 `showWhenLocked` 透明 Activity（系统限制，悬浮窗在锁屏之上不可见）。
- **验收**：在任意第三方 App 内触发查岗/情绪弹窗 → 弹窗浮在当前 App 内容上方，前台 App 不切换。

### 3. 巡回查岗 / 训诫 / 时间感知等插件挂后台稳定运行 ✅ 已改
- **根因**：`PolicyScheduler.stop` 由无障碍服务与陪伴服务各自销毁时调用——**任一宿主死亡就连坐停掉调度器**；HandlerThread 在 Doze 深睡/进程回收后停摆无恢复手段；重启/更新后无自启。
- **改动**：
  1. **宿主引用计数**：`start/stop(ctx, host)` 按宿主（accessibility/companion/settings）计数，任一宿主销毁不再连坐停止，全部退出才真正停；
  2. **AlarmManager 看门狗**：新增 `SchedulerWatchdogReceiver`，`setAndAllowWhileIdle` 约 15 分钟拉活一次（幂等），覆盖 Doze/进程回收；看门狗不占用宿主计数，不破坏主动停止；
  3. **开机/更新自启**：新增 `BootCompletedReceiver`（BOOT_COMPLETED + MY_PACKAGE_REPLACED）→ 拉起前台陪伴服务 + 调度器；manifest 补 `RECEIVE_BOOT_COMPLETED` 权限与两个 receiver；
  4. 陪伴服务本身已是 `startForeground` + `START_STICKY`（此前已有，未动）。
- **验收**：重启手机 → 开机后服务与调度器自动运行（通知栏有常驻通知）；在开发者选项里停止任一服务，调度器日志显示"其余宿主仍在，继续运行"；Doze 深睡一晚后查岗仍按间隔触发。

### 4. MCP 按掌心窗内最后写入的管理要求行动 ✅ 已改
- **根因**：保存设置只自动同步训诫配置（`/api/discipline`），巡回配置要手动点「同步给服务器（可选）」才上送；且保存那一刻没网就永久失联——MCP 读到的 `get_discipline_mode`/`get_tour_schedule` 会与手机端最后写入不一致。
- **改动**：新增 `PolicySync` 统一推送器（本机真实配置 → `/api/tour/schedule` + `/api/discipline`，无硬编码假值、best-effort）：
  1. 两处「保存设置」（全局卡 + 单分区导航条）保存即推送；
  2. 陪伴服务启动即对齐一次，运行期每 15 分钟自动对齐（内部节流）——最终一致；
  3. MCP 侧链路已核实：`get_discipline_mode` 读 `/api/discipline`、`get_tour_schedule` 读 `/api/tour/state`，正是推送目标，语气模式/档位/安静时段/阈值/冷却全部按手机端最后写入生效。
- **验收**：手机端改严厉/温柔档位保存 → 无网等 15 分钟或恢复网络后 → MCP `get_discipline_mode` 返回新值；改查岗间隔/安静时段保存 → `get_tour_schedule` 同步。

## 验证记录
- Android 完整 `build.sh` 通过，APK v1/v2/v3 签名全部 Verified。
- patch：`CommitH_POPUP_BG_MCP_8619e2c_to_3ddfb26.patch` 干净基线 `git apply --check` OK。
- MCP 读取端点与推送端点逐一对过（无新增服务端改动，服务端 `/api/discipline`、`/api/tour/schedule` 此前已支持 last-write-wins）。

## 如实说明
- 弹窗互斥是"替换"语义：同一时刻只显示最新的一个弹窗，不做排队；被替换的弹窗视为已跳过（其触发已按原逻辑留痕/写冷却）。
- Activity 兜底在**无悬浮窗权限**时仍会切到掌心窗弹窗——这是没有 overlay 权限时唯一的弹出方式（Android 机制决定）；设置页已有悬浮窗权限引导。
- 看门狗闹钟为 inexact（`setAndAllowWhileIdle`），系统可能合并延迟至多几分钟；对 20 秒粒度的调度评估无实际影响。
- 锁屏时悬浮窗被系统遮挡，拉回弹窗仍走透明 Activity（`showWhenLocked`），与互斥规则协同（Activity 启动前清掉悬浮窗）。
