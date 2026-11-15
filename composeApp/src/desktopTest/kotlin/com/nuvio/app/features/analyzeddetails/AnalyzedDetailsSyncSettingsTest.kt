package com.nuvio.app.features.analyzeddetails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AnalyzedDetailsSyncSettingsTest {
    @Test
    fun settingsShowsCreateAndJoinBeforeASpaceExists() {
        DesktopComposeUiTest(
            width = 960,
            height = 1200,
            useStandardTestDispatcherForComposition = false,
        ).runTest {
            setContent {
                NuvioTheme(darkTheme = true) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF101418))
                            .verticalScroll(rememberScrollState())
                            .testTag("sync-settings"),
                    ) {
                        Box(Modifier.padding(24.dp)) {
                            AnalyzedDetailsSyncSettingsPage(isTablet = false)
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Create a space", substring = false).fetchSemanticsNode()
            onNodeWithText("Join a space", substring = false).fetchSemanticsNode()
            onNodeWithText("Enroll secret", substring = false).fetchSemanticsNode()
            onNodeWithText("What syncs", substring = false).fetchSemanticsNode()
            onNodeWithText("Fingerprints stay on this computer", substring = true).fetchSemanticsNode()
            onNodeWithText("https://nuvio-sync.tullebukktil1000.workers.dev", substring = false).fetchSemanticsNode()
            onNodeWithText("Create", substring = false).fetchSemanticsNode()
            onNodeWithText("Join", substring = false).fetchSemanticsNode()
            assertTrue(onAllNodesWithText("Server URL", substring = false).fetchSemanticsNodes().size == 2)
            assertTrue(onAllNodesWithText("Passphrase", substring = false).fetchSemanticsNodes().size == 2)
            val image = captureToImage(onNodeWithTag("sync-settings").fetchSemanticsNode()).toAwtImage()
            val directory = File("build/reports/sync").apply { mkdirs() }
            val file = File(directory, "sync-settings-signed-out.png")
            ImageIO.write(image, "png", file)
            assertTrue(file.length() > 0L)
            setContent {
                NuvioTheme(darkTheme = true) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF101418))
                            .verticalScroll(rememberScrollState())
                            .testTag("sync-joined"),
                    ) {
                        Box(Modifier.padding(24.dp)) {
                            AnalyzedDetailsSyncSettingsPage(
                                isTablet = false,
                                preview = SyncSession(
                                    baseUrl = "https://nuvio-sync.tullebukktil1000.workers.dev",
                                    spaceId = "11111111-1111-4111-8111-111111111111",
                                    saltBase64 = "AAECAwQFBgcICQoLDA0ODw==",
                                    keyCheckBase64 = "gxGlP4cq0kTOQIzx2+gq7w==",
                                    deviceId = "desktop-example",
                                    lastSuccessfulSyncMs = 1_700_000_000_000,
                                    secretStoreKind = "encrypted-file",
                                    statusText = "Synced.",
                                    joined = true,
                                ),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Synced.", substring = false).fetchSemanticsNode()
            onNodeWithText("Sync now", substring = false).fetchSemanticsNode()
            assertTrue(onAllNodesWithText("Leave", substring = false).fetchSemanticsNodes().size == 2)
            onNodeWithText("2023", substring = true).fetchSemanticsNode()
            val joined = captureToImage(onNodeWithTag("sync-joined").fetchSemanticsNode()).toAwtImage()
            ImageIO.write(joined, "png", File(directory, "sync-settings-joined.png"))
        }
    }
}
