# 掌心窗 UI 统一回执 · v2026-11-20（图一小手机美化 / 界面去重 / 去旧 UI）

## 用户诉求
> 你不需要多余重复的UI界面，只需要参考小手机的UI美化进行统一更改即可，不需要保留掌心窗原来的UI设计

## 本次改动

### 1. 全界面统一图一小手机美化（唯一视觉语言）
新增 `StarryBg`：柔绿径向渐变 + 星点背景；`glass()`：毛玻璃白 + 白描边圆角卡片；时间用细体大字。
所有页面（世界 / 终端 / 守护 / 一起听）经 `pageFrame()` 套同一背景，主题切换即时生效，没有第二套视觉。

### 2. 界面去重（三屏各司其职，不再三套雷同宫格）
- **世界页（图1左屏）**：一起听入口 + 掌心窗真实插件宫格（DeskPluginStore，可增删改换图标/移动/保存）。
- **终端页（图1右屏）**：大字时间 + 真实天气 + 真实周历 + 「此刻」真实状态卡（位置/天气/守护开启数/时间）——**删掉原来重复的能力宫格**。
- **守护页**：图2 守护功能项宫格，按 prefKey 去重、开关即存、点开进设置。
- **一起听页**：网易云同款，位置/头像/时间。
删除 `buildCapabilityGrid / capabilityCell / capabilityIcon / openCapability`（重复宫格整套）。

### 3. 不保留旧 UI
- 删除 `PAGE_CLASSIC` 及所有跳旧 `MainActivity` 的代码；`classic` 类动作统一收敛到新设置页。
- 守护页移除「打开完整功能与设置」旧 UI 按钮。
- Dock 从 5 项收敛为图一 4 项：`世界 / 终端 / 守护 / 主题`。
- 源码中 `MainActivity` 仅出现在注释，无任何代码引用（已核验）。

### 4. 保留能力
插件宫格完整编辑（换图标/改名/◀▶移动/✕删除/＋新增/✓保存/↺恢复默认）、守护开关持久化、天气坐标优先与错误返回、免费音乐一起听、守护项深链白名单（不存在的分区不再跳空白）。

## 验证
- `bash android/build.sh` 编译通过；APK v1/v2/v3 验签通过（签名摘要 `aea75c9b…f002fb`）。
- dex 校验：统一视觉类 `StarryBg/pageFrame/glass` 与三屏构建方法均在；重复能力宫格、`CosmicLove`、假内容方法计数为 0。
- 启动 Activity = `.PalmDeskActivity`。
- APK：`android/Zhangxinchuang-public-v0.3.9.0.apk`，sha256 `66e4c3a81356dce50d6cbdfe048aad839b5c57c72c765cc8eaad84c9cf527f0b`。
- 两个 `update.json` 的 sha256 与 changelog 已同步。
