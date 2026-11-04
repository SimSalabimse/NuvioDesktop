package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdateChannelTest {
    @Test
    fun unsetEnhancedBranchChecksForReleaseUpdates() {
        assertEquals(
            DesktopUpdateChannel.RELEASE,
            DesktopUpdateChannel.resolve(property = null, branch = "enhanced"),
        )
        assertTrue(DesktopUpdateChannel.checksEnabled(DesktopUpdateChannel.RELEASE))
    }

    @Test
    fun unsetNonEnhancedBranchIsIntegrationAndDoesNotCheck() {
        listOf("cursor/desktop-submit-step1", "pull/32", "HEAD", "", null).forEach { branch ->
            val channel = DesktopUpdateChannel.resolve(property = null, branch = branch)
            assertEquals(DesktopUpdateChannel.INTEGRATION, channel)
            assertFalse(DesktopUpdateChannel.checksEnabled(channel))
            assertNull(ForkUpdateFeed.requestUrl(channel))
        }
    }

    @Test
    fun explicitPropertyWinsOverTheBranch() {
        assertEquals(
            DesktopUpdateChannel.INTEGRATION,
            DesktopUpdateChannel.resolve(property = "integration", branch = "enhanced"),
        )
        assertEquals(
            DesktopUpdateChannel.RELEASE,
            DesktopUpdateChannel.resolve(property = " release ", branch = "some-pr"),
        )
        assertEquals(
            DesktopUpdateChannel.INTEGRATION,
            DesktopUpdateChannel.resolve(property = "nightly", branch = "enhanced"),
        )
    }

    @Test
    fun integrationChannelDoesNotContactAnyFeed() {
        val url = ForkUpdateFeed.requestUrl(DesktopUpdateChannel.INTEGRATION)
        assertNull(url)
        assertFalse(DesktopUpdateChannel.checksEnabled(DesktopUpdateChannel.INTEGRATION))
    }
}
