package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val escape = macOwner(WindowPlacement.Floating)
        val button = macOwner(WindowPlacement.Floating)
        enterMac(escape)
        enterMac(button)

        val escapeCommands = commandsForPlayerFullscreenToggle(
            owner = escape,
            value = 1.0,
            statePlacement = WindowPlacement.Fullscreen,
        )
        val buttonCommands = commandsForPlayerFullscreenToggle(
            owner = button,
            value = 0.0,
            statePlacement = WindowPlacement.Fullscreen,
        )

        assertEquals(escapeCommands, buttonCommands)
        assertEquals(listOf(FullscreenCommand.RequestMacosNativeExit), escapeCommands)
        escape.onMacosNativeExitResult(accepted = true)
        button.onMacosNativeExitResult(accepted = true)
        assertFalse(escape.isFullscreen)
        assertFalse(button.isFullscreen)
        assertEquals(WindowPlacement.Floating, escape.placement)
        assertEquals(button.placement, escape.placement)
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

        assertEquals(
            emptyList(),
            owner.onPlacementObserved(
                observed = WindowPlacement.Floating,
                windowPlacement = WindowPlacement.Fullscreen,
            ),
        )
        assertEquals(WindowPlacement.Floating, owner.placement)
        assertFalse(owner.isFullscreen)
        assertTrue(owner.isAwaitingMaximizedRestore)

        val maximized = owner.onPlacementObserved(
            observed = WindowPlacement.Floating,
            windowPlacement = WindowPlacement.Floating,
        )
        assertEquals(listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Maximized)), maximized)
        assertEquals(WindowPlacement.Maximized, owner.placement)
        assertTrue(owner.isAwaitingMaximizedRestore)
        assertEquals(
            emptyList(),
            owner.onPlacementObserved(
                observed = WindowPlacement.Maximized,
                windowPlacement = WindowPlacement.Maximized,
            ),
        )
        assertFalse(owner.isAwaitingMaximizedRestore)
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
        assertTrue(owner.isAwaitingMaximizedRestore)
        assertEquals(emptyList(), owner.onPlacementObserved(WindowPlacement.Maximized))
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
    fun `a second toggle during native exit waits until the window leaves fullscreen`() {
        val owner = macOwner(WindowPlacement.Floating)
        enterMac(owner)
        owner.toggle()
        owner.onMacosNativeExitResult(accepted = true)
        assertFalse(owner.isFullscreen)
        assertEquals(WindowPlacement.Floating, owner.placement)

        assertEquals(
            emptyList(),
            owner.toggle(statePlacement = WindowPlacement.Fullscreen),
        )
        assertFalse(owner.isFullscreen)
        assertEquals(WindowPlacement.Floating, owner.placement)

        val entered = owner.onPlacementObserved(
            observed = WindowPlacement.Floating,
            windowPlacement = WindowPlacement.Floating,
        )
        assertEquals(listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Fullscreen)), entered)
        assertTrue(owner.isFullscreen)
        assertEquals(emptyList(), owner.onPlacementObserved(WindowPlacement.Fullscreen))
        assertTrue(owner.isFullscreen)
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
        assertFalse(maximized.isFullscreen)
        assertEquals(WindowPlacement.Maximized, maximized.placement)
        assertEquals(
            listOf(FullscreenCommand.PublishPlacement(WindowPlacement.Maximized)),
            maximized.onPlacementObserved(WindowPlacement.Floating),
        )
        assertFalse(maximized.isFullscreen)
        assertEquals(WindowPlacement.Maximized, maximized.placement)
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

    @Test
    fun `controller second toggle during native exit enters only after the window leaves fullscreen`() {
        val harness = MacWindowHarness(initial = WindowPlacement.Floating, nativeExit = { true })
        harness.use {
            it.enter()
            val exit = it.controller.toggle(it.frame, it.state)
            assertEquals(listOf(FullscreenCommand.RequestMacosNativeExit), exit)
            assertFalse(it.controller.isFullscreen(it.frame, it.state))
            assertEquals(WindowPlacement.Fullscreen, it.state.placement)

            val again = it.controller.toggleFromPlayer(it.frame, it.state, value = 1.0)
            assertEquals(emptyList(), again)
            assertFalse(it.controller.isFullscreen(it.frame, it.state))
            assertEquals(WindowPlacement.Fullscreen, it.state.placement)

            it.state.placement = WindowPlacement.Floating
            it.windowPlacement = WindowPlacement.Floating
            it.controller.onWindowPlacementChanged(
                it.frame,
                it.state,
                observedState = it.state.placement,
                observedWindow = it.windowPlacement,
            )
            assertEquals(WindowPlacement.Fullscreen, it.state.placement)
            assertTrue(it.controller.isFullscreen(it.frame, it.state))
        }
    }

    @Test
    fun `player escape and the fullscreen button issue the same controller commands`() {
        val escape = MacWindowHarness(initial = WindowPlacement.Floating, nativeExit = { true })
        val button = MacWindowHarness(initial = WindowPlacement.Floating, nativeExit = { true })
        escape.use {
            button.use {
                escape.enter()
                button.enter()
                val escapeCommands = escape.controller.toggleFromPlayer(escape.frame, escape.state, value = 1.0)
                val buttonCommands = button.controller.toggleFromPlayer(button.frame, button.state, value = 0.0)
                assertEquals(escapeCommands, buttonCommands)
                assertEquals(listOf(FullscreenCommand.RequestMacosNativeExit), escapeCommands)
                assertFalse(escape.controller.isFullscreen(escape.frame, escape.state))
                assertFalse(button.controller.isFullscreen(button.frame, button.state))
                assertEquals(WindowPlacement.Fullscreen, escape.state.placement)
                assertEquals(escape.state.placement, button.state.placement)
            }
        }
    }

    @Test
    fun `fallback floating echo does not restore maximized or persist that frame`() {
        val harness = MacWindowHarness(initial = WindowPlacement.Maximized, nativeExit = { false })
        harness.use {
            it.enter()
            it.controller.toggle(it.frame, it.state)
            assertEquals(WindowPlacement.Floating, it.state.placement)
            assertTrue(it.controller.isAwaitingMaximizedRestore())
            assertFalse(it.controller.isFullscreen(it.frame, it.state))

            val geometryAtApply = shouldPersistWindowedGeometry(
                WindowedGeometryPersistSample(
                    placement = it.state.placement,
                    fullscreen = it.controller.isFullscreen(it.frame, it.state),
                    awaitingMaximizedRestore = it.controller.isAwaitingMaximizedRestore(),
                ),
            )
            assertFalse(geometryAtApply)

            it.windowPlacement = WindowPlacement.Fullscreen
            it.controller.onWindowPlacementChanged(
                it.frame,
                it.state,
                observedState = it.state.placement,
                observedWindow = it.windowPlacement,
            )
            assertEquals(WindowPlacement.Floating, it.state.placement)
            assertTrue(it.controller.isAwaitingMaximizedRestore())
            assertFalse(it.controller.isFullscreen(it.frame, it.state))
            assertFalse(
                shouldPersistWindowedGeometry(
                    WindowedGeometryPersistSample(
                        placement = it.state.placement,
                        fullscreen = it.controller.isFullscreen(it.frame, it.state),
                        awaitingMaximizedRestore = it.controller.isAwaitingMaximizedRestore(),
                    ),
                ),
            )

            it.windowPlacement = WindowPlacement.Floating
            it.controller.onWindowPlacementChanged(
                it.frame,
                it.state,
                observedState = WindowPlacement.Floating,
                observedWindow = it.windowPlacement,
            )
            assertEquals(WindowPlacement.Maximized, it.state.placement)
            assertTrue(it.controller.isAwaitingMaximizedRestore())
            assertFalse(geometryAtApply)

            it.windowPlacement = WindowPlacement.Maximized
            it.controller.onWindowPlacementChanged(
                it.frame,
                it.state,
                observedState = WindowPlacement.Maximized,
                observedWindow = it.windowPlacement,
            )
            assertFalse(it.controller.isAwaitingMaximizedRestore())
            assertEquals(WindowPlacement.Maximized, it.state.placement)
        }
    }

    @Test
    fun `a failed windows enter is retried by the next toggle`() {
        val frame = Frame()
        val state = WindowState(placement = WindowPlacement.Floating)
        var enters = 0
        val controller = DesktopAppFullscreenController(
            initialPlacement = WindowPlacement.Floating,
            platform = FullscreenPlatform.Windows,
            windowsEnter = { _, _, wasMaximized ->
                enters += 1
                assertFalse(wasMaximized)
                throw IllegalStateException("no peer")
            },
        )
        try {
            assertFailsWith<IllegalStateException> { controller.toggle(frame, state) }
            assertFalse(controller.isFullscreen(frame, state))
            assertFailsWith<IllegalStateException> { controller.toggle(frame, state) }
            assertEquals(2, enters)
            assertFalse(controller.isFullscreen(frame, state))
        } finally {
            frame.dispose()
        }
    }

    private class MacWindowHarness(
        initial: WindowPlacement,
        nativeExit: (java.awt.Window) -> Boolean,
    ) : AutoCloseable {
        var windowPlacement: WindowPlacement = initial
        val frame: Frame = Frame()
        val state: WindowState = WindowState(placement = initial)
        val controller = DesktopAppFullscreenController(
            initialPlacement = initial,
            platform = FullscreenPlatform.Macos,
            nativeExit = nativeExit,
            readWindowPlacement = { _, _ -> windowPlacement },
        )

        fun enter() {
            controller.toggle(frame, state)
            windowPlacement = WindowPlacement.Fullscreen
            controller.onWindowPlacementChanged(frame, state, state.placement, windowPlacement)
        }

        override fun close() {
            frame.dispose()
        }
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
