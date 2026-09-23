# Desktop Auto Sync Audio Capture Roadmap

## Current Status: STUB (Graceful Fallback)

The Auto Sync button is fully wired in the UI and the SubtitleAutoSyncEngine algorithm is ready, but desktop returns empty audio samples (shows "could not capture audio" message).

## Why Native Work Is Required

Desktop uses **libmpv** for playback via JNI bridges in:
- `composeApp/src/desktopMain/native/windows/player_bridge.cpp` (2595 lines)
- `composeApp/src/desktopMain/native/linux/player_bridge.cpp` (2031 lines)
- macOS (similar structure)

Currently, the bridge:
- ✅ Exposes MPV properties (playback position, tracks, subtitles)
- ✅ Handles playback control (play, pause, seek)
- ✅ Manages WebView2/WebKitGTK controls overlay
- ❌ **No audio pipeline tap or real-time sample access**

## What's Needed for Real Audio Capture

### 1. MPV Audio Filter Hook
MPV supports audio filters via `--af` option or runtime property changes. We need:
```cpp
// Set up audio lavfi filter to tap samples
mpv_set_property_string(mpv, "af", "lavfi=[aresample=48000,asplit[ao][tap];[tap]volume=1.0]");
```

**Challenge**: Requires understanding MPV's audio filter graph and how to extract samples without affecting playback.

### 2. Audio Sample Collection
Need a native circular buffer to store RMS energy samples:
```cpp
struct AudioEnergySample {
    int64_t timestamp_ms;
    double rms_energy;
};

class AudioCaptureBuffer {
    std::mutex mutex;
    std::vector<AudioEnergySample> samples;
    int64_t capture_start_ms = 0;
    bool is_capturing = false;
    
    // Thread-safe add/retrieve
    void addSample(int64_t ts, double energy);
    std::vector<AudioEnergySample> getSamples();
};
```

### 3. RMS Energy Computation
Process PCM audio in native code:
```cpp
double computeRmsEnergy(const float* pcm_samples, size_t count) {
    double sum_squares = 0.0;
    for (size_t i = 0; i < count; ++i) {
        sum_squares += pcm_samples[i] * pcm_samples[i];
    }
    return std::sqrt(sum_squares / count);
}
```

### 4. New JNI Methods
Add to `NativePlayerBridge.kt` and implement in C++:
```kotlin
external fun startAudioEnergyCapture(handle: Long, startTimeMs: Long)
external fun stopAudioEnergyCapture(handle: Long): String  // JSON array
external fun getAudioCaptureDuration(handle: Long): Long
```

C++ signatures:
```cpp
JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_startAudioEnergyCapture(
    JNIEnv* env, jobject, jlong handle, jlong startTimeMs);

JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_stopAudioEnergyCapture(
    JNIEnv* env, jobject, jlong handle);

JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_getAudioCaptureDuration(
    JNIEnv* env, jobject, jlong handle);
```

### 5. Platform-Specific Considerations

**Windows (WDM/WASAPI):**
- MPV typically uses WASAPI for audio output
- May need custom audio sink to tap samples

**Linux (ALSA/PulseAudio):**
- MPV uses ALSA or PulseAudio
- Similar tap requirements

**macOS (CoreAudio):**
- MPV uses CoreAudio
- May require AVAudioEngine integration

## Alternative Approaches Considered

### Option A: MPV Property Observation
MPV exposes `audio-pts` but not raw PCM samples. Cannot compute RMS without samples.

### Option B: System Audio Capture
Could use platform APIs (WASAPI loopback on Windows, etc.) but:
- Captures ALL system audio (not just MPV)
- Requires additional permissions
- More complex than MPV-internal tap

### Option C: Lavfi Custom Filter
Most viable: implement custom lavfi filter that:
1. Receives audio frames from MPV pipeline
2. Computes RMS in fixed windows (100ms)
3. Stores samples in bridge-accessible buffer
4. Continues normal audio output

## Estimated Work

**Complexity:** High  
**Lines of C++ code:** ~500-800 (per platform)  
**Testing required:** All 3 desktop platforms  
**Timeline:** Multiple days for a native developer familiar with:
- MPV internals
- Audio DSP (RMS computation)
- JNI
- Cross-platform audio APIs

## Current Implementation

**Status:** Graceful stub  
**Behavior:** 
- Auto Sync button is visible and wired
- Pressing it triggers the capture flow
- `getAudioCaptureDuration()` returns 0
- `stopAudioEnergyCapture()` returns empty list
- UI shows: "Could not capture audio data. Auto-sync may not be available on this platform."

**No crashes, no fake success** - just honest "not available" messaging.

## Recommendation

1. **Keep graceful stub** for this PR (Enhanced branch)
2. **File separate issue** for native desktop audio capture implementation
3. **Mobile Enhanced reference:** Android already has `AudioEnergyCaptureProcessor` that taps ExoPlayer's audio pipeline - can be used as algorithm reference once MPV tap is available

## References

- MPV client.h: `composeApp/src/desktopMain/native/macos/include/mpv/client.h`
- Android audio capture: Mobile Enhanced PR #2 (already ported algorithm to Desktop)
- SubtitleAutoSyncEngine: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAutoSyncEngine.kt`
