#!/usr/bin/env bash
#
# 校验 Go 门面能被 gomobile 正确绑定，并核对 Kotlin 侧调用的方法名一致。
#
# 用途：gomobile 对 Go 函数签名有严格限制（如返回值数量、参数类型），
# 一旦违反在 Gradle 构建阶段才会报错，排查成本高。此脚本提前拦截。
#
# 用法：./verify-bindings.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GOCORE_DIR="$SCRIPT_DIR/gocore"
KT_DIR="$SCRIPT_DIR/mobile/app/src/main/java/com/kugo/mobile"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail=0

echo "▶ 1/4 编译 Go 核心"
if ! (cd "$GOCORE_DIR" && GOFLAGS=-mod=mod go build ./...); then
    echo "❌ Go 编译失败"
    exit 1
fi
echo "  ✅ 通过"

echo "▶ 2/4 检查 gomobile 绑定可行性"
if ! command -v gobind >/dev/null 2>&1; then
    echo "  ⚠️  未找到 gobind，跳过。安装：go install golang.org/x/mobile/cmd/gobind@latest"
else
    BIND_LOG="$TMP_DIR/bind.log"
    (cd "$GOCORE_DIR" && gobind -lang=java -outdir="$TMP_DIR/out" . >"$BIND_LOG" 2>&1) || true

    # gobind 对不支持的签名会打印错误但不返回非零退出码，需查日志。
    if grep -qE "too many result values|must be of type error|unsupported" "$BIND_LOG"; then
        echo "❌ 存在 gomobile 不支持的签名："
        grep -E "too many result values|must be of type error|unsupported" "$BIND_LOG" | sed 's/^/     /'
        fail=1
    else
        echo "  ✅ 绑定可行"

        # 从生成的 Java 里提取真实方法名，与 Kotlin 调用比对。
        GEN_JAVA="$TMP_DIR/out/java/gocore/Gocore.java"
        if [ -f "$GEN_JAVA" ]; then
            echo "▶ 3/4 核对 Kotlin 调用与生成 API"
            grep -oE 'public static native [A-Za-z0-9_<>.]+ ([a-zA-Z0-9_]+)\(' "$GEN_JAVA" \
                | sed -E 's/.* ([a-zA-Z0-9_]+)\($/\1/' | sort -u > "$TMP_DIR/gen_api.txt"

            # 先剔除行注释，避免注释里提到的 Gocore.xxx 造成误报。
            grep -v '^\s*//' "$KT_DIR/core/DecryptEngine.kt" \
                | grep -v '^\s*\*' \
                | grep -ohE 'Gocore\.[a-zA-Z0-9_]+' \
                | sed 's/Gocore\.//' | sort -u > "$TMP_DIR/kt_calls.txt"

            mismatch=0
            while read -r m; do
                [ -z "$m" ] && continue
                if ! grep -qx "$m" "$TMP_DIR/gen_api.txt"; then
                    echo "     ❌ Kotlin 调用了不存在的方法：Gocore.$m"
                    mismatch=1
                fi
            done < "$TMP_DIR/kt_calls.txt"

            if [ "$mismatch" -eq 0 ]; then
                echo "  ✅ 所有 Kotlin 调用均有对应 API（$(wc -l < "$TMP_DIR/kt_calls.txt" | tr -d ' ') 个）"
            else
                fail=1
            fi
        fi
    fi
fi

echo
echo "▶ 4/4 核对错误码常量一致性"

python3 - "$GOCORE_DIR/decryptor.go" "$KT_DIR/core/DecryptEngine.kt" <<'PY' || fail=1
import re, sys

go_path, kt_path = sys.argv[1], sys.argv[2]

# --- Go 侧：取 const 块内 Code* int32 = iota 起始的顺序值 ---
go_src = open(go_path, encoding='utf-8').read()
codes = {}
for block in re.findall(r'const\s*\((.*?)\n\)', go_src, re.S):
    if 'iota' not in block:
        continue
    n, started = 0, False
    for line in block.split('\n'):
        line = line.split('//')[0].strip()
        m = re.match(r'^(Code[A-Za-z0-9]+)\s+int32\s*=\s*iota', line)
        if m:
            started = True
            codes[m.group(1)] = 0
            n = 1
            continue
        if started:
            m2 = re.match(r'^(Code[A-Za-z0-9]+)\s*$', line)
            if m2:
                codes[m2.group(1)] = n
                n += 1
            elif line:
                started = False

if not codes:
    print("     ⚠️  未能从 Go 侧解析出错误码，跳过")
    sys.exit(0)

# --- Kotlin 侧：const val CODE_X = N ---
kt_src = open(kt_path, encoding='utf-8').read()
kt = {m.group(1): int(m.group(2))
      for m in re.finditer(r'const\s+val\s+(CODE_[A-Z_]+)\s*=\s*(\d+)', kt_src)}

def to_kt(name):
    """CodeIOError -> CODE_IO_ERROR，正确处理连续大写缩写。"""
    s = name[4:]                                   # 去掉 Code
    s = re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1_\2', s)  # IOError -> IO_Error
    s = re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', s)     # UnsupportedFormat -> Unsupported_Format
    return 'CODE_' + s.upper()

bad = 0
for gname, gval in codes.items():
    kname = to_kt(gname)
    if kname not in kt:
        print(f"     ❌ Go 的 {gname}({gval}) 在 Kotlin 侧缺少 {kname}")
        bad += 1
    elif kt[kname] != gval:
        print(f"     ❌ {gname} 码值不一致：Go={gval} Kotlin={kt[kname]}")
        bad += 1

if bad == 0:
    print(f"  ✅ 错误码全部一致（{len(codes)} 个）")
else:
    sys.exit(1)
PY

echo
if [ "$fail" -eq 0 ]; then
    echo "✅ 全部校验通过"
else
    echo "❌ 校验未通过，请修复上述问题"
    exit 1
fi
