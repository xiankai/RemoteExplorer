# Thumbnail benchmark

Measures what a folder of thumbnails costs — bytes off the share and wall clock per file —
so #28 can be argued with numbers rather than with the claim that a new shape ought to be
cheaper.

```bash
tools/fixture/up.sh up -d                       # the share
tools/dev-seed-connection.sh                    # once per emulator
tools/bench/thumbnails.sh --out /tmp/before.txt # ~2 min, seeds its media on first run
```

Then make the change and run it again into `/tmp/after.txt`. The reports diff cleanly.

Useful flags: `--variant releaseDebug`, `--folder Photos`, `--concurrency 4`,
`--device <serial>`.

## What it measures, and how

**Bytes** come from the fixture container's `eth0` transmit counter, not from inside the
app. That is what actually crossed the wire, SMB framing included, and no accounting bug in
the code under test can flatter it. The cost is that the share must otherwise be idle.

**Timing** comes from `curl` per request, and the app's own `Thumbnail <outcome> … in Nms`
log is read alongside it to tell a decode from a cache hit.

**It drives the loopback HTTP server directly**, not the UI. The obvious harness taps
through to a folder and watches the grid fill, and it measures the wrong thing badly: the
grid is lazy, so only on-screen cells ever request anything; a folder tile renders a 2×2
gallery of its *contents*, so the parent screen bills thumbnails from other folders to the
run; and there is no signal for "finished", so the report gets taken mid-flight. The first
version of this script had all three faults. The server starts with the ViewModel and is
the single entry point every thumbnail goes through, so requesting from it gives an exact
file set, an exact order, honest timing, and explicit concurrency. What it does not cover
is Sketch and the grid above it — deliberately, since the cost #28 is about is all below
that line.

**It refuses to quietly report a contaminated number.** The app keeps rendering folder
galleries for a while after a connection is opened, so the harness waits for that to go
quiet, clears the cache, and then checks the app's log: any cache hit, or any thumbnail
rendered outside the target folder, prints a `CONTAMINATED` banner. An early version
reported 70 MiB and a 0.03 s "decode" of a 24 MB video, which was a cache hit it had caused
itself.

## The media

`seed-bench.sh` writes `Bench/` into the fixture share — about 280 MiB, a minute to
generate, kept out of `tools/fixture/seed.sh` so the normal dev loop stays fast. It is
re-run automatically when the tree is missing, and skips files that already exist.

Everything in it is high-entropy on purpose. The first attempt used plain `testsrc` and a
smooth `gradient:`, and both compress to nearly nothing — a 1080p clip nominally encoded at
20 Mbit came out at 1.3 MB. At that size the difference between reading a 2 MB prefix and a
targeted 200 KB range is invisible.

| file | what it is for |
|---|---|
| `hd-1..6.mp4` | 1080p, `+faststart`. The common case, and enough of them to see a folder stall. |
| `uhd-1..2.mp4` | 4K, where the native side currently hands back a full-resolution RGBA frame — ~33 MB across JNI per decode. |
| `no-faststart.mp4` | `moov` atom at the end. The case the current code spends its whole budget on and then fails anyway. |
| `embedded-1..2.jpg` | 17 MB photos that *do* carry an EXIF thumbnail, inherited from the committed fixture asset — ImageMagick carries the profile through a re-encode, IFD1 thumbnail and orientation included. |
| `plain-1..2.jpg` | 6 MB photos that do not, so the fallback is measured too. |

The photo pair is what makes #27's claim measurable: the committed fixture photos are
~36 KB, which proves the mechanism but cannot show a bandwidth saving.

## Baseline

Taken on `cdd6703`, before any #28 work, on an `rex-api36` emulator — recorded so a later
run has something to be compared against. Absolute numbers are machine-specific; the
ratios are the point.

```
served         12 ok, 1 without a thumbnail
bytes off SMB  69,712,272 (66 MiB)
read fraction  23.7% of the folder
per thumbnail  5.1 MiB average
wall clock     32s total, concurrency 1
```

Reproducible to within 0.002% on bytes across runs.

Per file, the three things worth noticing:

```
  9.53s  404  no-faststart.mp4     <- 9.5s spent to produce nothing, on every scroll
  2.85s  200  plain-*.jpg          <- no embedded thumbnail: the whole file crosses
  1.85s  200  hd-*.mp4
  0.13s  200  embedded-*.jpg       <- embedded thumbnail: ~20x faster than its plain twin
```

At `--concurrency 4` the wall clock improves only 32s → 28s while every individual request
roughly doubles (`no-faststart` 9.5s → 19.7s), which is the unbounded-concurrency problem
in #28 showing up as a measurement.
