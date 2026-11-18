#!/usr/bin/env bash
set -euo pipefail

FFMPEG_VERSION=7.1.1
FFMPEG_SHA256=733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1
NDK_VERSION=27.2.12479018
API_LEVEL=35

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
dest="$repo_root/app/src/main/cpp/ffmpeg"
work="${FFMPEG_WORK_DIR:-$repo_root/build/ffmpeg}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$sdk" ] || { echo "set ANDROID_HOME to an SDK with NDK $NDK_VERSION" >&2; exit 1; }
ndk="$sdk/ndk/$NDK_VERSION"
[ -d "$ndk" ] || { echo "NDK $NDK_VERSION not found at $ndk" >&2; exit 1; }

case "$(uname -s)" in
  Linux) host=linux-x86_64 ;;
  Darwin) host=darwin-x86_64 ;;
  *) echo "unsupported build host $(uname -s)" >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host"
clang="$toolchain/bin/aarch64-linux-android$API_LEVEL-clang"

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$work"
tarball="$work/ffmpeg-$FFMPEG_VERSION.tar.xz"
[ -f "$tarball" ] || curl -fsSL -o "$tarball" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
actual="$(sha256 "$tarball")"
[ "$actual" = "$FFMPEG_SHA256" ] || { echo "checksum mismatch for $tarball: $actual" >&2; exit 1; }

src="$work/ffmpeg-$FFMPEG_VERSION"
rm -rf "$src" "$work/install"
tar -xJf "$tarball" -C "$work"

cd "$src"
./configure \
  --prefix="$work/install" \
  --enable-cross-compile \
  --target-os=android \
  --arch=aarch64 \
  --sysroot="$toolchain/sysroot" \
  --cc="$clang" \
  --cxx="$clang++" \
  --ld="$clang" \
  --ar="$toolchain/bin/llvm-ar" \
  --as="$clang" \
  --nm="$toolchain/bin/llvm-nm" \
  --ranlib="$toolchain/bin/llvm-ranlib" \
  --strip="$toolchain/bin/llvm-strip" \
  --extra-cflags='-O3 -fPIC' \
  --extra-ldflags='-Wl,-z,max-page-size=16384' \
  --enable-shared \
  --disable-static \
  --disable-vulkan
make -j"$(getconf _NPROCESSORS_ONLN)"
make install

libs=(avcodec avdevice avfilter avformat avutil swresample swscale)
for lib in "${libs[@]}"; do
  [ -f "$work/install/lib/lib$lib.so" ] || { echo "the build produced no lib$lib.so" >&2; exit 1; }
done

rm -rf "$dest/include/arm64-v8a" "$dest/lib/arm64-v8a"
mkdir -p "$dest/include" "$dest/lib/arm64-v8a"
cp -R "$work/install/include" "$dest/include/arm64-v8a"
for lib in "${libs[@]}"; do
  cp "$work/install/lib/lib$lib.so" "$dest/lib/arm64-v8a/"
done
echo "FFmpeg $FFMPEG_VERSION installed into $dest"
