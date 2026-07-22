#!/bin/bash

set -e

echo "=========================================="
echo "  UniApp 发卡机 客户端构建脚本"
echo "=========================================="

# 项目路径配置
UNIAPP_DIR="uniapp"
ANDROID_ASSETS_DIR="app/src/main/assets"
BUILD_OUTPUT_DIR="$UNIAPP_DIR/dist/build/h5"

echo ""
echo "1. 检查 Node.js 环境..."
if ! command -v node &> /dev/null; then
    echo "❌ 错误: 未找到 Node.js，请先安装 Node.js"
    exit 1
fi
echo "✅ Node.js 版本: $(node -v)"

echo ""
echo "2. 进入 UniApp 目录..."
cd "$UNIAPP_DIR"

echo ""
echo "3. 检查依赖是否已安装..."
if [ ! -d "node_modules" ]; then
    echo "⚠️  依赖未安装，正在安装..."
    npm install --legacy-peer-deps
    if [ $? -ne 0 ]; then
        echo "❌ 依赖安装失败"
        exit 1
    fi
    echo "✅ 依赖安装成功"
fi

echo ""
echo "4. 开始构建 H5 生产版本..."
npm run build:h5
if [ $? -ne 0 ]; then
    echo "❌ 构建失败"
    exit 1
fi
echo "✅ 构建成功"

echo ""
echo "5. 检查 static 资源目录..."
if [ -d "static" ]; then
    cp -rf "static" "dist/build/h5/"
    echo "✅ 顶层 static 目录复制成功"
elif [ -d "dist/build/h5/static" ]; then
    echo "✅ uni-app 已将 src/static 打包到构建产物"
else
    echo "❌ 构建产物中未找到 static 资源"
    exit 1
fi

echo ""
echo "6. 复制构建产物到 Android assets 目录..."
cd ..

# 清空目标目录
rm -rf "$ANDROID_ASSETS_DIR"/*

# 复制构建产物
cp -rf "$BUILD_OUTPUT_DIR"/* "$ANDROID_ASSETS_DIR"/

if [ $? -ne 0 ]; then
    echo "❌ 复制失败"
    exit 1
fi

echo "✅ 复制成功"

echo ""
echo "=========================================="
echo "  构建完成！"
echo ""
echo "  构建产物已复制到:"
echo "  $ANDROID_ASSETS_DIR"
echo ""
echo "  可以开始构建 Android 项目了"
echo "=========================================="
