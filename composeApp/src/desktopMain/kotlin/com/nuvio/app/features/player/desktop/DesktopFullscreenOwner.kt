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
        get() = pendingTarget == WindowPlacement.Maximized || awaitingMaximizedPublish

    private var windowsWasMaximized: Boolean = false

    /**
     * Placement to publish after the window itself has left fullscreen.
     * Null when no exit is waiting on AppKit or on the compose fallback.
     */
    private var pendingTarget: WindowPlacement? = null

    /** Maximized was published and has not been observed on the window yet. */
    private var awaitingMaximizedPublish: Boolean = false

    /**
     * Rule: a toggle that arrives while a native exit is queued and WindowState
     * is still fullscreen is queued. It is applied once, after the window
     * placement leaves fullscreen. It does not publish fullscreen onto the
     * placement Compose already has, and it does not cancel the exit.
     */
    private var reenterAfterExit: Boolean = false

    /**
     * True after this owner publishes fullscreen onto a placement Compose had
     * not already applied. A trailing windowed frame from the exit just
     * cancelled must not be treated as a new external exit.
     */
    private var awaitingFullscreen: Boolean = false

    fun toggle(
        frameMaximized: Boolean = false,
        statePlacement: WindowPlacement = placement,
    ): List<FullscreenCommand> {
        if (platform == FullscreenPlatform.Macos &&
            pendingTarget != null &&
            statePlacement == WindowPlacement.Fullscreen
        ) {
            reenterAfterExit = true
            return emptyList()
        }
        return if (isFullscreen) exit() else enter(frameMaximized, statePlacement)
    }

    /**
     * Whether [placement] is a windowed frame worth saving.
     * While a maximized restore is open, the Floating sample is rejected here,
     * before and after the placement collector runs.
     */
    fun shouldPersistWindowedGeometry(placement: WindowPlacement): Boolean =
        placement == WindowPlacement.Floating &&
            !isFullscreen &&
            !isAwaitingMaximizedRestore

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
        reenterAfterExit = false
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
        // that is the real target, stays pending until the window itself
        // reports that fullscreen is gone. The Floating write is not that report.
        placement = WindowPlacement.Floating
        if (target == WindowPlacement.Floating) pendingTarget = null
        return listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Floating))
    }

    fun onPlacementObserved(
        observed: WindowPlacement,
        windowPlacement: WindowPlacement = observed,
    ): List<FullscreenCommand> {
        if (platform == FullscreenPlatform.Windows) {
            if (!isFullscreen && observed != WindowPlacement.Fullscreen) {
                placement = observed
                windowedPlacement = observed.asWindowedPlacement()
            }
            return emptyList()
        }
        if (awaitingFullscreen) {
            if (windowPlacement == WindowPlacement.Fullscreen) {
                awaitingFullscreen = false
                isFullscreen = true
                placement = WindowPlacement.Fullscreen
            }
            return emptyList()
        }
        if (awaitingMaximizedPublish) {
            if (windowPlacement == WindowPlacement.Maximized) {
                awaitingMaximizedPublish = false
                placement = WindowPlacement.Maximized
                windowedPlacement = WindowPlacement.Maximized
                isFullscreen = false
            }
            return emptyList()
        }
        val pending = pendingTarget
        if (pending != null) {
            if (windowPlacement == WindowPlacement.Fullscreen) return emptyList()
            if (reenterAfterExit) {
                return publishFullscreen(windowPlacement)
            }
            pendingTarget = null
            windowedPlacement = pending
            placement = pending
            isFullscreen = false
            return if (windowPlacement == pending) {
                emptyList()
            } else {
                awaitingMaximizedPublish = pending == WindowPlacement.Maximized
                listOf(FullscreenCommand.PublishPlacement(pending))
            }
        }
        if (windowPlacement == placement) return emptyList()
        if (windowPlacement == WindowPlacement.Fullscreen) {
            if (placement != WindowPlacement.Fullscreen) {
                windowedPlacement = placement.asWindowedPlacement()
            }
            isFullscreen = true
            placement = WindowPlacement.Fullscreen
            return emptyList()
        }
        isFullscreen = false
        placement = windowPlacement
        windowedPlacement = windowPlacement.asWindowedPlacement()
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
                awaitingMaximizedPublish = false
                reenterAfterExit = false
                isFullscreen = true
                placement = WindowPlacement.Fullscreen
                listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen))
            }
        }
    }

    private fun enter(
        frameMaximized: Boolean,
        statePlacement: WindowPlacement,
    ): List<FullscreenCommand> = when (platform) {
        FullscreenPlatform.Windows -> {
            windowsWasMaximized = frameMaximized
            isFullscreen = true
            listOf(FullscreenCommand.WindowsBorderless(enter = true, wasMaximized = frameMaximized))
        }
        FullscreenPlatform.Linux -> enterPlacementFullscreen(enforceLinux = true)
        FullscreenPlatform.Compose -> enterPlacementFullscreen(enforceLinux = false)
        FullscreenPlatform.Macos -> publishFullscreen(statePlacement)
    }

    private fun publishFullscreen(appliedPlacement: WindowPlacement): List<FullscreenCommand> {
        val remembered = when {
            pendingTarget == WindowPlacement.Maximized -> WindowPlacement.Maximized
            placement != WindowPlacement.Fullscreen -> placement.asWindowedPlacement()
            else -> windowedPlacement
        }
        pendingTarget = null
        reenterAfterExit = false
        awaitingMaximizedPublish = false
        awaitingFullscreen = appliedPlacement != WindowPlacement.Fullscreen
        windowedPlacement = remembered.asWindowedPlacement()
        isFullscreen = true
        placement = WindowPlacement.Fullscreen
        return listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen))
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
            reenterAfterExit = false
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

/**
 * Player Esc sends 1 and the fullscreen button sends 0. Both are one toggle.
 */
internal fun commandsForPlayerFullscreenToggle(
    owner: DesktopFullscreenOwner,
    value: Double,
    statePlacement: WindowPlacement,
): List<FullscreenCommand> {
    require(value == 0.0 || value == 1.0)
    return owner.toggle(statePlacement = statePlacement)
}

internal fun DesktopHostOs.toFullscreenPlatform(): FullscreenPlatform = when (this) {
    DesktopHostOs.MACOS -> FullscreenPlatform.Macos
    DesktopHostOs.WINDOWS -> FullscreenPlatform.Windows
    DesktopHostOs.LINUX -> FullscreenPlatform.Linux
    DesktopHostOs.UNKNOWN -> FullscreenPlatform.Compose
}
