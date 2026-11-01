#!/usr/bin/env bash
# Capture a focused logcat from the connected phone for MLM VPN debugging.
#
# Usage:
#   ./scripts/grab-log.sh            # clear the buffer, then follow until Ctrl-C
#   ./scripts/grab-log.sh dump       # just dump what is already in the buffer
#
# Why a script: adb is installed under a WinGet package path with spaces in it, and the
# interesting lines are a handful of tags buried in a very chatty Samsung log. Filtering to
# the app's own PID keeps the core's own output (which is logged under generic tags) instead
# of losing it to a tag allow-list.

set -u

ADB="/c/Users/user/AppData/Local/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe"
PKG="com.mlmvpn.scanner"

if [ ! -x "$ADB" ]; then
    echo "adb not found at: $ADB" >&2
    echo "Re-run: winget install --id Google.PlatformTools" >&2
    exit 1
fi

if ! "$ADB" devices | grep -q "device$"; then
    echo "No authorised device. Plug the phone in, enable USB debugging, and accept the prompt." >&2
    "$ADB" devices >&2
    exit 1
fi

PID="$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
if [ -z "$PID" ]; then
    echo "$PKG is not running — start the app first, then re-run this." >&2
    exit 1
fi
echo "Following $PKG (pid $PID). Ctrl-C to stop." >&2

if [ "${1:-follow}" = "dump" ]; then
    "$ADB" logcat -d --pid="$PID" -v time
else
    "$ADB" logcat -c
    "$ADB" logcat --pid="$PID" -v time
fi
