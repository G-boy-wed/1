# 真机问题（闪退 / 权限 / 后台被杀）说明

> 你的交付要求：「真机问题（闪退、权限、后台被杀）附报错日志：发生时间点 + 关键堆栈或日志行」。
> **如实说明**：本次执行环境是 Linux 沙箱，**没有连接 vivo 真机**，也没有手机端 logcat / 崩溃堆栈文件。
> 因此我**不能编造**发生时间点和堆栈行（编造日志属于「假装修好/造假证据」）。
> 下面给出：(1) 本环境能确认的事实与查证；(2) 需要你按下列方法采集真机日志，我据此定位。

## 一、本环境已能确认的事实（无需真机）

| 项 | 事实 | 证据 |
|---|---|---|
| 编译期崩溃 | 无 | `bash build.sh`：45 个 Java 文件 javac 0 error；aapt2/javac/d8/zipalign 全流程通过 |
| 签名/安装完整性 | 通过 | APK v1/v2/v3 `Verified using ...: true` |
| 逻辑层崩溃 | 无（已跑到的路径） | Java 运行时 24/24 PASS；Python 运行时 7 项；HTTP 训诫归一实测 |
| 具体闪退堆栈 | **无法采集** | 无真机、无 `adb logcat`、无 tombstone / `ANR` / crash 文件 |

清单里列的是**运行态数据现象**（分心误判、pending 求助、状态字段矛盾等），不是闪退堆栈；
本轮修复针对的是这些可复现的数据逻辑，已用「Java 真跑 + Python 真跑 + HTTP 实测」替代真机做行为验证。

## 二、需要你采集的真机日志（若出现闪退/权限/后台被杀）

在手机连电脑、开 USB 调试后执行：

```bash
# 1) 清空并开始抓取
adb logcat -c
adb logcat -v time > peek_logcat.txt &

# 2) 复现问题（记录精确到本地时间点，例如 2026-11-05 14:23:04）
#    - 闪退：进入【具体页面】→【具体动作】
#    - 权限：设置页关掉【通知/悬浮窗/无障碍/后台自启】再进
#    - 后台被杀：锁屏/切到其他 App 5 分钟后再回

# 3) 抓崩溃关键行
adb logcat -d -v time | grep -iE "AndroidRuntime|FATAL EXCEPTION|dev.linjian.peek|ANR|Force finishing|died|killed" 

# 4) 若有 native tombstone
adb shell ls -l /data/tombstones/ && adb pull /data/tombstones/ .
```

**请提供**：发生时间点（精确到秒）+ `FATAL EXCEPTION` 起 20 行堆栈 / `ANR` 段 / 被杀前后 `ActivityManager` 行。
拿到后我按「文件:行号」定位并给最小修复，**再打一版增量提交**（不覆盖现有三版历史）。

## 三、与权限/后台相关的已知代码路径（供定位参考，非崩溃结论）

| 现象 | 相关代码位置 | 说明 |
|---|---|---|
| 悬浮弹窗不显示 | `AppGate.java:314-489`（`canDrawOverlay`/`showOverlayLock`）、`PullBackActivity` overlay 优先 | 需 `TYPE_APPLICATION_OVERLAY` 权限；本轮未改 |
| 后台被杀后状态读不到 | `CompanionService.java:96,129`（命令轮询）、`mcp/server.js` 的 `get_phone_state` 缓存态 | 依赖前台服务/无障碍存活；本轮未改 |
| 归电/巡回到点不触发 | `PolicyScheduler.java`（Handler tick）、`GuidianState.java:244` | 依赖后台调度存活；本轮未改 |

> 以上三条**不在你本次两份清单的编号内**，按你「只改清单内、不顺手重构清单外」的边界，本轮**未改动**，仅列出定位线索。
