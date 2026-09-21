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
# Only for splicing an EXIF thumbnail below, which neither of the other two can do. Stdlib
# only, so any python3 will do.
need python3

rm -rf "$media"
mkdir -p "$media/Photos" "$media/Videos" "$media/Documents"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

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
# Neither ImageMagick nor ffmpeg can write an IFD1 thumbnail, so the APP1 segment is
# assembled here: a TIFF block with IFD0 (orientation) and IFD1 (the thumbnail's offset and
# length), spliced in after SOI. Portrait and orientation 6 on purpose — a quarter turn
# clockwise — so a thumbnail that ignores rotation is obvious on sight rather than subtly
# wrong.
magick -size 900x1200 gradient:purple-white "$media/Photos/portrait.jpg"
magick -size 120x160 gradient:purple-white "$tmp/embedded-thumb.jpg"

python3 - "$media/Photos/portrait.jpg" "$tmp/embedded-thumb.jpg" <<'PY'
import struct, sys

photo_path, thumb_path = sys.argv[1], sys.argv[2]
photo = open(photo_path, "rb").read()
thumb = open(thumb_path, "rb").read()

ORIENTATION_ROTATE_90 = 6

def entry(tag, type_, value):
    # A SHORT value is left-justified within the 4-byte value field; a LONG fills it.
    packed = struct.pack(">H", value) + b"\0\0" if type_ == 3 else struct.pack(">I", value)
    return struct.pack(">HHI", tag, type_, 1) + packed

ifd0 = struct.pack(">H", 1) + entry(0x0112, 3, ORIENTATION_ROTATE_90)
ifd0_end = 8 + len(ifd0) + 4          # TIFF header, IFD0, pointer to IFD1
thumb_offset = ifd0_end + 2 + 12 * 2 + 4

ifd1 = (
    struct.pack(">H", 2)
    + entry(0x0201, 4, thumb_offset)  # JPEGInterchangeFormat
    + entry(0x0202, 4, len(thumb))    # JPEGInterchangeFormatLength
    + struct.pack(">I", 0)            # no further IFD
)

# Offsets are relative to the start of the TIFF header, per TIFF 6.0.
tiff = b"MM\x00\x2a" + struct.pack(">I", 8) + ifd0 + struct.pack(">I", ifd0_end) + ifd1 + thumb
app1 = b"Exif\x00\x00" + tiff
segment = b"\xff\xe1" + struct.pack(">H", len(app1) + 2) + app1

with open(photo_path, "wb") as out:
    out.write(photo[:2] + segment + photo[2:])

print(f"  embedded a {len(thumb)}-byte thumbnail in portrait.jpg")
PY

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

echo "seeded $media:"
find "$media" -type f | sort | sed "s|$media|  media|"
