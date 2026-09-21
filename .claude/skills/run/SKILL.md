---
name: run
description: Build, install and drive Remote Explorer on an emulator or phone, against the local fixture SMB share. Use when asked to run, start, screenshot or visually verify the app, or to confirm a change works in the real app rather than only in a build.
---

# Run Remote Explorer

The app is an SMB browser: it does nothing without a share to point at. Local development
uses a throwaway Samba container rather than real infrastructure.

## One command

```bash
tools/dev-run.sh --shot /tmp/app.png
```

Builds, installs, launches, screenshots, and prints any crash from logcat. Read the
screenshot back to confirm the change — a green build proves nothing about the UI.

Useful flags: `--variant releaseDebug` (installs side by side under a `.releasedebug`
application id), `--device <serial>`, `--no-build`.

**Check anything touching ffmpeg/JNI, the Ktor server or packaging on `releaseDebug`.**
Several bugs in this repo's history reproduced only there and never in `debug`.

## The fixture share

```bash
tools/fixture/up.sh up -d      # seeds on first run, then starts the share
tools/fixture/up.sh down       # stop it
tools/fixture/seed.sh          # regenerate the media tree from scratch
```

Always go through `up.sh`: it sets `REX_FIXTURE_DIR`, which `compose.yml` requires, and the
media lives at `~/.cache/remoteexplorer-fixture` rather than in the repo. That is not
fussiness — a relative bind mount ties the share to the checkout it was started from, and a
worktree is a checkout that gets deleted. Removing a merged worktree once emptied the
running share, after which the app crashed listing a directory the server no longer had.

Then add the connection to the app — write it straight into Room rather than typing it:

```bash
tools/dev-seed-connection.sh
```

| field | value |
|---|---|
| Hostname | `10.0.2.2` (the emulator's alias for the host's loopback) |
| Port | `1445` |
| Username / Password | `explorer` / `explorer` |
| Share | `media` |

Port 1445 rather than 445 because macOS runs its own SMB server on `*:445` and Docker
cannot bind an occupied port. That is what the per-connection port field is for.

**Do not fill the form with `adb input` instead.** `windowSoftInputMode` is `adjustPan`,
so the keyboard shifts the form the moment the first field takes focus, and every
coordinate captured beforehand is stale for the rest of the sequence — the values land in
the wrong fields silently. The seed script edits the database instead (it needs a
debuggable build for `run-as`, and edits the file on the host since the device has no
`sqlite3`).

Connections survive `adb install -r`, so this is a one-time step per emulator.

The fixture binds to **loopback only** — its credentials are fixed and public, so it must
not be on the LAN. A physical phone therefore cannot reach it; point a phone at a real
share instead.

## The emulator

`rex-api36` (API 36, arm64-v8a, Google Play image):

```bash
"$ANDROID_HOME/emulator/emulator" -avd rex-api36 -no-audio -no-boot-anim &
until adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do sleep 5; done
```

**It must be arm64.** The bundled FFmpeg under `app/src/main/cpp/ffmpeg/lib` ships
`arm64-v8a` only, so an x86_64 emulator installs fine and then dies on
`System.loadLibrary("ffmpeg")`. `dev-run.sh` checks the ABI and refuses early.

## Driving the UI

Tap by querying real bounds, never by guessing coordinates from a screenshot:

```bash
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
adb shell cat /sdcard/ui.xml    # find the clickable node's bounds="[x1,y1][x2,y2]"
adb shell input tap <centre-x> <centre-y>
```

Cold start takes ~10s on the emulator. Tapping before the activity is drawn lands on
whatever is underneath — usually the launcher, which is confusing to debug from a
screenshot alone. `am start -W` blocks until it is drawn.

## What to exercise after a change

The fixture tree is built to cover the paths that actually break:

- `Photos/` — four plain images, so the 2x2 folder gallery icon is populated. They carry no
  EXIF, so their thumbnails come from the decode-the-file fallback
- `Photos/portrait.jpg` — carries an embedded EXIF thumbnail and orientation 6, so it
  exercises the fast path: look for `Served … from its embedded thumbnail` in logcat. It is
  portrait with a top-to-bottom gradient, so a correctly rotated thumbnail shows as
  landscape with the gradient running left to right — if it appears portrait, rotation has
  regressed
- `Videos/clip.mp4` — the JNI/ffmpeg thumbnail decoder (written `+faststart`, since the
  decoder reads from the start of the stream and would never find a trailing moov atom)
- `Photos/*.xmp` — sidecars feeding the Tags screen, in both naming conventions: `beach.xmp`
  binds to `beach.jpg` and `city.jpg.xmp` binds to `city.jpg`. `holiday` should show a count
  of 2 and `cityscape` a count of 1 — a missing `cityscape` means the full-filename
  convention has regressed. The screen also says "From folders you have opened", because
  sidecars are only found in directories that have been listed
- `Documents/notes.txt` — the `ACTION_VIEW` handoff to an external app
