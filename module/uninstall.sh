#!/system/bin/sh
# Magisk 模块卸载脚本 —— 收拾常驻进程与运行时文件
# 注意：不还原 stay_on_while_plugged_in（无法得知用户原值，且该项多为用户自设）

pkill -f com.scrcpymultisession.holder 2>/dev/null
killall tunnel-server 2>/dev/null
pkill -f "tunnel-server -mode server" 2>/dev/null

rm -rf /data/local/tmp/display-holder
rm -f /data/local/tmp/display-holder.jar

# 隧道二进制与 PSK 放在 /data/local/tmp，随模块一起清掉
rm -f /data/local/tmp/tunnel-server /data/local/tmp/tunnel-server.log

# adbd 恢复成仅 USB
setprop service.adb.tcp.port -1
stop adbd
sleep 1
start adbd
