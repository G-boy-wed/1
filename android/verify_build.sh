#!/usr/bin/env bash
# 本地 Android 编译验证：不需要 Gradle，直接用 aapt2 + javac + d8 + apksigner 走一遍。
# 作用：在推送 GitHub 之前，把「编译不过」的问题全部挡在本地。
#
# 依赖：JDK 17+、Android SDK (platform-34, build-tools 34.0.0)
# 用法：
#   export ANDROID_HOME=$HOME/android-sdk
#   ./verify_build.sh
#
# 退出码 0 = 编译通过（并已生成可安装 APK）；非 0 = 失败，日志里有完整报错。

set -euo pipefail

PROJECT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
OUT="${OUT:-/tmp/linjian_verify_build}"
PKG_PATH="dev/linjian/peek"

if [ ! -f "$PLATFORM" ]; then
  echo "✗ 找不到 Android platform 34：$PLATFORM"
  echo "  先安装：sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\""
  exit 1
fi
if [ ! -x "$BUILD_TOOLS/aapt2" ]; then
  echo "✗ 找不到 build-tools 34.0.0：$BUILD_TOOLS"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/apk" "$OUT/res"

echo "=== 1/5 编译资源 ==="
"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT/app/src/main/res" -o "$OUT/res/"

echo "=== 2/5 链接资源（生成 R.java）==="
"$BUILD_TOOLS/aapt2" link \
  -o "$OUT/apk/app.unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJECT/app/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT/res"/*.flat

echo "=== 3/5 编译 Java（这里是绝大多数错误的来源）==="
find "$PROJECT/app/src/main/java" -name "*.java" > "$OUT/sources.txt"
echo "$OUT/gen/$PKG_PATH/R.java" >> "$OUT/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "=== 4/5 生成 DEX ==="
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
"$BUILD_TOOLS/d8" --lib "$PLATFORM" --min-api 21 --output "$OUT/apk/" "$OUT/classes.jar"

echo "=== 5/5 组装并签名 APK ==="
cd "$OUT/apk"
cp app.unsigned.apk app.tmp.apk
zip -d app.tmp.apk classes.dex >/dev/null 2>&1 || true
zip -j app.tmp.apk classes.dex >/dev/null
mv app.tmp.apk app.unsigned.apk

PUBLIC_KS="$PROJECT/signing/zhangxinchuang-public-release.p12"
PUBLIC_KS_PASSWORD="${PUBLIC_KS_PASSWORD:-zhangxinchuang-public-30600}"
if [ ! -f "$PUBLIC_KS" ]; then
  echo "! 未找到公开版签名密钥，跳过签名步骤（APK 未签名，仅供编译验证）"
  echo "  APK: $OUT/apk/app.unsigned.apk"
  exit 0
fi

"$BUILD_TOOLS/zipalign" -f 4 app.unsigned.apk app.aligned.apk
"$BUILD_TOOLS/apksigner" sign \
  --ks "$PUBLIC_KS" \
  --ks-type PKCS12 \
  --ks-pass pass:"$PUBLIC_KS_PASSWORD" \
  --key-pass pass:"$PUBLIC_KS_PASSWORD" \
  --ks-key-alias zhangxinchuang-public \
  --out "$OUT/Zhangxinchuang-public-v0.3.9.0.apk" \
  app.aligned.apk

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$OUT/Zhangxinchuang-public-v0.3.9.0.apk" | head -12

echo ""
echo "✓ 编译通过"
echo "  APK: $OUT/Zhangxinchuang-public-v0.3.9.0.apk"
ls -lh "$OUT/Zhangxinchuang-public-v0.3.9.0.apk"
