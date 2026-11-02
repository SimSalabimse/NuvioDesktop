package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncControlVisibilityTest {
    @Test
    fun autoSyncControlIsNotShownWhenAudioCaptureIsUnsupported() {
        assertFalse(isAutoSyncControlShown(supportsAutoSyncAudioCapture = false))
        assertTrue(isAutoSyncControlShown(supportsAutoSyncAudioCapture = true))
    }
}
