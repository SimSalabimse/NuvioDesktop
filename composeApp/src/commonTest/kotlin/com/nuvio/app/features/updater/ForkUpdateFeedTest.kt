package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkUpdateFeedTest {
    private val installed = "0.1.30.3-alpha.1-submit-step1"
    private val dmgSelector = AppUpdateAssetSelector(fileExtensions = listOf(".dmg"))

    @Test
    fun releaseChecksUseOnlyThisForksPublicReleases() {
        val url = ForkUpdateFeed.requestUrl(DesktopUpdateChannel.RELEASE)
        assertEquals(ForkUpdateFeed.RELEASES_URL, url)
        assertEquals(
            "https://api.github.com/repos/SimSalabimse/NuvioDesktop/releases?per_page=100",
            url,
        )
        assertFalse(url!!.contains("NuvioMedia"))
        assertFalse(url.contains("releases/latest"))
        val headers = ForkUpdateFeed.requestHeaders("NuvioDesktop")
        assertEquals(setOf("Accept", "User-Agent"), headers.keys)
        assertFalse(headers.keys.any { it.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun upstreamThreePartReleaseIsNotOffered() {
        val body = releaseList(
            tag = "0.1.34",
            assetName = "Nuvio-macOS-arm64-0.1.34.dmg",
            assetUrl = "https://github.com/NuvioMedia/NuvioDesktop/releases/download/0.1.34/Nuvio.dmg",
        )
        assertNull(AppUpdaterRepository.selectForkUpdate(body, installed, dmgSelector))
        assertFalse(
            ForkUpdateFeed.isForkAssetUrl(
                "https://github.com/NuvioMedia/NuvioDesktop/releases/download/0.1.34/Nuvio.dmg",
            ),
        )
    }

    @Test
    fun olderOrEqualForkBuildsAreNotOffered() {
        val older = releaseList(
            tag = "0.1.30.2-alpha",
            assetName = "Nuvio.dmg",
            assetUrl = forkAsset("0.1.30.2-alpha"),
        )
        val same = releaseList(
            tag = "0.1.30.3-alpha.1-submit-step1",
            assetName = "Nuvio.dmg",
            assetUrl = forkAsset("0.1.30.3-alpha.1-submit-step1"),
        )
        assertNull(AppUpdaterRepository.selectForkUpdate(older, installed, dmgSelector))
        assertNull(AppUpdaterRepository.selectForkUpdate(same, installed, dmgSelector))
    }

    @Test
    fun strictlyNewerForkBuildIsOffered() {
        val body = """[
            ${releaseObject("0.1.30.3-alpha", "Nuvio-old.dmg", forkAsset("0.1.30.3-alpha"))},
            ${releaseObject("0.1.30.4", "Nuvio-macOS-arm64.dmg", forkAsset("0.1.30.4"))},
            ${releaseObject("0.1.34", "upstream.dmg", "https://github.com/NuvioMedia/NuvioDesktop/releases/download/0.1.34/Nuvio.dmg")}
        ]"""
        val update = AppUpdaterRepository.selectForkUpdate(body, "0.1.30.3-alpha", dmgSelector)
        assertEquals("0.1.30.4", update?.tag)
        assertEquals(forkAsset("0.1.30.4"), update?.assetUrl)
        assertTrue(update?.assetUrl.orEmpty().contains("SimSalabimse/NuvioDesktop"))
        assertFalse(update?.assetUrl.orEmpty().contains("NuvioMedia"))
    }

    @Test
    fun emptyFeedOrMissingAssetsReportsNothing() {
        assertNull(AppUpdaterRepository.selectForkUpdate("[]", installed, dmgSelector))
        assertNull(AppUpdaterRepository.selectForkUpdate("", installed, dmgSelector))
        assertNull(
            AppUpdaterRepository.selectForkUpdate(
                """[{"tag_name":"0.1.30.4","assets":[]}]""",
                "0.1.30.3-alpha",
                dmgSelector,
            ),
        )
        assertNull(
            AppUpdaterRepository.selectForkUpdate(
                """[{"tag_name":"not-a-version","assets":[{"name":"Nuvio.dmg","browser_download_url":"${forkAsset("x")}"}]}]""",
                installed,
                dmgSelector,
            ),
        )
    }

    @Test
    fun suffixesOnForkTagsDoNotCrashSelection() {
        val body = releaseList(
            tag = "0.1.30.4-alpha.1-submit-step1",
            assetName = "Nuvio.dmg",
            assetUrl = forkAsset("0.1.30.4-alpha.1-submit-step1"),
        )
        assertEquals(
            "0.1.30.4-alpha.1-submit-step1",
            AppUpdaterRepository.selectForkUpdate(body, "0.1.30.3-alpha", dmgSelector)?.tag,
        )
    }

    private fun forkAsset(tag: String): String =
        "https://github.com/SimSalabimse/NuvioDesktop/releases/download/$tag/Nuvio.dmg"

    private fun releaseObject(tag: String, assetName: String, assetUrl: String): String =
        """{"tag_name":"$tag","name":"$tag","assets":[{"name":"$assetName","browser_download_url":"$assetUrl"}]}"""

    private fun releaseList(tag: String, assetName: String, assetUrl: String): String =
        "[${releaseObject(tag, assetName, assetUrl)}]"
}
