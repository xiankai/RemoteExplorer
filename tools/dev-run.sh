#!/usr/bin/env bash
# Build, install, launch and screenshot the app on an emulator or phone.
#
# Usage:
#   tools/dev-run.sh                          # debug variant, auto-detected device
#   tools/dev-run.sh --variant releaseDebug   # release config, installs side by side
#   tools/dev-run.sh --device <serial>        # or set REX_DEVICE
#   tools/dev-run.sh --no-build               # reinstall + relaunch what is already built
#   tools/dev-run.sh --shot /tmp/after.png    # where to write the screenshot
#
# Anything touching ffmpeg/JNI, the Ktor server or packaging should be checked with
# --variant releaseDebug: several bugs in this repo's history reproduced only there.
set -euo pipefail

variant=debug
device="${REX_DEVICE:-}"
build=1
shot=""

while [ $# -gt 0 ]; do
  case "$1" in
    --variant) variant="$2"; shift 2 ;;
    --device)  device="$2";  shift 2 ;;
    --no-build) build=0; shift ;;
    --shot)    shot="$2";    shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
[ -x "$adb" ] || { echo "no adb at $adb — set ANDROID_HOME" >&2; exit 1; }

# Task name as well as application id: macOS ships bash 3.2, which has no ${var^}.
case "$variant" in
  debug)        appid=com.bespectacledcyborg.remoteexplorer.debug;        task=assembleDebug ;;
  releaseDebug) appid=com.bespectacledcyborg.remoteexplorer.releasedebug; task=assembleReleaseDebug ;;
  release)      appid=com.bespectacledcyborg.remoteexplorer;              task=assembleRelease ;;
  *) echo "unknown variant: $variant" >&2; exit 2 ;;
esac

if [ -z "$device" ]; then
  # Field 1 of every line that is actually a device, ignoring the header and offline
  # entries. Plain word splitting rather than mapfile, which is bash 4+.
  devices="$("$adb" devices | awk '$2 == "device" { print $1 }')"
  count="$(printf '%s\n' "$devices" | grep -c . || true)"
  [ "$count" -eq 0 ] && { echo "no device: start the AVD (emulator -avd rex-api36) or plug in a phone" >&2; exit 1; }
  [ "$count" -gt 1 ] && { printf 'several devices, pass --device:\n%s\n' "$devices" >&2; exit 1; }
  device="$devices"
fi
echo "==> device $device, variant $variant ($appid)"

# The bundled FFmpeg is arm64-only, so an x86_64 emulator installs but dies on
# System.loadLibrary. Fail here with a useful message instead of there with a stack trace.
abi="$("$adb" -s "$device" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in
  arm64*) ;;
  *) echo "device ABI is '$abi'; this app ships arm64-v8a only (see cpp/ffmpeg/lib)" >&2; exit 1 ;;
esac

if [ "$build" -eq 1 ]; then
  echo "==> assembling $variant"
  ( cd "$root" && ./gradlew "$task" )
fi

apk="$(find "$root/app/build/outputs/apk/$variant" -name '*.apk' -print -quit)"
[ -n "$apk" ] || { echo "no apk under app/build/outputs/apk/$variant — build first" >&2; exit 1; }

echo "==> installing $(basename "$apk")"
# -d allows a version DOWNGRADE. versionCode is Instant.now().epochSecond captured at
# configuration time, so the number tracks when a build was configured, not which code it
# contains — and with several worktrees in play the APK you just built is routinely "older"
# than whatever was installed from another branch minutes ago. Without -d that fails with
# INSTALL_FAILED_VERSION_DOWNGRADE, which reads like a real problem and is not one. -r keeps
# app data either way, so a saved connection survives the swap.
"$adb" -s "$device" install -r -d "$apk" >/dev/null

"$adb" -s "$device" logcat -c || true
"$adb" -s "$device" shell am force-stop "$appid"
echo "==> launching"
# -W blocks until the activity is drawn; cold start here runs ~10s on an emulator, and
# screenshotting before it returns catches the launcher rather than the app.
"$adb" -s "$device" shell am start -W -n "$appid/com.bespectacledcyborg.remoteexplorer.MainActivity" >/dev/null

if [ -n "$shot" ]; then
  sleep 2
  "$adb" -s "$device" exec-out screencap -p > "$shot"
  echo "==> screenshot $shot"
fi

# Surface crashes rather than leaving them in the buffer for someone to go looking for.
errors="$("$adb" -s "$device" logcat -d -t 400 2>/dev/null | grep -iE 'FATAL|AndroidRuntime|remoteexplorer.*\bE\b' || true)"
if [ -n "$errors" ]; then
  echo "==> errors in logcat:"
  printf '%s\n' "$errors" | tail -30
else
  echo "==> logcat clean"
fi
