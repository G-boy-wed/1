
版本：`0.3.9.0`（versionCode `30900`） · 日期：2026-11-20 · 包名 `dev.linjian.peek`

> 用户要求：**UI 只还原排列，不参考颜色**。本批严格按两张目标图的排列结构实现，配色统一沿用已交付的设计令牌。

---

## 一、图1掌心窗桌面（新主入口）

启动页由 `MainActivity` 切换为新增的 `PalmDeskActivity`，单 Activity 三页用底部 Dock 切换，排列与图1一致：

| 区域 | 实现 |
|---|---|
| 大字时间 + 天气 | `64sp` Light 大时间 + 当前位置天气摘要 |
| 周历条 | 年/月 + SUN~SAT 七天，当天高亮圆点 |
| 照片卡 | 两张叠放装饰卡（此刻的窗 / 想你的花） |
| 插件宫格 | 3 列 × N 行，全部插件去重排列 |
| 便签卡 | 点击进入守护页 |

## 二、图2守护标签（可点开更改）

守护页按图2分组排列，每行「图标 + 标题 + 描述 + 开关」，**点击整行进入编辑页（深链到对应设置分区），开关即时写偏好并保存**：

| 分组 | 标签项 |
|---|---|
| 基础守护 | 屏幕日记、外勤报告、email |
| 情感守护 | 情绪感知、五星保护 |
| 安全守护 | 应用门禁、专注模式 |
| 设备行为 | 数字日历、防止夜、自拍记录、抓取辅助、情感分类 |
| 结束会话后 | 天气模块、保密模式、备份相册 |

另有「更改情感相册」按钮（进入日记备份）。

## 三、插件增删改（简单明了、不频繁打扰）

- `DeskPluginStore.java`：单一事实来源，全部以 JSON 落 SharedPreferences。
- 默认 20 个插件，来自 `PluginRegistry` 五类能力 + 图1常见快捷，**按 id 去重**。
- 操作：**长按**任意插件进入编辑模式（平时不显示编辑控件，不打扰）；点图标循环换图标、点 ◀/▶ 移动位置、点 ✕ 删除、点卡片改名/改动作；底部新增插件、保存退出、恢复默认。
- 插件点击动作：`policy:分区`（打开设置）、`page:music/guard/classic`、`app:包名`、`none`。

## 四、天气定位修复

| 问题 | 修复 |
|---|---|
| 只取 `getLastKnownLocation` 旧缓存导致定位不准 | `NowState.refreshLocation()` 主动请求一次新鲜 GPS/网络定位，8 秒超时回退缓存 |
| `weatherBrief()`/`makeAdvice()` 缓存 key 漏传 district | 两处补传 district，与 45 分钟缓存 key 对齐 |
| 中文长地名地理编码漂移 | 坐标优先取天气（`source=coordinate`），服务端 16 条城市 override 兜底 |
| 失败时显示错误天气 | 无参数/无效地名返回明确 code（`missing_city`/`city_not_found`），App 端失败显示 `--°` + 原因，不返回假数据 |

端到端验证：坐标定位不漂移、城市名查询、两类错误返回、advice 生成全部通过。

- `FreeMusic.java`：免费音乐 API 封装，两个公开源按序降级，全部失败返回明确提示；播放控制复用系统媒体会话（`MediaState`），不内置音源解码。

## 六、MCP 全量核对（无遗留 bug）

| 项 | 结果 |
|---|---|
| 定义工具数 | 123 |
| 实现但没定义（多余） | 0 |
| SPECIAL 目标方法缺失 | 0 |
| schema 脏 default | 清理 30 处 `.describe(` 泄漏，`mcp_tools.json` 仍是合法 123 工具 |
| tools/list 清洗抽查 | 5/5 PASS（双保险：引擎出口 + 服务端出口） |
| 死代码 | 移除 `_t_generic_gate_read()` 未使用的 `key` 变量 |

---

## 核验与交付

| 项 | 结果 |
|---|---|
| Android 真实构建 | 58 个 Java 编译 → DEX → 打包 → 对齐 → 签名 → 验签全通过；v1/v2/v3 = true，证书 `aea75c9b…` 与固定公钥一致 |
| 启动 Activity | `dev.linjian.peek.PalmDeskActivity` |
| APK 内容 | dex 含 `PalmDeskActivity` / `DeskPluginStore` / `FreeMusic` / `refreshLocation` 等新代码 |
| 服务端 | `py_compile` 通过；`verify_mcp_schema` → `ZERO_ERRORS_ALL_RESOLVED`；`verify_server_hard` → `PASS=18 FAIL=0` |
| 天气 E2E | 坐标/城市/错误返回/advice 全通过 |
| MCP 审计 | 无缺失、无多余、无脏数据 |

- APK SHA-256：`d9ab75911e7e20ef47fa21afabf56c4b1ac8340258ba937a43eb77d6c7567300`
- 下载：https://github.com/G-boy-wed/1/releases/download/v0.3.9.0/Zhangxinchuang-public-v0.3.9.0.apk

## 说明与折中

- 沙箱无 Android 模拟器，视觉以 `UI_HOME_PREVIEW_v2026-11-20.html`（按真实 APK 排列还原，三页可对照）为准；编译与验签是真实跑过的。
- 免费音乐 API 为公开社区接口，可能随时间失效；`FreeMusic` 内置多源降级 + 本机媒体会话兜底，失效时给用户明确提示而非空白。播放控制跟随系统媒体会话，不内置音源解码（版权与稳定性考虑）。
- 旧 `MainActivity` 所有功能保留，从新桌面 Dock「我的」进入。
