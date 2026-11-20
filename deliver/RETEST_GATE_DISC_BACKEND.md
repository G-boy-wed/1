# 复测清单（Commit J = `5f171e0`）

> 真机/模拟复测项；编译已通过、签名 v1/v2/v3 Verified。

## A. 应用门禁「写入即监测」
- [ ] 在设置页「应用门禁」新增受限 App 后，回到该 App → 立即被拦截（不再需先切走再切回）
- [ ] 通过 MCP/服务端 `lock_app` 锁定时若目标已在当前前台 → 200ms 内立即弹锁定页/遮罩
- [ ] 无障碍事件被节流时（如后台 Doze），调度器每 20s tick 仍能识别并拦截受限 App
- [ ] 锁定界面已显示时，停留在受限 App 上不出现每 20 秒重复闪烁（去重守卫生效）
- [ ] 解除锁后切回该 App → 不再拦截

## B. 训诫语气变严厉
- [ ] 训诫总开关开 + 语气模式「严厉」→ 弹窗标题/边框呈 danger 红色（accentFor=strict→danger）
- [ ] 同一情绪下，严厉模式弹窗文案带管束后缀（如「别让我说第二遍。」），温柔模式带「听话，回来嘛。」
- [ ] 关闭情绪驱动文案时，界面与 `get_emotion_snapshot` 的 `message` 仍带训诫语气（messageForDisplay 已套用）
- [ ] `set_discipline_mode(strict)` 后 `tone` 快照在平稳情绪下变为 `strict`/harsh，而非 `gentle`
- [ ] 训诫关 → 弹窗恢复温柔样式，文案无管束后缀

## C. 后台自主运行
- [ ] 开机/应用更新自启后，`PolicyScheduler` 调度器与看门狗均拉起并持续 tick
- [ ] 进程被杀后 15 分钟内看门狗 `SchedulerWatchdogReceiver` 重新拉活调度器
- [ ] 后台期间门禁/使用时长自动锁持续生效（无障碍已开启前提下）

## D. 垃圾清理
- [ ] `deliver/changed_files/` 已删除（源码副本）
- [ ] `deliver/Zhangxinchuang-public-v0.3.9.0.apk` 重复副本已删除
- [ ] `android/` 下源码与 `android/Zhangxinchuang-public-v0.3.9.0.apk` 完好
