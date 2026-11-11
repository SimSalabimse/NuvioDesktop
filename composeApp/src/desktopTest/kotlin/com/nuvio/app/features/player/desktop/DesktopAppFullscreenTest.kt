package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppFullscreenTest {
    @Test
    fun `native fullscreen exit updates placement instead of leaving the session fullscreen`() {
        val owner = macOwner(WindowPlacement.Floating)
        enterMac(owner)

        val request = owner.toggle()
        assertEquals(listOf(FullscreenCommand.RequestMacosNativeExit), request)
        assertFalse(owner.isFullscreen)

        val published = owner.onMacosNativeExitResult(accepted = true)

        assertEquals(emptyList(), published)
        assertFalse(owner.isFullscreen)
        assertEquals(WindowPlacement.Floating, owner.placement)
        assertFalse(owner.isAwaitingMaximizedRestore)
        assertNoPlacementConflict(published)
    }

    @Test
    fun `player escape exits through the same owner as the fullscreen toggle`() {
        val owner = macOwner(WindowPlacement.Floating)
        enterMac(owner)

        val request = owner.toggle()
        owner.onMacosNativeExitResult(accepted = true)

        assertEquals(listOf(FullscreenCommand.RequestMacosNativeExit), request)
        assertFalse(owner.isFullscreen)
        assertEquals(WindowPlacement.Floating, owner.placement)
    }

    @Test
    fun `fallback publishes one floating placement and maximizes only after fullscreen clears`() {
        val owner = macOwner(WindowPlacement.Maximized)
        enterMac(owner)
        assertEquals(WindowPlacement.Maximized, owner.windowedPlacement)

        owner.toggle()
        val fallback = owner.onMacosNativeExitResult(accepted = false)

        assertEquals(listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Floating)), fallback)
        assertEquals(WindowPlacement.Floating, owner.placement)
        assertFalse(owner.isFullscreen)
        assertTrue(owner.isAwaitingMaximizedRestore)
        assertNoPlacementConflict(fallback)

        val maximized = owner.onPlacementObserved(WindowPlacement.Floating)
        assertEquals(listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Maximized)), maximized)
        assertEquals(WindowPlacement.Maximized, owner.placement)
        assertEquals(emptyList(), owner.onPlacementObserved(WindowPlacement.Maximized))
    }

    @Test
    fun `native exit to maximized waits for the window to leave fullscreen before publishing it`() {
        val owner = macOwner(WindowPlacement.Maximized)
        enterMac(owner)
        owner.toggle()

        val native = owner.onMacosNativeExitResult(accepted = true)

        assertEquals(emptyList(), native)
        assertEquals(WindowPlacement.Maximized, owner.placement)
        assertFalse(owner.isFullscreen)
        assertTrue(owner.isAwaitingMaximizedRestore)
        assertEquals(emptyList(), owner.onPlacementObserved(WindowPlacement.Fullscreen))
        assertFalse(owner.isFullscreen)

        assertEquals(
            listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Maximized)),
            owner.onPlacementObserved(WindowPlacement.Floating),
        )
        assertEquals(WindowPlacement.Maximized, owner.placement)
        assertFalse(owner.isAwaitingMaximizedRestore)
    }

    @Test
    fun `fullscreen cannot be restored as its own exit placement`() {
        val owner = macOwner(
            placement = WindowPlacement.Fullscreen,
            windowed = WindowPlacement.Fullscreen,
        )

        assertEquals(WindowPlacement.Floating, owner.windowedPlacement)
        owner.toggle()
        val fallback = owner.onMacosNativeExitResult(accepted = false)

        assertEquals(listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Floating)), fallback)
        assertEquals(WindowPlacement.Floating, owner.placement)
        assertFalse(owner.isFullscreen)
    }

    @Test
    fun `a second toggle after a native exit enters fullscreen again`() {
        val owner = macOwner(WindowPlacement.Floating)
        enterMac(owner)
        owner.toggle()
        owner.onMacosNativeExitResult(accepted = true)
        assertFalse(owner.isFullscreen)

        assertEquals(
            listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen)),
            owner.toggle(),
        )
        assertTrue(owner.isFullscreen)
        assertEquals(emptyList(), owner.onPlacementObserved(WindowPlacement.Floating))
        assertTrue(owner.isFullscreen)
        assertEquals(emptyList(), owner.onPlacementObserved(WindowPlacement.Fullscreen))
    }

    @Test
    fun `green button and the system menu update fullscreen without a second placement write`() {
        val floating = macOwner(WindowPlacement.Floating)
        assertEquals(emptyList(), floating.onPlacementObserved(WindowPlacement.Fullscreen))
        assertTrue(floating.isFullscreen)
        assertEquals(WindowPlacement.Floating, floating.windowedPlacement)

        assertEquals(emptyList(), floating.onPlacementObserved(WindowPlacement.Floating))
        assertFalse(floating.isFullscreen)
        assertEquals(WindowPlacement.Floating, floating.placement)

        val maximized = macOwner(WindowPlacement.Maximized)
        assertEquals(emptyList(), maximized.onPlacementObserved(WindowPlacement.Fullscreen))
        assertEquals(WindowPlacement.Maximized, maximized.windowedPlacement)
        maximized.toggle()
        maximized.onMacosNativeExitResult(accepted = true)
        assertEquals(
            listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Maximized)),
            maximized.onPlacementObserved(WindowPlacement.Floating),
        )
    }

    @Test
    fun `a restored fullscreen session remembers the windowed placement for the later exit`() {
        val macosWindowed = windowedPlacementAfterFullscreenRestore(
            wasMaximized = true,
            hasSavedGeometry = true,
            restoresMaximizedWindowPlacement = false,
        )
        val macos = DesktopFullscreenOwner(
            platform = FullscreenPlatform.Macos,
            initialPlacement = WindowPlacement.Fullscreen,
            initialWindowedPlacement = macosWindowed,
        )
        assertEquals(
            listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen)),
            macos.restoreSession(fullscreen = true, windowedPlacement = macosWindowed),
        )
        macos.toggle()
        macos.onMacosNativeExitResult(accepted = true)
        assertFalse(macos.isFullscreen)
        assertEquals(WindowPlacement.Floating, macos.placement)
        assertEquals(emptyList(), macos.onPlacementObserved(WindowPlacement.Floating))

        val linuxWindowed = windowedPlacementAfterFullscreenRestore(
            wasMaximized = true,
            hasSavedGeometry = true,
            restoresMaximizedWindowPlacement = true,
        )
        val linux = DesktopFullscreenOwner(
            platform = FullscreenPlatform.Linux,
            initialPlacement = WindowPlacement.Fullscreen,
            initialWindowedPlacement = linuxWindowed,
        )
        assertEquals(
            listOf(
                FullscreenCommand.PublishPlacement(WindowPlacement.Maximized),
                FullscreenCommand.EnforceLinuxDeviceFullscreen,
            ),
            linux.toggle(),
        )
        assertEquals(WindowPlacement.Maximized, linux.placement)
        assertFalse(linux.isFullscreen)
        assertFalse(linux.isAwaitingMaximizedRestore)
    }

    @Test
    fun `a restored fullscreen session returns to maximized when that was the windowed mode`() {
        assertEquals(
            WindowPlacement.Maximized,
            windowedPlacementAfterFullscreenRestore(
                wasMaximized = true,
                hasSavedGeometry = true,
                restoresMaximizedWindowPlacement = true,
            ),
        )
        assertEquals(
            WindowPlacement.Floating,
            windowedPlacementAfterFullscreenRestore(
                wasMaximized = false,
                hasSavedGeometry = true,
                restoresMaximizedWindowPlacement = true,
            ),
        )
        assertEquals(
            WindowPlacement.Maximized,
            windowedPlacementAfterFullscreenRestore(
                wasMaximized = false,
                hasSavedGeometry = false,
                restoresMaximizedWindowPlacement = true,
            ),
        )
        assertEquals(
            WindowPlacement.Floating,
            windowedPlacementAfterFullscreenRestore(
                wasMaximized = true,
                hasSavedGeometry = true,
                restoresMaximizedWindowPlacement = false,
            ),
        )
    }

    @Test
    fun `windows and linux fullscreen commands stay on their existing paths`() {
        val windowsFloating = DesktopFullscreenOwner(
            platform = FullscreenPlatform.Windows,
            initialPlacement = WindowPlacement.Floating,
        )
        assertEquals(
            listOf(FullscreenCommand.WindowsBorderless(enter = true, wasMaximized = false)),
            windowsFloating.toggle(frameMaximized = false),
        )
        assertTrue(windowsFloating.isFullscreen)
        assertEquals(WindowPlacement.Floating, windowsFloating.placement)
        assertEquals(
            listOf(FullscreenCommand.WindowsBorderless(enter = false, wasMaximized = false)),
            windowsFloating.toggle(),
        )
        assertFalse(windowsFloating.isFullscreen)
        assertEquals(WindowPlacement.Floating, windowsFloating.placement)
        assertFalse(windowsFloating.isAwaitingMaximizedRestore)

        val windowsMaximized = DesktopFullscreenOwner(
            platform = FullscreenPlatform.Windows,
            initialPlacement = WindowPlacement.Maximized,
        )
        assertEquals(
            listOf(FullscreenCommand.WindowsBorderless(enter = true, wasMaximized = true)),
            windowsMaximized.toggle(frameMaximized = true),
        )
        assertEquals(WindowPlacement.Maximized, windowsMaximized.placement)
        assertEquals(
            listOf(FullscreenCommand.WindowsBorderless(enter = false, wasMaximized = true)),
            windowsMaximized.toggle(),
        )
        assertEquals(WindowPlacement.Maximized, windowsMaximized.placement)
        assertEquals(
            listOf(FullscreenCommand.WindowsBorderless(enter = true, wasMaximized = true)),
            windowsMaximized.restoreSession(
                fullscreen = true,
                windowedPlacement = WindowPlacement.Maximized,
                frameMaximized = true,
            ),
        )

        val linux = DesktopFullscreenOwner(
            platform = FullscreenPlatform.Linux,
            initialPlacement = WindowPlacement.Maximized,
        )
        assertEquals(
            listOf(
                FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen),
                FullscreenCommand.EnforceLinuxDeviceFullscreen,
            ),
            linux.toggle(),
        )
        assertEquals(
            listOf(
                FullscreenCommand.PublishPlacement(WindowPlacement.Maximized),
                FullscreenCommand.EnforceLinuxDeviceFullscreen,
            ),
            linux.toggle(),
        )
        assertEquals(WindowPlacement.Maximized, linux.placement)
        assertFalse(linux.isAwaitingMaximizedRestore)
        assertEquals(
            listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen)),
            linux.restoreSession(
                fullscreen = true,
                windowedPlacement = WindowPlacement.Maximized,
            ),
        )
    }

    private fun macOwner(
        placement: WindowPlacement,
        windowed: WindowPlacement = placement.asWindowedPlacement(),
    ) = DesktopFullscreenOwner(
        platform = FullscreenPlatform.Macos,
        initialPlacement = placement,
        initialWindowedPlacement = windowed,
    )

    private fun enterMac(owner: DesktopFullscreenOwner) {
        assertEquals(
            listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen)),
            owner.toggle(),
        )
        assertTrue(owner.isFullscreen)
        assertEquals(emptyList(), owner.onPlacementObserved(WindowPlacement.Fullscreen))
    }

    private fun assertNoPlacementConflict(commands: List<FullscreenCommand>) {
        val placements = commands.mapNotNull { command ->
            (command as? FullscreenCommand.PublishPlacement)?.placement
        }
        assertTrue(placements.size <= 1)
        assertFalse(placements.contains(WindowPlacement.Floating) && placements.contains(WindowPlacement.Maximized))
        assertTrue(commands.none { it is FullscreenCommand.RequestMacosNativeExit })
    }
}
