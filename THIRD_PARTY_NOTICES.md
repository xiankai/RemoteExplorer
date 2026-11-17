# Third-party notices

Remote Explorer is licensed under the GNU General Public License, version 3 or (at your
option) any later version. See [LICENSE](LICENSE).

## FFmpeg

The APK bundles shared libraries from [FFmpeg](https://ffmpeg.org) 7.1.1 (`libavcodec`,
`libavdevice`, `libavfilter`, `libavformat`, `libavutil`, `libswresample`, `libswscale`),
used to decode the first frame of a video for its thumbnail.

FFmpeg is licensed under the GNU Lesser General Public License, version 2.1 or later. The
libraries are built without `--enable-gpl` or `--enable-nonfree` and link no external
libraries. The license text is in
[app/src/main/cpp/ffmpeg/COPYING.LGPLv2.1](app/src/main/cpp/ffmpeg/COPYING.LGPLv2.1).

**Source.** The unmodified source is the release tarball
<https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz> (SHA-256
`733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1`).
[tools/ffmpeg/build.sh](tools/ffmpeg/build.sh) downloads it, checks that hash, and builds the
libraries for `arm64-v8a` with Android NDK 27.2.12479018 and these configure flags:

```
--enable-cross-compile --target-os=android --arch=aarch64
--extra-cflags='-O3 -fPIC' --extra-ldflags='-Wl,-z,max-page-size=16384'
--enable-shared --disable-static --disable-vulkan
```

The libraries are loaded as separate shared objects, so they can be replaced with a
modified build of the same FFmpeg version.
