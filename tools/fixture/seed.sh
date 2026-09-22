#!/usr/bin/env bash
# Generate the fixture share's contents. Everything here is synthetic, so the tree can be
# deleted and regenerated at will and nothing real is ever copied into the repo.
#
# The layout is chosen to exercise the paths that actually break:
#   - several images in one folder  -> the 2x2 folder gallery icon needs >= 4
#   - an mp4 with faststart         -> the JNI/ffmpeg thumbnail decoder reads from the
#                                      START of the stream, so a trailing moov atom would
#                                      never be found within its 2 MB read window
#   - .xmp sidecars next to images  -> a sidecar binds to its image by name, in both
#                                      conventions: beach.xmp and city.jpg.xmp
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

rm -rf "$media"
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

sidecar() {
  cat > "$media/Photos/$1.xmp" <<XMP
<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
   <dc:subject>
    <rdf:Bag>
$(for tag in "${@:2}"; do echo "     <rdf:li>$tag</rdf:li>"; done)
    </rdf:Bag>
   </dc:subject>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
XMP
}

# Overlapping tags on purpose: "holiday" should end up with a count of 2.
sidecar beach  holiday beach
sidecar forest holiday nature
sidecar sunset beach

# Both sidecar naming conventions are in the wild, and only the first three above use the
# one that replaces the extension (Lightroom, Bridge). darktable and digiKam keep the whole
# filename and append .xmp, so this writes city.jpg.xmp next to city.jpg. That convention
# used to bind to nothing at all and be discarded silently, so it stays covered here.
sidecar city.jpg cityscape

printf 'Opened through ACTION_VIEW rather than the in-app browser.\n' \
  > "$media/Documents/notes.txt"

# ---- the rolled-up tag index --------------------------------------------------------------
#
# gallery-dl writes one append-only log plus a meta file describing it, and the app reads
# tags from that instead of walking a sidecar per file. Everything below matches the real
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
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/examplebooru/[artist] a gallery (with parens)","site":"examplebooru","site_id":"991","title":"a gallery","token":"deadbeef","tags":["artist:someone","female:blush","parody:touhou project"],"files":2,"mtime":1790047600}'
  printf '%s\n' '{"scope":"file","path":"gallery-dl/patreon/loose files/single.jpg","site":"patreon","tags":["creator:somebody"],"mtime":1790047700}'
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/pixiv/[999] deleted gallery","site":"pixiv","tags":["gone"],"files":0,"deleted":true,"mtime":1790047800}'
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/pixiv/[137128361] 羽衣狐","site":"pixiv","site_id":"137128361","title":"羽衣狐","tags":["Original","羽衣狐","再掲"],"files":2,"mtime":1790047900,"dir_mtime":1790047910}'
  printf '%s\n' '{"scope":"dir","path":"gallery-dl/pixiv/[360] empty gallery","site":"pixiv","tags":["orphaned"],"files":0,"mtime":1790047950}'
} >> "$log"

# bytes is the log's length, which is both where a reader stops and the offset it resumes
# from — so it has to be measured after the file is written, never guessed.
log_bytes=$(wc -c < "$log" | tr -d ' ')
records=$(wc -l < "$log" | tr -d ' ')
printf '{"schema":1,"generation":"fixture-1","records":%s,"bytes":%s,"built_at":1790047900}\n' \
  "$records" "$log_bytes" > "$index/index.meta.json"

# The compressed copy the first pull prefers. gzip -n keeps the name and timestamp out, so
# regenerating the fixture produces an identical file rather than a spurious diff.
gzip -nkf "$log"

echo "seeded $media:"
find "$media" -type f | sort | sed "s|$media|  media|"
