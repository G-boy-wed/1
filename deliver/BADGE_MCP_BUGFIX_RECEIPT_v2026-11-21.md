# 掌心窗 · 角标图片替换 + 贴图编辑 + 源码补完 + MCP 闭环 交付回执

日期：2026-11-21　版本：v0.3.9.0　包名：`dev.linjian.peek`

## 一、角标图片替换（按参考图色系）

### 1.1 找图说明
你提供了粉白四宫格参考图（玫瑰花茶与书 / 蝴蝶结甜点盘 / 樱花冰饮 / 粉瓷茶具），要求按此色系找图替换角标。

- 尝试接入小红书搜索：已下载并启动 xiaohongshu-mcp v2.7.4（含内置浏览器），但**当前环境未登录小红书**，`check_login_status` 返回"未登录"，获取登录二维码需你在小红书 App 扫码授权（本环境无人值守，无法完成扫码）。为避免用不确定来源/版权图片，改用你提供的参考图作为唯一色系来源。
- 处理：把参考四宫格**切成 4 张正方形角标图**，再延展生成 4 张同色系亮度变体，共 **8 张内置粉白图标**，打包进 `res/drawable-nodpi/ic_badge_0..7.png`。

### 1.2 替换规则
- **前 4 个功能角标**分别对应四宫格切出的 4 张：查岗=玫瑰书页、情绪=蝴蝶结甜点、主动找=樱花冰饮、训诫=粉瓷茶具。
- 其余角标按 id 稳定映射到 8 张内置图，整体 UI 色系统一为粉白/奶油。
- 图标优先级：**自定义上传图片 > 内置粉白图 > 字符图标**。
- 手机端替换：长按角标 → 左上角编辑按钮 → 「选内置粉白图标」挑一张（也可「自定义上传图标」「换字符图标」）。旧功能入口页同样支持。
- 名字仍在**图标正下方居中**，不跑偏。

## 二、贴图可改大小与位置

确认并保障（FreeDeskLayout）：
- 相册页默认 3 张贴图（可加至 9 张），每张都可**拖动位置**、**拖右下角把手缩放尺寸**、长按左上角红按钮**删除**。
- 页设置 → 「添加贴图」从相册新增；新增贴图同样可拖动、可缩放、可删除。
- 贴图拖动时**自动避让角标**，不会遮挡；位置/尺寸落 `DeskLayoutStore`，重启不丢。

## 三、源码 bug 审查与补完

本轮发现并修复：
1. **页设置行页号错误**：`pageSettingRow()` 内部用 `currentPage`，rebuildAll 重建四页时每页返回箭头/齿轮都指向错误页号。改为 `pageSettingRow(int pageIndex)`，返回箭头与齿轮菜单均使用本页 index。
2. **长按事件冲突**（上一轮已修，本轮复核）：角标内部 longClick 与容器长按拖动冲突，已统一由容器处理；编辑态下点左上角编辑按钮弹菜单。
3. `DeskAssetStore` 缺少 `clearAll`（机端 desk_reset 需要），已补。
4. `DeskIconPack.resetOne` 在"恢复默认"路径补齐调用，避免内置图残留。

编译、v1/v2/v3 验签、DEX 关键串检查全部通过。

## 四、MCP 核对与新增

核对结论：原 129 个工具中日记/陪伴/守护/一起看齐全，但**桌面角标/图标/背景/文字没有专门工具**，机端无法改桌面。本轮补齐"机端与手机端双向可改"：

- 新增手机端 `DeskCommand.java`，经命令通道处理：`desk_set_badge_icon` / `desk_set_badge_name` / `desk_set_old_icon` / `desk_set_old_name` / `desk_set_page_bg` / `desk_set_text` / `desk_reset` / `desk_get_state`。
- `CompanionService.handleCommandBody` 增加 `desk_*` 命令分发。
- MCP 工具 129 → **132**，新增：
  - `set_desk_badge`：改角标/旧入口名字、内置图标（builtin_index）、字符图标、页面背景色；
  - `get_desk_state`：读桌面配置快照（未上报时给提示，不报错）；
  - `reset_desk`：恢复默认（scope=all/layout/icons/bg）。
- MCP 静态校验：`py_compile` 通过，`verify_mcp_schema.py --static` 零错误；新工具 schema 与 handler 映射检查通过。

> 机端无法访问手机相册 URI，因此图片类只支持内置图标索引；自定义上传仍由手机端完成。这是平台边界，不是功能缺失。

## 五、涉及文件

新增：`DeskIconPack.java`、`DeskCommand.java`、`res/drawable-nodpi/ic_badge_0..7.png`、两个预览 HTML、本回执。
修改：`PalmDeskActivity.java`（图标优先级/内置图标菜单/页号 bug 修复）、`DeskAssetStore.java`（clearAll）、`CompanionService.java`（desk 命令分发）、`server/mcp_engine.py`（3 handler + 注册）、`server/mcp_tools.json`（3 工具）。

## 六、验证结果

- `bash android/build.sh`：编译通过，v1/v2/v3 全 Verified（同一公开版证书）。
- DEX：`DeskCommand`/`DeskIconPack`/`ic_badge`/`desk_set_badge_icon`/`desk_set_page_bg`/`选内置粉白图标`/`desk_get_state` 全部命中。
- APK：`android/Zhangxinchuang-public-v0.3.9.0.apk`（1.9M，含 8 张内置图标）。
  SHA-256 见交付时输出。
- MCP：`py_compile` OK，schema 静态校验 ZERO_ERRORS。

## 七、仍待你决定的两项

1. **小红书图片**：本环境未登录，无法扫码。若你要用小红书实时图片替换角标，请扫码登录后告诉我，我再抓取同色系图片补充进内置图标池；当前已用你的参考图完成同色系替换。
2. **GitHub 上传**：当前会话无 GitHub PAT / gh 未登录，本轮先完成本地构建。提供 Token 后我推送 main 并覆盖 Release APK。
