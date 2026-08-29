#!/bin/bash
# ============================================================
#  串口诊断脚本 v2 — 完整 root cause 分析
#  对比场景：仅你的 App vs sark App + 你的 App
# ============================================================
set -e

SARK_PKG="com.huiningdianzi.sark"
TRACE_REMOTE="/sdcard/strace.log"
TRACE_LOCAL="./serial_trace_full.log"
DURATION=25

echo "========================================="
echo "  串口诊断脚本 v2"
echo "  对比：仅有你的app vs sark+你的app"
echo "========================================="

adb root
sleep 2

# ────────────────────────────────────────────
# 工具函数
# ────────────────────────────────────────────
dump_serial_info() {
    local label="$1"
    echo ""
    echo ">>> [$label] 串口状态 <<<"
    echo "  /proc/tty/driver/serial:"
    adb shell "cat /proc/tty/driver/serial 2>/dev/null" | head -10
    echo ""
    echo "  ls -la /dev/ttyS* /dev/ttyUSB*:"
    adb shell "ls -la /dev/ttyS* /dev/ttyUSB* 2>/dev/null || echo '(无)'"
    echo ""
    echo "  GPIO exported:"
    adb shell "ls /sys/class/gpio/ 2>/dev/null | head -20 || echo '(无)'"
    echo ""
    echo "  kernel dmesg (最后5行 uart/tty相关):"
    adb shell "dmesg | grep -iE 'uart|ttyS|serial|rs485' | tail -5"
    echo ""
}

# ────────────────────────────────────────────
# 场景 A: 只有 sark app（baseline）
# ────────────────────────────────────────────
echo ""
echo "========================================="
echo "  场景 A: 启动 sark app 并完整追踪初始化"
echo "========================================="

echo "[A1] 停止所有相关进程..."
adb shell "am force-stop $SARK_PKG" 2>/dev/null || true
sleep 2

dump_serial_info "sark 停止后"

echo ""
echo "[A2] 使用 wrap 属性从 sark 进程启动时捕获全量 syscall..."
adb shell "pkill strace" 2>/dev/null || true
adb shell "rm -f $TRACE_REMOTE"
sleep 1

# Android wrap 机制：设置 wrap.<pkg> 后，系统启动该 app 时会自动用指定命令包裹
# 注意 setprop 值上限 92 字节，需精简参数
adb shell "setprop wrap.$SARK_PKG 'logwrapper strace -f -e trace=openat,read,write,ioctl -v -s 512 -o $TRACE_REMOTE'"

echo "  启动 sark app（strace 从 init 阶段就跟进）..."
adb shell "am start -n \$(cmd package resolve-activity --brief $SARK_PKG | tail -n1)"

# 等待 app 启动
sleep 8

# 获取 sark PID
SARK_PID=$(adb shell "pidof $SARK_PKG" 2>/dev/null | tr -d '\r\n' | awk '{print $1}')
echo "  sark PID: $SARK_PID"

# 继续追踪
sleep $DURATION

# 停止 sark 并清除 wrap 属性
adb shell "am force-stop $SARK_PKG"
adb shell "setprop wrap.$SARK_PKG ''"
sleep 2

# 拉取日志
adb pull "$TRACE_REMOTE" "$TRACE_LOCAL" 2>/dev/null

dump_serial_info "sark 运行中"

echo ""
echo "--- [场景A] sark 完整 strace ---"
if [ -f "$TRACE_LOCAL" ] && [ -s "$TRACE_LOCAL" ]; then
    echo "日志行数: $(wc -l < "$TRACE_LOCAL" | tr -d ' ')"
    
    echo ""
    echo "=== openat() 调用（查找串口设备路径）==="
    grep "openat.*tty" "$TRACE_LOCAL" | head -20 || echo "(无tty openat)"
    
    echo ""
    echo "=== 串口 ioctl（tcgetattr/tcsetattr）==="
    grep -E "TCGETS|TCSETS" "$TRACE_LOCAL" | head -20 || echo "(无)"
    
    echo ""
    echo "=== 串口 read()/write()（仅前30条）==="
    grep -E "read|write" "$TRACE_LOCAL" | head -30 || echo "(无)"
    
    echo ""
    echo "=== GPIO/sysfs 写入（sark 是否使能了硬件？）==="
    grep -E "write.*(gpio|export|direction|value)" "$TRACE_LOCAL" | head -20 || echo "(无 GPIO 写入)"
    
    echo ""
    echo "=== openat sysfs 文件 ==="
    grep "openat.*sys" "$TRACE_LOCAL" | head -20 || echo "(无 sysfs openat)"
    
    echo ""
    echo "=== 完整日志保存至: $TRACE_LOCAL ==="
else
    echo "日志为空，strace 可能未成功启动"
fi

# ────────────────────────────────────────────
# 场景 B: sark 停止后检查串口状态
# ────────────────────────────────────────────
echo ""
echo "========================================="
echo "  场景 B: sark 停止后"
echo "========================================="

dump_serial_info "sark 停止后"

echo ""
echo "[B1] 检查串口是否还被占用..."
adb shell "lsof 2>/dev/null | grep tty || echo '(lsof 不可用，跳过)'"

echo ""
echo "========================================="
echo "  诊断完成"
echo "========================================="
echo ""
echo "关键检查项:"
echo "  1. GPIO/sysfs 写入 → sark 是否使能了硬件收发器?"
echo "  2. 场景A的 openat() 输出 → 确认 sark 使用的设备路径"
echo "  3. 场景A的 TCGETS/TCSETS → 确认 sark 的完整串口参数"
echo "  4. 场景A vs 场景B 的 /proc/tty/driver/serial → RX计数器是否在 sark 停止后归零"
echo ""
echo "完整日志: $TRACE_LOCAL"
