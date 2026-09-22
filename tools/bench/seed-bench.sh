#!/usr/bin/env bash
# Generate the benchmark tree inside the fixture share.
#
# Separate from tools/fixture/seed.sh on purpose. That tree is seeded on every `up.sh` and
# is deliberately tiny so the dev loop stays fast; these files are tens of megabytes and
# take a couple of minutes to encode. They also answer a different question: the default
# fixture proves a code path runs, this one measures what it costs.
#
# Not wired into seed.sh, which does `rm -rf` on the whole media tree and would therefore
# wipe this. That is fine — tools/bench/thumbnails.sh re-runs this script whenever the tree
# is missing, and it is idempotent: files already present are left alone.
set -euo pipefail

media="${REX_FIXTURE_DIR:-$HOME/.cache/remoteexplorer-fixture}/media"
bench="$media/Bench"

need() { command -v "$1" >/dev/null || { echo "missing required tool: $1" >&2; exit 1; }; }
need magick
need ffmpeg

here=$(cd "$(dirname "$0")" && pwd)

# The one input this script does not generate. Checked up front alongside the tools,
# because without it `set -e` would abort mid-seed on a raw ImageMagick error that names
# neither this script nor the file it wanted.
exif_source="$here/../fixture/assets/portrait-with-exif-thumbnail.jpg"
[ -f "$exif_source" ] || {
  echo "missing fixture asset: $exif_source" >&2
  echo "it is the only source of an embedded EXIF thumbnail here — see its README" >&2
  exit 1
}

mkdir -p "$bench"

# Everything here is deliberately high-entropy. The first attempt at this file used plain
# `testsrc` and a smooth `gradient:`, and both compress to almost nothing — a 1920x1080
# clip nominally encoded at 20 Mbit came out at 1.3 MB, and a 6000x4000 gradient asked to
# reach 12 MB came out at 1.1 MB. At those sizes the difference between reading a 2 MB
# prefix and a targeted 200 KB range is invisible, which defeats the point. Noise is what
# forces the encoder to actually spend the bitrate.

# A video's thumbnail cost is dominated by how far into the file the decoder must read
# before it finds a frame, so the set varies the two things that move that: resolution,
# and whether the moov atom is at the front.
video() {
  local name=$1 size=$2 seconds=$3 bitrate=$4
  shift 4
  [ -f "$bench/$name" ] && return 0
  echo "  encoding $name"
  # minrate/maxrate/bufsize pinned to the target: without them x264 spends what the
  # content needs rather than what was asked for, and the file lands far under size.
  ffmpeg -loglevel error -y \
    -f lavfi -i "testsrc=size=$size:rate=30,noise=alls=70:allf=t+u" \
    -t "$seconds" -pix_fmt yuv420p -preset veryfast \
    -b:v "$bitrate" -minrate "$bitrate" -maxrate "$bitrate" -bufsize "$bitrate" \
    "$@" "$bench/$name"
}

# The common case: 1080p, faststart, enough of them that the grid has something to stall on.
for i in 1 2 3 4 5 6; do
  video "hd-$i.mp4" 1920x1080 8 25M -movflags +faststart
done

# 4K, where the native side currently returns a full-resolution RGBA frame — about 33 MB
# across JNI per decode, before anything is scaled down to 128 px.
video "uhd-1.mp4" 3840x2160 6 40M -movflags +faststart
video "uhd-2.mp4" 3840x2160 6 40M -movflags +faststart

# No +faststart, so the moov atom sits at the END of the file. This is the case the current
# code burns its whole 18 MB budget on and then fails anyway, and the case a targeted read
# has to handle by seeking to the tail rather than by reading a longer prefix.
video "no-faststart.mp4" 1920x1080 8 25M

# Two kinds of large photo, which together are what makes #27's claim measurable. The
# committed fixture photos are ~36 KB: the embedded-thumbnail path is proven in mechanism
# there but its bandwidth saving cannot show up at that size.

# WITH an embedded thumbnail. Enlarged from the committed fixture asset rather than built
# from scratch, because ImageMagick carries the EXIF profile through a re-encode intact —
# IFD1 thumbnail (~1.4 KB) and orientation 6 included. Neither ImageMagick nor ffmpeg can
# author an IFD1 thumbnail, and assembling the APP1 segment by hand would need a language
# runtime this script should not depend on, so inheriting one is the way to get a
# multi-megabyte photo that still has one. Expected cost: the ~128 KB header read, and
# nothing else.
embedded_photo() {
  local name=$1
  [ -f "$bench/$name" ] && return 0
  echo "  generating $name"
  magick "$exif_source" -resize 4000x6000 +noise Gaussian -quality 88 "$bench/$name"
}

embedded_photo embedded-1.jpg
embedded_photo embedded-2.jpg

# WITHOUT one, so the fallback is measured too: no embedded thumbnail means the whole file
# comes across the wire to be decoded and thrown away at 128 px.
plain_photo() {
  local name=$1
  [ -f "$bench/$name" ] && return 0
  echo "  generating $name"
  magick -size 5000x3500 plasma:fractal +noise Gaussian -quality 85 "$bench/$name"
}

plain_photo plain-1.jpg
plain_photo plain-2.jpg

# A codec and container spread, in its own folder. Small files on purpose — this set is
# about which formats each decoder can open, not how fast it does it, so a few seconds of
# each is enough. It is what showed that the platform extractor and FFmpeg cover different
# formats rather than one dominating (#41), and what caught a decode failure escaping as a
# 500 instead of a 404.
#
# Each encode is optional: a local ffmpeg without libaom or libtheora should skip that one
# and carry on rather than abort the whole seed.
codecs="$media/Codecs"
mkdir -p "$codecs"

codec() {
  local name=$1
  shift
  [ -f "$codecs/$name" ] && return 0
  if ffmpeg -loglevel error -y \
      -f lavfi -i "testsrc=size=1280x720:rate=24,noise=alls=40:allf=t+u" \
      -t 4 "$@" "$codecs/$name" 2>/dev/null; then
    echo "  encoded $name"
  else
    echo "  skipped $name (this ffmpeg cannot encode it)"
    rm -f "$codecs/$name"
  fi
}

codec h264.mp4  -c:v libx264    -pix_fmt yuv420p -movflags +faststart
codec hevc.mp4  -c:v libx265    -pix_fmt yuv420p -tag:v hvc1 -movflags +faststart
codec vp9.webm  -c:v libvpx-vp9 -pix_fmt yuv420p -b:v 1M
codec av1.mp4   -c:v libaom-av1 -pix_fmt yuv420p -cpu-used 8 -b:v 1M
codec h264.mkv  -c:v libx264    -pix_fmt yuv420p
codec mpeg4.avi -c:v mpeg4      -pix_fmt yuv420p
codec theora.ogv -c:v libtheora -pix_fmt yuv420p -q:v 5

echo "bench tree at $bench:"
find "$bench" -type f -exec ls -lh {} + | awk '{print "  " $9 " (" $5 ")"}' | sed "s|$bench/||"
