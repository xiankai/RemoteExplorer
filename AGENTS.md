# Remote Explorer

An Android SMB/CIFS browser aimed at media: save Samba connections, browse folders as a
gallery, get thumbnails for images and video, swipe through images, hand other file types to
external apps, and browse by tags harvested from XMP sidecars.

Written pre-agentically between Nov 2024 and Apr 2025, dormant until Mar 2026. Treat older
code as deliberate but undocumented — several oddities below turned out to be load-bearing.

## Architecture

Single `:app` module, Kotlin + Compose + Material3, manual DI, roughly clean-architecture:

```
MainActivity ── ViewModelProvider.Factory (hand-wired DI, MainActivity.kt)
  ├ ExplorerViewModel   path/nav stack, files, connections, tags, thumbnails, sort/filter
  └ SettingsViewModel
       Repositories: File / Server / Connection / Tag / Settings / ThumbnailManager
       DataSources:  SambaFileDataSource (SMBJ)  +  LocalDatabaseDataSource (Room v8)
```

**The central trick.** `ServerRepository` runs an embedded Ktor/Jetty HTTP server on
localhost that re-serves SMB files over HTTP with `Range` support. Everything downstream
then consumes plain URLs — Sketch loads thumbnails, `ACTION_VIEW` hands video to external
players — without downloading files first. URLs look like
`http://127.0.0.1:<port>/<path>?connectionId=N[&thumbnail=true]`.

**Room is a mirror, not just preferences.** `LocalFile` caches the remote listing with
per-file `tagStatus` / `thumbnailStatus` state machines. `listFiles` serves from the DB and
only hits SMB when the DB is empty or you pull-to-refresh.

**Native.** Prebuilt FFmpeg (`app/src/main/cpp/ffmpeg/`) plus a JNI wrapper that decodes the
first video frame from an in-memory buffer. It exists because Sketch/Coil's HTTP fetchers
cannot do partial requests for video.

## Running it

```bash
tools/fixture/up.sh up -d                                                   # once
tools/dev-seed-connection.sh                                                # once per emulator
tools/dev-run.sh --shot /tmp/app.png                                        # every change
```

`.claude/skills/run/SKILL.md` carries the detail. A green build proves nothing about this
app — read the screenshot.

Requires: JDK 21, SDK 36, NDK 27.2.12479018, cmake 3.22.1, and an **arm64** device. The
`rex-api36` AVD is arm64-v8a.

## Things that will bite you

- **arm64 only.** The bundled FFmpeg ships `arm64-v8a` and nothing else, so `abiFilters` is
  pinned in `defaultConfig`. An x86_64 emulator installs fine and then dies inside
  `System.loadLibrary("ffmpeg")`. `dev-run.sh` refuses early.
- **Release-only bugs are a recurring class here.** The 16 KB page-size fix, the ktor/Jetty
  breakage and an NDK segfault all reproduced only in release. Check anything touching
  ffmpeg/JNI, the Ktor server or packaging with `--variant releaseDebug`, which installs
  side by side under a `.releasedebug` application id.
- **Signing is optional by design.** `keystore.properties` and the `.jks` are gitignored;
  the build registers the release signing config only when both exist, so a plain clone can
  build `debug`. CI writes both before building. Consequence: a release task with no
  signing material produces an *unsigned* artifact rather than failing loudly.
- **macOS ships bash 3.2.** No `mapfile`, no `${var^}`. Scripts in `tools/` target 3.2.
- **Port 445 is taken on macOS** by its own SMB server, which is why the fixture publishes
  1445 and why `Connection.port` exists.
- **Do not drive the connection form with `adb input`.** `windowSoftInputMode` is
  `adjustPan`, so the keyboard shifts the layout as soon as the first field focuses and
  every pre-captured coordinate goes stale mid-sequence — values land in the wrong fields
  silently. `tools/dev-seed-connection.sh` writes the row into Room instead.
- **Tap by real bounds**, never by eyeballing a screenshot: `adb shell uiautomator dump`,
  then tap the node's centre. Cold start is ~10s; `am start -W` blocks until drawn.
- **Video fixtures need `+faststart`.** The decoder reads from the start of the stream and
  will never find a trailing `moov` atom inside its read window.

## CI and release

Two systems, deliberately:

| | where | trigger | does |
|---|---|---|---|
| Pre-merge checks | Forgejo, `.forgejo/workflows/build.yml` | `pull_request` | shellcheck, unit tests, lint |
| Automated review | Forgejo, `.forgejo/workflows/review.yml` | CODEOWNERS auto-request | `stack-reviewer` posts findings |
| Release | **GitHub** `xiankai/RemoteExplorer` | push to `master` | `bundleRelease` → Play internal, draft |

`origin` is Forgejo; the release pipeline lives on GitHub and holds the only copy of the
release keystore (as Actions secrets, which cannot be read back).

**CI deliberately skips the native build and the APK.** The NDK is several GB on a runner
with no layer cache, and the arm64-only prebuilts mean CI could not run the result anyway.
`assembleDebug` is covered on a developer machine by `tools/dev-run.sh`.

`-Prex.skipNative` is what achieves that, and it is load-bearing. Inspecting the task graph
is **not** sufficient to conclude the NDK is unused: no CMake task enters the graph for
`lintDebug` or `testDebugUnitTest`, yet merely *declaring* `externalNativeBuild` makes AGP
resolve `ndkVersion` at configuration time, so an unguarded run downloads the whole NDK to
compile nothing. That mistake shipped once and was caught by reading a CI log. Measured on
`lintDebug --dry-run --info`: 76 NDK references without the flag, zero with it.

Never use the flag to produce an installable artifact — the APK would ship without
`libffmpeg.so` and die inside `System.loadLibrary` at the first video thumbnail.

Workflows here follow `infra`'s proven conventions: `pull_request` rather than
`pull_request_target` for anything executing PR code, no third-party Actions, the ephemeral
run token unset before PR code runs, and jobs combined because the runner pool is capacity 2.

### The reviewer

`stack-reviewer` reviews every PR. Settle its findings **in the same commit**: fix genuine
bugs, and mark accept-by-design items with a `stack-reviewer-ignore: <reason>` pragma on the
line — an unsettled item returns with a fresh fingerprint every round. Pushing re-triggers a
pass; the second pass is incremental and cheap.

## Known issues

Not yet fixed, roughly by user-visible impact:

1. **Search is a no-op.** `ExplorerScreen` computes the filtered list and discards the
   result, and the DAO's `filename LIKE :filter` has no wildcards, so only an exact filename
   matches. The remote path uses case-sensitive `contains`.
2. **Collectors stack up.** `ExplorerViewModel.listFiles()` launches a new permanent
   `combine(path, search).collect` on every call — every connect and pull-to-refresh adds
   another, all still reacting to path changes.
3. **Tags never refresh in-session.** `_tags` is populated once in `init {}` and never
   updated after `ScanTagsWorker` finishes, so a newly scanned tag is invisible until
   restart.
4. **The storage cache setting does nothing.** `MainActivity` builds a `Sketch` instance and
   discards it without installing it as the singleton, and reads the setting before the DB
   flow has emitted, so it is always the default.
5. **`defaultColumnCount` is never wired** to `ExplorerViewModel`, which hardcodes 4.
6. **The HTTP server binds `0.0.0.0`**, exposing the share to the LAN with `connectionId` as
   the only thing resembling auth. SMB passwords are stored in plaintext in Room.
7. **`fallbackToDestructiveMigration()`** despite exported schemas and real auto-migrations —
   a schema bump silently wipes the cache.
8. Dead code: `PropertiesLoader`, `ThumbnailRepository`, `VideoDataSource`,
   `FileRepository.getDataSource`, `ThumbnailStatus.NESTED`, and a stale
   `app/schemas/com.example.remoteexplorer.*` directory from the package rename.
9. **No real tests.** `test/` and `androidTest/` hold only IDE scaffolding.
