#!/system/bin/sh
# Magisk late_start service —— 启动并守护两个常驻组件：
#   1. tunnel-server      QUIC 隧道（PSK + TLS 1.3），把远程连接转发到本机 adbd
#   2. display-holder     虚拟显示持有者（app_process），让应用在主控端断开后继续运行
MODDIR=${0%/*}
BIN=$MODDIR/bin
RUN=/data/local/tmp
HOLDER_DIR=$RUN/display-holder
LOG=$RUN/tunnel-server.log
HOLDER_LOG=$HOLDER_DIR/holder.log
PORT=22289
TARGET=127.0.0.1:5555

# app_process 需要 Android 运行时环境（init 里有，但显式导出更稳）
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
export ANDROID_RUNTIME_ROOT=/apex/com.android.runtime
export ANDROID_TZDATA_ROOT=/apex/com.android.tzdata
export PATH=/system/bin:/system/xbin:$PATH

mkdir -p "$HOLDER_DIR" 2>/dev/null
chmod 0777 "$HOLDER_DIR" 2>/dev/null

# ── 1. 等网络 ────────────────────────────────────────────────
i=0
while [ $i -lt 60 ]; do
    ip addr show wlan0 2>/dev/null | grep -q "inet " && break
    i=$((i + 1))
    sleep 2
done
sleep 3

# ── 2. adbd 监听 TCP + 防火墙（5555 只允许回环，外部一律丢弃）──
setprop service.adb.tcp.port 5555
stop adbd
sleep 1
start adbd

iptables -C INPUT -i lo -p tcp --dport 5555 -j ACCEPT 2>/dev/null ||
    iptables -I INPUT 1 -i lo -p tcp --dport 5555 -j ACCEPT
iptables -C INPUT -p tcp --dport 5555 -j DROP 2>/dev/null ||
    iptables -A INPUT -p tcp --dport 5555 -j DROP

# ── 3. 充电时保持唤醒 ────────────────────────────────────────
# 虚拟显示用的 FLAG_DEVICE_DISPLAY_GROUP 会跟随主屏电源状态：主屏一休眠，
# 虚拟显示随即 OFF，其上的应用被冻结（实测 CPU 归零）。挂机必须保持唤醒。
# 换成 FLAG_OWN_DISPLAY_GROUP 虽能独立供电，但 SurfaceFlinger 无法镜像该显示，
# 主控端就看不到画面 —— 两害相权，选保持唤醒。
# 只在用户没设置过时写入，避免覆盖用户自己的选择
case "$(settings get global stay_on_while_plugged_in 2>/dev/null)" in
    0 | "" | null)
        settings put global stay_on_while_plugged_in 7
        ;;
esac

# ── 4. 原生显示参数（虚拟显示必须与主屏同尺寸/密度，否则应用行为异常）──
SIZE=$(wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}
H=${SIZE#*x}
DPI=$(wm density 2>/dev/null | grep -oE '[0-9]+' | tail -1)
[ -z "$W" ] && W=1080
[ -z "$H" ] && H=2400
[ -z "$DPI" ] && DPI=480

# ── 5. 启动函数 ──────────────────────────────────────────────
start_tunnel() {
    [ -x "$BIN/tunnel-server" ] || return 0
    pgrep -f "tunnel-server -mode server" >/dev/null 2>&1 && return 0
    nohup "$BIN/tunnel-server" -mode server -listen ":$PORT" -target "$TARGET" >>"$LOG" 2>&1 &
}

start_holder() {
    [ -f "$BIN/display-holder.jar" ] || return 0
    pgrep -f com.scrcpymultisession.holder >/dev/null 2>&1 && return 0
    # --restore 1: 起来后按上次的 slot 记录重建显示并重新拉起应用
    CLASSPATH="$BIN/display-holder.jar" nohup app_process / \
        com.scrcpymultisession.holder.DisplayHolder \
        --width "$W" --height "$H" --dpi "$DPI" \
        --dir "$HOLDER_DIR" --surface reader --restore 1 \
        >>"$HOLDER_LOG" 2>&1 &
}

start_tunnel
start_holder

# ── 6. 守护 ──────────────────────────────────────────────────
# holder 一死，它的虚拟显示随之销毁 → 应用被踢回主显并暂停。必须自动拉起。
while true; do
    sleep 20
    start_tunnel
    start_holder
done &
