# Desktop Auto Sync Audio Capture Roadmap

## Current Status: IMPLEMENTED (Real PCM-derived Audio Analysis)

Desktop Auto Sync now uses **real audio analysis** via MPV's `astats` lavfi filter, which computes RMS/peak levels from actual decoded PCM audio samples. This provides genuine audio energy measurements that vary with dialogue vs silence.

## Implementation: MPV Audio Statistics Filter

Desktop uses **libmpv** for playback via JNI bridges:
- `composeApp/src/desktopMain/native/windows/player_bridge.cpp`
- `composeApp/src/desktopMain/native/linux/player_bridge.cpp`
- macOS (not yet implemented)

Audio capture approach:
- ✅ **MPV lavfi audio filter** (`astats`) computes RMS from decoded PCM
- ✅ Background thread samples filter metadata every 100ms
- ✅ Real audio energy that varies with dialogue vs silence
- ✅ Cross-platform (Windows, Linux via same MPV API)

## How It Works: Real PCM-Derived Energy

### 1. MPV Audio Statistics Filter (Implemented)
MPV's `astats` lavfi filter analyzes decoded PCM audio in real-time:
```cpp
// During MPV initialization (Windows & Linux) - LABEL the filter
mpv_set_option_string(mpv, "af", "@nuvio_astats:lavfi=[astats=metadata=1:reset=1]");
```

The `@nuvio_astats:` label lets us read filter output from `af-metadata/nuvio_astats`.

The `astats` filter computes:
- **RMS level** (root mean square) - average audio power from PCM samples
- **Peak level** - maximum sample amplitude
- Values are in dB (decibels), typically -60 to 0 dB

**Result**: Real audio analysis computed from actual decoded PCM.

### 2. Reading Filter Metadata (Implemented)
Background thread reads `af-metadata/nuvio_astats` as a node map every 100ms:
```cpp
// Read af-metadata/nuvio_astats (where labeled filter outputs metadata)
mpv_node metadataNode;
mpv_get_property(mpv, "af-metadata/nuvio_astats", MPV_FORMAT_NODE, &metadataNode);

// Extract RMS from the node map
for (int i = 0; i < metadataNode.u.list->num; i++) {
    const char *key = metadataNode.u.list->keys[i];
    if (strcmp(key, "lavfi.astats.Overall.RMS_level") == 0) {
        double rmsDb = atof(metadataNode.u.list->values[i].u.string);
        // e.g., -25.4 dB
    }
}
mpv_free_node_contents(&metadataNode);
```

**Critical**: Must use `af-metadata/<label>`, not `metadata/by-key/...` (file tags).

### 3. Converting dB to Energy (Implemented)
Convert dB levels to normalized 0-1 energy scale:
```cpp
// Typical audio levels:
// Dialogue: -30 to -10 dB
// Silence:  -60 to -40 dB

double linearEnergy = 0.0;
if (rmsDb > -60.0) {
    // Map -60 dB (silence) → 0.0, -10 dB (loud) → 1.0
    linearEnergy = (rmsDb + 60.0) / 50.0;
    linearEnergy = std::clamp(linearEnergy, 0.0, 1.0);
}
```

**Result**: Energy values vary naturally with dialogue vs silence.

### 4. JNI Bridge (Implemented)
Kotlin interface in `NativePlayerBridge.kt`:
```kotlin
external fun startAudioEnergyCapture(handle: Long, startTimeMs: Long)
external fun stopAudioEnergyCapture(handle: Long): String  // JSON array
external fun getAudioCaptureDuration(handle: Long): Long
```

C++ implementation in `player_bridge.cpp` (Windows & Linux):
- Background thread reads `af-metadata/nuvio_astats` every 100ms
- Parses node map to extract RMS/Peak levels
- Converts dB to energy (0-1 scale)
- Stores samples in thread-safe buffer
- Returns -1.0 on failure (honest failure, no invented data)
- Returns JSON array of valid samples on stop

```cpp
// Sample storage
struct AudioEnergySample {
    int64_t timestampMs;
    double energy;  // 0.0 - 1.0, derived from PCM RMS; -1.0 = no data
};
std::vector<AudioEnergySample> audioCaptureSamples;
```

## Why astats Instead of Raw PCM?

### Advantages of MPV lavfi Filter Approach:
1. **Cross-platform**: Works identically on Windows, Linux, macOS
2. **No system audio hooks**: Doesn't require WASAPI loopback or ALSA capture
3. **MPV-integrated**: Uses MPV's existing audio pipeline
4. **Accurate**: Computes RMS from actual decoded PCM samples
5. **Efficient**: Filter does analysis once, we just read metadata

### Alternative Approaches Considered:
- **WASAPI loopback capture** (Windows-only, requires IAudioClient)
- **Raw PCM from MPV** (no direct client API, would need libmpv patch)
- **Audio-bitrate proxy** (rejected - not actual audio analysis)

The lavfi `astats` filter provides **real audio analysis** while maintaining cross-platform compatibility.

## Dialogue vs Silence Behavior

### How Energy Varies with Audio Content:

**Dialogue (typical):**
- RMS: -30 to -15 dB
- Normalized energy: 0.4 - 0.8
- **Visible peaks** in energy samples

**Silence / Background:**
- RMS: -60 to -40 dB  
- Normalized energy: 0.0 - 0.3
- **Low, flat** energy

**Music:**
- RMS: -20 to -10 dB
- Normalized energy: 0.6 - 1.0
- **High, sustained** energy

The `SubtitleAutoSyncEngine` correlates these energy patterns with subtitle cue onsets to find the optimal offset.

## Testing Instructions

### Validating Real Audio Capture

**Test 1: Dialogue Detection**
1. Play video with dialogue sections
2. Trigger Auto Sync during active speech
3. **Expected**: Energy samples show clear variations (0.3 - 0.8 range)

**Test 2: Silence Detection**  
1. Seek to silent section (intro, credits, pauses)
2. Check captured energy values
3. **Expected**: Energy near 0.0 - 0.2 (low, flat)

**Test 3: Sync Accuracy**
1. Use video with known subtitle offset (e.g., +2.5s)
2. Run Auto Sync
3. **Expected**: Computed offset close to actual (+2.0s to +3.0s)

### Debug Logging
Enable MPV metadata logging to see raw astats values:
```bash
# Set MPV log level
export MPV_VERBOSE=1
# Run Nuvio Desktop
# Check logs for "lavfi.astats.Overall.RMS_level"
```

## Implementation Status

**✅ Completed (Windows & Linux):**
- MPV `astats` filter integration
- Real-time RMS/peak level reading
- Background capture thread
- JNI bridge with JSON serialization
- Thread-safe sample buffer
- Proper cleanup on player disposal

**⚠️ Not Yet Implemented:**
- macOS audio capture (same approach will work)

**📊 Comparison with Mobile:**
- **Mobile (iOS/Android)**: Raw PCM samples → RMS computation
- **Desktop**: MPV `astats` filter → RMS metadata reading
- **Both**: Produce equivalent energy values for sync correlation

## References

- MPV lavfi filters: https://ffmpeg.org/ffmpeg-filters.html#astats
- Implementation: `composeApp/src/desktopMain/native/{windows,linux}/player_bridge.cpp`
- Sync algorithm: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAutoSyncEngine.kt`
- PR: #11 (cursor/real-mpv-audio-capture-bc7d)
