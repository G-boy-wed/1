# 手机端 UI 显示 · 全量改动审查与完善（Commit F）

**范围**：`ecdc938..HEAD` 五轮全部 Android 端改动（13 文件 +519/−63）逐一过 UI 视角审查；发现问题即修，审查自洽的如实列出。

## 一、发现并已修复的 UI 问题

### F-1 六个新「保存」按钮被全局样式覆盖成次级样式 — ✅ 已修（`MainActivity.java`）
- **问题**：Commit E 在 6 个抽屉新增的显式保存按钮（保存提醒规则/周期提醒/回家模式/连接设置/权限设置/调试字段）虽然在 XML 里声明了主色 pill 背景 + 白字，但 `applyVisualTheme → styleTree → styleButton()` 会把**所有** Button 统一改成次级 chip 样式（`t.text` 文字色 + `t.chip(false)` 柔和背景）→ 保存按钮丢失主色观感，与 `保存归电设置`、`启动服务` 等主按钮不一致。
- **修复**：`applyVisualTheme()` 新增 `stylePrimaryPill(...)` 对 6 个按钮做主色覆盖（`t.pill(true)` 主色背景 + 白字 + 12sp 中量字重），与既有主按钮完全同规格，且**六套主题全部自适应**（pill(true) 取当前主题主色）。

## 二、逐面审查结论（无需改动的如实列出）

| 改动面 | 来源 | 审查结论 |
|---|---|---|
| 归电页导航栏避让（`GuidianActivity`） | Bug 4.1 | **自洽**。`LAYOUT_HIDE_NAVIGATION` + body 底部 padding/理由抽屉 bottomMargin 均已加 `navBarHeight()`；浅/深主题的导航栏图标色逻辑未动；不涉及状态栏 |
| 锁屏遮罩（`AppGate.showOverlayLock`） | Bug 2.1 | **自洽**。不透明浅紫底 `0xFFF2EAFB` + 卡片浅渐变 + 深色文字（`#2D3E39`/`#596D66`），对比度充足；按钮「回到桌面/查看详情」自绘样式未受主题机制影响 |
| 拉回卡片软/强按钮（`PullBackCard`） | Bug 1.2 | **自洽**。按钮为自绘 TextView（主色实底白字/次色 12% 透明底），`setEnabled(true)` 无视觉回归 |
| 拉回浮窗同步移除（`PullBackOverlay`） | Bug 1.2 | **自洽**。仅移除时机变化（removeViewImmediate），无视觉改动；消除了新旧弹窗叠加这一显示问题本身 |
| 主动提醒/周期/回家/连接/权限抽屉布局（`activity_main.xml`） | Commit E | **自洽**。按钮尺寸/边距与抽屉内既有控件一致（36dp 高、pill_primary），抽屉隐藏时随容器 gone；`styleTree` 的 EditText/CheckBox 处理不受影响 |
| 归电设置抽屉、天气、日历、包名、主题抽屉 | 未改动 | 原本合规，本轮未触碰 |
| 日历横幅模板（`CalendarState` 分组文案） | P2-14 | 走通知渠道展示，无 App 内视图改动 |
| 专注求助 pending 提示（`FocusMode`） | P0-4 | 通知文案，无 App 内视图改动 |

## 三、验证
- 完整 `build.sh` 通过，APK v1/v2/v3 签名 Verified。
- `stylePrimaryPill` 与既有 `saveGuidianSettingsButton` 覆盖逻辑同源（`t.pill(true)`），无新依赖、无新权限。
