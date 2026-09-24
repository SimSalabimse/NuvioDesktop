# macOS Fullscreen Investigation Summary

## Investigation Goal
Find and fix bugs in macOS fullscreen functionality reported by Simen.

## Files Investigated
- `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/DesktopAppFullscreen.kt`
- `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/DesktopWindowChrome.kt`
- `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/DesktopWindowModeStorage.kt`
- `composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/MacosAwtViewResolver.kt`
- `composeApp/src/desktopMain/kotlin/com/nuvio/app/core/ui/FullscreenAction.desktop.kt`
- `composeApp/src/desktopMain/kotlin/com/nuvio/app/Main.kt`
- `composeApp/src/desktopMain/native/macos/player_bridge.mm`

## Bugs Found and Fixed

### Bug 1: Missing WindowState Update After Native Fullscreen Exit
**Location:** `DesktopAppFullscreen.kt`, `applyMacosComposeFullscreenExit()` function

**Problem:**
When exiting fullscreen on macOS, the code would:
1. Call `requestNativeComposeFullscreenExit()` to ask AppKit to exit fullscreen
2. If that succeeded, return immediately without updating `windowState.placement`
3. Rely entirely on Compose's window listener to eventually update the state

There was no guarantee that Compose's listener would fire reliably or immediately. The comment said "Let AppKit complete its asynchronous fullscreen exit and let Compose's native window listener restore WindowState" but provided no fallback.

**Symptoms:**
- UI shows fullscreen button as "exit fullscreen" when already exited
- Double-toggle required to re-enter fullscreen
- Wrong fullscreen state saved to storage
- `isDesktopAppFullscreen()` returns incorrect value
- Keyboard shortcuts (F11, Cmd+Ctrl+F) become unreliable

**Fix:**
Always update `windowState.placement` after requesting native fullscreen exit:
- For Floating target: Set immediately to Floating
- For Maximized target: Set to Floating first (to avoid interfering with AppKit's frame restoration), then set to Maximized asynchronously via `SwingUtilities.invokeLater`

This ensures WindowState is always synchronized, even if Compose's listener is delayed or doesn't fire.

### Bug 2: Conflicting Placement Updates in Fallback Path
**Location:** `DesktopAppFullscreen.kt`, `toggleComposeFullscreen()` and `applyMacosComposeFullscreenExit()`

**Problem:**
When the native fullscreen exit request failed (or wasn't available), the code would:
1. Call `clearComposeFullscreen()` which set `window.placement = Floating`
2. Call `setStatePlacement()` which set `windowState.placement = Maximized` (when exiting to maximized)

These two operations could conflict and cause visual flicker between Floating and Maximized states.

**Fix:**
Removed the `clearComposeFullscreen` parameter entirely. State is now managed only through `windowState.placement`, eliminating the conflict.

### Bug 3: Direct Window Mutation Bypassing State Management
**Location:** `DesktopAppFullscreen.kt`, line 149 in the old code

**Problem:**
The `clearComposeFullscreen` lambda tried to cast the window to `ComposeWindow` and set placement directly:
```kotlin
clearComposeFullscreen = {
    (window as? ComposeWindow)?.placement = WindowPlacement.Floating
}
```

This could silently fail if the cast returned null, and it was redundant with the subsequent `setStatePlacement()` call that properly updated `windowState.placement`.

**Fix:**
Removed this direct window mutation. All state updates now go through `windowState.placement`, which is the proper state management approach in Compose.

## Technical Details

### How macOS Fullscreen Works in Nuvio Desktop

1. **Native Layer:** AppKit's `NSWindow` has `toggleFullScreen:nil` for fullscreen transitions
2. **Bridge Layer:** `player_bridge.mm` JNI function `setMacosWindowFullscreen()` calls `[window toggleFullScreen:nil]`
3. **Kotlin Layer:** `DesktopAppFullscreenController` manages the fullscreen state and calls the native bridge
4. **Compose Layer:** `WindowState.placement` tracks whether the window is Fullscreen/Maximized/Floating

### The Synchronization Challenge

AppKit's fullscreen transition is **asynchronous**:
- You call `toggleFullScreen:nil`
- AppKit animates the transition (~250-500ms)
- AppKit sends notifications: `NSWindowWillEnterFullScreenNotification`, `NSWindowDidEnterFullScreenNotification`, etc.
- Compose's window listener receives these and updates `WindowState.placement`

The problem was that the Kotlin code assumed Compose's listener would always work reliably. The fix ensures `WindowState.placement` is updated explicitly, not just via the listener.

### Why Floating First When Exiting to Maximized?

The original comment in the code mentioned: "ComposeWindow does not clear its fullscreen flag when placement is changed directly from Fullscreen to Maximized on macOS."

Setting `placement = Maximized` while AppKit is still animating its fullscreen exit can interfere with the frame AppKit is trying to restore. By setting Floating first and then Maximized asynchronously, we:
1. Let AppKit complete its transition to windowed mode
2. Let the window settle at its pre-fullscreen position
3. Then apply the Maximized state

This is safer and matches the pattern used on enter (where `restoreWindowPlacement` is captured before setting Fullscreen).

## Areas Not Changed (By Design)

- **Windows fullscreen:** Uses a different code path with native borderless fullscreen, unchanged
- **Linux fullscreen:** Uses X11/Wayland fullscreen, unchanged
- **PiP windows:** Separate `DesktopPlayerPipWindow`, unchanged
- **Keyboard shortcuts:** F11 and Cmd+Ctrl+F handlers, unchanged
- **Native player bridge:** The JNI and Objective-C code, unchanged (it was already correct)
- **Window chrome:** The `apple.awt.fullWindowContent` setup, unchanged

## Testing Recommendations for Simen

Since this is macOS-specific, manual testing on macOS is required:

### Basic Fullscreen Toggle
1. Launch app
2. Press F11 → window should enter fullscreen smoothly
3. Press F11 again → window should exit fullscreen, return to previous placement
4. Repeat 5-10 times rapidly → should remain smooth, no desyncs

### Maximized State Preservation
1. Maximize the window (green traffic-light button or WindowPlacement.Maximized)
2. Enter fullscreen (F11 or Cmd+Ctrl+F)
3. Exit fullscreen
4. Window should return to maximized state, not floating

### Multiple Monitors
1. Move window to second monitor
2. Enter fullscreen → should fullscreen on that monitor
3. Exit fullscreen → should return to that monitor in correct position

### Green Traffic-Light Button
1. Click the green traffic-light button in the title bar → native macOS fullscreen
2. The app's fullscreen state should synchronize
3. Click green button again to exit → state should remain synchronized

### State Persistence
1. Enter fullscreen
2. Quit the app (Cmd+Q)
3. Relaunch → should restore fullscreen state
4. Exit fullscreen
5. Quit and relaunch → should restore windowed state

### Keyboard Shortcuts
1. Test both F11 and Cmd+Ctrl+F shortcuts
2. Both should toggle fullscreen reliably
3. Try pressing them rapidly → no hangs or desyncs

## Questions for Simen (If Bugs Persist)

If manual testing reveals issues not fixed by this PR, please describe:

1. **Exact repro steps:** What sequence of actions causes the bug?
2. **Observed behavior:** What actually happens?
3. **Expected behavior:** What should happen?
4. **Window state:** Was the window floating, maximized, or fullscreen before the issue?
5. **Monitor setup:** Single monitor, multiple monitors, different DPIs?
6. **Video playing:** Does it happen with/without video playing?
7. **macOS version:** Which version of macOS?
8. **Hardware:** Intel or Apple Silicon?

## Potential Future Issues (Not Present in Current Code)

Areas that could cause future fullscreen problems on macOS:

1. **MPV video surface desyncing:** If the MPV/AWT video surface doesn't resize properly during fullscreen transitions (not observed in current code, but a common issue with embedded native players)
2. **Title bar not hiding:** If `apple.awt.fullWindowContent` configuration breaks (currently correct in `configureMacosWindowBeforePeer`)
3. **Multi-monitor edge cases:** If window moves between monitors during fullscreen (not handled specially in current code)
4. **PiP vs fullscreen conflicts:** If both PiP and main window try to be fullscreen (not an issue currently since PiP is `isUndecorated = true` and `isResizable = true`, not fullscreen)

## Conclusion

Three concrete bugs were found and fixed:
1. ✅ Missing WindowState update after successful native fullscreen exit
2. ✅ Conflicting placement updates in fallback path
3. ✅ Direct window mutation bypassing proper state management

All fixes are in the `cursor/macos-fullscreen-fixes-8270` branch, PR #15 into `enhanced`.

The fixes should resolve any desync issues between the app's fullscreen state and the actual window state. Manual testing on macOS is required to confirm all scenarios work correctly.
