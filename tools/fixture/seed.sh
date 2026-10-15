#!/usr/bin/env bash
# Generate the fixture share's contents. Everything here is synthetic, so the tree can be
# deleted and regenerated at will and nothing real is ever copied into the repo.
#
# The layout is chosen to exercise the paths that actually break:
#   - several images in one folder  -> the 2x2 folder gallery icon needs >= 4
#   - an mp4 with faststart         -> the JNI/ffmpeg thumbnail decoder reads from the
#                                      START of the stream, so a trailing moov atom would
#                                      never be found within its 2 MB read window
#   - a non-previewable file        -> exercises the ACTION_VIEW handoff path
set -euo pipefail

# Deliberately NOT inside the repo. The compose file used to bind ./media relative to
# itself, which ties the share to whichever checkout it was started from — and a git
# worktree is a checkout that gets deleted. Removing a merged worktree silently emptied the
# running share, and the app then crashed listing a directory the server no longer had.
# A stable path outside the tree cannot be pulled out from under a running container.
media="${REX_FIXTURE_DIR:-$HOME/.cache/remoteexplorer-fixture}/media"

need() { command -v "$1" >/dev/null || { echo "missing required tool: $1" >&2; exit 1; }; }
need magick
need ffmpeg

# Where the committed fixture assets live, relative to this script rather than to the
# caller's working directory.
here=$(cd "$(dirname "$0")" && pwd)

# Empty it rather than replace it. `rm -rf "$media"` deletes the directory the running
# container has bind-mounted, and a bind mount follows the inode, not the path — so the
# share the container serves goes empty and STAYS empty however many times this is re-run,
# until the container is restarted. The app then browses happily from its Room cache and
# shows a tree that no longer exists, which is a confusing way to discover the problem.
mkdir -p "$media"
find "$media" -mindepth 1 -delete
mkdir -p "$media/Photos" "$media/Videos" "$media/Documents"

# Distinct colours so a thumbnail grid is readable at a glance in a screenshot. No text
# overlay: ImageMagick's -annotate needs a configured font, which is not portable enough
# to be worth depending on here, and the app draws the filename under each tile anyway.
image() { magick -size 640x480 "gradient:$2-white" "$media/Photos/$1.jpg"; }

image beach  blue
image forest green
image city   gray
image sunset orange

# A photo carrying the thumbnail its camera embedded, which is the fast path for the image
# grid: the app reads the first ~128 KB and lifts that thumbnail out, rather than pulling
# the whole file across to throw almost all of it away. The four above have no EXIF at all,
# so between them the two paths are both covered.
#
# The one file here that is copied rather than generated. Neither ImageMagick nor ffmpeg can
# write an IFD1 thumbnail, and assembling the APP1 segment by hand needs a language runtime
# this script should not depend on for test data. It is portrait with orientation 6 — a
# quarter turn clockwise — so a thumbnail that ignores rotation is obvious on sight rather
# than subtly wrong. See assets/README.md for how it was built.
cp "$here/assets/portrait-with-exif-thumbnail.jpg" "$media/Photos/portrait.jpg"

# +faststart moves the moov atom to the front; without it the in-app decoder cannot find
# the stream header in the first chunks it reads and the thumbnail silently fails.
ffmpeg -loglevel error -y \
  -f lavfi -i "testsrc=size=320x240:rate=15" -t 3 \
  -pix_fmt yuv420p -movflags +faststart \
  "$media/Videos/clip.mp4"

printf 'Opened through ACTION_VIEW rather than the in-app browser.\n' \
  > "$media/Documents/notes.txt"

# ---- the rolled-up tag index --------------------------------------------------------------
#
# gallery-dl writes one append-only log plus a meta file describing it, and the app reads
# its tags from that. Everything below matches the real
# record shape, including the parts that are awkward on purpose:
#
#   - a directory name with a space, brackets and non-ASCII, because the real library is
#     full of them and a path that only works when it is [A-Za-z0-9._/-] is how the imagor
#     spike died
#   - namespaced tags (artist:...) next to bare ones, since examplebooru and pixiv differ
#   - one file-scoped record, which is rare but real
#   - a tombstone, so dropping a path's tags is exercised rather than assumed
#   - a re-appended record for a path that already appeared, because last-one-wins is what
#     makes resuming from a byte offset valid, and a reader that takes the first would pass
#     every other test here
#   - dir_mtime on the newest record, a field added to the real generator after the first
#     index shipped, so an older reader ignoring it stays covered
#   - a live record with files:0, which exists upstream where a work's files could not be
#     attributed to one of two sibling directories. Displayable but empty, not an error
index="$media/gallery-dl/_index"
mkdir -p "$index" \
  "$media/gallery-dl/pixiv/[137128361] 羽衣狐" \
  "$media/gallery-dl/examplebooru/[artist] a gallery (with parens)" \
  "$media/gallery-dl/patreon/loose files"

image_at() { magick -size 320x240 "gradient:$2-white" "$1/$3"; }
image_at "$media/gallery-dl/pixiv/[137128361] 羽衣狐"           purple 001.jpg
image_at "$media/gallery-dl/pixiv/[137128361] 羽衣狐"           violet 002.jpg
image_at "$media/gallery-dl/examplebooru/[artist] a gallery (with parens)" red    01.jpg
image_at "$media/gallery-dl/examplebooru/[artist] a gallery (with parens)" pink   02.jpg
image_at "$media/gallery-dl/patreon/loose files"                teal   single.jpg
image_at "$media/gallery-dl/patreon/loose files"                cyan   untagged.jpg

# Paths are relative to the share root with no leading slash, exactly as the generator emits
# them. Written with printf rather than a heredoc so the JSON stays on one line each.
log="$index/index.ndjson"
: > "$log"
{
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/pixiv/[137128361] 羽衣狐","site":"pixiv","site_id":"137128361","title":"羽衣狐","tags":["Original","羽衣狐"],"files":2,"mtime":1790047563}'
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/examplebooru/[artist] a gallery (with parens)","site":"examplebooru","site_id":"991","title":"a gallery","token":"deadbeef","tags":["artist:someone","character:hagoromo gitsune","female:blush","parody:touhou project"],"files":2,"mtime":1790047600}'
  printf '%s\n' '{"scope":"file","path":"gallery-dl/patreon/loose files/single.jpg","site":"patreon","tags":["creator:somebody"],"mtime":1790047700}'
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/pixiv/[999] deleted gallery","site":"pixiv","tags":["gone"],"files":0,"deleted":true,"mtime":1790047800}'
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/pixiv/[137128361] 羽衣狐","site":"pixiv","site_id":"137128361","title":"羽衣狐","tags":["Original","羽衣狐","再掲"],"files":2,"mtime":1790047900,"dir_mtime":1790047910,"annotations":["narration"]}'
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/pixiv/[360] empty gallery","site":"pixiv","tags":["orphaned"],"files":0,"mtime":1790047950}'
} >> "$log"

# bytes is the log's length, which is both where a reader stops and the offset it resumes
# from — so it has to be measured after the file is written, never guessed.
#
# Spelled exactly as the real generator writes it: pretty-printed, an INTEGER generation and
# an ISO built_at. This used to write "generation":"fixture-1" and an epoch built_at, the app
# was typed to match, and the index on the real share then failed to parse on every pass —
# invisibly, since an unreadable meta reads as no index at all.
log_bytes=$(wc -c < "$log" | tr -d ' ')
records=$(wc -l < "$log" | tr -d ' ')
printf '{\n  "schema": 1,\n  "generation": 1,\n  "records": %s,\n  "bytes": %s,\n  "built_at": "2026-09-26T06:00:28+0900"\n}\n' \
  "$records" "$log_bytes" > "$index/index.meta.json"

# The compressed copy the first pull prefers. gzip -n keeps the name and timestamp out, so
# regenerating the fixture produces an identical file rather than a spurious diff.
gzip -nkf "$log"

# The alias layer: groups of raw tags that name the same thing, rewritten whole on every
# build under a generation of its own. Line shape, key order and spacing are the real
# generator's (json.dumps with sort_keys). Two groups, both reaching across providers:
#
#   - 羽衣狐 (pixiv) and character:hagoromo gitsune (examplebooru), so opening either tag shows
#     both galleries — the thing the layer exists for, visible on screen
#   - touhou project with its bare leaf, a member no gallery carries, which must still widen
aliases="$index/aliases.ndjson"
{
  printf '%s\n' '{"canonical": "characters/hagoromo gitsune", "confidence": "medium", "members": ["character:hagoromo gitsune", "hagoromo gitsune", "羽衣狐"], "needs_root": false, "sources": ["danbooru"]}'
  printf '%s\n' '{"canonical": "franchise/touhou project", "confidence": "high", "members": ["parody:touhou project", "touhou project", "東方Project"], "needs_root": false, "sources": ["translated_name"]}'
} > "$aliases"
groups=$(wc -l < "$aliases" | tr -d ' ')
printf '{\n  "schema": 1,\n  "generation": 1,\n  "groups": %s,\n  "queued": 0,\n  "overrides": 0,\n  "built_at": "2026-09-26T12:42:49+0900"\n}\n' \
  "$groups" > "$index/aliases.meta.json"

annotations="$index/annotations.ndjson"
{
  printf '%s\n' '{"body": {"format": "text/plain", "language": "ja", "value": "フィクスチャの説明文です。"}, "classifier": {"by": "fixture", "verdict": "narration"}, "created": "2026-10-10T17:23:46+0900", "creator": "author", "generator": {"field": "caption", "source": "gallery-dl"}, "id": "gallery-dl:pixiv.caption", "kind": "narration", "motivation": "describing", "path": "gallery-dl/pixiv/[137128361] 羽衣狐", "quality": "narration", "scope": "dir", "source_hash": "fixture"}'
  printf '%s\n' '{"body": {"format": "text/plain", "language": "en", "value": "A fixture caption, translated."}, "created": "2026-10-10T17:23:46+0900", "creator": "machine", "derived_from": {"id": "gallery-dl:pixiv.caption", "path": "gallery-dl/pixiv/[137128361] 羽衣狐"}, "generator": {"model": "fixture", "source": "llm"}, "id": "llm:translate-en:fixture", "kind": "narration", "motivation": "describing", "path": "gallery-dl/pixiv/[137128361] 羽衣狐", "quality": "narration", "scope": "dir", "source_hash": "fixture"}'
  printf '%s\n' '{"classifier": {"by": "fixture", "verdict": "not"}, "created": "2026-10-10T17:23:46+0900", "creator": "author", "deleted": true, "generator": {"field": "uploader_comment", "source": "gallery-dl"}, "id": "gallery-dl:examplebooru.uploader_comment", "kind": "narration", "motivation": "describing", "path": "gallery-dl/examplebooru/[artist] a gallery (with parens)", "quality": "not", "scope": "dir", "source_hash": "fixture"}'
} > "$annotations"
printf '{\n  "schema": 1,\n  "generation": 1,\n  "records": %s,\n  "bytes": %s,\n  "built_at": "2026-10-10T17:23:46+0900"\n}\n' \
  "$(wc -l < "$annotations" | tr -d ' ')" "$(wc -c < "$annotations" | tr -d ' ')" > "$index/annotations.meta.json"

echo "seeded $media:"
find "$media" -type f | sort | sed "s|$media|  media|"
