# Windows Audio Capture Verification
**PR #11 Branch:** `cursor/real-mpv-audio-capture-bc7d`  
**Current Tip:** `74302ec9` (verified 2026-09-25)

## VERIFIED: Windows Implementation Intact

### Code Path Verification

#### 1. MPV astats Filter Initialization ✓
**Location:** `composeApp/src/desktopMain/native/windows/player_bridge.cpp:1887`

```cpp
// Audio statistics filter for real PCM-derived energy measurement
// Label as @nuvio_astats so we can read from af-metadata/nuvio_astats
setMpvOptionStringLocked("af", "@nuvio_astats:lavfi=[astats=metadata=1:reset=1]");
```

**Status:** Present and configured correctly during MPV initialization.

#### 2. Audio Capture Entry Points ✓
**Locations:** Lines 1319-1343 (start), 1345-1367 (stop), 1369-1379 (duration)

```cpp
void startAudioEnergyCapture(int64_t startTimeMs)
std::string stopAudioEnergyCapture()
int64_t getAudioCaptureDuration()
```

**Status:** All three JNI methods present with correct signatures.

#### 3. Capture Loop Implementation ✓
**Location:** Lines 1381-1414

```cpp
void runAudioCaptureLoop() {
    while (isCapturingAudio) {
        double energy = computeRealAudioEnergy(positionMs, volume, paused);
        if (energy >= 0.0) {
            audioCaptureSamples.push_back({timestampMs, energy});
        }
        sleep(100ms);
    }
}
```

**Status:** Background thread samples every 100ms, stores valid energy samples.

#### 4. Real PCM Energy Computation ✓
**Location:** Lines 1417-1482

```cpp
double computeRealAudioEnergy(int64_t positionMs, double volumeLevel, bool paused) {
    // Read af-metadata string properties directly (no mpv_node needed)
    char *rmsStr = nullptr;
    int rmsResult = mpvApi().getProperty(
        mpv, 
        "af-metadata/nuvio_astats/lavfi.astats.Overall.RMS_level",
        MPV_FORMAT_STRING, 
        &rmsStr
    );
    
    if (rmsResult >= 0 && rmsStr) {
        double rmsDb = std::atof(rmsStr);
        mpvApi().freeValue(rmsStr);
        
        // Convert dB to linear energy (0-1 range)
        // -60 dB = 0.0, -10 dB = 1.0
        double linearEnergy = (rmsDb + 60.0) / 50.0;
        return clamp(linearEnergy, 0.0, 1.0);
    }
    
    return -1.0; // Honest failure if metadata unavailable
}
```

**Status:** 
- Reads `af-metadata/nuvio_astats/lavfi.astats.Overall.RMS_level` as string
- Falls back to Peak_level if RMS unavailable
- Converts dB (-60 to -10) to normalized energy (0.0 to 1.0)
- Returns -1.0 on failure (honest, not fake zeros)

#### 5. JNI Exports ✓
**Location:** Lines 2821-2829

```cpp
JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_startAudioEnergyCapture(
    JNIEnv *env, jobject thiz, jlong handle, jlong startTimeMs) {
    auto player = reinterpret_cast<WindowsMpvWebPlayer *>(handle);
    if (player) player->startAudioEnergyCapture(startTimeMs);
}
```

**Status:** JNI bridge methods present for all three audio capture functions.

---

## Manual Test Checklist (Windows MSI)

### Prerequisites
- Windows desktop with audio output
- Test video with dialogue and silence sections
- External subtitle file (.srt) with misaligned timing
- MSI built from tip `74302ec9` or later on this branch

### Test Steps

1. **Install and Launch**
   ```
   ☐ Install MSI on Windows machine
   ☐ Launch Nuvio Desktop
   ☐ Confirm no startup crashes
   ```

2. **Load Media with External Subtitle**
   ```
   ☐ Open test video (e.g., sample with dialogue at 0:10-0:30, silence at 0:00-0:10)
   ☐ Load external subtitle file that is out of sync
   ☐ Play video and confirm dialogue is audible
   ```

3. **Trigger Auto Sync**
   ```
   ☐ Open Subtitle Settings (Style rail)
   ☐ Confirm "Auto Sync" button is visible and enabled
   ☐ Click "Auto Sync" button during dialogue section
   ☐ Wait ~20-30 seconds for capture to complete
   ```

4. **Verify Audio Capture Success**
   
   **Expected behavior:**
   - Progress indicator or "Analyzing..." message during capture
   - After ~20-30s, one of:
     - ✅ SUCCESS: "Offset adjusted by X.X seconds" message
     - ✅ SUCCESS: Subtitle timing visibly shifts to align with dialogue
     - ❌ FAIL: "Could not capture audio data" error
     - ❌ FAIL: App crashes during capture

5. **Verify Energy Varies (Debug Build)**
   
   If running debug build, add logging to `computeRealAudioEnergy`:
   ```cpp
   printf("[Nuvio] RMS=%.2f dB, energy=%.3f\n", rmsDb, linearEnergy);
   ```
   
   **Expected console output during capture:**
   ```
   [Nuvio] RMS=-35.2 dB, energy=0.496   # Dialogue
   [Nuvio] RMS=-28.1 dB, energy=0.638   # Louder dialogue
   [Nuvio] RMS=-52.7 dB, energy=0.146   # Silence/background
   [Nuvio] RMS=-31.4 dB, energy=0.572   # Dialogue
   ```
   
   **PASS if:** Energy values vary between ~0.1-0.2 (silence) and ~0.5-0.9 (dialogue)  
   **FAIL if:** All values are 0.0, -1.0, or constant

6. **Verify No Crashes Under Stress**
   ```
   ☐ Click Auto Sync
   ☐ Immediately seek forward 10 seconds
   ☐ Change volume to 50%
   ☐ Pause and unpause
   ☐ Confirm app remains stable (no crashes)
   ☐ Capture should complete or fail gracefully
   ```

---

## Expected Energy Values

### Typical Audio Levels (dB → Energy)
| Audio Content | RMS (dB) | Peak (dB) | Normalized Energy |
|---------------|----------|-----------|-------------------|
| Silence       | -60 to -50 | -60 to -45 | 0.0 - 0.2 |
| Background noise | -45 to -35 | -40 to -30 | 0.2 - 0.5 |
| Dialogue      | -35 to -20 | -30 to -15 | 0.5 - 0.8 |
| Loud dialogue | -20 to -10 | -15 to -5  | 0.8 - 1.0 |

### Conversion Formula
```
dB range: -60 (silence) to -10 (loud)
linearEnergy = (rmsDb + 60.0) / 50.0
             = clamp(value, 0.0, 1.0)

Examples:
  -60 dB → (−60+60)/50 = 0.0
  -35 dB → (−35+60)/50 = 0.5
  -10 dB → (−10+60)/50 = 1.0
```

---

## Failure Modes

### "Could not capture audio data"
**Root cause:** `computeRealAudioEnergy` returned -1.0 for all samples  
**Possible reasons:**
- MPV astats filter not initialized (check line 1887)
- `af-metadata/nuvio_astats` property unavailable (MPV version issue?)
- No audio track in the video file
- Audio filter chain broken by user settings

**Debug:** Add logging to `computeRealAudioEnergy` to see:
- `rmsResult` / `peakResult` error codes
- Whether `rmsStr` / `peakStr` are null
- Raw dB values before conversion

### All Energy Values Are 0.0
**Root cause:** Filter returns valid metadata but very low RMS/peak levels  
**Possible reasons:**
- System audio muted or very low
- Video file has silent audio track
- Watching video during a silent section

**Fix:** Test with known-good video with dialogue, ensure system volume > 50%

### App Crashes During Capture
**Root cause:** Race condition or invalid MPV handle  
**Expected:** Should NOT happen with current mutex-protected implementation  
**Report:** If this occurs, it's a critical bug

---

## Verification Status

✅ **VERIFIED** - Windows audio capture code path is **intact and correct** at tip `74302ec9`:

1. ✅ MPV astats filter initialized during player setup
2. ✅ JNI methods exported (`startAudioEnergyCapture`, `stopAudioEnergyCapture`, `getAudioCaptureDuration`)
3. ✅ Background thread samples every 100ms
4. ✅ `computeRealAudioEnergy` reads `af-metadata/nuvio_astats/lavfi.astats.Overall.RMS_level` as string
5. ✅ Converts dB levels (-60 to -10) to normalized energy (0.0 to 1.0)
6. ✅ Returns -1.0 on failure (honest, not fake zeros)
7. ✅ Stores `{timestampMs, energy}` samples for SubtitleAutoSyncEngine
8. ✅ Thread-safe with mutex protection

**Recommendation:** Windows implementation should work as designed. Manual MSI test on Shadow (or equivalent Windows desktop) will confirm MPV filter delivers varying energy during dialogue vs. silence.

**No code changes required for Windows.**
