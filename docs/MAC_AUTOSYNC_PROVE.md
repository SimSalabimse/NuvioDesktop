# Mac Auto Sync Prove Harness

Headless automated testing for Mac Auto Sync audio capture (v5 direct tap architecture).

## Requirements

- Test media file at: `test-media/autosync/autosync-fixture.mp4`
- Mac with audio output capability
- Installed Nuvio.app DMG

## Usage

### Quick Test (Default Media)

```bash
cd /path/to/nuvio/workspace
NUVIO_AUTOSYNC_PROVE=1 /Applications/Nuvio.app/Contents/MacOS/Nuvio
```

### Custom Media File

```bash
NUVIO_AUTOSYNC_PROVE=/path/to/video.mp4 /Applications/Nuvio.app/Contents/MacOS/Nuvio
```

### With Console.app Logging

```bash
# Terminal 1: Start prove harness
NUVIO_AUTOSYNC_PROVE=1 /Applications/Nuvio.app/Contents/MacOS/Nuvio

# Terminal 2: Capture console logs
log stream --predicate 'process == "Nuvio"' --style compact > autosync-prove-console.log
```

## Expected Output

### Success (N > 0 samples)

```
========================================
[AutoSyncProve] HEADLESS AUTO SYNC PROVE HARNESS [v5_DIRECT_TAP_PROVE]
[AutoSyncProve] Tip: 3c704252+ (v5 direct tap architecture)
========================================
[AutoSyncProve] Media path: test-media/autosync/autosync-fixture.mp4
[AutoSyncProve] Found media: /path/to/test-media/autosync/autosync-fixture.mp4 (12345678 bytes)
[AutoSyncProve] Initializing native player bridge...
[AutoSyncProve] Creating player instance...
[AutoSyncProve] Player created: handle=12345
[AutoSyncProve] Waiting 2000ms for audio to start...
========================================
[AutoSyncProve] STARTING AUDIO ENERGY CAPTURE [v5_DIRECT_TAP_PROVE]
[AutoSyncProve] Duration: 30000ms (~30s)
========================================
[AutoSyncProve] startAudioEnergyCapture called successfully
[AutoSyncProve] Capturing audio...
[AutoSyncProve] Progress: 5000ms / 30000ms (16%)
[AutoSyncProve] Progress: 10000ms / 30000ms (33%)
[AutoSyncProve] Progress: 15000ms / 30000ms (50%)
[AutoSyncProve] Progress: 20000ms / 30000ms (66%)
[AutoSyncProve] Progress: 25000ms / 30000ms (83%)
[AutoSyncProve] Progress: 30000ms / 30000ms (100%)
========================================
[AutoSyncProve] STOPPING AUDIO ENERGY CAPTURE [v5_DIRECT_TAP_PROVE]
========================================
[AutoSyncProve] Raw result JSON length: 1234 chars
========================================
[AutoSyncProve] RESULTS [v5_DIRECT_TAP_PROVE]
========================================
[AutoSyncProve] Sample count: 291
[AutoSyncProve] Expected: N > 0 (varying energy samples)
[AutoSyncProve] ✅✅✅ SUCCESS - Got 291 non-zero samples
[AutoSyncProve] IOProc received audio data (not empty buffers)
[AutoSyncProve] Process tap is capturing mpv audio output
[AutoSyncProve] Full result JSON:
{"samples":[...],"startTimeMs":123,...}
========================================
[AutoSyncProve] Cleaning up player...
[AutoSyncProve] Test complete, exiting.

Exit code: 0
```

### Failure (0 samples / empty buffers)

```
[AutoSyncProve] ========================================
[AutoSyncProve] RESULTS [v5_DIRECT_TAP_PROVE]
[AutoSyncProve] ========================================
[AutoSyncProve] Sample count: 0
[AutoSyncProve] Expected: N > 0 (varying energy samples)
[AutoSyncProve] ❌❌❌ FAILURE - Got 0 samples (empty IOProc buffers)
[AutoSyncProve] Check Console.app for IOProc logs:
[AutoSyncProve]   - Look for [v5_DIRECT_TAP] and [v5_TAP_READ] markers
[AutoSyncProve]   - Check 'Called but no input data' vs 'Captured audio'
[AutoSyncProve]   - Verify tap format query succeeded

Exit code: 1
```

## Binary Verification (Stale Build Detection)

The harness logs unique markers to prove DMG is built from correct tip:

✅ **Current v5 build** shows:
- `[v5_DIRECT_TAP_PROVE]` in harness output
- `[BUILD_20260925_v5_DIRECT_TAP]` in Console.app
- `NO AGGREGATE DEVICE` in Console.app
- `Skipping aggregate device - reading tap directly`

❌ **Stale build** shows:
- `[BUILD_v4]` or no v5 markers
- `MANDATORY CONFIG SEQUENCE: 1️⃣ 2️⃣ 3️⃣ 4️⃣`
- SubDeviceList/MasterDevice/TapList logs

## Console.app Markers

Expected CoreAudio logs (Console.app → filter "Nuvio"):

```
[Nuvio] CoreAudio: Starting Mac Auto-Sync Audio Capture [BUILD_20260925_v5_DIRECT_TAP]
[Nuvio] CoreAudio: Using Process Tap API (macOS 14.2+) - NO AGGREGATE DEVICE
[Nuvio] CoreAudio: ✅ Process tap created successfully (ID=128) [BUILD_v5_DIRECT_TAP]
[Nuvio] CoreAudio: Query tap format: status=0 [v5_DIRECT_TAP]
[Nuvio] CoreAudio: Tap format: sampleRate=48000 channels=2 ...
[Nuvio] CoreAudio: Skipping aggregate device - reading tap directly as input [v5_DIRECT_TAP]
[Nuvio] CoreAudio: AudioDeviceStart(tap) returned 0 [v5_DIRECT_TAP]
[Nuvio] CoreAudio: ✅✅✅ SUCCESS - Tap started, IOProc should receive audio [BUILD_v5_DIRECT_TAP]
[Nuvio] CoreAudio IOProc: Called #1 (device=128 capturing=1) [v5_DIRECT_TAP]
[Nuvio] CoreAudio IOProc: Input data details #1 - buffers=2 [v5_TAP_READ]
[Nuvio] CoreAudio IOProc: Captured audio #0 - RMS=0.1234 energy=0.3702
```

## Test Media Setup

Create test fixture at workspace root:

```bash
mkdir -p test-media/autosync
# Copy any video file with audio track
cp /path/to/video-with-audio.mp4 test-media/autosync/autosync-fixture.mp4
```

Fixture requirements:
- Must have audio track
- Playable by mpv
- Duration: 30+ seconds recommended
- Any codec (mpv auto-decodes)

## Automation Integration

Mac specialist automation script:

```bash
#!/bin/bash
set -e

DMG_PATH="$1"
WORKSPACE="/path/to/nuvio/workspace"

echo "Installing DMG..."
hdiutil attach "$DMG_PATH"
cp -R "/Volumes/Nuvio/Nuvio.app" /Applications/
hdiutil detach "/Volumes/Nuvio"

echo "Running Auto Sync prove harness..."
cd "$WORKSPACE"
NUVIO_AUTOSYNC_PROVE=1 /Applications/Nuvio.app/Contents/MacOS/Nuvio 2>&1 | tee autosync-prove-output.log

EXIT_CODE=$?
echo "Exit code: $EXIT_CODE"

if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ PROVE PASSED"
else
    echo "❌ PROVE FAILED"
    echo "Capturing Console logs..."
    log show --predicate 'process == "Nuvio"' --last 5m > autosync-prove-console.log
fi

exit $EXIT_CODE
```

## Troubleshooting

### "Media file not found"
- Ensure `test-media/autosync/autosync-fixture.mp4` exists
- Check current directory: harness searches from `pwd`
- Use absolute path: `NUVIO_AUTOSYNC_PROVE=/full/path/to/video.mp4`

### "Failed to load native player bridge"
- Verify DMG is installed correctly
- Check Nuvio.app structure
- Run from correct MacOS executable path

### 0 samples but no errors
- Check Console.app for "Called but no input data" logs
- Verify [v5_DIRECT_TAP] markers appear (proves correct build)
- Confirm audio is playing (check system volume)
- Investigate mpv audio routing vs process tap capture

## Architecture Notes

- **v5 approach**: Direct process tap read (no aggregate device)
- **Process tap**: Captures audio OUTPUT from Nuvio process
- **IOProc**: Reads tap as INPUT device (captured audio buffers)
- **No GUI**: Harness creates headless player, no Compose UI
- **Exit codes**: 0 = success (N>0 samples), 1 = failure (N=0 samples)
