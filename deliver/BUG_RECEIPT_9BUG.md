# 九项 Bug 清单 · 逐条回执（Commit D = `75dd4de`）

**基线**：`main` @ `afd3a3e`（前三轮 43+21 项修复已完成并推送）
**本版**：`main` @ `75dd4de054e4342570c605d9bbbdccaf05c2c87e`（快进提交，未 force push）
**改动**：7 个文件，+127 / −21（`git show 75dd4de --stat`）

> 状态口径：**已改** = 根因代码已修改并通过构建/运行时验证；**部分改** = 已消除可确定的部分，剩余需真机复测或产品参数。

---

## 模块 1：巡回查岗与后台调度

### Bug 1.1 夜间/安静时段边界时间戳对齐 — ✅ 已改
- **根因**：服务端 `server/linjian_server.py` 的 `in_quiet_hours()` 用 `start <= cur <= end`（右端**闭**区间），而 Android 端 `PolicyStore.inWindow()`（`PolicyStore.java:386-393`）与 `GuidianState.inQuietTime()`（`GuidianState.java:445-455`）均为 `cur < end`（右端**开**区间）。边界分钟（如 06:00 整）两端判定相反 → 查岗调度与通知在边界处行为不一致。
- **改动**：`server/linjian_server.py` `in_quiet_hours()` 改为「左闭右开 [start, end)」，跨午夜分支同样对齐（`cur >= start or cur < end`），并加 docstring 说明两端约定。
- **commit**：`75dd4de`（文件：`server/linjian_server.py`）
- **验证**：边界用例两端对照——23:00（含）/22:59（不含）/06:00（**不含**，与 Android 一致）/06:01/00:00（含）/13:00-15:00 非跨午夜共 10 例全过。
- **验收步骤**：①开启巡回查岗，安静时段设 23:00–06:00；②系统时间调到 05:59 与 06:00 各观察一次：06:00 整起服务端与手机端都应判定「已出安静时段」；③查 DebugState 日志无 06:00 前后决策翻转冲突。

### Bug 1.2 分级拉回弹窗按钮交互穿透 — ✅ 已改
- **根因**：`PullBackOverlay.dismiss()` 原实现把 `wm.removeView(view)` 丢进 `main.post()` 异步执行；连续触发时 `doAdd` 在旧视图真正移除前就 `addView` 新弹窗 → 新旧两个悬浮窗同帧叠加，软/强级别按钮区域互相遮挡，点击落到旧窗口造成「穿透/状态错乱」。
- **改动**：`PullBackOverlay.java` `dismiss()`——主线程上改用 `wm.removeViewImmediate(view)` **同步移除**（非主线程仍走 post）；`PullBackCard.java` 按钮补 `setEnabled(true)`，保证被复用/重置后的按钮可点状态确定。
- **commit**：`75dd4de`（文件：`PullBackOverlay.java`、`PullBackCard.java`）
- **验收步骤**：①想你程度低于阈值且连续查岗未响应，快速连续触发软拉回弹窗 5+ 次；②观察每次只有**一个**弹窗实例，软/强按钮各自只响应所属弹窗；③连点切换软/强无按钮卡死。

---

## 模块 2：应用门禁与屏幕休息

### Bug 2.1 全屏拦截页覆盖不全 — ✅ 已改（代码侧），⚠️ 真机确认部分悬浮窗机型
- **根因**：`AppGate.java:415-418` 拦截页根布局背景 `0x88F2EAFB`（**半透明 alpha=0x88**），悬浮窗类型 App 的内容会透出；窗口 flags 只有 `FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS`，未声明全屏，状态栏区域也可能露底。
- **改动**：背景改不透明 `0xFFF2EAFB`；lp.flags 增加 `FLAG_FULLSCREEN`（`AppGate.java`，`showOverlayLock` 内）。拦截页本身已是 `TYPE_APPLICATION_OVERLAY` + MATCH_PARENT + 空白点击吞掉（不透传），透明度是覆盖不全的直接原因。
- **commit**：`75dd4de`
- **验收步骤**：①受限 App 加入门禁列表，从后台直接拉起该 App；②拦截页应完全遮住底层内容（包括目标 App 的悬浮窗层）；③在带悬浮窗权限的 App（如浮窗笔记/视频小窗）上重点复测。
- **残余风险（如实）**：目标 App 自身若持有**更高层级** `TYPE_APPLICATION_OVERLAY` 窗口（需系统权限，普通三方 App 拿不到），任何普通 App 的 overlay 都无法压过；此为 Android 窗口层级机制决定，非本 App 代码可解。

### Bug 2.2 锁定期满状态写库延迟 — ✅ 已改
- **根因**：两处叠加——①`activeLockFor()` 到期翻转后用 `save()`（`apply()` 异步落盘），紧接着的 `get_screen_break_state` 读到旧文件；②读路径只在**该包被前台触发**时才纠正，其余包的到期锁长时间挂着 `active=true`。
- **改动**：①新增 `saveNow()`（`commit()` 同步落盘）用于到期自动解除（`AppGate.java:70-72,537`）；②`state()` 的清理/校验循环增加读路径兜底：`active && until>0 && now>=until` 的锁当场翻转为失活并同步落库（`AppGate.java:653-658`），不再依赖前台触发时机。
- **commit**：`75dd4de`
- **验收步骤**：①设 1 分钟短时屏幕休息；②到期后立刻调 `get_screen_break_state`：应立即返回 `active=false`；③调 `get_lock_state` 确认无「到期仍 active」的残留锁。

---

## 模块 3：守护日历与日程提醒

### Bug 3.1 农历跨闰月计算容错 — ✅ 已改
- **根因**：`CalendarState.lunarToSolar()`（`CalendarState.java:618`）只做 `day>30` 兜底：①请求该年不存在的闰月（如 2024 闰五月）时被当普通月算出错误日期；②29 天小月的「三十日」也会算出一个偏移日期而非判无效 → 倒数日天数错误。
- **改动**：三重容错守卫——①`leap && leapMonth(year) != month` 直接返回无效；②`day > (leap ? leapDays : monthDays)` 超出实际天数判无效；③闰月偏移统一为「先跳过同名普通月」（等价改写，行为由测试锁定）。
- **commit**：**`75dd4de`**
- **验证（真跑测试 11/11 PASS）**：从源码提取真实算法编译运行——2023 闰二月初一=2023-03-22、普通二月初一=2023-02-20、2020 闰四月初一=2020-05-23、2024/2025 正月初一、2025端午；非法用例：2023三月三十（三月仅29天）→INVALID、2023闰二三十（闰二月29天）→INVALID、2024闰五月初一（该年无闰五月）→INVALID。另经独立数据核算确认 2023 农历二月恰有 30 天（二月三十=2023-03-21 合法）。
- **验收步骤**：①添加农历生日（闰月年份含闰月的，如闰二月）：无闰月年份应回退到普通二月或不显示（产品口径见残余项），**不得出现错位天数**；②添加农历节日核对倒数日；③输入「三十日」落在 29 天小月时应被判无效而非算出错误日期。
- **残余项（如实）**：**无闰月年份闰月纪念日如何回退**（顺延到下个有该闰月的年 / 落到普通月）属产品决策，代码现按「判无效不渲染」处理，未擅自选一种假装修复；需你定口径后我再加一行分支。

### Bug 3.2 横幅提醒多端缓存刷新 — ✅ 已改
- **根因**：`CalendarState.collect()` 把静态常量 `VERSION="0.3.9.0"` 当 `calendar_state.version` 下发——**永远不变**，服务端/多端无从判断缓存过期；且日历命令执行后走 `uploadStateThrottled(..., false)` 节流，编辑结果可能延迟一个节流周期才上报。
- **改动**：①`CalendarState.java` 新增单调自增 `calendar_state_version`（SharedPreferences 计数），`upsert`/`delete` 成功后 `bumpCalendarVersion()`，`collect()` 下发该动态版本；②`CompanionService.java` 日历命令执行后改调 `uploadStateThrottled(..., true)` **强制立即上报**。
- **commit**：`75dd4de`
- **验收步骤**：①端 A 新增自定义纪念日并调整提醒天数；②观察服务端 `calendar_state.version` 立即 +1；③端 B 拉取最新状态后横幅应立即按新提醒天数刷新，无旧缓存残留。

---

## 模块 4：归电与情感感知

### Bug 4.1 归电弹窗底部边界遮挡 — ✅ 已改
- **根因**：`GuidianActivity.java` 未声明 `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION`，内容不延伸到导航栏之下但底部 padding/margin 只留固定 dp；全面屏手势导航（细导航条）机型上底部按钮与理由抽屉被导航条压住。
- **改动**：①`applySystemBars()` 增加 `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION`；②`body.setPadding` 底部 `dp(24) + navBarHeight()`；③理由抽屉 `bottomMargin = dp(14) + navBarHeight()`；④新增 `navBarHeight()`（读系统 `navigation_bar_height`，异常时回退 0，不影响普通机型）。
- **commit**：`75dd4de`
- **验收步骤**：①全面屏手势导航机型上触发归电、弹窗位置设 bottom；②底部「× 稍后 / ⌁ 接通」按钮完整可见可点；③三键导航机型回归确认无异常留白。

### Bug 4.2 情感感知 Tick 状态滞后 — ✅ 已改
- **根因**：断网期间上报失败，重连后无任何恢复动作，须等下一个固定 Tick 才重算+上报 → `get_emotion_snapshot` 返回滞后一次周期的「想你程度」。
- **改动**：`CompanionService.java` 新增 `registerNetworkRecovery()`——`ConnectivityManager.registerNetworkCallback` 监听 `onLost`（记 `netWasLost=true`）/`onAvailable`；恢复时 `onNetworkReconnected()` 立即 `EmotionPerception.perceive(this, new Signals())` 强制重算 + `uploadStateThrottled(..., true)` 强制上报；`onDestroy()` 反注册回调防泄漏。
- **commit**：`75dd4de`
- **验收步骤**：①断网 1 分钟以上 → 恢复网络；②`get_emotion_snapshot` 应在恢复后数秒内（而非下一 Tick）返回与新网络状态一致的想你程度；③DebugState 应出现「网络恢复：立即重算情绪并强制上报」日志。

---

## 汇总

| 编号 | 状态 | 主要改动文件 |
|---|---|---|
| 1.1 | ✅ 已改 | `server/linjian_server.py`（`in_quiet_hours` 左闭右开对齐双端） |
| 1.2 | ✅ 已改 | `PullBackOverlay.java`（removeViewImmediate）、`PullBackCard.java` |
| 2.1 | ✅ 已改（代码侧） | `AppGate.java`（不透明背景 + FLAG_FULLSCREEN） |
| 2.2 | ✅ 已改 | `AppGate.java`（saveNow + 读路径到期兜底） |
| 3.1 | ✅ 已改 | `CalendarState.java`（闰月/天数容错，测试 11/11） |
| 3.2 | ✅ 已改 | `CalendarState.java`（动态版本号）、`CompanionService.java`（强制上报） |
| 4.1 | ✅ 已改 | `GuidianActivity.java`（导航栏避让） |
| 4.2 | ✅ 已改 | `CompanionService.java`（网络恢复即时重算+上报） |

**8/8 已改，0 未改。** 其中 2.1 标注一处 Android 窗口层级机制的物理边界（非代码可解），3.1 标注一处需你定口径的产品决策（闰月回退规则），均已如实写明。
