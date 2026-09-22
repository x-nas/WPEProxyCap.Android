#!/usr/bin/env bash
# 把 core/（mihomo 封装）编成 app/libs/wpccore.aar。
#
# 用法（Git Bash，在仓库根目录）：  bash tools/build-core.sh
# 环境：Go 1.26、gomobile / gobind、Android SDK + NDK r28、JDK 17+。
#       默认从 ~/AndroidDev 取（本机的便携工具链），可用同名环境变量覆盖。
#
# ⚠️ 构建标签必须带 cmfa（原因见 core/wpccore.go 顶部）；with_gvisor 给 TUN 用用户态协议栈。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV="${WPC_ANDROID_DEV:-/c/Users/Gary/AndroidDev}"

export PATH="$DEV/go/bin:$DEV/gopath/bin:$DEV/jdk/bin:$PATH"
export GOTOOLCHAIN=local
# proxy.golang.org 在本机不可达（被墙），默认走 goproxy.cn；可用 GOPROXY 环境变量覆盖
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
export GOPATH="${GOPATH:-$(cygpath -w "$DEV/gopath")}"
export GOMODCACHE="${GOMODCACHE:-$GOPATH\\pkg\\mod}"
export ANDROID_HOME="${ANDROID_HOME:-$(cygpath -w "$DEV/sdk")}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(cygpath -w "$DEV/sdk/ndk/28.2.13676358")}"
export JAVA_HOME="${JAVA_HOME:-$(cygpath -w "$DEV/jdk")}"

cd "$ROOT/core"

echo "== go mod tidy"
go get github.com/metacubex/mihomo@v1.19.31
go get -tool golang.org/x/mobile/cmd/gobind@latest
go mod tidy

echo "== host compile check (windows, tags cmfa)"
go build -tags with_gvisor,cmfa ./...

echo "== gomobile bind (android arm64 + x86_64, API 26)"
mkdir -p "$ROOT/app/libs"
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi 26 \
  -javapkg com.wpe64.wpc \
  -tags with_gvisor,cmfa \
  -trimpath \
  -ldflags "-s -w -buildid= -X github.com/metacubex/mihomo/constant.Version=1.19.31" \
  -o "$ROOT/app/libs/wpccore.aar" \
  .

ls -la "$ROOT/app/libs"
echo "CORE-BUILD-OK"
