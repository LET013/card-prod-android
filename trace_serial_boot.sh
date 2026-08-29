#!/bin/bash
set -e

PKG="com.xingyao.card"
LOG_REMOTE="/sdcard/serial_boot.log"
LOG_LOCAL="./serial_boot.log"
DURATION=90

echo "=========================================="
echo "  串口启动 Trace 脚本"
echo "  目标: $PKG | 时长: ${DURATION}s"
echo "=========================================="

# 1. root
echo "[1/6] 获取 root..."
adb root
sleep 1

# 2. 清理
echo "[2/6] 停 App + 清旧日志..."
adb shell "am force-stop $PKG" 2>/dev/null
adb shell "rm -f $LOG_REMOTE"
sleep 2

# 3. 启动
echo "[3/6] 启动 App..."
adb shell "am start -n $PKG/.MainActivity" 2>/dev/null
echo "  等待进程出现..."

PID=""
for i in $(seq 1 50); do
  PID=$(adb shell "pidof $PKG" 2>/dev/null | tr -d '\r\n' | awk '{print $1}')
  if [ -n "$PID" ] && [ "$PID" != "" ]; then
    echo "  PID: $PID"
    break
  fi
  sleep 0.2
done

if [ -z "$PID" ]; then
  echo "错误: 进程未启动！"
  exit 1
fi

# 4. attach strace
echo "[4/6] Attach strace, 抓 ${DURATION}s..."
adb shell "strace -f -p $PID -e trace=read,write -v -x -s 512 -o $LOG_REMOTE" &
STRACE_BG=$!

sleep $DURATION

# 5. 停止 strace
echo "[5/6] 停止 strace..."
kill $STRACE_BG 2>/dev/null || true
adb shell "killall strace" 2>/dev/null || true
sleep 2

# 6. 拉日志
echo "[6/6] 拉取日志..."
adb pull "$LOG_REMOTE" "$LOG_LOCAL" 2>/dev/null

if [ -f "$LOG_LOCAL" ] && [ -s "$LOG_LOCAL" ]; then
  LINE_COUNT=$(wc -l < "$LOG_LOCAL" | tr -d ' ')
  echo ""
  echo "=========================================="
  echo "  日志: $LOG_LOCAL ($LINE_COUNT 行)"
  echo "=========================================="

  # 只提取串口相关的行（fd 199 和包含 dd cc 帧头的行）
  echo ""
  echo "=== 串口 fd 199 读写 (含帧头 dd cc) ==="
  grep -n "199\|dd.cc\|DD.CC" "$LOG_LOCAL" | grep -E "read|write" | head -200

  # 统计
  SERIAL_WRITES=$(grep -c "write(199," "$LOG_LOCAL" 2>/dev/null || echo 0)
  SERIAL_READS=$(grep -c "read(199," "$LOG_LOCAL" 2>/dev/null || echo 0)
  echo ""
  echo "=== 统计 ==="
  echo "  write(199) 次数: $SERIAL_WRITES"
  echo "  read(199) 次数:  $SERIAL_READS"

  echo ""
  echo "=== 完整日志已保存至: $LOG_LOCAL ==="
else
  echo ""
  echo "错误: 日志为空或不存在！"
  echo "  尝试检查: adb shell ls -la $LOG_REMOTE"
fi
