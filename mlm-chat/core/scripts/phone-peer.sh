#!/usr/bin/env bash
# Run the reference node (peer-cli) on one phone through adb, interactively: type its commands
# (card, add, send, read, history, sn, quit) and read its events.
#
#   bash phone-peer.sh <serial> <name>        serial from `adb devices`
#
# Build first, in core/:  cargo ndk --platform 24 -t arm64-v8a build --release --bin peer-cli
# The phone keeps its test identity in /data/local/tmp/mlmchat/me until you remove it:
#   adb -s <serial> shell rm -rf /data/local/tmp/mlmchat
set -euo pipefail
SERIAL="${1:?serial from adb devices}"
NAME="${2:?a name for this phone}"
WINGET_ADB="$HOME/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe"
if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb; else ADB="$WINGET_ADB"; fi
fi
here="$(cd "$(dirname "$0")" && pwd)"
BIN="$here/../target/aarch64-linux-android/release/peer-cli"
[ -f "$BIN" ] || { echo "peer-cli for Android is not built yet (see the top of this file)"; exit 1; }
# Git Bash would rewrite the phone's /data/... paths into Windows paths
export MSYS_NO_PATHCONV=1
local_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

# adb shell forwards stdin: only the last, interactive call may read it
"$ADB" -s "$SERIAL" shell mkdir -p /data/local/tmp/mlmchat </dev/null
"$ADB" -s "$SERIAL" push "$(local_path "$BIN")" /data/local/tmp/mlmchat/peer-cli </dev/null >/dev/null
"$ADB" -s "$SERIAL" shell chmod 755 /data/local/tmp/mlmchat/peer-cli </dev/null
"$ADB" -s "$SERIAL" shell "cd /data/local/tmp/mlmchat && { [ -f me/chat.db ] || ./peer-cli me init '$NAME'; }" </dev/null
echo "--- type commands (help for the list, quit to stop) ---"
exec "$ADB" -s "$SERIAL" shell "cd /data/local/tmp/mlmchat && ./peer-cli me run"
