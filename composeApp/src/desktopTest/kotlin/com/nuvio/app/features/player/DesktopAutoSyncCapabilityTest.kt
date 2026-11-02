package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse

class DesktopAutoSyncCapabilityTest {
    @Test
    fun desktopHidesAutoSyncUntilAudioCaptureExists() {
        assertFalse(PlayerCapabilities.supportsAutoSyncAudioCapture)
        assertFalse(isAutoSyncControlShown(PlayerCapabilities.supportsAutoSyncAudioCapture))
    }
}
