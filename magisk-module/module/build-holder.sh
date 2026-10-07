#!/bin/bash
# 构建 display-holder 的 dex（holder 侧唯一需要编译的部分）
#
# 依赖：JDK 17+、d8（Android cmdline-tools 自带）、Android platform 的 android.jar
# 产物：build/display-holder.jar（内含 classes.dex，供 app_process 通过 CLASSPATH 加载）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="${OUT_DIR:-$ROOT/build}"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
# holder 用到的是 Android 14+ 的隐藏 API 包装（照抄 scrcpy），编译用哪个 platform 差别不大
API_LEVEL="${HOLDER_API_LEVEL:-36}"
ANDROID_JAR="$SDK/platforms/android-$API_LEVEL/android.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "找不到 android.jar: $ANDROID_JAR" >&2
    echo "设置 ANDROID_SDK_ROOT 或 HOLDER_API_LEVEL" >&2
    exit 1
fi

D8=""
for c in "$SDK/cmdline-tools/latest/bin/d8" "$SDK/build-tools"/*/d8 "$(command -v d8 2>/dev/null || true)"; do
    if [ -x "$c" ]; then D8="$c"; break; fi
done
if [ -z "$D8" ]; then
    echo "找不到 d8（Android cmdline-tools 或 build-tools 里）" >&2
    exit 1
fi

JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
JAVAC="$JAVA_HOME/bin/javac"
[ -x "$JAVAC" ] || JAVAC="javac"

CLASSES="$OUT/holder-classes"
DEX="$OUT/holder-dex"
rm -rf "$CLASSES" "$DEX"
mkdir -p "$CLASSES" "$DEX" "$OUT"

echo ">>> javac 编译 holder"
"$JAVAC" -classpath "$ANDROID_JAR" -d "$CLASSES" \
    $(find "$HERE/holder/src" -name '*.java')

echo ">>> d8 转 dex"
"$D8" --lib "$ANDROID_JAR" --min-api 26 --output "$DEX" \
    $(find "$CLASSES" -name '*.class')

echo ">>> 打包 display-holder.jar"
mkdir -p "$OUT/jar"
cp "$DEX/classes.dex" "$OUT/jar/classes.dex"
(cd "$OUT/jar" && zip -q -X -FS "$OUT/display-holder.jar" classes.dex)

echo ">>> 完成: $OUT/display-holder.jar ($(stat -c%s "$OUT/display-holder.jar") 字节)"
