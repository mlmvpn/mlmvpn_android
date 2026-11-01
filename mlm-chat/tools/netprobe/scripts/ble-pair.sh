#!/usr/bin/env bash
# Two phones on one computer: install netprobe on both, make a card on each, give each phone the
# other's card, start the Bluetooth test on both, and print both results (logcat tag netprobe).
#
#   scripts/ble-pair.sh SERIAL_A SERIAL_B [range_seconds] [apk]
#
# SERIAL_A becomes the GATT server (its card is remade until its session id is the smaller one):
# put an Android 11-or-older phone with location off there, since it cannot scan.
# The cards travel only phone -> this script -> phone (never into a log or a file that stays).
# The app needs its Bluetooth permissions; the first run on a phone asks for them on screen.
set -u
A="$1"; B="$2"; RANGE="${3:-300}"
APK="${4:-$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk}"
P=com.mlmchat.netprobe
for s in "$A" "$B"; do adb -s "$s" install -r "$APK" | tail -1; done
for s in "$A" "$B"; do MSYS_NO_PATHCONV=1 adb -s "$s" shell am start -n $P/.MainActivity --es run make_card >/dev/null 2>&1; done
sleep 4
sid() { local b="${1#*:}"; b=$(printf '%s' "$b" | tr '_-' '/+'); while [ $(( ${#b} % 4 )) -ne 0 ]; do b="$b="; done; printf '%s' "$b" | base64 -d 2>/dev/null | grep -o '"sid":"[^"]*"'; }
CA=$(adb -s "$A" shell run-as $P cat files/card.txt)
CB=$(adb -s "$B" shell run-as $P cat files/card.txt)
[ -z "$CA" ] || [ -z "$CB" ] && { echo "a card is missing"; exit 2; }
for _ in $(seq 1 12); do
  [[ "$(sid "$CA")" < "$(sid "$CB")" ]] && break
  MSYS_NO_PATHCONV=1 adb -s "$A" shell am start -n $P/.MainActivity --es run make_card >/dev/null 2>&1
  sleep 2
  CA=$(adb -s "$A" shell run-as $P cat files/card.txt)
done
MSYS_NO_PATHCONV=1 adb -s "$A" shell am start -n $P/.MainActivity --es peer "$CB" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 adb -s "$B" shell am start -n $P/.MainActivity --es peer "$CA" >/dev/null 2>&1
sleep 2
for s in "$A" "$B"; do adb -s "$s" logcat -c; done
for s in "$A" "$B"; do MSYS_NO_PATHCONV=1 adb -s "$s" shell am start -n $P/.MainActivity --es run ble --ei range_s "$RANGE" >/dev/null 2>&1; done
echo "Bluetooth test started on both; waiting for results (range mode ${RANGE}s)"
for _ in $(seq 1 $((RANGE + 150))); do
  ra=$(adb -s "$A" logcat -d -s netprobe:I | grep -m1 " ble {")
  rb=$(adb -s "$B" logcat -d -s netprobe:I | grep -m1 " ble {")
  [ -n "$ra" ] && [ -n "$rb" ] && break
  sleep 1
done
echo "== $A"; echo "${ra:-no result}" | sed 's/.* ble //'
echo "== $B"; echo "${rb:-no result}" | sed 's/.* ble //'
for s in "$A" "$B"; do adb -s "$s" logcat -d | grep -A3 "FATAL EXCEPTION" | grep -m4 "netprobe\|Exception" ; done
