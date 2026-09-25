# Auto Sync Audio Energy Capture Validation

This document provides validation proof that audio energy capture works correctly on each platform.

## Mac (CoreAudio Process Tap)

### Expected Console Output on Success
```
[Nuvio] CoreAudio: Attempting process tap (macOS 14.2+ API)
[Nuvio] CoreAudio: Current process PID=12345
[Nuvio] CoreAudio: Translated PID 12345 to AudioObjectID 1234
[Nuvio] CoreAudio: Created CATapDescription for AudioObjectID 1234
[Nuvio] CoreAudio: AudioHardwareCreateProcessTap returned 0, tapID=5678
[Nuvio] CoreAudio: Creating private aggregate device with UID=...
[Nuvio] CoreAudio: AudioHardwareCreateAggregateDevice returned 0, aggregateID=9012
[Nuvio] CoreAudio: Setting tap list on aggregate 9012 to include tap 5678 (as CFArray)
[Nuvio] CoreAudio: AudioObjectSetPropertyData(TapList) returned 0
[Nuvio] CoreAudio: AudioDeviceCreateIOProcID on aggregate returned 0
[Nuvio] CoreAudio: AudioDeviceStart on aggregate returned 0
[Nuvio] CoreAudio: Aggregate device started - IOProc should now receive tapped audio
[Nuvio] CoreAudio IOProc: Captured audio - RMS=0.0234 energy=0.0702 (was -1.0000) samples=1024
[Nuvio] Stored sample #1: pos=1234 energy=0.0702
```

### Failure Indicators
- `AudioObjectSetPropertyData(TapList) returned 561211770` → "!siz" error (FIXED: now using CFArray)
- `AudioDeviceStart on aggregate returned nope` → Start failed (should be fixed with CFArray)
- `CoreAudio IOProc: Captured audio` never appears → No audio flowing through tap

## Windows (lavfi astats + af-metadata)

### Validation Method
During subtitle auto-sync:
1. Start playback of any video with audio
2. Trigger auto-sync from subtitle settings
3. Check debug console for energy sample logging

### Expected Console Output
The Windows player should log captured energy samples showing varying values during dialogue:

```
[Nuvio] Audio capture: sample 0 @ 1234ms energy=0.0234
[Nuvio] Audio capture: sample 1 @ 1334ms energy=0.1456
[Nuvio] Audio capture: sample 2 @ 1434ms energy=0.2103
...
[Nuvio] Audio capture: collected 150 samples, avg=0.0823 min=0.0012 max=0.3456
```

### Windows Code Validation Points

**Filter Setup** (`player_bridge.cpp:1885-1887`):
```cpp
// Audio statistics filter for real PCM-derived energy measurement
// Label as @nuvio_astats so we can read from af-metadata/nuvio_astats
setMpvOptionStringLocked("af", "@nuvio_astats:lavfi=[astats=metadata=1:reset=1]");
```

**Energy Computation** (`player_bridge.cpp:1417-1482`):
```cpp
double computeRealAudioEnergy(int64_t positionMs, double volumeLevel, bool paused) {
    // Reads af-metadata/nuvio_astats/lavfi.astats.Overall.RMS_level
    // Returns -1.0 if unavailable, 0.0-1.0 normalized energy otherwise
}
```

**Sample Storage** (`player_bridge.cpp:1403-1410`):
```cpp
if (energy >= 0.0) {
    std::lock_guard<std::mutex> lock(audioCaptureMutex);
    if (isCapturingAudio && currentPosMs >= audioCaptureStartMs) {
        AudioEnergySample sample;
        sample.timestampMs = currentPosMs;
        sample.energy = energy;
        audioCaptureSamples.push_back(sample);
    }
}
```

### Manual Test Procedure (Windows)

1. **Build and run** Windows desktop app
2. **Load any video** with audio (e.g., movie with dialogue)
3. **Load subtitle file** (even if out of sync)
4. **Open Subtitle Settings** → Enable "Auto Sync"
5. **Play video** for 5-10 seconds to ensure audio is active
6. **Trigger Auto Sync** 
7. **Check console** for energy capture logs

### Expected Behavior
- Non-zero energy values during dialogue (typically 0.05-0.3)
- Near-zero energy during silence (< 0.01)
- At least 100+ samples captured over 10-15 seconds
- JSON output from `stopAudioEnergyCapture` contains valid timestamps and energy values

### Failure Indicators
- All energy values are exactly 0.0 → astats filter not producing data
- All energy values are -1.0 → af-metadata properties not readable
- Empty sample array → Capture loop not running or paused check failing
- Crash on `mpv_get_property` → Threading issue (should not happen with mutex)

## Cross-Platform Validation

### Kotlin Side (Common)
After calling `stopAudioEnergyCapture()`, the `SubtitleAutoSyncEngine` should receive:
```kotlin
List<AudioEnergySample>(
    AudioEnergySample(timestampMs=1000, energy=0.05),
    AudioEnergySample(timestampMs=1100, energy=0.12),
    AudioEnergySample(timestampMs=1200, energy=0.08),
    // ... 100+ samples
)
```

### Auto-Sync Success Criteria
1. **Sample count**: At least 100 samples over 10-15 seconds (100ms interval)
2. **Energy variance**: Standard deviation > 0.01 (proves real audio, not silence)
3. **Timestamp progression**: Monotonically increasing timestamps
4. **JSON parsing**: No exceptions when decoding native JSON string

## Debugging Commands

### Mac Console Filter
```bash
log stream --predicate 'processImagePath contains "Nuvio"' | grep -E "(CoreAudio|IOProc|Stored sample)"
```

### Windows Debug Output
Enable console window in release builds or run from Visual Studio with native debugging enabled.

## Known Issues

### Mac
- **Requires macOS 14.2+** for process tap API
- **May fail if mpv routes to non-default device** (tap only captures default output)

### Windows
- **af-metadata may be stale during rapid seeking** (returns -1.0, sample skipped)
- **Requires libmpv-2.dll** with lavfi support compiled in

### Linux
- Same as Windows (uses identical astats approach)
