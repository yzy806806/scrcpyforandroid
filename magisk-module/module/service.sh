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
# 隧道监听端口：默认 22289，可在模块目录放一个 port 文件覆盖（内容就是端口号）
# ── 0. 补齐 ART 运行时环境 ──────────────────────────────────
# 部分 ROM（实测 MIUI 12 / Android 12）的 shell 里没有 BOOTCLASSPATH 等变量，
# 而 app_process 靠 BOOTCLASSPATH 定位 boot image（boot.art）。缺了它进程会
# 静默退出：RC=0、无任何输出、不写日志 —— 极难定位。这里从 zygote 进程的环境
# 复制过来（zygote 一定有完整的一套）。
_ZENV=""
for _zp in $(pgrep -f zygote64 2>/dev/null); do
    _env="/proc/$_zp/environ"
    [ -r "$_env" ] || continue
    [ -n "$_ZENV" ] || _ZENV="$_env"
    for _k in ANDROID_ROOT ANDROID_DATA ANDROID_ART_ROOT ANDROID_I18N_ROOT \
              ANDROID_TZDATA_ROOT ANDROID_ASSETS ANDROID_STORAGE BOOTCLASSPATH \
              DEX2OATBOOTCLASSPATH; do
        _v=$(tr '\0' '\n' < "$_env" | grep "^$_k=")
        [ -n "$_v" ] && export "$_v"
    done
    break
done

PORT=22289
[ -f "$MODDIR/port" ] && PORT=$(head -c 32 "$MODDIR/port" | tr -cd '0-9')
[ -n "$PORT" ] || PORT=22289
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
    # 默认不启动：holder 依赖 createVirtualDisplay，Android 12 起该调用会校验
    # "packageName must match the calling uid"（root 没有任何包名，必然被拒）。
    # 需要挂机时，在被控端放 /data/adb/modules/tunnel_server/enable_holder 文件后重启。
    [ -f "$MODDIR/enable_holder" ] || return 0
    return 0
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
