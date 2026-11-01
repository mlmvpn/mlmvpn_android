#!/bin/bash
# Replaces the relay's rotation and its trigger so a rotation can never fill the disk (1 Nov 2026).
# What went wrong: the rotation kept 12 hours of frames, but a day after the 1.2.44 flood 12 hours
# of them were larger than the disk left free beside the old database. Each try filled the disk;
# the copy died with an I/O error, and the relay, writing to the same full disk, crashed with it
# (3,300 restarts over ten hours: no photo, file or message through our relay). The trigger then
# started the same doomed copy again every 40 minutes.
# Now: frames go across an hour at a time, newest first, and the copy stops while RESERVE is still
# free, keeping as many hours as fit (12 at most). The trigger fires at 20 GiB of data, or when
# the disk has less than 16 GiB free, so a rotation always has room.
# Usage: bash -s [hours to keep now, default 12]   (the rotation starts detached)
set -u
HOURS_NOW=${1:-12}

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

bash -n /usr/local/bin/gereh-relay-rotate && bash -n /usr/local/bin/gereh-relay-capacity && echo "scripts parse"
# a rotation of the old kind still copying is stopped, and what it left goes
systemctl stop gereh-relay-capacity.timer gereh-relay-capacity.service 2>/dev/null
pkill -f 'strfry --config=/etc/gereh-relay/strfry-rotate.conf' || true
sleep 2
rm -rf /var/lib/strfry-rotate /etc/gereh-relay/strfry-rotate.conf /tmp/gereh-relay-rotate.lock
df -h /var/lib | tail -1
# detached: it does not end with this session
systemd-run --unit=gereh-relay-rotate-now --collect /usr/local/bin/gereh-relay-rotate "$HOURS_NOW" && echo "rotation started"
systemctl start gereh-relay-capacity.timer
