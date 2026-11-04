#!/usr/bin/env bash
# Prove analyzeStreamWindow against a local ranged HTTP server.
# One command, on a Mac with Xcode and ffmpeg:
#   ./scripts/macos-stream-window-spike.sh
# This host cannot compile the macOS bridge. The script exits 2 anywhere but Darwin.

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "stream-window spike: this script links the bundled macOS libav*.dylib with xcrun." >&2
  echo "This machine is $(uname -s). The native file was not compiled here." >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "$(uname -m)" in
  arm64|aarch64) ARCH=arm64 ;;
  x86_64) ARCH=x86_64 ;;
  *)
    echo "unsupported architecture $(uname -m)" >&2
    exit 1
    ;;
esac
RUNTIME="$ROOT/composeApp/src/desktopMain/native/macos/runtime/$ARCH"
SOURCE="$ROOT/composeApp/src/desktopMain/native/macos/stream_window_spike.mm"
LIB="$RUNTIME/libavformat.61.dylib"

if [[ ! -f "$LIB" ]]; then
  echo "missing $LIB" >&2
  exit 1
fi
if head -n 1 "$LIB" | grep -q "git-lfs.github.com"; then
  echo "FFmpeg dylibs are Git LFS pointers. From the repo root run:" >&2
  echo "  git lfs pull --include='composeApp/src/desktopMain/native/macos/runtime/**'" >&2
  exit 1
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg is required to generate the local test file (system ffmpeg, not a new project dependency)." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to serve the test file." >&2
  exit 1
fi
if ! xcrun --find clang++ >/dev/null 2>&1; then
  echo "xcrun clang++ was not found. Install Xcode command line tools." >&2
  exit 1
fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-stream-window.XXXXXX")"
SERVERS=()
cleanup() {
  local pid
  for pid in "${SERVERS[@]+"${SERVERS[@]}"}"; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  rm -rf "$TMP"
}
trap cleanup EXIT

cat >"$TMP/range_server.py" <<'PY'
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

media_path, port_text, mode, stats_path = sys.argv[1:5]
payload = open(media_path, "rb").read()
stats = {"bytes": 0, "ranges": [], "requests": 0}

def flush():
    tmp = stats_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(stats, handle)
    os.replace(tmp, stats_path)

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        return

    def range_start(self):
        header = self.headers.get("Range")
        if not header or not header.startswith("bytes="):
            return None
        spec = header.split("=", 1)[1].split(",", 1)[0].strip()
        left, _, right = spec.partition("-")
        if left == "":
            return max(0, len(payload) - int(right or "0"))
        return int(left)

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Accept-Ranges", "none" if mode == "norange" else "bytes")
        self.end_headers()

    def do_GET(self):
        stats["requests"] += 1
        start = self.range_start()
        if mode == "norange" and start not in (None, 0):
            body = b""
            self.send_response(416)
            self.send_header("Content-Range", "bytes */%d" % len(payload))
            self.send_header("Content-Length", "0")
            self.end_headers()
            stats["ranges"].append([start, -1])
            flush()
            return
        if start is None or mode == "norange":
            body = payload
            status = 200
            begin, end = 0, len(payload) - 1
        else:
            end = len(payload) - 1
            begin = min(start, end)
            body = payload[begin:end + 1]
            status = 206
            stats["ranges"].append([begin, end])
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Accept-Ranges", "bytes" if mode == "range" else "none")
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (begin, end, len(payload)))
        self.end_headers()
        self.wfile.write(body)
        stats["bytes"] += len(body)
        flush()

flush()
ThreadingHTTPServer(("127.0.0.1", int(port_text)), Handler).serve_forever()
PY

start_server() {
  local mode="$1"
  local stats="$2"
  local port
  port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
  python3 "$TMP/range_server.py" "$MEDIA" "$port" "$mode" "$stats" >/dev/null 2>&1 &
  SERVERS+=("$!")
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    if [[ -f "$stats" ]]; then
      echo "$port"
      return 0
    fi
    sleep 0.1
  done
  echo "local server did not start" >&2
  return 1
}

echo "generating a local video+audio file"
if ! ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "testsrc=size=32x32:rate=8:duration=60" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=60" \
  -c:v mpeg4 -q:v 15 \
  -c:a pcm_s16le -ac 1 \
  -cues_to_front 1 \
  -f matroska \
  "$TMP/window.mkv"; then
  echo "ffmpeg could not write the test file (needs lavfi testsrc/sine, mpeg4, pcm_s16le, matroska)." >&2
  exit 1
fi
MEDIA="$TMP/window.mkv"
SIZE="$(wc -c <"$MEDIA" | tr -d ' ')"
if [[ "$SIZE" -lt 4000000 ]]; then
  echo "test file is only $SIZE bytes; expected at least 4 MB so a short window cannot be the whole file." >&2
  exit 1
fi

echo "compiling the spike CLI against the bundled FFmpeg dylibs"
xcrun clang++ -std=c++17 -O2 -ObjC++ \
  -DSTREAM_WINDOW_SPIKE_CLI -DNUVIO_STREAM_WINDOW_SPIKE=1 \
  -mmacosx-version-min=12.0 \
  "$SOURCE" \
  -o "$TMP/stream_window_spike" \
  -Wl,-rpath,"$RUNTIME" \
  "$RUNTIME/libavformat.61.dylib" \
  "$RUNTIME/libavcodec.61.dylib" \
  "$RUNTIME/libavfilter.10.dylib" \
  "$RUNTIME/libavutil.59.dylib" \
  -lc++

export DYLD_LIBRARY_PATH="$RUNTIME${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"

TOKEN="spike-secret-token"
RANGE_STATS="$TMP/range-stats.json"
RANGE_PORT="$(start_server range "$RANGE_STATS")"
URL="http://127.0.0.1:${RANGE_PORT}/window.mkv?${TOKEN}=1"

run_window() {
  local start="$1"
  local duration="$2"
  local out="$3"
  "$TMP/stream_window_spike" "$URL" "$start" "$duration" >"$out"
  python3 - "$out" "$TOKEN" <<'PY'
import json, sys
path, token = sys.argv[1:3]
raw = open(path, encoding="utf-8").read()
if token in raw or "http" in raw or "window.mkv" in raw:
    sys.exit("CLI output included the URL, path, or token")
doc = json.loads(raw)
fields = ["sampleRate", "channels", "frames", "bytesTransferred", "wallMillis", "seekable", "durationSec", "error"]
missing = [name for name in fields if name not in doc]
if missing:
    sys.exit("missing fields: " + ",".join(missing))
print(
    "sampleRate={sampleRate} channels={channels} frames={frames} bytesTransferred={bytesTransferred} "
    "wallMillis={wallMillis} seekable={seekable} durationSec={durationSec} error={error}".format(**doc)
)
PY
}

echo "head window"
run_window 0 2 "$TMP/head.json"
echo "tail window"
DURATION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["durationSec"])' "$TMP/head.json")"
python3 - "$DURATION" "$TMP/tail-args" <<'PY'
import sys
duration = float(sys.argv[1])
if duration < 20:
    sys.exit("container duration was not reported")
tail = min(2.0, duration)
start = max(0.0, duration - 2.0)
open(sys.argv[2], "w", encoding="utf-8").write("%s %s\n" % (start, tail))
PY
read -r TAIL_START TAIL_DURATION <"$TMP/tail-args"
run_window "$TAIL_START" "$TAIL_DURATION" "$TMP/tail.json"

python3 - "$TMP/head.json" "$TMP/tail.json" "$RANGE_STATS" "$SIZE" <<'PY'
import json, sys
head, tail, stats, size = sys.argv[1:5]
head = json.load(open(head))
tail = json.load(open(tail))
stats = json.load(open(stats))
size = int(size)
errors = []
if head.get("error") not in (None,):
    errors.append("head error %s" % head.get("error"))
if tail.get("error") not in (None,):
    errors.append("tail error %s" % tail.get("error"))
for name, doc in (("head", head), ("tail", tail)):
    if doc.get("sampleRate") != 8000 or doc.get("channels") != 1:
        errors.append("%s did not downmix to 8000 Hz mono" % name)
    frames = doc.get("frames") or 0
    if not (8000 <= frames <= 20000):
        errors.append("%s frames %s outside 8000..20000" % (name, frames))
    if (doc.get("bytesTransferred") or 0) <= 0:
        errors.append("%s transferred no bytes" % name)
    if not doc.get("seekable"):
        errors.append("%s was not seekable" % name)
if stats.get("bytes", 0) >= int(size * 0.9):
    errors.append("server sent %s bytes for a %s byte file; seek did not limit the read" % (stats.get("bytes"), size))
ranges = [item for item in stats.get("ranges", []) if item and item[0] > size * 0.4]
if not ranges:
    errors.append("no HTTP range started past 40 percent of the file")
if errors:
    sys.exit(" ; ".join(errors))
print("range server bytes=%s requests=%s high_ranges=%s file_bytes=%s" % (
    stats.get("bytes"), stats.get("requests"), len(ranges), size))
PY

echo "connection refused"
"$TMP/stream_window_spike" "http://127.0.0.1:1/none" 0 2 >"$TMP/refused.json"
python3 - "$TMP/refused.json" <<'PY'
import json, sys
raw = open(sys.argv[1], encoding="utf-8").read()
if "127.0.0.1" in raw or "http" in raw:
    sys.exit("refused result included a URL")
doc = json.loads(raw)
if doc.get("error") != "connection_refused":
    sys.exit("expected connection_refused, got %s" % doc.get("error"))
print("connection_refused frames=%s" % doc.get("frames"))
PY

echo "range rejected"
NORANGE_STATS="$TMP/norange-stats.json"
NORANGE_PORT="$(start_server norange "$NORANGE_STATS")"
"$TMP/stream_window_spike" "http://127.0.0.1:${NORANGE_PORT}/window.mkv" 20 2 >"$TMP/norange.json"
python3 - "$TMP/norange.json" <<'PY'
import json, sys
raw = open(sys.argv[1], encoding="utf-8").read()
if "window.mkv" in raw or "http" in raw:
    sys.exit("unseekable result included a URL")
doc = json.loads(raw)
allowed = {"not_seekable", "no_range", "seek_failed", "moov_or_no_range"}
if doc.get("error") not in allowed:
    sys.exit("expected a seek failure, got %s" % doc.get("error"))
print("unseekable error=%s bytesTransferred=%s" % (doc.get("error"), doc.get("bytesTransferred")))
PY

echo "spike ok"
