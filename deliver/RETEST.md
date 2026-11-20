# 掌心窗 · 复测清单（每条 bug 的验证步骤 + 预期结果）

> 复测前置：把 zip 里 `changed_files/` 覆盖到仓库、或用 `patches/ALL_vs_remote_main.patch`：`git apply ALL_vs_remote_main.patch`；
> Android 端安装 `Zhangxinchuang-public-v0.3.9.0.apk`（已在本机 javac 全量编译 + v1/v2/v3 签名验证通过）；
> MCP/服务端改动需重启 `mcp/server.js` 与 `server/linjian_server.py`。
> 标 **[真机]** 的必须手机上点按，我无法代验；标 **[MCP]** 的用只读探针工具；标 **[运行时]** 的我已在本机跑过、给出可复现命令。

## A. 已改项（应逐条通过）

| # | 复测步骤 | 预期结果 |
|---|---|---|
| 1/R2/P0-1 | **[MCP]** `list_guardian_days` 拿一个真实 id → `delete_guardian_day{id, confirm:true}` → 再 `list_guardian_days` | 返回 `ok:true`、`action_done:"已删除守护日历事件"`；列表里该 id 消失；不再出现 `not_found:<命令id>`，也不再「谎报已删除」却不删 |
| 2/R1/P0-2 | **[MCP]** 新建一条带 `group=study/note=带课本/repeat_type=none` 的事件记下 id → `update_guardian_day{id, title:"改个名"}`（只传 title） | 只 title 变；group 仍 study、note 仍「带课本」、repeat_type 仍 none、**id 不变**；不再被默认值 `our_days/yearly` 覆盖 |
| 3/P0-3 | **[MCP]** `get_phone_state` / `get_life_state` | `screen_text`/无障碍节点不含完整页面文本、token/口令/邮箱/手机号被 `***` 遮蔽、长度 ≤200 字；有 `privacy_note` |
| 7/P1-7 | **[真机]** 门禁页 2 秒内连点 13 次同理由解锁申请 | 只落 1 条申请，带 `repeat_count`；`get_screen_break_state` 不再出现 13 条 |
| 8/40/P1-8 | **[MCP]** `get_screen_break_state` | `locks` 里无 `active:false` 且已过期的残留锁；每条锁不再带一堆恒 0 的 `temporary_*` 字段（仅临时放行期间出现） |
| 9/48/P1-9 | **[MCP]** `get_weather_state` 查「绵阳」 | `location.admin1=四川`，`latitude≈31.47`、`longitude≈104.68`；不是贵州 27.5/106.4 |
| 12/P1-12 | **[MCP]** 调一次 `list_diary_books` → 看活动事件流 | 只新增 1 条中文标题事件，没有「英文 list diary books + 中文」两条 |
| 13/P1-13 | **[MCP]** `get_visit_stats`（到访统计） | 返回 `today_key`/`today_timezone_offset`；today_count 与 recent_24h 的口径差可用时区解释，不再 today=0 而 24h=3 无解 |
| 14/69/P2-14 | **[MCP]** `get_guardian_calendar` 看一条 `group=study` 的横幅 | `banner_text`=「…提前把课本和作业备好，别迟到」等课表文案，**不含**「别忘了这份小仪式」；节日文案是「一起过个节」 |
| 15/P2-15 | **[MCP]** 建一条 `banner_enabled=false` 的课表 → `get_guardian_calendar` | 该项 `banner_text=""`、不出现在 `nearest`/`active_banners`/`summary` |
| 17/P2-17 | **[MCP]** 任意门禁命令，看返回的 `note` 与 `command_status` | `command_status=completed` 时 note=「手机端已确认回传」；`pending` 时=「先返回，可稍后查询」；不再写死「未等到回传先返回」 |
| 18/51/P2-18 | **[MCP]** `get_weather_state` | `temperature_2m_max/min` 与文案「今天 13~18℃」是同一组 `Math.round` 整数；降水 100% 但天气码=阴天时有「数据可能不一致」提示 |
| 19/P2-19 | **[MCP]** `get_guidian_state` 看 `next_prompt_after` | 不落在 `quiet_start~quiet_end` 内；如原本会落入则顺延到安静结束之后 |
| 20/P2-20 | **[MCP]** `get_care_policy` 看 `allowed_actions` | 含 `get_focus_status/start_focus_mode/end_focus_mode/set_focus_plan/reply_focus_request/approve_focus_unlock/deny_focus_unlock` |
| 24/64/P2-24 | **[MCP]** `linjian_status` 看 `latest` | 有 `mtime_local`/`age_seconds`/`stale`；>6h 截图 `stale:true` |
| 25/55/P2-25 | **[MCP]** 触发 `get_screen_break_state` 后看事件流 `metadata_json` | 无完整门禁 state 塞入；最长字符串 ≤1006 字、`JSON.parse` 不报错 |
| 34/【一】 | **[真机]** 在 Kelivo 打字弹 KK 键盘、从相册选图 | `get_distraction`/训诫事件里不再出现 `im.weshine.keyboard`/`com.android.providers.media.module`/`com.vivo.card` 被记成「分心」 |
| 35/【一】 | **[运行时]** `POST /api/discipline {"enabled":true,"level":0}` | 回 `{enabled:false, level:0}`；`{"enabled":false,"level":4}`→`{enabled:false,level:0}`；开启 level=2 保留；且 0 档无「训诫(0档)」事件 |
| 38/【二】 | **[MCP]** `get_screen_break_state` 看申请 | 每条 `unlock_requests` 带 `status:"pending"`；旧申请缺字段被 `pruneState` 补齐 |
| 39/【三】 | **[MCP]** 专注模式开→关多次 → `get_focus_status` | `active=false` 时 `started_at_ms/until_ms` 不再停在过期值；`ended_at` 是本次结束时间；不再出现「结束早于开始 3 天」 |
| 44/【四】 | **[运行时]** 巡回配置 `alert_tone=gentle` + 造一个强情绪(angry) → 触发 `tour_step_check_in` | `show_center_alert` 的 `tone='gentle'`（本机实测 `tones=['gentle']`），不再被 angry 覆盖 |
| 46/【四】 | **[运行时]** `GET /api/emotion/snapshot` 在安静时段 | `quiet=true` 时 `suggestion="quiet"`、reason 是「深夜休息/安静上课」之一，不再 suggestion=pullback 却写不打扰 |
| 47/【四】 | **[MCP]** `set_care_policy` 加一个新 App 不带 `max_lock_minutes` → `get_care_policy` | 新 App 自动带 `max_lock_minutes:90`，不再是空的 |
| 52/【六】 | **[MCP]** 连调 `get_care_policy`/`get_discipline_mode`/`get_focus_status` 等只读工具 | 事件流不再新增 `status_check`；只有写类工具才写事件 |
| 53/【六】 | **[MCP]** 同一秒连续两次同一读动作 | 事件流只 1 条（3s 去重） |
| 56/【六】 | **[真机]** 1 秒内 Kelivo↔桌面来回切 | 事件流合并为 1 条，不再成对「打开X离开Y / 打开Y离开X」 |
| 57/【七】 | **[MCP]** 尝试把陪伴 App（target_package，如 Kelivo）加进门禁/锁定 | 返回 `protected_package:...`，加不进去；不会把自己锁在陪伴 App 外 |
| 58/59/【七】 | **[MCP]** `get_care_policy` 与 `linjian_status` | 无 `state_path`（`/data/data/...`）、无 `linjian_url`/`127.0.0.1`；只有 `linjian_configured` + `linjian_host_masked` |
| 61/【七】 | **[运行时]** `linjian_status.health.version` 与 `get_focus_status.focus_version` | 都是 `0.3.9.0-public-diary-annotations`（本机实测一致），可程序化比较 |
| 65/【八】 | **[MCP]** `create_diary_book` / `list_diary_books` 工具描述 | 不再写「TA 的日记」，改为「名称由 name 决定 / 以本机实际存储为准」 |
| 70/【十】 | **[运行时]** `get_calendar_state`（Java 真跑） | `events.length()>custom_events.length()`；`event_count=events.length()`；`event_fields_note` 说明两者口径 |
| 71/【十】 | **[运行时]** 同上 | `event_count` 含内置节日、`custom_event_count` 只算自定义；`events` 与 `upcoming` 同源排序 |
| 72/【十】 | **[运行时]** 同上 | 有 `upcoming_total`/`upcoming_has_more`；超过 12 条时 `has_more=true`，生日不再被静默挤掉 |

## B. 部分改 / 未改项（复测用于确认「现状 + 阻塞点」，不是宣称已修）

| # | 复测步骤 | 预期（现状） | 要闭环还缺什么 |
|---|---|---|---|
| 33 | **[真机]** 巡回/归电/门禁/专注/训诫五页改一项后退出重进 | 主设置页「保存设置」、归电「保存归电设置」、专注「保存默认规则」保存后**刷新不丢**；但无「未保存离开二次确认」 | 需你确认五页改为「草稿+脏标记+显式保存」的交互；真机点按复测 |
| 36/4 | **[真机]** 专注页连发求助 | 求助带 `pending_hint`/超时提示、不再无限 pending；但未必推送到陪伴对象会话 | 需 N 分钟阈值 + 陪伴对象会话侧接收端点 |
| 37/5 | **[真机]** 设 `emergency_total=1` 后离线应急放行 | 新会话 `emergency_used` 归零；但放行时长/额度仍待核 | 需真机离线放行日志 + 确认时长字段 |
| 41 | **[MCP]** 看历史测试数据 | 过期/失活记录自动清理；`goal:"测试专注模式"` 等手动清理入口**没有** | 需确认「清理范围」+ 新增按钮/命令 |
| 42 | **[真机]** 设 `single_app_lock_duration_minutes=5` → 手动锁 3 次 | 时长仍可能 30/2 分钟（未统一） | 需确认手动锁是否服从该配置 + 真机三链路时长 |
| 43 | **[MCP]** `get_guidian_state` 看 `next_prompt_after` | interval=60 但 next 仍可能 $>60$ 分钟（cooldown/quiet 取大）；已能顺延出安静段 | 需确认 interval 与 cooldown 优先级 |
| 45/10 | **[MCP]** 分别读四套安静时段字段 | 仍是四套；但 04:01 不再误写「上课时段」、quiet 时 suggestion=quiet | 需你定义四套语义与优先级（时段模型合并） |
| 49/6a | **[MCP]** 看 `unlock_count_today` | 有 `unlock_count_reliable=false` 标注，值仍可能 0 | 需真机解锁事件采集 + 权限 |
| 50/6c/6d | **[MCP]** 对比 `get_companion_emotion` 与 `get_emotion_snapshot`、看 `screen_text` vs `current_package` | 已改的 quiet/句式一致；但两工具标签、采样时刻仍可能不一致 | 需产品口径（以哪个为准）+ 真机同秒采样 |
| 54 | **[真机]** 触发一次门禁拦截 | 已有 1800ms 节流；但无「×N 合并计数」 | 需真机 logcat 复现是否绕过节流 |
| 60/11 | **[MCP]** 看 `/health` 工具别名 | 7 个兼容别名可用；但旧名无弃用时间、`added_locked_app` 等旧键仍在 | 需弃用日期 + 全链路改名回归 |
| 62 | **[真机]** 面板手动新增一条记忆 → MCP `list_shared_memory` | 需核对是否即时显示 | 需真机面板操作验证 |
| 63 | — | 无分组/去重（未改） | 需分组维度 + 合并策略 |
| 16 | **[MCP]** 看 `get_life_state`/门禁返回 | `state`≈`life_state`、`now_state`≈`current_state`、门禁多份同文仍在（note 已动态化） | 需确认可变更响应契约 + 全链路回归 |
| 21 | **[MCP]** `get_care_history` vs 巡回 check_in 次数 | 仍只有 1 条（两套存储未合并） | 需确认关心记录是否纳入自动巡回 |
| 22 | **[MCP]** 看 `current_app_dwell_minutes` vs `top_apps` | 仍是两套口径、无说明 | 需确认字段改名/口径说明 |
| 23 | **[MCP]** 短时间连写 5 次情绪 | 5 条都写入（未节流） | 需节流窗口 N |
| 26 | **[真机]** 看活动事件是否按天过期 | 有 500 条上限（基线），无按时间 TTL 清理 | 需 TTL 天数 |
| 66/67/68 | **[真机]** 看课程是否每周重复、课表作息模块 | `ClassScheduleState.java` 已存在（与清单「不存在」描述不符）；课程仍是一次性日历事件 | 需周重复结构 + 起止周次/单双周决策 + 是否删除已有 4 条重复课程 |
| 6(R3整体) | 见上 49/50/6a-d | 6b 时间倒挂已修 | 6a/6c/6d 见上 |

## C. 本机已跑过的运行时验证（可复现）

```bash
# 1) Java 真跑 CalendarState（R1/R2 + 日历专项，24/24 PASS）
#    用官方 org.json jar + 最小 Android stub 编译，直接调 CalendarState.handleCommand/collect
java -cp "out:lib/json.jar" TestMain    # 输出 RESULT: PASS=24 FAIL=0

# 2) Python 运行时（7 项）
python3.11 -m py_compile server/linjian_server.py
node --check mcp/server.js && node --check server/cloudflare-worker/worker.js

# 3) #35 训诫单开关（真实 HTTP）
PORT=8513 LINJIAN_TOKEN=xxx python3.11 server/linjian_server.py &
curl -X POST localhost:8513/api/discipline -H 'X-Auth-Token: xxx' -d '{"enabled":true,"level":0}'
# -> {"discipline":{"enabled":false,"level":0}}

# 4) #44 alert_tone（Python 直调）
# configured_tone=gentle 优先于强情绪 angry -> tones=['gentle']

# 5) Android 真编译
cd android && ANDROID_HOME=/root/android-sdk bash build.sh
# -> 45 个 java 文件 javac 0 error；APK v1/v2/v3 签名 Verified:true
```
