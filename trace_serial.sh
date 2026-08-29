#!/bin/bash
set -e

PKG="com.huiningdianzi.sark"
LOG_REMOTE="/sdcard/serial_trace.log"
LOG_LOCAL="./serial_trace.log"
DURATION=20

echo "=========================================="
echo "  串口指令拦截脚本"
echo "  目标 App: $PKG"
echo "=========================================="

# 1. adb root
echo ""
echo "[1/6] 获取 root 权限..."
adb root
sleep 2

# 2. 记录串口计数器初始值
echo ""
echo "[2/6] 记录串口 rx 计数器初始值..."
echo "--- 初始值 ---"
adb shell "cat /proc/tty/driver/serial 2>/dev/null | grep -E '^\s*5:'" || echo "(无法读取)"

# 3. 停止 App
echo ""
echo "[3/6] 停止目标 App..."
adb shell "am force-stop $PKG"
sleep 2
echo "App 已停止"

# 4. 自动获取 Launcher Activity 并启动
echo ""
echo "[4/6] 获取启动 Activity 并启动 App..."

# 尝试方法1：直接用 am start -n 自动解析
echo "正在探测 Launcher Activity..."
LAUNCHER=$(adb shell "cmd package resolve-activity --brief $PKG | tail -n1" 2>/dev/null | tr -d '\r\n')

# 方法2：如果 cmd 不支持，用 dumpsys
if [ -z "$LAUNCHER" ]; then
    LAUNCHER=$(adb shell "dumpsys package $PKG | grep -A1 'android.intent.action.MAIN' | grep -oP '$PKG/\K[^ ]+' | head -1" 2>/dev/null | tr -d '\r\n')
fi

if [ -n "$LAUNCHER" ] && [ "$LAUNCHER" != "" ]; then
    echo "Launcher Activity: $PKG/$LAUNCHER"
    adb shell "am start -n $PKG/$LAUNCHER" > /dev/null 2>&1 &
else
    echo "无法自动探测 Activity，尝试 am start 包名方式..."
    adb shell "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG" > /dev/null 2>&1 &
fi

# 轮询等待 App 进程出现
echo "等待 App 进程启动..."
PID=""
for i in $(seq 1 100); do
    PID=$(adb shell "pidof $PKG" 2>/dev/null | tr -d '\r\n' | awk '{print $1}')
    if [ -n "$PID" ] && [ "$PID" != "" ]; then
        echo "找到进程 PID: $PID"
        break
    fi
    sleep 0.1
done

if [ -z "$PID" ]; then
    echo "=========================================="
    echo "  无法自动启动 App！"
    echo "  请在设备上手动打开 App，然后运行："
    echo "  ./trace_serial_attach.sh"
    echo "=========================================="
    exit 1
fi

# 5. attach strace (同时抓 ioctl 和 write)
echo ""
echo "[5/6] Attach strace 到 PID $PID，监听 ${DURATION} 秒..."
adb shell "strace -f -p $PID -e trace=ioctl,write -v -x -s 512 -o $LOG_REMOTE" &
STRACE_BG=$!
sleep $DURATION

# 停止 strace
echo "停止 strace..."
kill $STRACE_BG 2>/dev/null || true
adb shell "killall strace" 2>/dev/null || true
sleep 1

# 6. 拉取日志
echo ""
echo "[6/6] 拉取日志..."
adb pull "$LOG_REMOTE" "$LOG_LOCAL" 2>/dev/null && echo "日志已保存到: $LOG_LOCAL" || echo "拉取失败"

# 对比计数器
echo ""
echo "--- 最终 rx 计数器 ---"
adb shell "cat /proc/tty/driver/serial 2>/dev/null | grep -E '^\s*5:'" || echo "(无法读取)"

echo ""
echo "=========================================="
echo "  拦截完成"
echo "=========================================="

# 显示日志
if [ -f "$LOG_LOCAL" ] && [ -s "$LOG_LOCAL" ]; then
    LINE_COUNT=$(wc -l < "$LOG_LOCAL" | tr -d ' ')
    echo ""
    echo "--- 日志内容 ($LINE_COUNT 行) ---"
    cat "$LOG_LOCAL"
else
    echo ""
    echo "日志文件为空或不存在"
fi
