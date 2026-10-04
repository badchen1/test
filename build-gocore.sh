#!/usr/bin/env bash
#
# 把 Go 解密核心编译成 Android 可用的 .aar。
#
# 前置条件：
#   1. Go 1.21+（建议 1.22 以上）
#   2. gomobile：go install golang.org/x/mobile/cmd/gomobile@latest
#   3. Android SDK + NDK（NDK 版本建议 r26 或更新）
#   4. 环境变量 ANDROID_HOME 指向 SDK 根目录
#
# 用法：
#   ./build-gocore.sh              # 默认编译全部 ABI
#   ./build-gocore.sh arm64        # 只编 arm64-v8a
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GOCORE_DIR="$SCRIPT_DIR/gocore"
OUT_DIR="$SCRIPT_DIR/mobile/app/libs"

# --- 环境检查 ---

if ! command -v go >/dev/null 2>&1; then
    echo "❌ 未找到 go，请先安装 Go 1.21+" >&2
    exit 1
fi

if ! command -v gomobile >/dev/null 2>&1; then
    # 允许通过 $(go env GOPATH)/bin 找到 gomobile
    GOPATH_BIN="$(go env GOPATH)/bin"
    if [ -x "$GOPATH_BIN/gomobile" ]; then
        export PATH="$PATH:$GOPATH_BIN"
    else
        echo "❌ 未找到 gomobile，请执行：" >&2
        echo "     go install golang.org/x/mobile/cmd/gomobile@latest" >&2
        echo "     go install golang.org/x/mobile/cmd/gobind@latest" >&2
        exit 1
    fi
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "⚠️  未设置 ANDROID_HOME / ANDROID_SDK_ROOT" >&2
    echo "    gomobile 需要 Android SDK 与 NDK 才能编译 .aar" >&2
    echo "    例如：export ANDROID_HOME=\$HOME/Android/Sdk" >&2
    exit 1
fi

# --- 选择目标 ABI ---

case "${1:-all}" in
    arm64)  TARGETS=(-target=android/arm64) ;;
    arm)    TARGETS=(-target=android/arm) ;;
    x86_64) TARGETS=(-target=android/amd64) ;;
    all|"") TARGETS=(-target=android/arm64,android/arm,android/amd64) ;;
    *)
        echo "❌ 未知目标：$1（可用：arm64 / arm / x86_64 / all）" >&2
        exit 1
        ;;
esac

mkdir -p "$OUT_DIR"

echo "▶ 编译 Go 核心 -> $OUT_DIR/gocore.aar"
echo "  目标：${TARGETS[*]}"

cd "$GOCORE_DIR"

# gomobile bind 需要先下载依赖
echo "▶ 解析依赖…"
GOFLAGS=-mod=mod go mod tidy

echo "▶ 生成 .aar（首次编译较慢，需数分钟）…"
gomobile bind \
    "${TARGETS[@]}" \
    -androidapi 24 \
    -o "$OUT_DIR/gocore.aar" \
    -v \
    .

echo
echo "✅ 完成：$OUT_DIR/gocore.aar"
echo "   接下来可直接用 Android Studio 打开 mobile/ 目录并构建 APK。"
