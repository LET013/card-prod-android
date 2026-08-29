#!/bin/bash
set -e

PKG="com.huiningdianzi.sark"
LOG_REMOTE="/sdcard/serial_trace.log"
LOG_LOCAL="./serial_trace.log"
DURATION=20

echo "=========================================="
echo "  手动 Attach 模式"
echo "  目标 App: $PKG"
echo "=========================================="

PID=$(adb shell "pidof $PKG" 2>/dev/null | tr -d '\r\n' | awk '{print $1}')

if [ -z "$PID" ]; then
    echo "错误: $PKG 未运行，请先在设备上手动打开 App"
    exit 1
fi

echo "找到进程 PID: $PID"
echo "Attach strace，监听 ${DURATION} 秒..."
adb shell "strace -f -p $PID -e trace=write -v -x -s 512 -o $LOG_REMOTE" &
STRACE_BG=$!
sleep $DURATION

kill $STRACE_BG 2>/dev/null || true
adb shell "killall strace" 2>/dev/null || true
sleep 1

adb pull "$LOG_REMOTE" "$LOG_LOCAL" 2>/dev/null && echo "日志已保存到: $LOG_LOCAL" || echo "拉取失败"

if [ -f "$LOG_LOCAL" ] && [ -s "$LOG_LOCAL" ]; then
    echo "--- 日志内容 ---"
    cat "$LOG_LOCAL"
else
    echo "日志文件为空或不存在"
fi
