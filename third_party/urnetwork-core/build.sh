#!/bin/bash
# Builds liburnetwork.so (arm64) for MLMVPN Android. Usage: bash build.sh [out]
set -e
cd "$(dirname "$0")"
export GOROOT='I:\toolchains\go'
export PATH="/i/toolchains/go/bin:$PATH"
export GOCACHE='I:\toolchains\gocache'
export GOMODCACHE='I:\toolchains\gomodcache'
export GOTMPDIR='I:\toolchains\gotmp'
export GOPROXY='https://goproxy.cn,https://proxy.golang.org,direct'
export GOFLAGS='-mod=mod'
export CGO_ENABLED=0 GOOS=android GOARCH=arm64
out="${1:-liburnetwork-arm64}"
start=$(date +%s)
go build -trimpath -ldflags "-s -w -checklinkname=0" -o "$out" .
echo "built $out in $(( $(date +%s) - start ))s, $(stat -c %s "$out") bytes"
