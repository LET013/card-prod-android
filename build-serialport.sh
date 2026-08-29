#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "=== 1. 编译 serialport AAR ==="
./gradlew :serialport:clean :serialport:assembleRelease

AAR="serialport/build/outputs/aar/serialport-release-1.0.aar"

echo ""
echo "=== 2. 复制到 app/libs/ ==="
cp -v "$AAR" app/libs/

echo ""
echo "=== 3. 复制到 serial-debug/app/libs/ ==="
cp -v "$AAR" serial-debug/app/libs/

echo ""
echo "=== 完成 ==="
echo "AAR 已同步到 app/libs/ 和 serial-debug/app/libs/"
