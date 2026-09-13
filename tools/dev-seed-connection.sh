#!/usr/bin/env bash
# Insert the fixture connection straight into the app's Room database.
#
# Typing it into the UI is not reproducible: windowSoftInputMode is adjustPan, so the
# keyboard shifts the form as soon as the first field takes focus and every coordinate
# captured beforehand goes stale mid-sequence. This writes the row instead.
#
# Only works on a debuggable build — `run-as` is what grants access to the private data
# directory, and there is no sqlite3 binary on the device, so the file is edited here and
# copied back.
set -euo pipefail

name="${REX_NAME:-fixture}"
host="${REX_HOST:-10.0.2.2}"
port="${REX_PORT:-1445}"
user="${REX_USER:-explorer}"
pass="${REX_PASS:-explorer}"
domain="${REX_DOMAIN:-}"
share="${REX_SHARE:-media}"
appid="${REX_APPID:-com.bespectacledcyborg.remoteexplorer.debug}"

sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
db=databases/remote-explorer
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

command -v sqlite3 >/dev/null || { echo "sqlite3 not on PATH" >&2; exit 1; }

# The app must not be running: it holds the database open, and a copy taken underneath it
# can be torn.
"$adb" shell am force-stop "$appid"

"$adb" exec-out run-as "$appid" cat "$db" > "$tmp/db" 2>/dev/null || {
  echo "could not read $db — launch the app once so Room creates it, and make sure this is a debuggable build" >&2
  exit 1
}
[ -s "$tmp/db" ] || { echo "$db came back empty" >&2; exit 1; }

# name carries a UNIQUE index, so re-running updates in place rather than duplicating.
sqlite3 "$tmp/db" <<SQL
INSERT INTO \`Connection\` (name, hostname, port, username, password, domain, share, lastModified)
VALUES ('$name', '$host', $port, '$user', '$pass', '$domain', '$share', $(date +%s)000)
ON CONFLICT(name) DO UPDATE SET
  hostname=excluded.hostname, port=excluded.port, username=excluded.username,
  password=excluded.password, domain=excluded.domain, share=excluded.share,
  lastModified=excluded.lastModified;
SQL

"$adb" push "$tmp/db" /data/local/tmp/rex-seed.db >/dev/null
"$adb" shell run-as "$appid" cp /data/local/tmp/rex-seed.db "$db"
# Drop the write-ahead log: it belongs to the file we just replaced, and leaving it would
# shadow the row that was written.
"$adb" shell run-as "$appid" rm -f "$db-wal" "$db-shm"
"$adb" shell rm -f /data/local/tmp/rex-seed.db

echo "seeded connection '$name' -> $user@$host:$port/$share"
