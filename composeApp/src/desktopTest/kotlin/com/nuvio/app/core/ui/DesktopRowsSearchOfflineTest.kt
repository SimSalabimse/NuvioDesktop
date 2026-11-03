package com.nuvio.app.core.ui

import androidx.compose.ui.unit.dp
import com.nuvio.app.features.settings.SettingsRowPointerChrome
import com.nuvio.app.features.settings.settingsRowPointerChrome
import com.nuvio.app.features.settings.settingsSearchFieldInHeader
import com.nuvio.app.features.settings.settingsSearchListShowsField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRowsSearchOfflineTest {
    @Test
    fun verticalWheelScrollsAHorizontalRowUntilTheRowCannotMove() {
        val forward = rowWheelScroll(
            scrollX = 0f,
            scrollY = 3f,
            shiftPressed = false,
            canScrollBackward = true,
            canScrollForward = true,
        )
        assertEquals(3f, forward.delta)
        assertTrue(forward.consume)

        val atEnd = rowWheelScroll(
            scrollX = 0f,
            scrollY = 3f,
            shiftPressed = false,
            canScrollBackward = true,
            canScrollForward = false,
        )
        assertEquals(0f, atEnd.delta)
        assertFalse(atEnd.consume)

        val backward = rowWheelScroll(
            scrollX = 0f,
            scrollY = -2f,
            shiftPressed = false,
            canScrollBackward = false,
            canScrollForward = true,
        )
        assertEquals(0f, backward.delta)
        assertFalse(backward.consume)
    }

    @Test
    fun shiftWheelAndTrackpadHorizontalDeltaScrollTheRow() {
        assertEquals(4f, mapRowWheelToHorizontal(scrollX = 4f, scrollY = 9f, shiftPressed = true))
        assertEquals(9f, mapRowWheelToHorizontal(scrollX = 0f, scrollY = 9f, shiftPressed = true))
        assertEquals(5f, mapRowWheelToHorizontal(scrollX = 5f, scrollY = 1f, shiftPressed = false))
        assertEquals(-6f, mapRowWheelToHorizontal(scrollX = -1f, scrollY = -6f, shiftPressed = false))
        assertEquals(0f, mapRowWheelToHorizontal(scrollX = 0f, scrollY = 0f, shiftPressed = false))
    }

    @Test
    fun settingsSearchStaysInTheDesktopRootHeader() {
        assertTrue(settingsSearchFieldInHeader(desktop = true, rootPage = true))
        assertFalse(settingsSearchFieldInHeader(desktop = true, rootPage = false))
        assertFalse(settingsSearchFieldInHeader(desktop = false, rootPage = true))
        assertFalse(
            settingsSearchListShowsField(
                revealedByPull = true,
                queryPresent = true,
                fieldInHeader = true,
            ),
        )
        assertTrue(
            settingsSearchListShowsField(
                revealedByPull = false,
                queryPresent = true,
                fieldInHeader = false,
            ),
        )
        assertFalse(
            settingsSearchListShowsField(
                revealedByPull = false,
                queryPresent = false,
                fieldInHeader = false,
            ),
        )
    }

    @Test
    fun offlineCardUsesTheDialogWidthOnDesktop() {
        assertEquals(460.dp, cappedOfflineCardWidth(800.dp, 460.dp, desktop = true))
        assertEquals(300.dp, cappedOfflineCardWidth(300.dp, 460.dp, desktop = true))
        assertEquals(800.dp, cappedOfflineCardWidth(800.dp, 460.dp, desktop = false))
    }

    @Test
    fun settingsRowsShowHoverAndFocusOnlyOnDesktop() {
        assertEquals(
            SettingsRowPointerChrome.Hover,
            settingsRowPointerChrome(desktop = true, enabled = true, hovered = true, focused = false),
        )
        assertEquals(
            SettingsRowPointerChrome.Focus,
            settingsRowPointerChrome(desktop = true, enabled = true, hovered = true, focused = true),
        )
        assertEquals(
            SettingsRowPointerChrome.None,
            settingsRowPointerChrome(desktop = true, enabled = false, hovered = true, focused = true),
        )
        assertEquals(
            SettingsRowPointerChrome.None,
            settingsRowPointerChrome(desktop = false, enabled = true, hovered = true, focused = true),
        )
    }
}
