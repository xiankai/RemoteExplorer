# Remote Explorer

An Android SMB/CIFS browser aimed at media: save Samba connections, browse folders as a
gallery, get thumbnails for images and video, swipe through images, hand other file types to
external apps, and browse by tags — from gallery-dl's rolled-up index on the share and from
XMP sidecars.

Tags come from a `TagSource`. Every connection runs the same `CombinedTagSource`: the index
(`IndexFileTagSource`), the alias layer (`AliasFileTagSource`) and sidecars
(`XmpSidecarTagSource`). It is a union, not a fallback chain — they describe different parts of
a share, and a share can have any of them or none. Whichever fills the tables, reads always
come back out of Room, so browsing by tag stays offline.

Written pre-agentically between Nov 2024 and Apr 2025, dormant until Mar 2026. Treat older
code as deliberate but undocumented — several oddities below turned out to be load-bearing.

## Architecture

Single `:app` module, Kotlin + Compose + Material3, manual DI, roughly clean-architecture:

```
MainActivity ── ViewModelProvider.Factory (hand-wired DI, MainActivity.kt)
  ├ ExplorerViewModel   path/nav stack, files, connections, tags, thumbnails, sort/filter
  └ SettingsViewModel
       Repositories: File / Server / Connection / Tag / Settings / ThumbnailManager
       DataSources:  RoutingFileDataSource ─┬ SambaFileDataSource (SMBJ)
                                            └ LocalFileDataSource (java.io.File)
                     LocalDatabaseDataSource (Room v18)
```

**Two kinds of connection.** A `Connection` is an SMB share or, with `type = LOCAL`, a folder
on the device itself — a Syncthing'd copy of a share (#51). Both get a `connectionId`, so every
table keyed by connection works for either, and everything that reads a file holds the one
`RoutingFileDataSource` and never asks which kind it has. A local root is read with plain
`java.io.File` under all-files access (`MANAGE_EXTERNAL_STORAGE`), confined to its folder, and
never lists or opens Syncthing's own files (`.stfolder`, `.stversions`, temp files). It indexes
no tags of its own.

**Pairing** says a local folder is a copy of a remote one, matched by relative path.
`Pairings.canonicalize` names a paired local file as its remote twin, and it is the one name
for a file everywhere that identifies one: the view model applies it before `constructUrl`
(Sketch keys its cache by URL) and the server applies it again on receipt, before the
thumbnail cache key and the recorded thumbnail outcome. Where the bytes come from is a
separate question — `SourceResolver` reads the local copy when it has the file and the share
otherwise, whichever side the request came from, opening that connection on demand. Keep
the two apart: a key computed from the *served* identity splits the cache again. Pairings are
edited from the local root's connection screen and shown, read-only, on the share's.

**The central trick.** `ServerRepository` runs an embedded Ktor/Jetty HTTP server on
localhost that re-serves SMB files over HTTP with `Range` support. Everything downstream
then consumes plain URLs — Sketch loads thumbnails, `ACTION_VIEW` hands video to external
players — without downloading files first. URLs look like
`http://127.0.0.1:<port>/<path>?connectionId=N[&thumbnail=true]`.

**Room is a mirror, not just preferences.** `LocalFile` caches the remote listing with
per-file `tagStatus` / `thumbnailStatus` state machines. `listFiles` serves from the DB and
only hits SMB when the DB is empty or you pull-to-refresh.

**What has been viewed is user data, not cache** (#76). `FileView` holds one row per file
actually opened or watched, keyed by the canonical identity, and nothing but `ViewRecorder`
writes it: never put a view column on `LocalFile`, which the reconcile deletes and resets.
An image counts after `IMAGE_DWELL_MILLIS` on a settled page in the viewer. Anything handed
to another app counts as a launch, and video and audio add `watchedSeconds` from the bytes
the loopback server sends (`PlaybackSessions`: ranges merged per session, converted by the
container's duration, clamped by wall clock). Folders and tags are queries over the table,
never stored rollups. A viewing day starts at a configured time (noon) in a configured zone
(`Asia/Tokyo`), both read at write time.

**The tag index protocol** (generator side: infra#199). Files live under
`gallery-dl/_index/` relative to the share root — on the real library, share `library`,
which is `/srv/library`. Three independent parts, each with its own contract:

- `index.ndjson` (+ `.gz`) and `index.meta.json` — an append-only log of directory-scoped
  records, last record per path wins. Same `generation` → range-read from the stored byte
  offset (`TagIndexIngest`); a different one → full pull that *replaces* the connection's
  rows. A resume whose first line will not parse means the log was replaced underneath,
  and re-pulls. Rebuilt daily at 6am.
- `aliases.ndjson` and `aliases.meta.json` — groups of raw tags that mean the same thing
  (`眼鏡` ↔ `female:glasses`). Rewritten whole on every build and the generation bumps
  every time, so there is no offset: a new generation replaces the connection's `TagAlias`
  rows. Resolved at **query time**: opening a tag lists everything carrying any member of
  its group, one hop, never chained. `canonical` is the group's key and nothing parses it,
  but since the curated digiKam tags became the canonicals (infra#232) it reads as a
  name, so the list shows it — minus the `?/` the editor puts on one still waiting for a root.
  The meta also carries `hidden`: tags hidden in the editor, groups already expanded. They
  arrive with the groups into `TagHidden` and are left out of the tag list, not out of a
  gallery's own tags.

- `annotations.ndjson` and `annotations.meta.json` — author captions (infra#312),
  read with the same generation/byte-offset protocol as the index, keyed by `path` + `id`,
  last record wins. Only a live `quality: narration` record is kept; a tombstone or any other
  verdict (`not`, `minor`) removes it. An `llm:translate-en:*` record with `derived_from` is
  the English shown first, and only while its source is live. The index record's
  `annotations: ["narration"]` drives the tile badge. Both are behind the off-by-default
  "Show author captions" setting. The text is the authors' own words: never put real
  caption text in a commit, test or fixture.

All three metas spell `generation` as an integer and `built_at` as an ISO string. The fixture once
spelled them differently, the parser matched the fixture, and the real index silently read as
absent — an unreadable meta is deliberately treated as no meta. The tag list shows one entry
per alias group, labelled by its canonical, and counts what opening it lists across the whole
group.

**Native.** Prebuilt FFmpeg (`app/src/main/cpp/ffmpeg/`) plus a JNI wrapper that decodes the
first video frame. It is the *second* video decoder: `PlatformVideoThumbnailSource` goes
first and is cheaper, and FFmpeg covers what the device has no codec for — about 7.6% of the
real library is DivX/Xvid `.avi`, which is why the native build cannot be deleted (#41).

Both decoders read through one `RemoteRandomAccess`, a seekable ranged reader over whichever
source the connection has:
Android's `MediaDataSource` and FFmpeg's AVIO callbacks are the same abstraction spelled
differently. Nothing copies a prefix of the file any more.

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
  side by side under a `.releasedebug` application id. It departs from `release` in exactly
  two ways, both so it can be run at all on a dev machine: it is `isDebuggable` (otherwise
  `run-as` is refused and `dev-seed-connection.sh` cannot write the fixture connection, and
  a Play-image emulator has no `adb root`), and it falls back to the debug keystore. Neither
  touches packaging or the native build, which is what the variant is for.
- **Signing is optional by design.** `keystore.properties` and the `.jks` are gitignored;
  the build registers the release signing config only when both exist, so a plain clone can
  build `debug`. CI writes both before building. Consequence: a `release` task with no
  signing material produces an *unsigned* artifact rather than failing loudly — and an
  unsigned APK builds fine and then dies at install with
  `INSTALL_PARSE_FAILED_NO_CERTIFICATES`. `releaseDebug` is exempt, per above.
- **macOS ships bash 3.2.** No `mapfile`, no `${var^}`. Scripts in `tools/` target 3.2.
- **Port 445 is taken on macOS** by its own SMB server, which is why the fixture publishes
  1445 and why `Connection.port` exists.
- **Do not drive the connection form by tapping coordinates.** The form scrolls to keep the
  focused field above the keyboard, so every pre-captured coordinate goes stale
  mid-sequence and values land in the wrong fields silently. Tap the first field once, then
  move between fields with `input keyevent KEYCODE_TAB`. To just get a working connection,
  `tools/dev-seed-connection.sh` writes the row into Room instead.
- **Tap by real bounds**, never by eyeballing a screenshot: `adb shell uiautomator dump`,
  then tap the node's centre. While the mobile MCP is connected that dump is killed and
  writes nothing; take the bounds from `mobile_list_elements_on_screen` and tap with
  `adb shell input tap` instead (`.claude/skills/run/SKILL.md`). Cold start is ~10s; `am start -W` blocks until drawn.
- **A trailing `moov` atom is handled now, and used to be fatal.** Neither decoder needs
  `+faststart` any more — both seek to the end for the index. It was fatal because our AVIO
  shim answered `AVSEEK_SIZE` with the length of the 2 MB prefix it had copied, so the
  demuxer was told the file ended before its own index. `no-faststart.mp4` in the bench
  fixture is the regression test: it must come back 200, not 404.
- **There is no Firebase.** The `google-services` plugin is applied nowhere, so a
  `google-services.json` would be inert — one was committed for a long time and never read.
  Adding the file alone does nothing; adding the plugin is the actual decision. Its API key
  is still in git history, but the Firebase project behind it was shut down on 2026-09-27
  (#15), so the string is inert. Note the general rule it illustrates: deleting a secret from
  `HEAD` does not remove it from history, so a real credential is rotated at the source,
  never just deleted.
- **Credentials are encrypted by a Room type converter, not by callers.** `Connection.password`
  is a `SecretText`, and `CredentialConverters` is the single place it changes form — so no
  call site can forget. Two traps live here. `SecretText` must NOT become a `@JvmInline value
  class`: Room unwraps those itself, which silently persists the plaintext and skips the
  converter, and the failure looks exactly like success. And rows written outside the app stay
  in the clear on purpose — `tools/dev-seed-connection.sh` talks to SQLite directly and cannot
  reach the Keystore — so anything without the `enc:v1:` prefix is read back as-is rather than
  decrypted into garbage. The 8→9 migration rewrites the plaintext already on disk and is
  idempotent, because a re-run that double-encrypted would strand a user outside their share.
- **The thumbnail cache is not always in `cacheDir`.** A setting can point it at an SD card
  (#43), so anything that clears or counts it has to ask where it is —
  `tools/bench/thumbnails.sh --cache-dir`. A harness that assumes `cache/thumbnails` clears
  nothing, passes its own "cache is empty" check, and then measures cache hits as if they
  were work.
- **Room schema directories are named after the database CLASS**, e.g.
  `app/schemas/com.bespectacledcyborg.remoteexplorer.data.db.AppDatabase/`. Room reads only
  the directory matching the current fully-qualified name, so schemas left behind by a
  package rename are unreachable, not history. The live ones are load-bearing: deleting
  `5.json` would break `AutoMigration(5, 6)` at compile time.

## CI and release

Two systems, deliberately:

| | where | trigger | does |
|---|---|---|---|
| Pre-merge checks | Forgejo, `.forgejo/workflows/build.yml` | `pull_request` | shellcheck, unit tests, lint |
| Automated review | Forgejo, `.forgejo/workflows/review.yml` | CODEOWNERS auto-request | `stack-reviewer` posts findings |
| Sideload publish | Forgejo, `.forgejo/workflows/publish.yml` | push to `master` | signed APK → fileserver (one copy, overwritten) + Forgejo release (latest 3 kept) |
| Release | **GitHub** `xiankai/RemoteExplorer` | push to `master` | `bundleRelease` → Play internal, draft |

A change that touches only Markdown skips the checks, the sideload publish and the release.
The reviewer still runs.

The sideload job is the one that pays for the NDK, holds a signing key of its own
(`SIDELOAD_KEYSTORE_*`, generated 2026-09-19, alias `sideload`) and cannot be moved to GitHub,
because fileserver is only reachable from the self-hosted. Obtainium installs from the fileserver URL; it
could read the Forgejo releases instead only if this repo were public, since its Forgejo
source has no token field.

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

1. **The HTTP server treats `connectionId` as the only thing resembling auth.** It binds
   loopback now, so the exposure is other apps on the device rather than the whole LAN.
   Passwords in Room are encrypted as of v9 — see below. A local root widens it: with
   all-files access, another app can read anything under a configured root through this
   server. `LocalFileDataSource` confining every path to its root is what keeps that to the
   root rather than the whole of shared storage.
2. **Tests are thin.** `FileRepositoryTest` covers the cached-and-filtered path and
   `AppDatabaseMigrationTest` covers the v5→v13 chain, and the tag index, alias layer and
   their queries have DAO tests against a real Room database; the Ktor range server and the
   JNI decoder are still untested, and there is no `androidTest/` source set at all.

   The migration tests run under **Robolectric as JVM unit tests**, not on a device, because
   `MigrationTestHelper` needs an `Instrumentation` but CI has no emulator — so they sit
   inside `testDebugUnitTest`, which CI already runs. Robolectric serves the *variant's*
   merged assets, which is why the exported schemas are attached to the `debug` source set in
   `build.gradle.kts`: a `test` source set entry is invisible to it, and `main` would ship the
   schemas inside the release APK.

The entries that used to live here — search doing nothing, stacked collectors, tags not
refreshing in-session, the two inert settings, `fallbackToDestructiveMigration()`, the dead
code and the stale `com.example.remoteexplorer.*` schema directory — were cleared by PRs #6,
#7, #9, #11 and #12, and the Firebase key in history by shutting its project down (#15). The
reasoning is in those commits; this file tracks what is still true.
