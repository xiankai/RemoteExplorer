#!/usr/bin/env bash
# Front door for the fixture share. Sets REX_FIXTURE_DIR (which compose.yml requires) and
# seeds the tree if it is not there yet, then passes everything through to compose.
#
#   tools/fixture/up.sh up -d     start it
#   tools/fixture/up.sh down      stop it
#   tools/fixture/up.sh logs -f   follow the samba log
#
# Override the location with REX_FIXTURE_DIR if ~/.cache is not where you want a few MB of
# synthetic media.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REX_FIXTURE_DIR="${REX_FIXTURE_DIR:-$HOME/.cache/remoteexplorer-fixture}"
export REX_FIXTURE_DIR

if [ ! -d "$REX_FIXTURE_DIR/media" ]; then
  echo "seeding $REX_FIXTURE_DIR/media"
  "$here/seed.sh"
fi

exec docker compose -f "$here/compose.yml" "$@"
