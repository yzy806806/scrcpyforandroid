#!/system/bin/sh
# Magisk 模块安装脚本
SKIPUNZIP=0

ui_print "- Scrcpy Multi-Session Tunnel"
ui_print "- 组件: QUIC tunnel server + display-holder"

# 运行目录（与被控端既有部署保持一致）
TUNNEL_DIR=/data/local/tmp

# 二进制与 dex 都随模块走（$MODPATH/bin）
set_perm_recursive $MODPATH/bin 0 0 0755 0755
set_perm $MODPATH/service.sh 0 0 0755
set_perm $MODPATH/uninstall.sh 0 0 0755

# PSK 不存在时生成一个（64 位 hex）
if [ ! -f "$TUNNEL_DIR/tunnel-key" ]; then
    ui_print "- 生成隧道 PSK: $TUNNEL_DIR/tunnel-key"
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$TUNNEL_DIR/tunnel-key"
    echo >> "$TUNNEL_DIR/tunnel-key"
    chmod 600 "$TUNNEL_DIR/tunnel-key"
else
    ui_print "- 复用已有 PSK: $TUNNEL_DIR/tunnel-key"
fi

ui_print "- 隧道端口默认 22289"
ui_print "- 如需自定义: 在模块目录建 port 文件写端口号"

ui_print "- 安装完成，重启生效"
