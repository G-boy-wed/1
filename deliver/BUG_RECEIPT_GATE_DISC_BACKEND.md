# 应用门禁监测 / 训诫语气 / 后台自主 修复回执（Commit J = `5f171e0`）

> 基线：`01a3d6b`（Commit I，远端 HEAD）→ 本提交 `5f171e0`，fast-forward 推送 `main`。
> 真编译通过，APK v1/v2/v3 签名 Verified（SHA-256 `75c4eaee…c4da6f`）。
> patch：`patches/CommitJ_GATE_DISC_BACKEND_01a3d6b_to_5f171e0.patch`（已在干净基线 `01a3d6b` 通过 `git apply --check`）。

## 一、应用门禁「写入不自动监测」——根因与修复

**根因（为什么写入不自动监测）：**
旧实现里 `AppGate.onForegroundPackage()` 只由 `ScreenshotService` 的「前台包变化」无障碍事件驱动，
**没有周期性轮询**。因此：
1. 写入锁定/受限列表后，若用户此刻**已在受限 App 上**，只有 `lock_app` 后 200ms 的 `triggerCurrentForegroundIfNeeded`
   这一个极短窗口会检查一次；错过即不再拦截；
2. 无障碍事件被系统节流/丢弃、或后台 Doze 期间不回调时，门禁**永远等不到下一次前台切换**才触发；
3. 调度器每个 tick（20s）明明已经 `ScreenshotService.currentPackage()` 拿到了当前前台包，却从没把它交给门禁检查。

**修复：**
- `PolicyScheduler.evaluate()` 末尾新增 `AppGate.onForegroundPackage(appCtx, current)`——调度器每 tick 主动把当前前台 App 喂给门禁检查，
  实现「写入即监测」，不再仅依赖无障碍前台事件。已在显示锁定界面时去重，不会每 20 秒重新闪一次。
- `AppGate.onForegroundPackage()` 增加去重守卫：若全屏锁定页（`isLockActivityVisibleFor`）或悬浮遮罩（`overlayView` 且包名一致）已在显示，
  仅刷新时间戳维持「正在拦截」状态，不再重复弹。
- `AppGate` 新增 `visibleLockOverlayPackage` 跟踪悬浮遮罩目标包，配合 `removeOverlay()` / `showOverlayLock()` 维护。

## 二、训诫模块不改变语气——根因与修复

**根因（为什么语气不变严厉）：**
- `CompanionEmotionState.tone()` 直接 `return EmotionPerception.activeTone(ctx)`，该值只由情绪强度决定，
  **完全不读训诫模式**；而弹窗的 harsh 红色样式由 `tone` 字段驱动（`accentFor(tone)→danger`）。
  所以即便训诫开了「严厉」，弹窗语气仍是温柔，视觉上毫无变化。
- 文案侧虽在 `message()` 里通过 `withDisciplineTone` 加了后缀，但 `messageForDisplay()`（界面与给服务端的镜像）未套用。

**修复：**
- `CompanionEmotionState.tone()`：训诫开启且为 `strict` 时，把温柔语气强制升级为 `strict`（渲染为 danger 色 + 更硬话术）；强情绪/angry 时保持。
- `messageForDisplay()`：即使关闭情绪驱动文案、或在各情绪分支，也统一经 `withDisciplineTone` 包裹，
  让界面与推送给服务端镜像的「人设语气」带上管束意味（服务端据此组织陪伴话术）。
- `GuidianTheme.accentFor(tone)`：新增对 `strict` 的识别，与 `angry` 一样映射到 `danger` 强调色，弹窗视觉语气变硬。

## 三、后台启动后不自主跑代码 / 微小 bug

**根因与修复：**
- 门禁检查此前只在无障碍/服务宿主拉起时被动触发，后台自循环里缺这一环。现已并入调度器每 tick（见第一节），
  后台启动（开机/应用更新自启 `BootCompletedReceiver` → 调度器 + 看门狗 `SchedulerWatchdogReceiver` 每 15 分钟兜底拉活）后，
  门禁与使用时长自动锁等「代码」会自主持续运行，不依赖用户手动切前台。
- 微小 bug：悬浮遮罩重复弹/不跟踪目标包的问题已随 `visibleLockOverlayPackage` 与去重守卫一并修复。

## 四、遗留垃圾文件清理
- 删除 `deliver/changed_files/`（早期整份 android/mcp/server 源码副本，540K 冗余垃圾）。
- 删除 `deliver/Zhangxinchuang-public-v0.3.9.0.apk`（与 `android/` 下 APK 完全重复的副本）。

## 五、失败点（如实）
- 后台前台包检测仍依赖无障碍服务已开启（Android 安全限制，无法自动开无障碍）。调度器每 tick 现已主动监测，
  无障碍事件被节流/丢失时也能兜底；但若无障碍从未开启，仍无法获取前台包——此为系统限制，非代码 bug。
- 服务端「人设聊天语气」由 `PolicySync` 推过去的 `discipline.mode` 驱动（已在 `PolicySync` 推送 `mode` 字段）；
  本次在客户端把弹窗语气 + 镜像文案的「严厉」链路补全，服务端消费该字段即可生效，本次未改动服务端逻辑。
