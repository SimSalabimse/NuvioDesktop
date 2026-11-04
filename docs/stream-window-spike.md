# Stream window spike (macOS, debug only)

This is a measurement spike. It checks whether a second HTTP connection can read
just the head and tail audio of a remote file with the FFmpeg libraries already
shipped next to libmpv (`libavformat.61`, `libavcodec.61`, `libavfilter.10`,
`libavutil.59`). It does not change playback unless a debug switch is on, and
release packaging never compiles the fetch.

The reader opens the URL through its own AVIO callback, seeks, decodes the audio
stream only, drops video packets without decoding them, and downmixes to mono
float32 at **8000 Hz**. That rate is the Nyquist match for analysis v2's
300–2000 Hz bands. The PCM itself is discarded. What is kept is the frame count,
the payload bytes pulled through the read callback, and the wall time.

`bytesTransferred` is response body bytes returned by the inner read. HTTP
headers are not included, and a small protocol readahead buffer can remain
uncounted when the connection closes. Probe reads (capped at 2 MB and 3 seconds)
are included, because opening the file costs those bytes too.

## What a Mac run shows

One command, from the repo root, with Xcode command line tools, Git LFS, and a
system `ffmpeg` on `PATH`:

```bash
git lfs pull --include='composeApp/src/desktopMain/native/macos/runtime/**'
./scripts/macos-stream-window-spike.sh
```

The script generates a local MKV, serves it on `127.0.0.1` with HTTP ranges,
compiles `stream_window_spike.mm` into a temporary CLI, and runs three cases:
a 2 second head, a 2 second tail, a refused port, and a server that rejects
ranges. It prints one summary line per case. Read:

- `frames` — about 16000 for a 2 second window (8000 Hz).
- `bytesTransferred` — body bytes for that open, seek, and decode.
- `wallMillis` — time spent inside the call.
- `seekable` — `1` when the server advertised a normal byte seek.
- `error` — `null` on success. Failures use a short code (`connection_refused`,
  `not_seekable`, `no_range`, `seek_failed`, `moov_or_no_range`, `hls_or_nofile`,
  `invalid_data`, `no_audio`, `timeout`, `http_forbidden`, `http_not_found`)
  instead of crashing.
- `range server bytes` — must stay below the whole file, with at least one
  `Range` request past the first 40 percent. That is the tail seek.

The script does not print the URL. A query token used as a leak check must not
appear in the CLI output.

## In the app

The fetch is off unless both of these are true:

1. The process is a non-release Mac build configured with
   `NUVIO_STREAM_WINDOW_SPIKE=1` (or `-Pnuvio.streamWindowSpike=1`). DMG, package,
   notarize, and release tasks ignore that switch, and the binary then reports
   `spike_not_in_build` without opening the URL.
2. The same variable is set in the environment when the app starts.

```bash
git lfs pull --include='composeApp/src/desktopMain/native/macos/runtime/**'
NUVIO_STREAM_WINDOW_SPIKE=1 \
  NUVIO_STREAM_WINDOW_SPIKE_URL='http://127.0.0.1:8765/window.mkv' \
  ./gradlew :composeApp:run
```

An optional argument `--stream-window-spike-url=` is accepted only when the
environment variable is already set. With no URL of its own, the same switch
uses the stream that just started playing.

Head is the first 600 seconds. Tail is the last 480 seconds, using the duration
from the head open. The work runs on a background thread at low priority and
aborts when playback seeks, the player is stopped, or the process exits. It
does not open a second window after a hard connection failure.

Each window appends one JSON line to:

`~/Library/Application Support/Nuvio/logs/stream-window-spike.log`

Override the path with `NUVIO_STREAM_WINDOW_SPIKE_LOG`. Fields are `window`,
`startSec`, `durationSec`, `frames`, `bytesTransferred`, `wallMillis`,
`seekable`, `error`, and `host`. `host` is an 8 hex character SHA-256 prefix of
the hostname, or `local` / `unknown`. The URL, query string, tokens, and headers
are not written. Nothing is shown in the UI.

The spike sends no extra request headers. A URL that only works with header
authentication fails with an HTTP error code. That result is useful; do not put
the URL or the headers into the log to debug it.

## This VM

The cloud VM is Linux. It has no `xcrun` and the macOS dylibs are Git LFS
pointers, so the native file was not compiled here. Desktop CI does not compile
the macOS bridge on pull requests (`desktop-release` is a manual macOS
workflow). The numbers above are what a Mac run of the script is for.
