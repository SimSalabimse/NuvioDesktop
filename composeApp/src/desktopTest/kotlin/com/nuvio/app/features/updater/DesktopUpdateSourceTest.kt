package com.nuvio.app.features.updater

import com.nuvio.app.core.build.ForkRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdateSourceTest {
    @Test
    fun updatesComeFromThisForkAndAHigherUpstreamVersionIsNeverOffered() {
        val source = AppUpdaterPlatform.releaseSource
        assertEquals(ForkRepository.OWNER, source.owner)
        assertEquals(ForkRepository.REPO, source.repo)
        assertEquals("SimSalabimse", source.owner)
        assertEquals("NuvioDesktop", source.repo)

        val localVersion = "0.1.34.2"
        val upstream = releaseUpdate(
            tag = "9.9.9",
            assetUrl = "https://github.com/NuvioMedia/NuvioDesktop/releases/download/9.9.9/Nuvio.msi",
        )
        assertTrue(VersionUtils.isRemoteNewerLegacy(upstream.tag, localVersion))
        assertFalse(
            isUpdateOffered(
                update = upstream,
                source = source,
                localVersion = localVersion,
                legacyComparison = true,
            ),
        )

        val olderForkRelease = releaseUpdate(
            tag = "0.1.34.1",
            assetUrl = "https://github.com/SimSalabimse/NuvioDesktop/releases/download/0.1.34.1/Nuvio.msi",
        )
        assertFalse(
            isUpdateOffered(
                update = olderForkRelease,
                source = source,
                localVersion = localVersion,
                legacyComparison = true,
            ),
        )

        val newerForkRelease = releaseUpdate(
            tag = "0.1.35",
            assetUrl = "https://github.com/SimSalabimse/NuvioDesktop/releases/download/0.1.35/Nuvio.msi",
        )
        assertTrue(
            isUpdateOffered(
                update = newerForkRelease,
                source = source,
                localVersion = localVersion,
                legacyComparison = true,
            ),
        )
    }

    @Test
    fun listedSha256IsCheckedAndAMissingDigestIsNotInvented() {
        assertNull(parseListedSha256(null))
        assertNull(parseListedSha256("md5:abcd"))

        val file = File.createTempFile("nuvio-update", ".msi")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val actual = sha256Hex(file)
            assertTrue(verifyDownloadedUpdateFile(file, null).isSuccess)
            assertTrue(verifyDownloadedUpdateFile(file, "sha256:$actual").isSuccess)
            assertTrue(verifyDownloadedUpdateFile(file, "sha256:${actual.uppercase()}").isSuccess)
            assertTrue(verifyDownloadedUpdateFile(file, "sha256:" + "ab".repeat(32)).isFailure)
        } finally {
            file.delete()
        }
    }

    private fun releaseUpdate(tag: String, assetUrl: String) = AppUpdate(
        tag = tag,
        title = tag,
        notes = "",
        releaseUrl = null,
        assetName = assetUrl.substringAfterLast('/'),
        assetUrl = assetUrl,
        assetSizeBytes = null,
    )
}
