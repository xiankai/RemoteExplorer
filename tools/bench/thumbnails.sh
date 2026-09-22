#!/usr/bin/env bash
# Measure what thumbnails cost: bytes off the share, and wall clock per file.
#
# Usage:
#   tools/bench/thumbnails.sh                            # debug variant, Bench folder
#   tools/bench/thumbnails.sh --variant releaseDebug
#   tools/bench/thumbnails.sh --folder Photos
#   tools/bench/thumbnails.sh --concurrency 4            # default 1, one at a time
#   tools/bench/thumbnails.sh --out /tmp/before.txt
#
# Run it once on master and once on the branch, then diff the reports. #28 is an
# optimisation issue, and "to the max" needs numbers rather than an argument that the new
# shape ought to be cheaper.
#
# ## Why this drives HTTP rather than the UI
#
# The obvious harness taps through to a folder and watches the grid fill. That measures the
# wrong thing badly: the grid is lazy, so only the cells that happen to be on screen ever
# request anything; a folder tile renders a 2x2 gallery of its CONTENTS, so the parent
# screen bills thumbnails of files in other folders to this run; and there is no reliable
# signal for "finished", so the report gets taken while work is still in flight. The first
# version of this script did all three.
#
# The loopback server starts with the ViewModel, at app launch, and is the single entry
# point every thumbnail goes through. Requesting from it directly gives an exact file set,
# an exact order, honest per-request timing, and explicit control of concurrency — which is
# one of the things #28 is about. What it does not cover is Sketch and the grid above it;
# that is deliberate, since the cost this issue is about is all below that line.
#
# ## Why bytes come from the container
#
# The fixture's eth0 transmit counter is what actually crossed the wire, SMB framing
# included, and it cannot be fooled by an accounting bug in the thing being measured. It
# does mean the share must otherwise be idle for the duration.
set -euo pipefail

variant=debug
folder=Bench
out=""
concurrency=1
decoder=""
device="${REX_DEVICE:-}"
cache_dir="cache/thumbnails"
warm=0

while [ $# -gt 0 ]; do
  case "$1" in
    --variant)     variant="$2";     shift 2 ;;
    --folder)      folder="$2";      shift 2 ;;
    --out)         out="$2";         shift 2 ;;
    --concurrency) concurrency="$2"; shift 2 ;;
    # Names a specific thumbnail implementation instead of the app's default, so two
    # decoders can be compared on one build. Diagnostic requests bypass the app's cache,
    # so every measurement is a cold render.
    --decoder)     decoder="$2";     shift 2 ;;
    --device)      device="$2";      shift 2 ;;
    # Where the app is keeping its thumbnails, relative to its data directory — which is
    # a setting now (#43), so the harness cannot assume it. Clearing the wrong directory
    # would leave the real cache warm and report a folder of cache hits as if it were work.
    --cache-dir)   cache_dir="$2";   shift 2 ;;
    # Leaves the cache alone instead of clearing it, so the measured pass is every file
    # served from disk. That is the number that decides whether a card is worth using: a
    # cold render is dominated by SMB and decoding, and would barely notice the volume.
    --warm)        warm=1;           shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
container="${REX_FIXTURE_CONTAINER:-remoteexplorer-fixture}"
# Where compose mounts the media tree inside the container. Not /media — that is an empty
# system directory, and testing for the folder there silently reported zero files.
share=/share

case "$variant" in
  debug)        appid=com.bespectacledcyborg.remoteexplorer.debug ;;
  releaseDebug) appid=com.bespectacledcyborg.remoteexplorer.releasedebug ;;
  *) echo "unknown variant: $variant" >&2; exit 2 ;;
esac

case "$concurrency" in ''|*[!0-9]*) echo "--concurrency must be numeric" >&2; exit 2 ;; esac

[ -x "$adb" ] || { echo "no adb at $adb — set ANDROID_HOME" >&2; exit 1; }
docker inspect "$container" >/dev/null 2>&1 || {
  echo "fixture container '$container' is not running — tools/fixture/up.sh up -d" >&2; exit 1; }

if [ -z "$device" ]; then
  devices="$("$adb" devices | awk '$2 == "device" { print $1 }')"
  count="$(printf '%s\n' "$devices" | grep -c . || true)"
  [ "$count" -eq 1 ] || { echo "need exactly one device (found $count), pass --device" >&2; exit 1; }
  device="$devices"
fi

# run-as reaches the app's private cache, and is refused on a non-debuggable build. debug
# and releaseDebug are both debuggable; a plain release build is not.
"$adb" -s "$device" exec-out run-as "$appid" true 2>/dev/null || {
  echo "cannot run-as $appid — is it installed, and debuggable?" >&2; exit 1; }

if ! docker exec "$container" test -d "$share/$folder" 2>/dev/null; then
  if [ "$folder" = "Bench" ]; then
    echo "==> bench media missing, seeding (a minute or so)"
    "$root/tools/bench/seed-bench.sh" >/dev/null
  else
    echo "no $share/$folder in the share" >&2; exit 1
  fi
fi

# --folder is user-supplied and lands inside a shell running in the container, so it goes
# in as an environment variable and is quoted there. Interpolating it into the sh -c string
# would split "My Photos" into two arguments and would run anything more creative.
in_share() { docker exec -e REX_TARGET="$share/$folder" "$container" sh -c "$1"; }

# shellcheck disable=SC2016  # single quotes are the point: $REX_TARGET must reach the
# container's shell to be expanded there, not be substituted by this one.
files="$(in_share 'ls -1 "$REX_TARGET"' | tr -d '\r')"
file_count="$(printf '%s\n' "$files" | grep -c . || true)"
# shellcheck disable=SC2016  # as above
bytes_on_share="$(in_share 'du -sk "$REX_TARGET"' | awk '{print $1 * 1024}')"
dq=""
[ -n "$decoder" ] && dq="&decoder=$decoder"
echo "==> $folder: $file_count files, $((bytes_on_share / 1024 / 1024)) MiB on the share"

cleanup() { "$adb" -s "$device" forward --remove "tcp:$local_port" >/dev/null 2>&1 || true; }  # stack-reviewer-ignore: local_port is assigned on the very next line, BEFORE the trap is installed, and cleanup only runs at exit — so it is always bound under set -u, including on the early exit when no port is found. The finding cited the later reassignment as the first assignment.
local_port=""
trap cleanup EXIT

# ---- start the app and find the server -------------------------------------------------

echo "==> launching $appid"
# Stop the OTHER variant too. They install side by side and each runs its own SMB session,
# HTTP server and ffmpeg decodes, so one left running competes for the emulator's CPU and
# the share. Measured: a releaseDebug pass read 76s with debug still up and 28s without
# it — enough to look like a release-only regression that does not exist.
for other in com.bespectacledcyborg.remoteexplorer.debug \
             com.bespectacledcyborg.remoteexplorer.releasedebug \
             com.bespectacledcyborg.remoteexplorer; do
  "$adb" -s "$device" shell am force-stop "$other"
done
clear_cache() {
  [ "$warm" = 1 ] && return 0
  "$adb" -s "$device" exec-out run-as "$appid" sh -c "rm -f '$cache_dir'/*" || true
}

clear_cache
"$adb" -s "$device" logcat -c
"$adb" -s "$device" shell am start -W \
  -n "$appid/com.bespectacledcyborg.remoteexplorer.MainActivity" >/dev/null

# The port is picked at random per launch and announced on this line.
port=""
for _ in $(seq 1 30); do
  port="$("$adb" -s "$device" logcat -d -s ServerRepository |
    sed -n 's/.*Serving SMB over HTTP on 127\.0\.0\.1:\([0-9]*\).*/\1/p' | tail -1)"
  [ -n "$port" ] && break
  sleep 1
done
[ -n "$port" ] || { echo "the app never logged a server port" >&2; exit 1; }

local_port="$port"
"$adb" -s "$device" forward "tcp:$local_port" "tcp:$port" >/dev/null
echo "==> server on device port $port"

# ---- open the connection -----------------------------------------------------------------

# The HTTP server comes up with the ViewModel, but the SMB session does not: it is
# established when a connection is opened, and until then every request 500s inside
# openFile. One tap on the connection tile is the whole dependency on the UI — no folder
# navigation, no scrolling.
#
# Tapped by the node's real bounds. AGENTS.md is explicit that eyeballing a screenshot goes
# stale, and this screen is the one place a label is guaranteed unique.
tap_text() {
  "$adb" -s "$device" exec-out uiautomator dump /dev/tty 2>/dev/null | tr -d '\r' |
    python3 -c "
import re, sys
xml, label = sys.stdin.read(), sys.argv[1]
for tag in re.findall(r'<node[^>]*>', xml):
    text = re.search(r'text=\"([^\"]*)\"', tag)
    bounds = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', tag)
    if text and bounds and text.group(1) == label:
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
" "$1"
}

connection="${REX_CONNECTION:-fixture}"
coords="$(tap_text "$connection")"
[ -n "$coords" ] || {
  echo "no '$connection' tile on screen — seed one with tools/dev-seed-connection.sh" >&2
  exit 1; }
echo "==> opening connection '$connection'"
# shellcheck disable=SC2086  # two words, x and y, on purpose
"$adb" -s "$device" shell input tap $coords

# ---- find a usable connectionId ----------------------------------------------------------

# Probed rather than read out of Room: it avoids depending on sqlite3 and the schema, and a
# wrong guess is self-correcting. Retried, because the tap above returns long before the
# SMB session finishes negotiating.
probe="$(printf '%s\n' "$files" | head -1)"
connection_id=""
for _ in $(seq 1 15); do
  for candidate in 1 2 3 4 5; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 300 \
      "http://127.0.0.1:$local_port/$folder/$probe?connectionId=$candidate&thumbnail=true$dq" || true)"
    # 404 is "no thumbnail for this file", which still proves the connection resolved.
    if [ "$code" = "200" ] || [ "$code" = "404" ]; then connection_id="$candidate"; break 2; fi
  done
  sleep 2
done
[ -n "$connection_id" ] || {
  echo "no connectionId in 1..5 answered — is the share reachable from the device?" >&2
  exit 1; }
echo "==> connectionId $connection_id"

# Wait for the UI to stop asking for thumbnails of its own accord.
#
# Both the Connections screen and the share root draw a folder tile as a 2x2 gallery of the
# files inside it, and those galleries go through this very endpoint. Left running, they
# warm files before the measured pass asks for them and bill their bytes to the run — an
# early version of this script reported 70 MiB and a 0.03s "decode" of a 24 MB video, which
# was a cache hit it had caused itself. Backing out of the folder does not help, because
# the screen behind it renders galleries too.
#
# So rather than guess at a sleep, watch the app's own timing log and wait for it to go
# quiet. A render of a large video takes seconds, so the window has to be comfortably
# longer than one.
echo "==> waiting for the UI to go quiet"
last_seen=""
quiet=0
for _ in $(seq 1 90); do
  now="$("$adb" -s "$device" logcat -d -s ServerRepository | grep -c 'Thumbnail .* in ' || true)"  # stack-reviewer-ignore: the cumulative count stops growing once the UI stops rendering, which is the whole signal — equality holds from then on, not only on the first iteration. Simulated against a counter that grows for 3 ticks and then stops: settles after 6 iterations with quiet=5. Empirically too, a real run breaks out early and never prints the "still busy" warning.
  if [ "$now" = "$last_seen" ]; then
    quiet=$((quiet + 1))
    [ "$quiet" -ge 5 ] && break
  else
    quiet=0
  fi
  last_seen="$now"
  sleep 1
done
[ "$quiet" -ge 5 ] || echo "    (still busy after 90s — the numbers below may be contaminated)" >&2

# Everything warmed above has to go, or the measured pass scores cache hits instead of work.
# Unless that is the point: --warm measures exactly those hits.
clear_cache
cached_files="$("$adb" -s "$device" exec-out run-as "$appid" \
  sh -c "ls '$cache_dir' 2>/dev/null | wc -l" | tr -d ' \r')"
if [ "$warm" = 1 ]; then
  # A "warm" run over an empty cache would silently be a cold one, and the report would
  # not say so — the exact failure the contamination checks exist to prevent, inverted.
  [ "$cached_files" != "0" ] || {
    echo "--warm but $cache_dir is empty — run once without it first" >&2; exit 1; }
else
  # An unexpected survivor means the cache is not where --cache-dir says it is, so the
  # clear went somewhere harmless and the measured pass is about to score cache hits.
  [ "$cached_files" = "0" ] || {
    echo "thumbnail cache did not clear — is it really at $cache_dir?" >&2; exit 1; }
fi
"$adb" -s "$device" logcat -c

# ---- measured pass -----------------------------------------------------------------------

tx_bytes() {
  docker exec "$container" cat /proc/net/dev |
    awk '{ gsub(/:/, "", $1); if ($1 == "eth0") print $10 }'
}

request() {
  local name=$1
  curl -s -o /dev/null \
    -w "%{http_code} %{time_total} $name\n" \
    --max-time 600 \
    "http://127.0.0.1:$local_port/$folder/$name?connectionId=$connection_id&thumbnail=true$dq"
}

results="$(mktemp)"
trap 'cleanup; rm -f "$results"' EXIT

echo "==> requesting $file_count thumbnails, concurrency $concurrency"
before="$(tx_bytes)"
started="$(date +%s)"

if [ "$concurrency" -le 1 ]; then
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    request "$name" >> "$results"
    printf '.'
  done <<< "$files"
else
  # xargs -P is the portable way to bound concurrency here; bash 3.2 has no `wait -n`.
  export local_port connection_id folder dq
  # shellcheck disable=SC2016  # single quotes on purpose: the inner sh expands $local_port
  # and friends from the exported environment, while curl's %{http_code} must reach it
  # literally rather than being eaten by this shell.
  printf '%s\n' "$files" | grep . |
    xargs -P "$concurrency" -I{} sh -c \
      'curl -s -o /dev/null -w "%{http_code} %{time_total} {}\n" --max-time 600 \
        "http://127.0.0.1:$local_port/$folder/{}?connectionId=$connection_id&thumbnail=true$dq"' \
    >> "$results"
fi
printf '\n'

elapsed=$(( $(date +%s) - started ))
after="$(tx_bytes)"
bytes=$((after - before))

# The app's own log is the only thing that can tell a decode from a cache hit, and a cache
# hit during a measured pass means something warmed the file behind the harness's back.
# That makes every number here an underestimate, so say so rather than print it straight.
#
# The log line is written after respondFile returns, so curl can be finished while the last
# one is still in flight. Draining immediately would leave that request counted in the
# bytes and missing from the outcomes, which is exactly how a contaminated run prints as
# clean. Wait for the log to catch up first — bytes were already snapshotted above, so
# anything that arrives during this window still shows up as contamination rather than
# being silently added to the total.
read_outcomes() {
  "$adb" -s "$device" logcat -d -s ServerRepository |
    sed -n 's/.*Thumbnail \([a-z]*\) for \(.*\) in \([0-9]*\)ms.*/\1 \2/p'
}
for _ in $(seq 1 30); do
  outcomes="$(read_outcomes)"
  [ "$(printf '%s\n' "$outcomes" | grep -c . || true)" -ge "$file_count" ] && break
  sleep 1
done

cached_hits="$(printf '%s\n' "$outcomes" | grep -c '^cached ' || true)"
# index() rather than a regex: the folder name is user-supplied, and as a regex a name like
# "My.Photos" matches "MyXPhotos" too, so a genuinely stray render would be accepted as
# in-folder and the banner suppressed. index(s, f) == 1 is an exact literal prefix.
stray="$(printf '%s\n' "$outcomes" | awk -v f="$folder/" 'index($2, f) != 1' | grep -c . || true)"

# ---- report --------------------------------------------------------------------------------

report() {
  echo "thumbnail benchmark — $(date '+%Y-%m-%d %H:%M:%S')"
  echo "  commit         $(git -C "$root" rev-parse --short HEAD)$(git -C "$root" diff --quiet || echo ' (dirty)')"
  echo "  variant        $variant"
  echo "  folder         $folder — $file_count files, $((bytes_on_share / 1024 / 1024)) MiB on the share"
  echo "  concurrency    $concurrency"
  echo "  decoder        ${decoder:-app default}"
  echo "  cache          $cache_dir$([ "$warm" = 1 ] && echo ' (warm — every request is a cache hit)')"
  echo
  echo "  served         $(awk '$1 == 200' "$results" | wc -l | tr -d ' ') ok, $(awk '$1 != 200' "$results" | wc -l | tr -d ' ') without a thumbnail"
  echo "  bytes off SMB  $bytes ($((bytes / 1024 / 1024)) MiB)"
  # The headline number for #28: what fraction of the folder had to cross the wire to
  # produce 128 px images of it. A targeted read should drive this towards nothing.
  echo "  read fraction  $(awk -v b="$bytes" -v s="$bytes_on_share" 'BEGIN { printf "%.1f%% of the folder", (s ? 100 * b / s : 0) }')"
  echo "  per thumbnail  $(awk -v b="$bytes" -v n="$file_count" 'BEGIN { printf "%.1f MiB", (n ? b / n / 1048576 : 0) }') average"
  echo "  wall clock     ${elapsed}s total"
  # A cache hit is contamination in a cold run and the measurement itself in a warm one, so
  # --warm takes it out of the banner rather than crying wolf on every line of the report.
  # A stray render is still contamination either way: it is work from another folder whose
  # bytes land in this total.
  if { [ "$cached_hits" -gt 0 ] && [ "$warm" != 1 ]; } || [ "$stray" -gt 0 ]; then
    echo
    echo "  !! CONTAMINATED — treat these numbers as a floor, not a measurement."
    [ "$cached_hits" -gt 0 ] && [ "$warm" != 1 ] &&
      echo "     $cached_hits request(s) hit a warm cache, so their work is not counted here."
    [ "$stray" -gt 0 ] && echo "     $stray thumbnail(s) outside $folder were rendered, and their bytes are counted here."
    echo "     Something rendered behind the harness — usually the UI's folder galleries."
  fi
  # The inverse check, and the one that matters for --warm: anything that was not a cache
  # hit was rendered, and a render is orders of magnitude slower than the read being timed.
  # Even one drags the average somewhere the storage had nothing to do with.
  if [ "$warm" = 1 ] && [ "$cached_hits" -lt "$file_count" ]; then
    echo
    echo "  !! $((file_count - cached_hits)) of $file_count request(s) rendered rather than"
    echo "     hitting the cache, so this is not purely a measurement of reads."
  fi
  echo
  echo "  per file (http status, seconds):"
  sort -k2 -rn "$results" | awk '{ printf "    %6.2fs  %s  %s\n", $2, $1, $3 }'
}

if [ -n "$out" ]; then
  report | tee "$out"
  echo "==> written to $out"
else
  report
fi
