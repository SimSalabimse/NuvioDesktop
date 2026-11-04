package com.nuvio.app.features.updater

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class VersionUtilsTest {
    @Test
    fun `stable release is newer than its prerelease`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0", "1.1.0-rc.2"))
    }

    @Test
    fun `beta identifiers use numeric ordering`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.10", "1.1.0-beta.9"))
    }

    @Test
    fun `prerelease for a later minor is newer than current stable`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.1", "1.0.3"))
    }

    @Test
    fun `stable channel patch is not newer than later minor beta`() {
        assertFalse(VersionUtils.isRemoteNewer("1.0.4", "1.1.0-beta.2"))
    }

    @Test
    fun `version prefix and build metadata do not affect precedence`() {
        assertFalse(VersionUtils.isRemoteNewer("v1.0.0+18", "1.0.0+17"))
    }

    @Test
    fun `invalid remote version is not offered`() {
        assertFalse(VersionUtils.isRemoteNewer("latest", "1.0.0"))
    }

    @Test
    fun `legacy comparison accepts changed release labels`() {
        assertTrue(VersionUtils.isRemoteNewerLegacy("desktop-preview", "desktop-alpha"))
        assertFalse(VersionUtils.isRemoteNewerLegacy("desktop-preview", "desktop-preview"))
        assertFalse(VersionUtils.isRemoteNewerLegacy("", "desktop-preview"))
    }

    @Test
    fun `current beta naming is recognized as prerelease`() {
        assertTrue(VersionUtils.isPrerelease("0.8.12-beta"))
    }

    @Test
    fun `upstream three part version is not newer than a four part fork build`() {
        assertFalse(
            VersionUtils.isRemoteNewer("0.1.34", "0.1.30.3-alpha.1-submit-step1"),
        )
        assertFalse(
            VersionUtils.isRemoteNewer("v0.1.34", "0.1.30.3-alpha"),
        )
    }

    @Test
    fun `fourth numeric part can be strictly newer than the current alpha`() {
        assertTrue(VersionUtils.isRemoteNewer("0.1.30.4", "0.1.30.3-alpha"))
        assertTrue(VersionUtils.isRemoteNewer("0.1.30.4-alpha", "0.1.30.3-alpha.1-submit-step1"))
    }

    @Test
    fun `equal versions are not an update`() {
        assertFalse(VersionUtils.isRemoteNewer("0.1.30.3-alpha", "0.1.30.3-alpha"))
        assertFalse(VersionUtils.isRemoteNewer("0.1.30.4", "0.1.30.4"))
        assertFalse(VersionUtils.isRemoteNewer("1.1.0", "1.1.0"))
    }

    @Test
    fun `alpha and submit suffixes parse without crashing`() {
        val installed = VersionUtils.parse("0.1.30.3-alpha.1-submit-step1")
        assertEquals(listOf(0L, 1L, 30L, 3L), installed?.numbers)
        assertEquals(listOf("alpha", "1-submit-step1"), installed?.prerelease)
        assertEquals(listOf("alpha"), VersionUtils.parse("0.1.30.3-alpha")?.prerelease)
        assertEquals(null, VersionUtils.parse("-alpha"))
        assertEquals(null, VersionUtils.parse("-submit-step1"))
        listOf(
            "-alpha",
            "-submit-step1",
            "0.1.30.3-alpha",
            "0.1.30.3-alpha.1-submit-step1",
            "v0.1.30.3-submit-step1",
        ).forEach { tag ->
            VersionUtils.parse(tag)
            VersionUtils.isRemoteNewer(tag, "0.1.30.3-alpha.1-submit-step1")
            VersionUtils.isRemoteNewer("0.1.30.4", tag)
        }
    }
}
