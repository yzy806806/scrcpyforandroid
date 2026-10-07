#!/bin/bash
# 打包 Magisk 模块 zip：QUIC 隧道服务端（Go/arm64）+ display-holder（dex）
#
# 依赖：go 1.21+、JDK 17+、Android cmdline-tools 的 d8、Android platform 的 android.jar
#   （Go 源码在 tunnel/，本脚本只负责模块打包）
# 用法：bash module/build.sh            产物在 build/tunnel_server_v<version>.zip
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="${OUT_DIR:-$ROOT/build}"
STAGE="$OUT/module"

VERSION="$(sed -n 's/^version=//p' "$HERE/module.prop" | tr -d '\r')"
ZIP="$OUT/tunnel_server_${VERSION}.zip"

echo ">>> [1/4] 构建 display-holder"
bash "$HERE/build-holder.sh"

echo ">>> [2/4] 构建 tunnel-server (android/arm64)"
mkdir -p "$OUT"
(cd "$ROOT/tunnel" && CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
    go build -ldflags="-s -w" -o "$OUT/tunnel-server" ./cmd)
ls -la "$OUT/tunnel-server"

echo ">>> [3/4] 组装模块目录"
rm -rf "$STAGE"
mkdir -p "$STAGE/bin"
cp "$HERE/module.prop" "$HERE/service.sh" "$HERE/customize.sh" "$HERE/uninstall.sh" "$STAGE/"
cp "$OUT/tunnel-server" "$STAGE/bin/"
cp "$OUT/display-holder.jar" "$STAGE/bin/"
chmod 0755 "$STAGE"/service.sh "$STAGE"/customize.sh "$STAGE"/uninstall.sh "$STAGE"/bin/*

echo ">>> [4/4] 打包 zip"
rm -f "$ZIP"
(cd "$STAGE" && zip -q -r -X "$ZIP" .)
echo ">>> 完成: $ZIP ($(stat -c%s "$ZIP") 字节)"
unzip -l "$ZIP"
