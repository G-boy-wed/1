# 掌心窗 · 四页主界面重构 + 跨页移动 交付回执（v2026-11-21）

## 本轮诉求与完成

| 诉求 | 完成 |
|---|---|
| 第四页角标重复，只保留设置+日记本 | OldEntryStore 精简为「设置」「日记本」两项；剔除重复的守护/陪伴/生活/日历/归电入口（这些东西已作为主界面功能角标或陪伴展示卡呈现）。`old:gate/old:guidian` 等已清除。 |
| 窗边/此刻状态/今日轨迹/陪伴倒计日/陪伴者行动 展现在主界面 | 新增 5 张**陪伴展示卡**（KIND_CARD），以贴图形式直接展现：窗边卡放个人页（第1页），其余放动态页（第3页）。数据取自真实状态，非可点开角标。 |
| 应用角标剔除重复无用 | 只剔除第四页重复入口；不做无意义删除，17 个真实功能角标全部保留。 |
| 角标/贴图/展示标志可长按跨页随机摆放 | 新增 DeskLayoutStore 归属页映射 + 编辑态右上角「移动」按钮，所有元素都能移动到任意页，位置尺寸一并迁移。 |
| 修复"第四页加入后原角标不能点开/更改" | 根因：元素按 index 写死分页、页与点击回调强绑定。改为数据驱动归属页 `belongsOn()`，所有页统一遍历全部元素按归属页过滤；点击/编辑回调按 kind/id 统一分发。 |
| 不要 emoji/简笔画 | 清除散落的 ⌖ ✎ ⌕ 符号；搜索列表只显示功能名。角标仍是 ins 风景图。 |

## 陪伴展示卡数据来源（DeskCompanionCards）

- 窗边：`PublicCompanionState.whisper()` 最近窗语 + 署名
- 此刻状态：便签 `feednote` 或 天气+位置
- 今日轨迹：`CompanionWindowState.journey()` 最近 3 条
- 陪伴倒计日：`PublicCompanionState.daysTogether()` 「第 N 天」
- 陪伴者行动：`PublicCompanionState.actions()` 最近 2 条 + 相对时间

全部为真实状态，无数据时给温和占位文案。展示卡长按可跨页/拖动/缩放。

## 跨页移动实现

1. `DeskLayoutStore` 新增 `K_PAGE`（kind:id → 页），`pageOf/setPage/clearPage`。
2. `defaultPageOf(kind,id)` 定义默认页；`pageOfElement` 读当前归属页。
3. `populateStage` 对每页调用全部 populate 函数，各 `add*` 内 `belongsOn()` 过滤。
4. FreeDeskLayout 编辑态右上角 `moveBtn` → `onMoveToPage(kind,id,page,pos)` → 弹页选择 → 迁移位置+归属页 → rebuildAll。

## bug 补完（子代理深度审查）

1. 「恢复旧功能入口默认」未清 K_PAGE：跨页的设置移不回第四页 → 已改为逐项 remove+clearPage+rebuildAll。
2. 跨页删除贴图只重建归属页，默认页占位不刷新 → 改 rebuildAll。
3. 跨页移动 pos 用 (8,20,92,92) 兜底会突变尺寸 → 改为传入并迁移真实位置尺寸，坐标收敛画布内。
4. 编辑菜单"恢复默认"用写死页 → 改用归属页；old 入口改名/恢复/清图标改归属页。

## 验证

- 编译通过，v1/v2/v3 验签全 Verified（同既有公开证书）。
- DEX 命中：DeskCompanionCards/card_window/card_countdown/showMoveToPageDialog/onMoveToPage/移动到哪一页/日记本；`old:gate`/`old:guidian` 已清除。
- APK：`android/Zhangxinchuang-public-v0.3.9.0.apk`（约 1.2M）
- SHA-256：`8faabf36b12b5243f2782e2a17fa0e7e38ce847ae9875ea812fe69dcced07de9`
