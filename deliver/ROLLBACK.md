# 可回滚说明

## 一、本批改了哪些文件（10 个，相对远端基线 `ecdc938`）

| 文件 | 涉及提交 | 改动概要 |
|---|---|---|
| `CHANGES.md` | A/B/C | 追加各轮改动清单（文档） |
| `android/app/.../PolicyScheduler.java` | A/B | 系统级分心白名单(#34)、训诫 0 档守卫(#35) |
| `android/app/.../AppGate.java` | B | 门禁申请去重+status(#7/#38)、失活锁清理+pruneState(#8/#40)、陪伴 App 保护(#57) |
| `android/app/.../FocusMode.java` | B | normalizeState 时间字段收敛(#39/6b)、求助超时提示(#36)、应急计数按会话归零(#37) |
| `android/app/.../GuidianState.java` | B | 归电 next 顺延出安静段(#19/#43) |
| `android/app/.../LifeState.java` | B | unlock_count_reliable 标注(6a/#49) |
| `android/app/.../CalendarState.java` | C | **R1/R2 手机端真修**、P2-14/15、#70/71/72 |
| `android/app/.../CompanionService.java` | C | 旧日历死路径明确拒绝（不再构造缺 id 的 cmd） |
| `mcp/server.js` | A/B/C | 详见 `DIFF_SUMMARY.md`（321 行改动，最大） |
| `server/linjian_server.py` | A/B/C | 版本/训诫归一/quiet/metadata 截断/alert_tone |

> `android/Zhangxinchuang-public-v0.3.9.0.apk` 为构建产物，已被 `build.sh` 重新生成并签名，但**未纳入 git 提交**（`.gitignore`/未被 add），不随交付改动。

## 二、提交链（增量，无 force push、无 rebase、无覆盖历史）

```
ecdc938 (远端 main / 基线)
   └─ bfad3b1  Commit A  R1/R2/R4 根因版
        └─ c0bcef2  Commit B  R3 状态一致性专项
             └─ afd3a3e  Commit C  R1/R2 手机端真修 + 日历专项  ← 本地 HEAD
```
远端 `main` 仍指向 `ecdc938`（推送失败，未改变远端任何东西，也未带走别人的改动）。

## 三、如何回退

### 场景 1：只回退到「上一版」（撤销 Commit C，保留 A/B）
```bash
git reset --hard c0bcef2      # 回到 R3 版；或
git revert afd3a3e            # 生成一个反向提交（更适合已推送场景）
```
单独撤销 Commit C 的补丁：
```bash
git apply -R patches/CommitC_R1R2_phone_calendar.patch
```

### 场景 2：回退到 R1/R2/R4 版（撤销 B/C）
```bash
git reset --hard bfad3b1
```

### 场景 3：完全回到远端基线（放弃本批全部改动）
```bash
git reset --hard ecdc938
# 或基于远端重新克隆
```

### 场景 4：只回退单个文件
```bash
git checkout c0bcef2 -- android/app/src/main/java/dev/linjian/peek/CalendarState.java
```

### 场景 5：分版单独应用（在未改动的干净基线之上）
```bash
git apply patches/CommitA_R1R2R4.patch
git apply patches/CommitB_R3.patch
git apply patches/CommitC_R1R2_phone_calendar.patch
```

## 四、注意事项
- 三版 patch 是**相邻提交间的增量**，必须按 A→B→C 顺序应用；`ALL_vs_remote_main.patch` 是相对 `ecdc938` 的全量累计，可单独 `git apply`。
- 回退后 Android 需重新 `bash build.sh` 生成 APK；MCP/服务端改动需重启进程才生效。
- 数据层改动（AppGate/FocusMode/GuidianState 的 SharedPreferences 结构）向前兼容：新增字段带默认值，旧数据由 `pruneState`/`normalizeState` 收敛，回退代码后旧数据仍可读。
