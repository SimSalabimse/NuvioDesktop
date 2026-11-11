package com.nuvio.app.features.player.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private object DesktopAppFullscreen {
    private var toggleHandler: ((Window?) -> Unit)? = null
    private var fullscreenStateProvider: ((Window?) -> Boolean)? = null
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun setToggleHandler(
        handler: ((Window?) -> Unit)?,
        isFullscreen: (Window?) -> Boolean,
    ): () -> Unit {
        toggleHandler = handler
        fullscreenStateProvider = isFullscreen
        notifyChanged()
        return {
            if (toggleHandler === handler) {
                toggleHandler = null
                fullscreenStateProvider = null
                notifyChanged()
            }
        }
    }

    fun toggle(window: Window? = null) {
        val handler = toggleHandler ?: return
        if (SwingUtilities.isEventDispatchThread()) {
            handler(window)
            notifyChanged()
        } else {
            SwingUtilities.invokeLater {
                handler(window)
                notifyChanged()
            }
        }
    }

    fun isFullscreen(window: Window? = null): Boolean =
        fullscreenStateProvider?.invoke(window) == true

    fun noteExternalFullscreenChange() {
        notifyChanged()
    }

    private fun notifyChanged() {
        _changes.value += 1
    }
}

internal fun registerDesktopAppFullscreenToggle(
    handler: (Window?) -> Unit,
    isFullscreen: (Window?) -> Boolean,
): () -> Unit =
    DesktopAppFullscreen.setToggleHandler(handler, isFullscreen)

internal fun toggleDesktopAppFullscreen(window: Window? = null) {
    DesktopAppFullscreen.toggle(window)
}

/** Player Esc reports 1 and the fullscreen button reports 0. Both toggle. */
@Suppress("UNUSED_PARAMETER")
internal fun toggleDesktopAppFullscreenFromPlayer(window: Window?, value: Double) {
    toggleDesktopAppFullscreen(window)
}

internal fun isDesktopAppFullscreen(window: Window? = null): Boolean =
    DesktopAppFullscreen.isFullscreen(window)

internal val desktopFullscreenChanges: StateFlow<Int>
    get() = DesktopAppFullscreen.changes

internal class DesktopAppFullscreenController(
    initialPlacement: WindowPlacement = WindowPlacement.Floating,
    initialWindowedPlacement: WindowPlacement = initialPlacement.asWindowedPlacement(),
    private val platform: FullscreenPlatform = DesktopHostOs.current.toFullscreenPlatform(),
    private val nativeExit: ((Window) -> Boolean)? = null,
    private val windowsEnter: ((Window, WindowState, Boolean) -> Unit)? = null,
    private val readWindowPlacement: (Window, WindowPlacement) -> WindowPlacement = { window, statePlacement ->
        (window as? ComposeWindow)?.placement ?: statePlacement
    },
) {
    private val owner = DesktopFullscreenOwner(
        platform = platform,
        initialPlacement = initialPlacement,
        initialWindowedPlacement = initialWindowedPlacement,
    )
    private var windowsFullscreenState: WindowsFullscreenState? = null

    fun toggle(window: Window, windowState: WindowState): List<FullscreenCommand> =
        toggleFromPlayer(window, windowState, value = 0.0)

    /** [value] is the player event: Esc sends 1 and the button sends 0. */
    @Suppress("UNUSED_PARAMETER")
    fun toggleFromPlayer(
        window: Window,
        windowState: WindowState,
        value: Double,
    ): List<FullscreenCommand> {
        val produced = mutableListOf<FullscreenCommand>()
        if (platform != FullscreenPlatform.Windows) {
            val statePlacement = windowState.placement
            val sync = owner.onPlacementObserved(
                observed = statePlacement,
                windowPlacement = reportedWindowPlacement(window, statePlacement),
            )
            produced += sync
            apply(sync, window, windowState)
        }
        val frameMaximized = if (platform == FullscreenPlatform.Windows) {
            frameIsMaximized(window, windowState)
        } else {
            false
        }
        val commands = owner.toggle(
            frameMaximized = frameMaximized,
            statePlacement = windowState.placement,
        )
        produced += commands
        produced += apply(commands, window, windowState)
        return produced
    }

    fun onWindowPlacementChanged(
        window: Window,
        windowState: WindowState,
        observedState: WindowPlacement,
        observedWindow: WindowPlacement = readWindowPlacement(window, observedState),
    ) {
        val before = owner.isFullscreen
        val reportedWindow = if (platform == FullscreenPlatform.Macos) {
            observedWindow
        } else {
            observedState
        }
        apply(
            owner.onPlacementObserved(
                observed = observedState,
                windowPlacement = reportedWindow,
            ),
            window,
            windowState,
        )
        if (platform == FullscreenPlatform.Macos && owner.isFullscreen != before) {
            DesktopAppFullscreen.noteExternalFullscreenChange()
        }
    }

    /**
     * Compose applies [WindowState.placement] through updaters memoized on the
     * last value it applied, with a write-back listener that re-reads window
     * state on AWT window events. Some window managers (mutter) emit extra
     * state events that convince Compose the window is already windowed while
     * the X11 window still carries _NET_WM_STATE_FULLSCREEN — the exit write is
     * then skipped and the window stays fullscreen. Verify the AWT device state
     * against the intended placement and correct it; a no-op when Compose
     * applied the change itself.
     */
    private fun enforceLinuxFullscreen(window: Window, windowState: WindowState) {
        fun enforce(stage: String) {
            val device = window.graphicsConfiguration?.device ?: return
            val wantFullscreen = windowState.placement == WindowPlacement.Fullscreen
            val awtFullscreen = device.fullScreenWindow === window
            if (wantFullscreen == awtFullscreen) return
            device.fullScreenWindow = if (wantFullscreen) window else null
        }
        SwingUtilities.invokeLater { enforce("immediate") }
        Timer(250) { enforce("delayed") }.apply { isRepeats = false }.start()
    }

    fun dispose(window: Window) {
        exitWindowsFullscreen(window)
        owner.abandonWindowsFullscreen()
    }

    /**
     * Applies a fullscreen state restored from a previous session, before the
     * window has been interacted with. Only acts when [fullscreen] is true;
     * windowed is already the default state for a freshly created window.
     */
    fun applyRestoredFullscreenState(
        window: Window,
        windowState: WindowState,
        fullscreen: Boolean,
        windowedPlacement: WindowPlacement = WindowPlacement.Floating,
    ) {
        if (!fullscreen) return
        if (platform == FullscreenPlatform.Windows) {
            SwingUtilities.invokeLater {
                val frameMaximized = frameIsMaximized(window, windowState)
                apply(
                    owner.restoreSession(
                        fullscreen = true,
                        windowedPlacement = windowedPlacement,
                        frameMaximized = frameMaximized,
                    ),
                    window,
                    windowState,
                )
            }
            return
        }
        apply(
            owner.restoreSession(
                fullscreen = true,
                windowedPlacement = windowedPlacement,
            ),
            window,
            windowState,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun isFullscreen(window: Window, windowState: WindowState): Boolean {
        if (platform == FullscreenPlatform.Windows) {
            return windowsFullscreenState?.window === window && owner.isFullscreen
        }
        return owner.isFullscreen
    }

    fun isAwaitingMaximizedRestore(): Boolean = owner.isAwaitingMaximizedRestore

    fun shouldPersistWindowedGeometry(placement: WindowPlacement): Boolean =
        owner.shouldPersistWindowedGeometry(placement)

    private fun reportedWindowPlacement(window: Window, statePlacement: WindowPlacement): WindowPlacement =
        if (platform == FullscreenPlatform.Macos) {
            readWindowPlacement(window, statePlacement)
        } else {
            statePlacement
        }

    private fun apply(
        commands: List<FullscreenCommand>,
        window: Window,
        windowState: WindowState,
    ): List<FullscreenCommand> {
        val applied = mutableListOf<FullscreenCommand>()
        for (command in commands) {
            when (command) {
                is FullscreenCommand.PublishPlacement -> windowState.placement = command.placement
                FullscreenCommand.RequestMacosNativeExit -> {
                    val accepted = nativeExit?.invoke(window) ?: requestNativeComposeFullscreenExit(window)
                    applied += apply(owner.onMacosNativeExitResult(accepted), window, windowState)
                }
                is FullscreenCommand.WindowsBorderless -> if (command.enter) {
                    try {
                        val enter = windowsEnter
                        if (enter != null) {
                            enter(window, windowState, command.wasMaximized)
                        } else {
                            enterWindowsFullscreen(window, windowState, command.wasMaximized)
                        }
                    } catch (error: Throwable) {
                        if (windowsFullscreenState == null) owner.abandonWindowsFullscreen()
                        throw error
                    }
                } else {
                    exitWindowsFullscreen(window, windowState)
                }
                FullscreenCommand.EnforceLinuxDeviceFullscreen -> enforceLinuxFullscreen(window, windowState)
            }
        }
        return applied
    }

    private fun requestNativeComposeFullscreenExit(window: Window): Boolean {
        if (DesktopHostOs.current != DesktopHostOs.MACOS) return false
        return runCatching {
            NativePlayerBridge.setMacosWindowFullscreen(
                windowViewPtr = AwtNativeViewResolver.resolveNativeViewPointer(window),
                fullscreen = false,
            )
        }.isSuccess
    }

    private fun frameIsMaximized(window: Window, windowState: WindowState): Boolean =
        (window as? Frame)?.extendedState == Frame.MAXIMIZED_BOTH ||
            windowState.placement == WindowPlacement.Maximized

    private fun enterWindowsFullscreen(
        window: Window,
        windowState: WindowState,
        wasMaximized: Boolean,
    ) {
        val gc = window.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val screenBounds = gc.bounds
        val transform = gc.defaultTransform
        val scaleX = transform.scaleX
        val scaleY = transform.scaleY

        val hwnd = AwtNativeViewResolver.resolveNativeViewPointer(window)
        NativePlayerBridge.setWindowBorderlessFullscreen(
            windowHwnd = hwnd,
            fullscreen = true,
            x = (screenBounds.x * scaleX).toInt(),
            y = (screenBounds.y * scaleY).toInt(),
            width = (screenBounds.width * scaleX).toInt(),
            height = (screenBounds.height * scaleY).toInt(),
        )
        windowsFullscreenState = WindowsFullscreenState(
            window = window,
            windowHwnd = hwnd,
            wasMaximized = wasMaximized,
        )
        window.toFront()
        window.requestFocus()
    }

    private fun exitWindowsFullscreen(window: Window, windowState: WindowState? = null) {
        val fullscreenState = windowsFullscreenState?.takeIf { it.window === window } ?: return
        NativePlayerBridge.setWindowBorderlessFullscreen(
            windowHwnd = fullscreenState.windowHwnd,
            fullscreen = false,
            x = 0,
            y = 0,
            width = 0,
            height = 0,
        )
        val wasMaximized = fullscreenState.wasMaximized
        windowsFullscreenState = null

        if (window is Frame) {
            if (wasMaximized) {
                window.extendedState = Frame.NORMAL
                window.extendedState = Frame.MAXIMIZED_BOTH
                windowState?.placement = WindowPlacement.Maximized
            } else {
                window.extendedState = Frame.NORMAL
                windowState?.placement = WindowPlacement.Floating
            }
            window.revalidate()
            window.repaint()
        }
    }

    private data class WindowsFullscreenState(
        val window: Window,
        val windowHwnd: Long,
        val wasMaximized: Boolean,
    )
}

/**
 * The windowed placement to return to after a launch that starts in fullscreen.
 * Mirrors startup placement with the fullscreen branch removed: a saved
 * "not maximized" choice stays floating, and hosts that restore maximized
 * windows go back to maximized. Reading the live placement is wrong here,
 * because launch has already set it to fullscreen.
 */
internal fun windowedPlacementAfterFullscreenRestore(
    wasMaximized: Boolean?,
    hasSavedGeometry: Boolean,
    restoresMaximizedWindowPlacement: Boolean,
): WindowPlacement = when {
    wasMaximized == false && hasSavedGeometry -> WindowPlacement.Floating
    restoresMaximizedWindowPlacement -> WindowPlacement.Maximized
    else -> WindowPlacement.Floating
}

internal fun installDesktopAppFullscreenShortcuts(window: Window): () -> Unit {
    val dispatcher = KeyEventDispatcher { event ->
        if (!event.isDesktopAppFullscreenShortcut()) return@KeyEventDispatcher false
        toggleDesktopAppFullscreen(window)
        true
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}

private fun KeyEvent.isDesktopAppFullscreenShortcut(): Boolean {
    if (id != KeyEvent.KEY_PRESSED) return false
    if (keyCode == KeyEvent.VK_F11) return true
    if (keyCode != KeyEvent.VK_F) return false
    val modifiers = modifiersEx
    val hasMacFullscreenModifiers =
        modifiers and KeyEvent.META_DOWN_MASK != 0 &&
            modifiers and KeyEvent.CTRL_DOWN_MASK != 0 &&
            modifiers and KeyEvent.ALT_DOWN_MASK == 0
    return hasMacFullscreenModifiers
}
