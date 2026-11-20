# 掌心窗 交付回执 · 一起看角标 + 三项 Bug 修复 · v2026-11-21

## 一、新增「一起看」应用角标（双人共同观看 / 吐槽）

参考市面上「双人一起刷视频 / 共同看电影」设计，新增独立角标与界面，全部数据来自掌心窗真实状态，可被 MCP 实际操作。

### 手机端
- 新增角标 `watchparty`（一起看），落在 `DeskFeatureStore`，与其它角标一致：可点开使用、长按改名 / 换图标 / 排序 / 恢复默认。
- 新增 `WatchPartyActivity`：影片封卡（封面/标题/博主）+ 进度条 + 播放/暂停 + 双方位置头像时间同屏 + 弹幕飘屏区 + 8 枚表情互动 + 吐槽输入框 + 结束。
- 新增 `WatchPartyState`：房间状态持久化（媒体/进度/播放/双方/弹幕/表情），处理服务端 `watch_party_*` 命令，本机吐槽与表情即时镜像服务端。
- `PluginRegistry` 登记「一起看」插件；`AndroidManifest` 注册 Activity；桌面 `openAction` 路由 `watch:open`。

### 服务端 MCP（工具 123 → 129）
| 工具 | 作用 |
|---|---|
| `watch_party_start` | 发起房间，设置影片/博主/封面/时长/双方位置头像 |
| `get_watch_party` | 读实时状态：进度%、时长、双方位置头像时间、最新弹幕/表情（可读文本） |
| `watch_party_sync` | 同步 seek 进度 / 播放暂停，下发到手机 |
| `watch_party_danmaku` | 发弹幕吐槽（飘屏） |
| `watch_party_reaction` | 发表情互动 |
| `watch_party_end` | 结束房间 |

- 状态单一事实来源：`LocalState.watchparty`（`watchparty_state.json`，单进程持久化）。
- 新增 `/api/watch_party` 读取端点；写操作写入后经命令队列下发手机。
- 端到端实测：6 工具全部正确返回、5 条命令正确入队、get 可读到弹幕/进度/双方信息。

## 二、修复应用锁计时重复刷新导致不锁应用
**根因**：服务端在「巡回查岗 / poll 30s 未回执重投 / 情绪联动」多处重复下发 `lock_app`，手机端每次按 `now+minutes` 重算截止时间，deadline 被不断后推 → 永远到不了点。
**修复**：
1. 手机端 `AppGate.lockApp`：对同包「仍 active 且未到期」的锁、且命令未显式指定截止时间时，沿用原 `locked_until_ms`（幂等），并保留原创建时间与应急密码。
2. 服务端 `tour_step_check_in` 的 auto_lock 下发携带绝对 `locked_until_ms`，重投时命令自带同一截止时间，手机端据此不重算。

## 三、加强查岗 / 训诫 / 想你 使用强度
| 项 | 旧默认 | 新默认 |
|---|---|---|
| 查岗间隔上限 / 下限 | 60 / 8 分钟 | 35 / 5 分钟 |
| 强拉回阈值 | 70 | 55 |
| 同类冷却 | 5 分钟 | 3 分钟 |
| 想你 起始 / 增长 / 回落 | 8 / 2~5 / 4 | 12 / 4~8 / 2 |
| 主动找 沉默 / 间隔 | 30 / 20 分钟 | 20 / 12 分钟 |
| 训诫 开关 / 档位 / 语气 | 关 / 0 / gentle | 开 / 3 / strict |
| 使用时长自动锁 | 关 | 开 |
| 服务端主动关怀重复冷却 | 10 分钟 | 5 分钟 |

- 新安装直接生效；老用户走**一次性升级迁移** `applyStrengthUpgrade`（version 标记，仅当字段仍是旧默认值时提升，**不覆盖用户显式自定义**）。

## 四、修复双端同步
| 问题 | 根因 | 修复 |
|---|---|---|
| 机器写入手机端不显示 | `set_care_policy`/`tour_schedule_update` 只写服务端文件，不下发命令 | 写入后下发 `set_policy` 到手机；`start/stop_tour` 落地 `checkin_enabled` |
| 手机更改不覆盖 / 互相覆盖 | `/api/device/state` 整体覆盖；情绪上报无条件旧值盖新值 | 改为字段级合并（`prev.update`）；情绪上报加单调 `updated_at_ms` 校验 |
| 一起看状态双向可见 | 缺通道 | 状态经 `/api/device/state` 镜像 + `/api/watch_party` 读取 + 命令下发 |

## 五、验证
- `bash android/build.sh`：编译通过，APK v1/v2/v3 验签通过，启动页 `.PalmDeskActivity`。
- dex 校验：`WatchPartyActivity/WatchPartyState/addDanmaku/applyStrengthUpgrade/watch:open` 全部在位。
- `python3 -m py_compile`：三份 Python 全通过。
- `verify_mcp_schema.py --static`：`MISSING_ITEMS_COUNT = 0`，`PERFECT: ZERO_ERRORS_ALL_RESOLVED`。
- MCP 端到端：129 工具加载，watch_party 6 工具实调通过。
- APK：`android/Zhangxinchuang-public-v0.3.9.0.apk`，sha256 `00e1e388…4774ac491`，两个 `update.json` 已同步。
