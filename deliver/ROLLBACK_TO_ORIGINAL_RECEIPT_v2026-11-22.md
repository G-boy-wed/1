# 回滚到原版掌心窗 · 交付回执 v2026-11-22

## 需求确认（三项边界，用户已逐项确认）

| 项 | 确认结果 |
|---|---|
| 原版基准 | 恢复「小手机 UI 出现之前」的掌心窗：MainActivity 启动，含陪伴 / 守护 / 生活 / 设置 / 日记本完整功能（v0.3.9.0 修改版形态） |
| MCP 删除范围 | 删除所有和桌面角标相关的 MCP，保留掌心窗核心 MCP 接入与视频互动相关工具 |

## 一、Android 端改动

### 1. 启动入口恢复原版
- `AndroidManifest.xml`：删除 `.PalmDeskActivity` 整个 activity 声明，把 `MAIN/LAUNCHER` intent-filter 移回 `.MainActivity`。
- APK 验证：`aapt dump badging` → `launchable-activity: name='dev.linjian.peek.MainActivity'`；`classes.dex` 中 `PalmDeskActivity` 字符串 0 命中。

### 2. 保留原版 MainActivity 完整功能
- 当前 MainActivity（3590 行）本就是原版 3519 行 + 后续 bug 修复（天气定位等），未破坏。
- 移除桌面第四页角标专用的 `onNewIntent()` / `applyLaunchIntent()` 直达逻辑，恢复原版纯净。
- 保留后续有效 bug 修复：天气新鲜定位 `NowState.refreshLocation`、区县缓存 key 修复、明确错误返回。

### 4. 彻底删除小手机桌面角标 UI 专属类（11 个）
`PalmDeskActivity.java`、`FreeDeskLayout.java`、`DeskFeatureStore.java`、
`DeskFeatureToggleStore.java`、`DeskLayoutStore.java`、`DeskSkinStore.java`、
`DeskAssetStore.java`、`DeskIconPack.java`、`DeskCompanionCards.java`、
`OldEntryStore.java`、`DeskCommand.java`。
- 全局检查：保留文件对这些类的引用 0 命中；Android java / Manifest 中 `desk_` / `PalmDesk` 残留 0 命中。
- 图片资源 `ic_badge_*` 等仍被原版 FocusLock/Lock/Guidian/MainActivity 引用，保留不删（避免破坏原版功能）。

### 5. CompanionService 清理

## 二、MCP 端改动

| 文件 | 改动 |
|---|---|
| `mcp_tools.json` | 删除 4 个桌面角标工具，工具数 **133 → 129** |
| `mcp_engine.py` | 删除工具映射 4 项 + `_t_set_desk_badge` / `_t_set_desk_toggle` / `_t_get_desk_state` / `_t_reset_desk` 四个函数 |
| `linjian_server.py` | 删除 `desk_state()` 方法 |

- 删除的 4 工具：`set_desk_badge`、`set_desk_toggle`、`get_desk_state`、`reset_desk`。
- server 全局 `desk_badge` / `desk_toggle` / `desk_state` / `set_desk` / `desk_set_` 等残留 0 命中。

## 三、验证结果

| 检查项 | 结果 |
|---|---|
| APK 编译 | 通过 |
| 签名 | v1 / v2 / v3 全部 Verified: true |
| Android desk / PalmDesk 残留 | 0 |
| Server desk 残留 | 0 |
| MCP schema 校验 | 0 问题 |
| `python3 -m py_compile` | mcp_engine.py / linjian_server.py 通过 |
| Launcher | dev.linjian.peek.MainActivity |
| APK SHA-256 | `872c095f15d852cabf4b06eb8c2a763c21df4811f621acb0ee3df73a23df95f6` |

## 四、交付物

- APK：`android/Zhangxinchuang-public-v0.3.9.0.apk`
- `update.json`：sha256 与 changelog 已同步为本次回滚版本。
- 下载：https://github.com/G-boy-wed/1/releases/download/v0.3.9.0/Zhangxinchuang-public-v0.3.9.0.apk
