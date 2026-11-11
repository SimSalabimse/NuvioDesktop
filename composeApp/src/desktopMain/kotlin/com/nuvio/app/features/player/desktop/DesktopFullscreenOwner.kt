package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement

/**
 * Single source of truth for desktop fullscreen.
 *
 * The owner decides whether the session is fullscreen and which placement should
 * be published. Call sites apply the returned commands; they do not write a
 * second placement of their own. macOS keeps logical fullscreen separate from
 * the placement AppKit is animating, because those two move at different times.
 */
internal class DesktopFullscreenOwner(
    private val platform: FullscreenPlatform,
    initialPlacement: WindowPlacement,
    initialWindowedPlacement: WindowPlacement = initialPlacement.asWindowedPlacement(),
) {
    var isFullscreen: Boolean =
        platform != FullscreenPlatform.Windows && initialPlacement == WindowPlacement.Fullscreen
        private set

    var placement: WindowPlacement = initialPlacement
        private set

    var windowedPlacement: WindowPlacement = initialWindowedPlacement.asWindowedPlacement()
        private set

    /**
     * True while a macOS exit still has to publish [WindowPlacement.Maximized]
     * after fullscreen has cleared. The floating frame in between is not a
     * windowed geometry to persist.
     */
    val isAwaitingMaximizedRestore: Boolean
        get() = pendingTarget == WindowPlacement.Maximized

    private var windowsWasMaximized: Boolean = false

    /**
     * Placement to publish after macOS has actually left fullscreen.
     * Null when no exit is waiting on AppKit or on the compose fallback.
     */
    private var pendingTarget: WindowPlacement? = null

    /**
     * True after this owner publishes fullscreen and before that publish is
     * observed. A trailing windowed frame from the exit we just cancelled must
     * not be treated as a new external exit.
     */
    private var awaitingFullscreen: Boolean = false

    fun toggle(frameMaximized: Boolean = false): List<FullscreenCommand> =
        if (isFullscreen) exit() else enter(frameMaximized)

    /** Window teardown already left borderless fullscreen. Keep the flag in step. */
    fun abandonWindowsFullscreen() {
        if (platform != FullscreenPlatform.Windows) return
        windowsWasMaximized = false
        isFullscreen = false
    }

    fun onMacosNativeExitResult(accepted: Boolean): List<FullscreenCommand> {
        if (platform != FullscreenPlatform.Macos) return emptyList()
        val target = (pendingTarget ?: windowedPlacement).asWindowedPlacement()
        pendingTarget = target
        isFullscreen = false
        if (accepted) {
            // The logical placement is updated here. Publishing it as well would
            // issue a second fullscreen toggle on top of the native one, and
            // publishing Maximized would rewrite the frame AppKit is restoring.
            placement = target
            return emptyList()
        }
        // Fallback has one writer and one placement. Floating is the only
        // placement that clears Compose's fullscreen flag. Maximized, when
        // that is the real target, is published once fullscreen is observed gone.
        placement = WindowPlacement.Floating
        if (target == WindowPlacement.Floating) pendingTarget = null
        return listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Floating))
    }

    fun onPlacementObserved(observed: WindowPlacement): List<FullscreenCommand> {
        if (platform == FullscreenPlatform.Windows) {
            if (!isFullscreen && observed != WindowPlacement.Fullscreen) {
                placement = observed
                windowedPlacement = observed.asWindowedPlacement()
            }
            return emptyList()
        }
        if (awaitingFullscreen) {
            if (observed == WindowPlacement.Fullscreen) {
                awaitingFullscreen = false
                isFullscreen = true
                placement = WindowPlacement.Fullscreen
            }
            return emptyList()
        }
        val pending = pendingTarget
        if (pending != null) {
            if (observed == WindowPlacement.Fullscreen) return emptyList()
            pendingTarget = null
            windowedPlacement = pending
            placement = pending
            isFullscreen = false
            return if (observed == pending) {
                emptyList()
            } else {
                listOf(FullscreenCommand.PublishPlacement(pending))
            }
        }
        if (observed == placement) return emptyList()
        if (observed == WindowPlacement.Fullscreen) {
            if (placement != WindowPlacement.Fullscreen) {
                windowedPlacement = placement.asWindowedPlacement()
            }
            isFullscreen = true
            placement = WindowPlacement.Fullscreen
            return emptyList()
        }
        isFullscreen = false
        placement = observed
        windowedPlacement = observed.asWindowedPlacement()
        return emptyList()
    }

    fun restoreSession(
        fullscreen: Boolean,
        windowedPlacement: WindowPlacement,
        frameMaximized: Boolean = false,
    ): List<FullscreenCommand> {
        this.windowedPlacement = windowedPlacement.asWindowedPlacement()
        if (!fullscreen) return emptyList()
        return when (platform) {
            FullscreenPlatform.Windows -> {
                windowsWasMaximized = frameMaximized
                isFullscreen = true
                listOf(FullscreenCommand.WindowsBorderless(enter = true, wasMaximized = frameMaximized))
            }
            FullscreenPlatform.Linux -> {
                isFullscreen = true
                placement = WindowPlacement.Fullscreen
                listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen))
            }
            FullscreenPlatform.Macos,
            FullscreenPlatform.Compose,
            -> {
                pendingTarget = null
                awaitingFullscreen = false
                isFullscreen = true
                placement = WindowPlacement.Fullscreen
                listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen))
            }
        }
    }

    private fun enter(frameMaximized: Boolean): List<FullscreenCommand> = when (platform) {
        FullscreenPlatform.Windows -> {
            windowsWasMaximized = frameMaximized
            isFullscreen = true
            listOf(FullscreenCommand.WindowsBorderless(enter = true, wasMaximized = frameMaximized))
        }
        FullscreenPlatform.Linux -> enterPlacementFullscreen(enforceLinux = true)
        FullscreenPlatform.Compose -> enterPlacementFullscreen(enforceLinux = false)
        FullscreenPlatform.Macos -> {
            pendingTarget = null
            awaitingFullscreen = true
            if (placement != WindowPlacement.Fullscreen) {
                windowedPlacement = placement.asWindowedPlacement()
            }
            isFullscreen = true
            placement = WindowPlacement.Fullscreen
            listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen))
        }
    }

    private fun exit(): List<FullscreenCommand> = when (platform) {
        FullscreenPlatform.Windows -> {
            val wasMaximized = windowsWasMaximized
            windowsWasMaximized = false
            isFullscreen = false
            placement = if (wasMaximized) WindowPlacement.Maximized else WindowPlacement.Floating
            listOf(FullscreenCommand.WindowsBorderless(enter = false, wasMaximized = wasMaximized))
        }
        FullscreenPlatform.Linux -> exitToWindowedPlacement(enforceLinux = true)
        FullscreenPlatform.Compose -> exitToWindowedPlacement(enforceLinux = false)
        FullscreenPlatform.Macos -> {
            awaitingFullscreen = false
            pendingTarget = windowedPlacement.asWindowedPlacement()
            isFullscreen = false
            listOf(FullscreenCommand.RequestMacosNativeExit)
        }
    }

    private fun enterPlacementFullscreen(enforceLinux: Boolean): List<FullscreenCommand> {
        windowedPlacement = placement.asWindowedPlacement()
        isFullscreen = true
        placement = WindowPlacement.Fullscreen
        return buildList {
            add(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen))
            if (enforceLinux) add(FullscreenCommand.EnforceLinuxDeviceFullscreen)
        }
    }

    private fun exitToWindowedPlacement(enforceLinux: Boolean): List<FullscreenCommand> {
        val restore = windowedPlacement
        isFullscreen = false
        placement = restore
        return buildList {
            add(FullscreenCommand.PublishPlacement(restore))
            if (enforceLinux) add(FullscreenCommand.EnforceLinuxDeviceFullscreen)
        }
    }
}

internal enum class FullscreenPlatform {
    Macos,
    Windows,
    Linux,
    Compose,
}

internal sealed interface FullscreenCommand {
    data class PublishPlacement(val placement: WindowPlacement) : FullscreenCommand

    data object RequestMacosNativeExit : FullscreenCommand

    data class WindowsBorderless(
        val enter: Boolean,
        val wasMaximized: Boolean,
    ) : FullscreenCommand

    data object EnforceLinuxDeviceFullscreen : FullscreenCommand
}

internal fun WindowPlacement.asWindowedPlacement(): WindowPlacement =
    if (this == WindowPlacement.Fullscreen) WindowPlacement.Floating else this

internal fun DesktopHostOs.toFullscreenPlatform(): FullscreenPlatform = when (this) {
    DesktopHostOs.MACOS -> FullscreenPlatform.Macos
    DesktopHostOs.WINDOWS -> FullscreenPlatform.Windows
    DesktopHostOs.LINUX -> FullscreenPlatform.Linux
    DesktopHostOs.UNKNOWN -> FullscreenPlatform.Compose
}
