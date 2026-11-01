#!/usr/bin/env bash
# Gereh public Nostr relay (T54, SPEC 20): strfry behind nginx with a self-signed certificate.
# Only sealed Gereh frames (kind 1059) that expire within 3 days, and the official chats' sealed
# files (kind 3059, SPEC 18.3) that expire within 14 days; nothing is logged; caps per address
# generous enough for carrier NAT. Safe to run again: done steps are skipped, and the write
# policy and the config are written anew (run it again after an update of this file).
#
#   bash setup-strfry.sh <public IPv4 of this server>
#
# At the end it prints the relay address and the certificate fingerprints the app pins.
set -u
IP="${1:-}"
say() { printf '\n=== %s\n' "$*"; }
fail() { printf '\n!!! %s\n' "$*"; exit 1; }

[ "$(id -u)" = 0 ] || fail "run as root"
[ -n "$IP" ] || IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -1)"
[ -n "$IP" ] || fail "no IPv4 address given"

say "Server"
. /etc/os-release 2>/dev/null || true
echo "OS: ${PRETTY_NAME:-unknown}  CPU: $(nproc)  RAM MB: $(free -m | awk '/Mem:/{print $2}')  Disk free: $(df -h / | awk 'NR==2{print $4}')"
case "${ID:-}" in
  ubuntu|debian) ;;
  *) fail "this script needs Ubuntu (24.04 is best). Reinstall the server with Ubuntu 24.04 from the provider's panel, then run it again." ;;
esac

export DEBIAN_FRONTEND=noninteractive
if [ ! -x /usr/local/bin/strfry ]; then
  say "Packages"
  apt-get update -y || fail "apt-get update failed (the server's package mirror)"
  apt-get install -y git build-essential libyaml-perl libtemplate-perl libregexp-grammars-perl \
    libssl-dev zlib1g-dev liblmdb-dev libflatbuffers-dev libsecp256k1-dev libzstd-dev \
    nginx openssl python3 curl || fail "package install failed"

  # the C++ build wants about 2 GB of memory
  if [ "$(free -m | awk '/Mem:/{print $2}')" -lt 1800 ] && ! swapon --show | grep -q .; then
    say "Adding 2 GB of swap for the build"
    fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  fi

  say "Building strfry (several minutes)"
  rm -rf /opt/strfry-src
  ok=0
  for i in 1 2 3; do
    git clone --depth 1 https://github.com/hoytech/strfry.git /opt/strfry-src && ok=1 && break
    sleep 5
  done
  [ "$ok" = 1 ] || fail "could not download strfry from GitHub"
  cd /opt/strfry-src || fail "no source folder"
  git submodule update --init --depth 1 || make setup-golpe || fail "could not download golpe"
  make setup-golpe >/dev/null 2>&1 || true
  make -j"$(nproc)" || fail "strfry build failed"
  install -m 755 strfry /usr/local/bin/strfry
  cp strfry.conf /opt/strfry-default.conf
  cd /
fi
/usr/local/bin/strfry --help >/dev/null 2>&1 || fail "strfry does not run"

say "Relay user, data and certificate"
id strfry >/dev/null 2>&1 || useradd --system --home /var/lib/strfry --shell /usr/sbin/nologin strfry
mkdir -p /var/lib/strfry /etc/gereh-relay
chown strfry:strfry /var/lib/strfry
if [ ! -s /etc/gereh-relay/cert.pem ]; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
    -keyout /etc/gereh-relay/key.pem -out /etc/gereh-relay/cert.pem \
    -subj "/CN=gereh-relay" -addext "subjectAltName=IP:${IP}" >/dev/null 2>&1 || fail "certificate failed"
  chmod 600 /etc/gereh-relay/key.pem
fi

say "Write policy (kind 1059 within 3 days, official files kind 3059 within 14 days, caps per address, no logs)"
cat > /usr/local/bin/gereh-write-policy <<'PY'
#!/usr/bin/env python3
# strfry write policy for a Gereh relay (T54): sealed Gereh frames, kind 1059, with a p tag and an
# expiration within 3 days; and the official chats' sealed files (SPEC 18.3), kind 3059, half a
# sealed chunk each, named by file (x), chunk (i), half (h), chunks in all (n) and official chat (f),
# with an expiration within 14 days. The first part written under a name stands: a later one with
# other bytes is refused. At most RATE events a minute from one address (generous: carrier NAT puts
# many phones behind one), and FILE_DAY bytes of file parts a day. Nothing is written anywhere.
import json, re, sys, time
from collections import OrderedDict, defaultdict, deque

KIND = 1059
MAX_LIFE = 3 * 86400 + 600
FILE_KIND = 3059
FILE_LIFE = 14 * 86400 + 600
# an official chat's own photo: its own kind, kept for good (T66, 30 Oct 2026)
PHOTO_KIND = 3060
PHOTO_LIFE = 3650 * 86400 + 600
PHOTO_MAX_PARTS = 81  # 5 MiB of 65024-byte chunks
FILE_CHARS = 45000
FILE_MAX_PARTS = 323  # 20 MiB of 65024-byte chunks (Ehsan, 30 Oct 2026)
FILE_DAY = 400 * 1024 * 1024
# the public list of channels and groups (q6, T70, 30 Oct 2026): a chat's card, replaceable per
# signer and chat (d: the chat's id), JSON of at most 4 KiB, kept for good (31 Oct 2026); an
# empty one takes the chat out of the list. Its small photo is kind 30091, the same way, a JPEG of
# at most 32 KiB in base64 (empty: no photo)
DIR_KIND = 30090
DIR_PHOTO_KIND = 30091
DIR_TAG = "gereh-dir/1"
DIR_LIFE = 3650 * 86400 + 600
DIR_CHARS = 4096
DIR_PHOTO_CHARS = 44000
# a group's or channel's own photo (q16, T76, 31 Oct 2026): kind 3060 too, kept for good, but any
# chat may leave one, so at most 1 MiB each and CHAT_PHOTO_DAY bytes a day from one address
CHAT_PHOTO_MAX_PARTS = 17  # 1 MiB of 65024-byte chunks
CHAT_PHOTO_DAY = 20 * 1024 * 1024
# the official chats' items, once for everyone (SPEC 18.7, T77, 31 Oct 2026): kind 3061, tagged
# with its feed (f) and item (i), kept a week, a sealed signed item of at most FEED_ITEM_CHARS; the
# same item is the same event from every phone, so strfry keeps one
FEED_ITEM_KIND = 3061
FEED_ITEM_LIFE = 7 * 86400 + 600
FEED_ITEM_CHARS = 45000
FEED_ITEM_DAY = 50 * 1024 * 1024
HEX32 = re.compile(r"^[0-9a-f]{32}$")
# the official channel and group of each app: MLMVPN's (org.mlmchat.core.Official) and Dr.Net's
# own pair (18.2, GerehHost.setFeeds in Dr.Net). The core sends every configured official chat's
# files and photo here, so a feed missing from this set loses them on that app (31 Oct 2026)
FEEDS = {
    "549ef39b524c96654d0fee24414014b0", "d4249e84b11c00236edf28621d63575e",  # MLMVPN
    "fd3be941abf97b29bc81d2f5e581c18c", "03c039e56f343853e9e4d36bcc737f18",  # Dr.Net
}
HEX64 = re.compile(r"^[0-9a-f]{64}$")
RATE = 3000
window = defaultdict(deque)
file_bytes = defaultdict(int)
photo_bytes = defaultdict(int)
item_bytes = defaultdict(int)
file_day = [0]
first = OrderedDict()
FIRST_MAX = 300000
seen = 0

def expiry_ok(tags, now, life):
    exp = [t for t in tags if t[0] == "expiration"]
    if len(exp) != 1:
        return False
    try:
        e = int(exp[0][1])
    except (TypeError, ValueError):
        return False
    return now < e <= now + life

def one(tags, name):
    v = [t[1] for t in tags if t[0] == name]
    return v[0] if len(v) == 1 and isinstance(v[0], str) else None

def new_day(now):
    day = now // 86400
    if file_day[0] != day:
        file_day[0] = day
        file_bytes.clear()
        photo_bytes.clear()
        item_bytes.clear()

def file_verdict(ev, tags, now, src, life=FILE_LIFE, max_parts=FILE_MAX_PARTS, chats=False):
    if not expiry_ok(tags, now, life):
        return "reject", "blocked: expiration out of range"
    x, i, h, n, f = (one(tags, k) for k in ("x", "i", "h", "n", "f"))
    try:
        i, n = int(i), int(n)
    except (TypeError, ValueError):
        return "reject", "blocked: bad file part"
    if x is None or not HEX64.match(x) or h not in ("0", "1") or not 0 <= i < n <= 32768:
        return "reject", "blocked: bad file part"
    # a photo may be any group's or channel's (chats), smaller than an official chat's
    official = f in FEEDS
    if not official and not (chats and f is not None and HEX32.match(f)):
        return "reject", "blocked: bad file part"
    if n > (max_parts if official else min(max_parts, CHAT_PHOTO_MAX_PARTS)):
        return "reject", "blocked: file too large"
    content = ev.get("content") or ""
    if len(content) > FILE_CHARS:
        return "reject", "blocked: file part too large"
    key = (x, i, h)
    if first.get(key, ev.get("id")) != ev.get("id"):
        return "reject", "duplicate: a part of that name is here"
    new_day(now)
    if src:
        spent, cap = (file_bytes, FILE_DAY) if official else (photo_bytes, CHAT_PHOTO_DAY)
        if spent[src] + len(content) > cap:
            return "reject", "rate-limited: too many files today"
        spent[src] += len(content)
    first[key] = ev.get("id")
    while len(first) > FIRST_MAX:
        first.popitem(last=False)
    return "accept", ""

def item_verdict(ev, tags, now, src):
    if not expiry_ok(tags, now, FEED_ITEM_LIFE):
        return "reject", "blocked: expiration out of range"
    f, i = one(tags, "f"), one(tags, "i")
    if f not in FEEDS or i is None or not HEX32.match(i):
        return "reject", "blocked: bad item"
    content = ev.get("content") or ""
    if len(content) > FEED_ITEM_CHARS:
        return "reject", "blocked: item too large"
    new_day(now)
    if src:
        if item_bytes[src] + len(content) > FEED_ITEM_DAY:
            return "reject", "rate-limited: too many items today"
        item_bytes[src] += len(content)
    return "accept", ""

def dir_verdict(ev, tags, now, chars=DIR_CHARS):
    if not expiry_ok(tags, now, DIR_LIFE):
        return "reject", "blocked: expiration out of range"
    d, t = one(tags, "d"), one(tags, "t")
    if d is None or not HEX32.match(d) or t != DIR_TAG:
        return "reject", "blocked: bad card"
    if len(ev.get("content") or "") > chars:
        return "reject", "blocked: card too large"
    return "accept", ""

def verdict(req, now):
    ev = req.get("event") or {}
    tags = [t for t in ev.get("tags", []) if isinstance(t, list) and len(t) >= 2]
    src = req.get("sourceInfo") or ""
    if src:
        q = window[src]
        while q and now - q[0] >= 60:
            q.popleft()
        if len(q) >= RATE:
            return "reject", "rate-limited: slow down"
        q.append(now)
    if ev.get("kind") == FILE_KIND:
        return file_verdict(ev, tags, now, src)
    if ev.get("kind") == PHOTO_KIND:
        return file_verdict(ev, tags, now, src, PHOTO_LIFE, PHOTO_MAX_PARTS, chats=True)
    if ev.get("kind") == FEED_ITEM_KIND:
        return item_verdict(ev, tags, now, src)
    if ev.get("kind") == DIR_KIND:
        return dir_verdict(ev, tags, now)
    if ev.get("kind") == DIR_PHOTO_KIND:
        return dir_verdict(ev, tags, now, DIR_PHOTO_CHARS)
    if ev.get("kind") != KIND:
        return "reject", "blocked: only kinds 1059, 3059, 3060, 3061, 30090 and 30091"
    if not expiry_ok(tags, now, MAX_LIFE):
        return "reject", "blocked: expiration must be within 3 days"
    if not any(t[0] == "p" for t in tags):
        return "reject", "blocked: a p tag is required"
    return "accept", ""

for line in sys.stdin:
    now = int(time.time())
    try:
        req = json.loads(line)
        action, msg = verdict(req, now)
        out = {"id": (req.get("event") or {}).get("id", ""), "action": action, "msg": msg}
    except Exception:
        out = {"id": "", "action": "reject", "msg": "error"}
    sys.stdout.write(json.dumps(out) + "\n")
    sys.stdout.flush()
    seen += 1
    if seen % 10000 == 0:
        for k in [k for k, q in window.items() if not q or now - q[-1] >= 60]:
            del window[k]
PY
chmod 755 /usr/local/bin/gereh-write-policy

say "strfry config"
conf=/etc/gereh-relay/strfry.conf
[ -s /opt/strfry-default.conf ] || fail "no default strfry.conf (delete /usr/local/bin/strfry and run again)"
cp /opt/strfry-default.conf "$conf"
# noReadAhead: the database (up to 40 GiB) is ten times the server's RAM, so the kernel's read-ahead
# around each random page only fills the cache with pages nobody asked for. Measured on 30 Oct 2026
# with it off: 360 GB read in 2.5 hours and the disk waited on half the time (/proc/pressure/io).
sed -i \
  -e 's|^db = .*|db = "/var/lib/strfry/"|' \
  -e 's|^\([[:space:]]*\)mapsize = .*|\1mapsize = 48318382080|' \
  -e 's|^\([[:space:]]*\)noReadAhead = .*|\1noReadAhead = true|' \
  -e 's|^\([[:space:]]*\)bind = .*|\1bind = "127.0.0.1"|' \
  -e 's|^\([[:space:]]*\)port = .*|\1port = 7777|' \
  -e 's|^\([[:space:]]*\)realIpHeader = .*|\1realIpHeader = "x-real-ip"|' \
  -e 's|^\([[:space:]]*\)plugin = .*|\1plugin = "/usr/local/bin/gereh-write-policy"|' \
  -e 's|^\([[:space:]]*\)invalidEvents = .*|\1invalidEvents = false|' \
  -e 's|^\([[:space:]]*\)dumpIn\([A-Za-z]*\) = .*|\1dumpIn\2 = false|' \
  -e 's|^\([[:space:]]*\)rejectEventsOlderThanSeconds = .*|\1rejectEventsOlderThanSeconds = 259200|' \
  -e 's|^\([[:space:]]*\)name = .*|\1name = "Gereh"|' \
  -e 's|^\([[:space:]]*\)description = .*|\1description = "Sealed Gereh frames (kind 1059, at most 3 days), the official chats sealed files (kind 3059, at most 14 days) and their photos (kind 3060, kept), no logs."|' \
  "$conf"
# Newer strfry serves kind 1059 only to a reader logged in (NIP-42) as the tagged key. Gereh's tags
# are derived day tags, not keys, and its readers never log in: reads of 1059 stay open.
sed -i \
  -e '/^[[:space:]]*auth {/,/^[[:space:]]*}/ s|enabled = true|enabled = false|' \
  -e '/^[[:space:]]*auth {/,/^[[:space:]]*}/ s|restrictedReadKinds = .*|restrictedReadKinds = ""|' \
  -e '/^[[:space:]]*auth {/,/^[[:space:]]*}/ s|restrictReadToInvolvedPubkey = .*|restrictReadToInvolvedPubkey = false|' \
  "$conf"
grep -q 'gereh-write-policy' "$conf" || fail "write policy not set in the config"
grep -q 'x-real-ip' "$conf" || fail "real IP header not set in the config"

say "Service"
cat > /etc/systemd/system/gereh-relay.service <<UNIT
[Unit]
Description=Gereh Nostr relay (strfry)
After=network.target

[Service]
User=strfry
ExecStart=/usr/local/bin/strfry --config=$conf relay
Restart=always
RestartSec=3
LimitNOFILE=1048576
StandardOutput=null
StandardError=null

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable gereh-relay >/dev/null 2>&1
systemctl restart gereh-relay
sleep 3
systemctl is-active --quiet gereh-relay || fail "the relay service did not start"

say "storage: at most 45 GiB of data, trimmed oldest first above 40 GiB, every minute (30 Oct 2026)"
command -v mdb_stat >/dev/null || apt-get install -y -q lmdb-utils >/dev/null
# the trim, every minute
install -d -o strfry -g strfry /var/lib/gereh-relay-trim
cat > /usr/local/bin/gereh-relay-trim <<'TRIM'
#!/bin/bash
# Keeps the relay's data under its cap, oldest events first (Ehsan, 30 Oct 2026). Only sealed
# frames (kind 1059) go before their time: the official chats' files (3059) keep their 14 days,
# and photos and cards (3060, 30090, 30091) stay for good (Ehsan, 31 Oct 2026: the trim took the
# official group's photos with the 1.2.44 flood). It starts at 36 GiB, well under the 45 GiB map
# that filled and crashed the relay. Frames go a minute of them at a time, from where the
# last run got to: an hour of the 1.2.44 flood at once (some 800 thousand frames) made one delete
# hold 2.8 GB, and the relay beside it was killed for want of memory (31 Oct 2026). Its unit caps
# it besides, and lets it die before the relay.
set -u
DB=/var/lib/strfry
S="/usr/local/bin/strfry --config=/etc/gereh-relay/strfry.conf"
HIGH=$((36 * 1024 * 1024 * 1024))
LOW=$((34 * 1024 * 1024 * 1024))
STEP=60
DONE=/var/lib/gereh-relay-trim/done
exec 9>/tmp/gereh-relay-trim.lock
flock -n 9 || exit 0
live() {
  local e used free page
  e=$(mdb_stat -e "$DB") || { echo 0; return; }
  used=$(echo "$e" | awk '/pages used/ {print $NF}')
  page=$(echo "$e" | awk '/Page size/ {print $NF}')
  free=$(mdb_stat -f "$DB" | awk '/Free pages/ {print $NF}')
  echo $(( (used - free) * page ))
}
# sealed frames up to $1, STEP seconds of them at a time; with $2, only while over LOW
frames_until() {
  local end=$1 t
  t=$(cat "$DONE" 2>/dev/null)
  case "$t" in ''|*[!0-9]*) t=0 ;; esac
  [ "$t" -lt $((end - 7 * 86400)) ] && t=$((end - 7 * 86400))
  while [ "$t" -lt "$end" ]; do
    if [ -n "${2:-}" ] && [ "$(live)" -le "$LOW" ]; then
      return 0
    fi
    t=$((t + STEP))
    [ "$t" -gt "$end" ] && t=$end
    $S delete --filter "{\"kinds\":[1059],\"until\":$t}" >/dev/null 2>&1 || return 1
    echo "$t" > "$DONE"
  done
}
now=$(date +%s)
# what is past its own life anyway: frames 3 days, file parts 14 days (a few a day)
frames_until $((now - 3 * 86400 - 600)) || exit 1
$S delete --filter "{\"kinds\":[3059],\"until\":$((now - 14 * 86400 - 600))}" >/dev/null 2>&1
$S delete --filter "{\"kinds\":[3061],\"until\":$((now - 7 * 86400 - 600))}" >/dev/null 2>&1
[ "$(live)" -gt "$HIGH" ] || exit 0
logger -t gereh-relay-trim "over $HIGH bytes: trimming the oldest frames first"
frames_until $((now - 600)) low
logger -t gereh-relay-trim "now $(live) bytes, frames before $(cat "$DONE") gone"
TRIM
chmod 755 /usr/local/bin/gereh-relay-trim

cat > /etc/systemd/system/gereh-relay-trim.service <<'UNIT'
[Unit]
Description=Gereh relay: keep the data under its cap, oldest first

[Service]
Type=oneshot
User=strfry
Group=strfry
ExecStart=/usr/local/bin/gereh-relay-trim
# a delete that grows takes itself down, never the relay (31 Oct 2026)
MemoryMax=1200M
MemorySwapMax=0
OOMScoreAdjust=1000
UNIT
# the relay is the last to go when memory runs out, and swap gives a margin (31 Oct 2026)
mkdir -p /etc/systemd/system/gereh-relay.service.d
printf '[Service]
OOMScoreAdjust=-900
' > /etc/systemd/system/gereh-relay.service.d/oom.conf
if ! swapon --show | grep -q /swapfile; then
  [ -f /swapfile ] || fallocate -l 2G /swapfile
  chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
fi
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness=10' > /etc/sysctl.d/90-gereh-swap.conf && sysctl -q vm.swappiness=10
cat > /etc/systemd/system/gereh-relay-trim.timer <<'UNIT'
[Unit]
Description=Gereh relay trim every minute

[Timer]
OnBootSec=1min
OnUnitActiveSec=1min

[Install]
WantedBy=timers.target
UNIT

# the rotation and its trigger (31 Oct, 1 Nov 2026): the trim cannot keep up with a flood, so over
# 20 GiB of data, or under 16 GiB free on the disk, the database is copied fresh with what must stay
# (rotate-2026-11-01.sh says why; it also installs them on a running server)
cat > /usr/local/bin/gereh-relay-rotate <<'ROTATE'
#!/bin/bash
# Rotates the relay's database: what must stay goes into a fresh one and the old one goes. Stays:
# the official chats' files, photos and items (3059, 3060, 3061), the public list's cards and
# photos (30090, 30091), and the sealed frames of the last HOURS hours, newest first, as many
# hours as fit with RESERVE still free on the disk (1 Nov 2026: a copy that filled the disk took
# the relay down with it). The copy runs while the relay serves; the relay then stops only while
# what came during the copy follows and the two swap. On any failure the relay goes on with the
# old database, untouched.
# Usage: gereh-relay-rotate [HOURS, default 12]
set -u
set -o pipefail
HOURS=${1:-12}
RESERVE=$((6 * 1024 * 1024 * 1024))
S=/usr/local/bin/strfry
CONF=/etc/gereh-relay/strfry.conf
DB=/var/lib/strfry
NEW=/var/lib/strfry-rotate
OLD=/var/lib/strfry-old
NEWCONF=/etc/gereh-relay/strfry-rotate.conf
LOG=/var/log/gereh-relay-rotate.log
exec 9>/tmp/gereh-relay-rotate.lock
flock -n 9 || { echo "a rotation is running"; exit 0; }
say() { echo "$(date -u +%H:%M:%S) $*" >> "$LOG"; logger -t gereh-relay-rotate "$*"; }
free_bytes() { df --output=avail -B1 /var/lib | tail -1 | tr -d ' '; }
copy() {
  $S --config="$CONF" scan "$1" 2>>"$LOG" | sudo -u strfry $S --config="$NEWCONF" import --no-verify >>"$LOG" 2>&1
}
give_up() {
  say "failed ($1): the relay goes on with the old database"
  rm -rf "$NEW" "$NEWCONF"
  systemctl start gereh-relay
  systemctl start gereh-relay-trim.timer
  exit 1
}
t0=$(date +%s)
rm -rf "$NEW" "$OLD"
install -d -o strfry -g strfry "$NEW"
sed "s|^db = .*|db = \"$NEW/\"|" "$CONF" > "$NEWCONF"
: > "$LOG"
systemctl stop gereh-relay-trim.timer
say "rotating: files, photos, items, cards, and up to $HOURS hours of frames, newest first (the relay serves meanwhile)"
copy '{"kinds":[3059,3060,3061,30090,30091]}' || give_up "files"
kept=0
while [ "$kept" -lt "$HOURS" ]; do
  if [ "$(free_bytes)" -lt "$RESERVE" ]; then
    say "the disk is near full: $kept hours of frames kept"
    break
  fi
  until=$((t0 - kept * 3600))
  copy "{\"kinds\":[1059],\"since\":$((until - 3600)),\"until\":$((until - 1))}" || give_up "frames"
  kept=$((kept + 1))
done
say "copied $kept hours in $(( $(date +%s) - t0 )) s; the relay pauses for what came meanwhile"
systemctl stop gereh-relay
copy "{\"since\":$((t0 - 600))}" || give_up "the last events"
mv "$DB" "$OLD" && mv "$NEW" "$DB" || { [ -d "$DB" ] || mv "$OLD" "$DB"; give_up "swap"; }
systemctl start gereh-relay
say "done in $(( $(date +%s) - t0 )) s; the old database goes"
rm -rf "$OLD" "$NEWCONF"
systemctl start gereh-relay-trim.timer
ROTATE
chmod 755 /usr/local/bin/gereh-relay-rotate
cat > /usr/local/bin/gereh-relay-capacity <<'CAP'
#!/bin/bash
# Rotates the relay's database early (1 Nov 2026): over 20 GiB of live data, or under 16 GiB free
# on the disk. A rotation needs room for its copy beside the old database, so it must start while
# there is some; the rotation itself keeps as many hours of frames as fit.
set -u
DB=/var/lib/strfry
e=$(mdb_stat -e "$DB") || exit 0
used=$(echo "$e" | awk '/pages used/ {print $NF}')
page=$(echo "$e" | awk '/Page size/ {print $NF}')
free=$(mdb_stat -f "$DB" | awk '/Free pages/ {print $NF}')
live=$(( (used - free) * page ))
disk=$(df --output=avail -B1 /var/lib | tail -1 | tr -d ' ')
G=$((1024 * 1024 * 1024))
[ "$live" -gt $((20 * G)) ] || [ "$disk" -lt $((16 * G)) ] || exit 0
logger -t gereh-relay-capacity "live data $live bytes, disk free $disk bytes: rotating"
exec /usr/local/bin/gereh-relay-rotate 12
CAP
chmod 755 /usr/local/bin/gereh-relay-capacity
cat > /etc/systemd/system/gereh-relay-capacity.service <<'UNIT'
[Unit]
Description=Gereh relay: rotate the database when it nears its cap

[Service]
Type=oneshot
ExecStart=/usr/local/bin/gereh-relay-capacity
UNIT
cat > /etc/systemd/system/gereh-relay-capacity.timer <<'UNIT'
[Unit]
Description=Gereh relay capacity check every 10 minutes

[Timer]
OnBootSec=5min
OnUnitActiveSec=10min

[Install]
WantedBy=timers.target
UNIT

# journald never grows past 200 MB
mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nSystemMaxUse=200M\nRuntimeMaxUse=200M\n' > /etc/systemd/journald.conf.d/gereh.conf
systemctl restart systemd-journald || true

systemctl daemon-reload
systemctl enable --now gereh-relay-trim.timer >/dev/null 2>&1
systemctl enable --now gereh-relay-capacity.timer >/dev/null 2>&1

say "nginx (TLS on 443, no logs)"
rm -f /etc/nginx/sites-enabled/default
cat > /etc/nginx/conf.d/gereh-relay.conf <<'NGX'
limit_conn_zone $binary_remote_addr zone=gereh_conn:20m;
limit_req_zone $binary_remote_addr zone=gereh_req:20m rate=600r/m;

server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name _;
    ssl_certificate /etc/gereh-relay/cert.pem;
    ssl_certificate_key /etc/gereh-relay/key.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    access_log off;
    error_log /dev/null crit;

    location / {
        limit_conn gereh_conn 2000;
        limit_req zone=gereh_req burst=300 nodelay;
        proxy_pass http://127.0.0.1:7777;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
NGX
# a server without IPv6 cannot listen on [::]
[ -f /proc/net/if_inet6 ] || sed -i '/listen \[::\]/d' /etc/nginx/conf.d/gereh-relay.conf
sed -i -e 's|worker_connections [0-9]*;|worker_connections 20000;|' \
       -e 's|^\([[:space:]]*\)access_log .*|\1access_log off;|' \
       -e 's|^\([[:space:]]*\)error_log .*|\1error_log /dev/null crit;|' /etc/nginx/nginx.conf
grep -q '^worker_rlimit_nofile' /etc/nginx/nginx.conf || sed -i '1i worker_rlimit_nofile 100000;' /etc/nginx/nginx.conf
nginx -t >/dev/null 2>&1 || { nginx -t; fail "nginx config error"; }
systemctl enable nginx >/dev/null 2>&1
systemctl restart nginx || fail "nginx did not start"

say "Check"
info="$(curl -sk --max-time 5 -H 'Accept: application/nostr+json' https://127.0.0.1/ || true)"
echo "$info" | grep -q '"Gereh"' || fail "the relay does not answer through nginx: $info"
echo "relay answers: $info" | head -c 300; echo
# a Gereh reader's subscription (kind 1059 under a day tag) must end with EOSE, not CLOSED
sub="$(python3 - <<'PY'
import base64, json, os, socket
s = socket.create_connection(("127.0.0.1", 7777), timeout=5)
s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "
          + base64.b64encode(os.urandom(16)) + b"\r\nSec-WebSocket-Version: 13\r\n\r\n")
head = b""
while b"\r\n\r\n" not in head:
    head += s.recv(1)
msg = json.dumps(["REQ", "chk", {"kinds": [1059], "#p": ["0" * 64], "limit": 1}]).encode()
mask = os.urandom(4)
s.sendall(bytes([0x81, 0x80 | len(msg)]) + mask + bytes(c ^ mask[i % 4] for i, c in enumerate(msg)))
for _ in range(3):
    h = s.recv(2)
    n = h[1] & 0x7F
    if n == 126:
        n = int.from_bytes(s.recv(2), "big")
    data = b""
    while len(data) < n:
        data += s.recv(n - len(data))
    t = data.decode(errors="replace")
    if t.startswith('["EOSE"') or t.startswith('["CLOSED"'):
        print(t)
        break
PY
)"
echo "subscription: $sub"
case "$sub" in
  '["EOSE"'*) ;;
  *) fail "a kind 1059 subscription is refused: $sub" ;;
esac

say "Done"
cert_sha256="$(openssl x509 -in /etc/gereh-relay/cert.pem -outform der | sha256sum | awk '{print $1}')"
spki_sha256="$(openssl x509 -in /etc/gereh-relay/cert.pem -pubkey -noout | openssl pkey -pubin -outform der | sha256sum | awk '{print $1}')"
echo "GEREH_RELAY_URL=wss://${IP}"
echo "GEREH_RELAY_CERT_SHA256=${cert_sha256}"
echo "GEREH_RELAY_SPKI_SHA256=${spki_sha256}"
echo "If the provider has a firewall in its panel, port 443 (TCP) must be open."
