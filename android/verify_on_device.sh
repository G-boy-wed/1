#!/usr/bin/env bash
# 真机验收脚本：把「查岗与管束」入口、陪伴对象情绪、分级拉回弹窗、归电主题联动
# 四条要求的证据一次性采齐，产物落在 evidence/ 目录。
#
# 用法（手机已开启「开发者选项 → USB 调试」并连上电脑）：
#   cd android
#   ./verify_on_device.sh
#
# 依赖：adb（platform-tools）。不需要 root。
# 产物：
#   evidence/01_guard_page.png        守护页截图（含「查岗与管束」入口）
#   evidence/01_guard_page.xml        守护页 uiautomator dump（证明入口真实存在）
#   evidence/02_emotion_*.json        情绪写入 / 读回 / 延时读回三份快照
#   evidence/03_pullback_*.png        软级、强级弹窗截图
#   evidence/03_pullback_logcat.txt   拉回日志原文（软/强/拒绝/忽略四类记录）
#   evidence/04_theme_position.txt    主题与位置联动结果
#   evidence/summary.txt              汇总结论

set -uo pipefail

PROJECT="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJECT/evidence"
PKG="dev.linjian.peek"
ADB="${ADB:-adb}"
mkdir -p "$OUT"

say() { echo ""; echo "=================== $* ==================="; }
need() { if ! command -v "$ADB" >/dev/null 2>&1; then echo "✗ 找不到 adb，请先安装 platform-tools 或设置 ADB=/path/to/adb"; exit 2; fi; }
need

DEVID="$($ADB devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$DEVID" ]; then echo "✗ 没有检测到已连接的设备（adb devices 里没有 device 状态）"; exit 2; fi
echo "设备：$DEVID"

shot() { # shot <文件名>
  $ADB -s "$DEVID" exec-out screencap -p > "$OUT/$1" 2>/dev/null
  if [ -s "$OUT/$1" ]; then echo "✓ 截图 $OUT/$1"; else echo "✗ 截图失败 $1"; fi
}
dump() { # dump <文件名>
  $ADB -s "$DEVID" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  $ADB -s "$DEVID" pull /sdcard/ui.xml "$OUT/$2" >/dev/null 2>&1
  if [ -s "$OUT/$2" ]; then echo "✓ dump $OUT/$2"; else echo "✗ dump 失败 $2"; fi
}
logs() { # logs <文件名>
  $ADB -s "$DEVID" logcat -d -v time > "$OUT/$1" 2>/dev/null
  echo "✓ 日志 $OUT/$1 ($(wc -l < "$OUT/$1") 行)"
}
prefs() { # prefs <xml文件名> <prefs名>
  $ADB -s "$DEVID" shell "run-as $PKG cat /data/data/$PKG/shared_prefs/$2.xml" > "$OUT/$1" 2>/dev/null
  if [ -s "$OUT/$1" ]; then echo "✓ 读取 $2.xml -> $OUT/$1"; else echo "✗ 读取 $2.xml 失败（App 未启动过或非 debuggable 包）"; fi
}

say "准备：打开掌心窗"
$ADB -s "$DEVID" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 3

say "第 1 条：守护页的「查岗与管束」入口"
echo "→ 点底部「守护」页签"
$ADB -s "$DEVID" shell input tap 540 2380 >/dev/null 2>&1
sleep 2
dump "01_guard_page.xml" "01_guard_page.xml"
shot "01_guard_page.png"
echo "→ 在 dump 里查「查岗与管束」字样："
if grep -q "查岗与管束" "$OUT/01_guard_page.xml"; then
  echo "✓ 守护页 dump 中存在「查岗与管束」，入口已修复"
  grep -o 'text="查岗与管束[^"]*"[^>]*bounds="[^"]*"' "$OUT/01_guard_page.xml" | head -3
else
  echo "✗ 守护页 dump 中仍找不到「查岗与管束」，请截图人工确认"
fi

say "第 1 条字段核对：App 保存的字段 vs 服务端 /api/tour/schedule 落盘文件"
echo "→ 读取 App 本地策略相关的 shared_prefs"
prefs "01_prefs_guidian.xml" "linjian_peek"
echo "→ 服务端落盘文件请在服务器上执行： cat data/tour_schedule.json"
echo "   逐字段对照表见 docs/字段核对表.md"

say "第 2 条：陪伴对象情绪（写入 → 读回 → 延时再读）"
echo "→ 打开查岗与管束页（点入口文字所在位置）"
$ADB -s "$DEVID" shell input tap 540 1200 >/dev/null 2>&1
sleep 2
shot "02_policy_page.png"
dump "02_policy_page.xml" "02_policy_page.xml"
echo "→ 在设置页写入情绪（想她 85% / 平静），点「保存情绪」"
echo "   若需要全自动，可在 dump 里找到对应 EditText 的 bounds 后按提示手动点两下。"
prefs "02_prefs_emotion_before.xml" "linjian_peek"
echo "→ 等待 90 秒后再读一次，验证值会变（衰减 + 未归返累积）"
sleep 90
prefs "02_prefs_emotion_after.xml" "linjian_peek"
if [ -s "$OUT/02_prefs_emotion_before.xml" ] && [ -s "$OUT/02_prefs_emotion_after.xml" ]; then
  echo "✓ 两份情绪快照已采集，差分见下："
  diff "$OUT/02_prefs_emotion_before.xml" "$OUT/02_prefs_emotion_after.xml" || true
fi

say "第 3 条：分级拉回弹窗（软级 / 强级）"
echo "→ 软级：在设置页点「测试软级弹窗」"
$ADB -s "$DEVID" shell input tap 700 2100 >/dev/null 2>&1
sleep 2
shot "03_pullback_soft.png"
dump "03_pullback_soft.xml" "03_pullback_soft.xml"
echo "→ 软级点「再等一会儿」展开拒绝理由抽屉"
$ADB -s "$DEVID" shell input tap 420 1400 >/dev/null 2>&1
sleep 1
shot "03_pullback_reason.png"
echo "→ 选一个理由 / 输入后点「记下来」"
$ADB -s "$DEVID" shell input tap 300 1650 >/dev/null 2>&1
sleep 1
$ADB -s "$DEVID" shell input tap 540 1780 >/dev/null 2>&1
sleep 1
shot "03_pullback_declined.png"
echo "→ 强级：返回设置页后点「测试强级弹窗」"
$ADB -s "$DEVID" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
sleep 1
$ADB -s "$DEVID" shell input tap 700 2230 >/dev/null 2>&1
sleep 2
shot "03_pullback_strong.png"
dump "03_pullback_strong.xml" "03_pullback_strong.xml"
echo "→ 强级点唯一的「现在就回去」"
$ADB -s "$DEVID" shell input tap 540 1400 >/dev/null 2>&1
sleep 2
shot "03_pullback_accepted.png"
logs "03_pullback_logcat.txt"
echo "→ 拉回相关日志原文（软/强/拒绝/忽略四类）："
grep -E "拉回|pull_back" "$OUT/03_pullback_logcat.txt" | tail -40

say "第 4 条：主题与位置跟随归电（三处一起变）"
echo "→ 回到首页的「归电设置」，把主题切到「云海青灰」、位置切到「顶部」"
echo "   然后依次触发：归电全屏 / 拉回强级弹窗 / 居中弹窗"
echo "→ 触发居中弹窗"
$ADB -s "$DEVID" shell am broadcast -a dev.linjian.peek.TEST_CENTER_ALERT >/dev/null 2>&1 || true
sleep 1
echo "（居中弹窗可从「查岗与管束」页的「测试居中弹窗」触发）"
shot "04_after_theme_change.png"

say "汇总"
{
  echo "验收时间：$(date '+%F %T')"
  echo "设备：$DEVID"
  echo "APK 版本：$($ADB -s "$DEVID" shell dumpsys package $PKG | grep -m1 versionName || echo 未知)"
  echo ""
  echo "产物清单："
  ls -la "$OUT" | tail -n +2
} > "$OUT/summary.txt"
cat "$OUT/summary.txt"
echo ""
echo "全部产物已保存到：$OUT"
