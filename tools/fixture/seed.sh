#!/usr/bin/env bash
# Generate the fixture share's contents. Everything here is synthetic, so the tree can be
# deleted and regenerated at will and nothing real is ever copied into the repo.
#
# The layout is chosen to exercise the paths that actually break:
#   - several images in one folder  -> the 2x2 folder gallery icon needs >= 4
#   - an mp4 with faststart         -> the JNI/ffmpeg thumbnail decoder reads from the
#                                      START of the stream, so a trailing moov atom would
#                                      never be found within its 2 MB read window
#   - .xmp sidecars next to images  -> ScanTagsWorker matches a sidecar to its image by
#                                      basename, so beach.xmp binds to beach.jpg
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

printf 'Opened through ACTION_VIEW rather than the in-app browser.\n' \
  > "$media/Documents/notes.txt"

echo "seeded $media:"
find "$media" -type f | sort | sed "s|$media|  media|"
