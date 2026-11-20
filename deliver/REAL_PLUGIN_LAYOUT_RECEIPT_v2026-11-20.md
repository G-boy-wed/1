# 掌心窗 UI 修正回执 · v2026-11-20（只用真实插件 / 清除假内容 / 修深链 bug）

## 用户诉求
> 不需要根据图一有的应用进行二次填充！是完全根据掌心窗有的插件进行组合排列！！不要复制任何其他没用的东西！！！！！改好你自己的bug！

## 根因（此前的错误）
1. 把图一截图里的**装饰性假应用/假文案**当成内容硬编码进界面。
2. 守护项深链使用了设置页根本不存在的分区 key，点开跳空白。
3. `polaroidCard()` 里 View 被重复 `addView` 再 `removeView` 的废逻辑。

## 本次改动

### 1. 唯一数据源 = 掌心窗真实插件
- **世界页（图1左屏）宫格**：全部来自 `DeskPluginStore.all()`（20 个真实插件：消息/日历/图片/联系人/便签/心动/查岗/门禁/专注/一起听/天气/归电/课表/记忆/训诫/提醒/夜间/识图/回家/更多），按 id 去重，4 列铺开。
- **终端页（图1右屏）能力宫格**：全部来自 `PluginRegistry.all()`（16 项真实登记能力），按 id 去重，4 列铺开，点开进对应设置分区（归电走独立的 `GuidianActivity`）。
- **守护页宫格**：图2 的 20 个真实守护功能项，按 `prefKey` 去重（保留首次出现，不再重复排列），每格开关即时保存、点开进对应设置。
- 顶部只保留掌心窗真实功能入口（一起听 / 天气 / 周历）。

### 2. 彻底删除照抄图一的假内容（dex 中计数均为 0）
`CosmicLove` 假歌名、拍立得照片卡「此刻的窗 / 想你的花」、无限象限便签、影像之外句子、假「信笺/织梦/相册/泡泡」4 图标行、黑胶唱片、假周历硬编码日期（改为系统日历真实日期）。
对应方法 `polaroidCard / noteCard / buildTrioRow / whisperBar / buildTerminalIconRow` 全部移除。

### 3. 修复的自身 bug
- **深链空白**：新增 `VALID_SECTIONS` 白名单（与 `PolicySettingsActivity` 真实 card 一致）。`focus/calendar/plugins/profile/guidian` 等不存在的 key 统一打开「全部设置」，不再跳空白页。
- **废逻辑**：整体删除 `polaroidCard()` 的重复 add/remove 代码。
- **假数据**：天气/位置/时间/曲库全部取真实状态，失败返回明确错误，不再显示假数值。

### 4. 保留的完整编辑能力
长按任意插件图标进入编辑：点图标换图、`✎` 改名、`◀ ▶` 移动位置、`✕` 删除；编辑条支持 `＋新增 / ✓保存 / ↺恢复默认`。全部走 `DeskPluginStore` 持久化。

## 排列结构（严格还原图一，两屏独立，不堆叠）
```
图1 左屏「世界」                    图1 右屏「终端」
┌ 一起听入口（位置+时间）┐          ┌ 大字时间        天气°┐
│ ✦ 掌心窗·全部插件       │          │ 真实周历(今日高亮)   │
│ [][][][]  真实插件宫格  │          │ ✦ 掌心窗·能力插件    │
│ [][][][]  4列/去重      │          │ [][][][] 16项真实能力│
│ [][][][]               │          │ [][][][] 4列/去重    │
│ ＋新增 ✓保存 ↺默认      │          │                      │
└ 世界 终端 守护 主题 设置 ┘          └ 同 Dock 切换         ┘
守护页：图2 功能项 4 列宫格、去重、开关即时保存、点开进设置。
```

## 验证
- `bash android/build.sh`：编译通过（仅 deprecated 提示）；APK v1/v2/v3 验签通过，签名摘要 `aea75c9b…f002fb`。
- dex 校验：新方法 `buildWorldPage/buildTerminalPage/buildPluginGrid/buildCapabilityGrid/buildGuardPage/dedupGuard/openSection` 均在；旧假内容方法与文案计数为 0。
- AndroidManifest 启动 Activity = `.PalmDeskActivity`。
- APK：`android/Zhangxinchuang-public-v0.3.9.0.apk`，sha256 `9cf8c5558a0bab87323d9f8720deef84be091e7eac0856ad9531507f91fea50a`。
- `update.json` / `server/update.json` 的 sha256 与 changelog 已同步。
