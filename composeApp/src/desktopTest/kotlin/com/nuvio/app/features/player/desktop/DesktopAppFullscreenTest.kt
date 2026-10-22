package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAppFullscreenTest {
    @Test
    fun `native fullscreen exit to Floating updates state immediately`() {
        val updates = mutableListOf<String>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Floating,
            requestNativeFullscreenExit = {
                updates += "native"
                true
            },
            setStatePlacement = { updates += "state:$it" },
        )

        assertEquals(listOf("native", "state:Floating"), updates)
    }

    @Test
    fun `native fullscreen exit to Maximized sets Floating first then Maximized async`() {
        val updates = mutableListOf<String>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Maximized,
            requestNativeFullscreenExit = {
                updates += "native"
                true
            },
            setStatePlacement = { placement ->
                updates += "state:$placement"
            },
        )

        assertEquals(listOf("native", "state:Floating"), updates)
    }

    @Test
    fun `native request failure still updates WindowState to target placement`() {
        val updates = mutableListOf<String>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Maximized,
            requestNativeFullscreenExit = {
                updates += "native"
                false
            },
            setStatePlacement = { updates += "state:$it" },
        )

        assertEquals(listOf("native", "state:Maximized"), updates)
    }

    @Test
    fun `fullscreen cannot be restored as its own exit placement`() {
        val updates = mutableListOf<String>()

        applyMacosComposeFullscreenExit(
            restorePlacement = WindowPlacement.Fullscreen,
            requestNativeFullscreenExit = {
                updates += "native"
                false
            },
            setStatePlacement = { updates += "state:$it" },
        )

        assertEquals(listOf("native", "state:Floating"), updates)
    }
}
