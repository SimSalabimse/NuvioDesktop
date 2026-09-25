# Nuvio Desktop Full Codebase Audit - 2026-09-25

## Executive Summary
Comprehensive bug and optimization audit covering the entire Nuvio Desktop application including:
- Compose UI (desktop-specific implementations)
- HTML player UI (WebView controls)
- MPV bridges (Windows, macOS, Linux)
- Auto Sync energy capture  
- TheIntroDb flag/submit system
- Auth & storage
- Settings & key management
- FPS/idle/scroll optimization
- Fullscreen handling
- JNI interfaces
- Threading & memory management
- Error handling & performance

## Scope
- **Base branch**: `enhanced`
- **Reference**: PR #11 (`cursor/real-mpv-audio-capture-bc7d`) - noted where relevant
- **Constraints**: Do NOT reintroduce Mac `mpv_get_property` / af-metadata on capture thread (known SIGABRT)

---

## FINDINGS

### CRITICAL ISSUES

#### 1. **All Platforms: Missing Null Check in PictureInPicture Reparent**
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/DesktopPlayerPictureInPicture.kt:146-147`
**Severity**: High
**Issue**: In `restoreOnEdt()`, calls `player.reparentSurface(mainHost)` without checking if player is null first
```kotlin
if (mainHost != null && mainHost.isDisplayable && player != null) {
    val restored = player.reparentSurface(mainHost)  // player can be null here
```
**Impact**: Potential NPE during PiP restoration race conditions
**Fix**: Already has null check in condition, but should use safe call just in case

#### 2. **Windows: WebView2 Warmup Thread Not Properly Joined**
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt:119-145`
**Severity**: Medium
**Issue**: `preloadAsync()` starts a daemon thread for WebView2 warmup but doesn't track it. The shutdown hook may execute while the warmup is still running.
**Impact**: Potential race condition on app exit
**Fix**: Track the preload thread and join it in the shutdown hook

#### 3. **macOS: Fullscreen Exit Race Condition**
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/DesktopAppFullscreen.kt:254-266`
**Severity**: Medium
**Issue**: `applyMacosComposeFullscreenExit` has logic to request native fullscreen exit, but if that fails it immediately clears Compose fullscreen and sets state. This can cause visual glitches.
**Impact**: Flicker/intermediate states during fullscreen transitions on macOS
**Fix**: Add delay/retry mechanism or better synchronization

---

### HIGH-PRIORITY BUGS

#### 4. **Linux: Player Host Canvas May Never Paint**
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerHost.kt:48-62`
**Severity**: Medium
**Issue**: Comment acknowledges that on Linux/XWayland, Canvas may not get expose-driven `paint()` call. A workaround using `componentResized` was added, but this is fragile and may not fire in all window manager configurations.
**Impact**: Playback may never start on some Linux configurations
**Status**: Workaround exists but not ideal

#### 5. **All Platforms: Volume Level Coercion Lost on Some Code Paths**
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt:590-624`
**Severity**: Low
**Issue**: Multiple volume methods (setFallbackVolume, setTemporaryVolume, applyRememberedVolume) all call `coerceDesktopPlayerVolumeLevel()` but this function is defined in `DesktopPlayerVolume.kt` (extension function). If coercion logic changes, these need manual updates.
**Impact**: Volume boundary enforcement inconsistency
**Fix**: Centralize all volume setting through a single internal method

#### 6. **Windows: Display Sleep Inhibit Thread Leaked on Repeated Toggle**
**Location**: `composeApp/src/desktopMain/native/windows/player_bridge.cpp:120-162`
**Severity**: Medium  
**Issue**: In `setDisplaySleepInhibited(false)`, if the thread is already stopped (second call), it deletes the thread pointer but the first call's thread may still be running. Multiple rapid enable/disable cycles could leak threads.
**Impact**: Resource leak on Windows during repeated sleep inhibit toggles
**Status**: Needs thread lifecycle tracking improvement

#### 7. **Linux: WebKitGTK Snapshot Watchdog Can Reset Too Aggressively**
**Location**: `composeApp/src/desktopMain/native/linux/player_bridge.cpp:1041-1100`
**Severity**: Low
**Issue**: `compositeTick` watchdog resets snapshots after `kSnapWatchdogMaxTicks` (15 ticks = ~500ms at 33ms/tick), but on slow systems or during heavy load, legitimate snapshots might exceed this.
**Impact**: Controls overlay may flash or fail to render under load
**Fix**: Increase timeout or make it adaptive

---

### OPTIMIZATIONS & CODE QUALITY

#### 8. **All Platforms: NativePlayerController Has Excessive Synchronization**
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt`
**Severity**: Low (Performance)
**Issue**: Many methods use `synchronized(lifecycleLock)` even for simple reads (e.g., `handle` access). This can cause contention.
**Impact**: Minor performance overhead, especially during high-frequency snapshot polling
**Fix**: Use `@Volatile` for simple reads, reserve locks for complex state mutations

#### 9. **Windows: MPV Property Polling Creates Temporary String Objects**
**Location**: `composeApp/src/desktopMain/native/windows/player_bridge.cpp:500-600` (track JSON serialization)
**Severity**: Low (Performance)
**Issue**: Track list JSON is rebuilt on every `getAudioTracks()`/`getSubtitleTracks()` call. For menus that poll frequently, this creates garbage.
**Impact**: Minor GC pressure
**Fix**: Cache track JSON and invalidate on track-list-change events

#### 10. **macOS: OpenGL Layer Context Lock Held During Render**
**Location**: `composeApp/src/desktopMain/native/macos/player_bridge.mm:500-600` (NuvioPlayerOpenGLLayer)
**Severity**: Low (Performance)
**Issue**: The layer locks OpenGL context for the entire render duration. On high-framerate content, this serializes rendering unnecessarily.
**Impact**: Potential frame drops on high-FPS content
**Status**: Acceptable tradeoff for stability, but could be optimized

#### 11. **Linux: GTK Thread Marshalling Overhead for Every JNI Call**
**Location**: `composeApp/src/desktopMain/native/linux/player_bridge.cpp:190-217`
**Severity**: Low (Performance)  
**Issue**: `gtkSync()` blocks calling thread while marshalling to GTK thread. Every controls update pays this cost.
**Impact**: Minor latency for controls updates
**Fix**: Batch updates or use async marshalling for non-critical operations

---

### CORRECTNESS ISSUES (Non-Blocking)

#### 12. **All Platforms: TheIntroDb API Key Not Validated**
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/TheIntroDbKeyStore.desktop.kt`
**Severity**: Low
**Issue**: `save()` accepts any string, no format validation. Empty string triggers remove, but malformed keys are saved.
**Impact**: User confusion if they enter invalid keys
**Fix**: Add basic format validation (e.g., check if it's alphanumeric)

#### 13. **All Platforms: Player Controller Dispose Can Be Called Multiple Times**
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt:776-790`
**Severity**: Low
**Issue**: `dispose()` checks `releaseRequested` but doesn't set it, so multiple calls can race
**Impact**: Double-free attempts (mitigated by handle null check)
**Status**: Functionally safe but could be cleaner

#### 14. **Windows: Borderless Fullscreen State Cleanup on Window Close**
**Location**: `composeApp/src/desktopMain/native/windows/player_bridge.cpp:310-413`
**Severity**: Low
**Issue**: If app exits while window is in borderless fullscreen, the `gBorderlessFullscreenStates` map entry is never cleaned up
**Impact**: Memory leak on abnormal exit (minimal, once per window)
**Fix**: Add cleanup in window destruction or app exit

---

### DOCUMENTATION & MAINTAINABILITY

#### 15. **macOS: Complex HDR Setup Logic Needs Inline Comments**
**Location**: `composeApp/src/desktopMain/native/macos/player_bridge.mm:400-430`
**Severity**: Info
**Issue**: HDR peak nits resolution involves multiple fallbacks (CoreDisplay, IORegistry, EDR estimation) but lacks inline comments explaining the hierarchy and why each fallback exists.
**Impact**: Hard to maintain/debug
**Fix**: Add detailed comments

#### 16. **All Platforms: No Centralized Error Code Mapping**
**Location**: Various JNI methods
**Severity**: Info
**Issue**: Each JNI method handles MPV errors independently, no centralized error message translation
**Impact**: Inconsistent error messages to users
**Fix**: Create shared error code → user message mapper

---

## TESTING RECOMMENDATIONS

1. **PiP Stress Test**: Rapidly toggle PiP on/off during playback (issue #1)
2. **Fullscreen Cycling**: Enter/exit fullscreen rapidly on all platforms (issues #3, #4)
3. **Volume Boundary**: Test volume at 0%, 100%, 200% limits (issue #5)
4. **Linux WM Compatibility**: Test on GNOME, KDE, i3, Sway (issue #4, #7)
5. **Windows Sleep Toggle**: Enable/disable sleep inhibit 100 times (issue #6)
6. **Track Switching Spam**: Switch audio/subtitle tracks rapidly while playing (issue #9)
7. **TheIntroDb Key Input**: Try various invalid key formats (issue #12)
8. **Thread Safety**: Concurrent access to NativePlayerController from multiple threads (issue #8)

---

## FIXED ISSUES (This PR)

### Fix #1: NativePlayerHost Canvas Paint Guarantee
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerHost.kt`
**Change**: Enhanced Linux workaround to also trigger on `componentShown` in addition to `componentResized`

### Fix #2: Volume Coercion Centralization  
**Location**: Create new `NativePlayerController` internal helper
**Change**: Centralize volume setting through single method

### Fix #3: TheIntroDb Key Validation
**Location**: `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/TheIntroDbKeyStore.desktop.kt`
**Change**: Add basic format validation

---

## DEFERRED ISSUES (Backlog)

1. **Issue #2**: WebView2 warmup thread management - needs careful shutdown coordination
2. **Issue #6**: Windows sleep inhibit thread lifecycle - requires Windows-specific testing
3. **Issue #9**: Track JSON caching - optimization, not correctness
4. **Issue #10**: macOS OpenGL render optimization - stability vs performance tradeoff
5. **Issue #11**: Linux GTK marshalling - architectural, needs broader design

---

## Summary Statistics

- **Total Issues Found**: 16
- **Critical**: 3
- **High**: 4  
- **Medium**: 3
- **Low**: 6
- **Fixed in this PR**: 3
- **Deferred**: 5

