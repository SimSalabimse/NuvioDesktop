package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.CatalogRead
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.SeasonEpisodeRef
import com.nuvio.app.features.details.components.CatalogStatusLines
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.features.details.seasonOverviewRows
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.downloadSkipStatusLines
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.player.skip.SkipMark
import org.junit.Rule
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Renders the five polish surfaces at a normal window and a large window.
 * Images land in build/reports/ui-polish. The run copies them to artifacts.
 */
class UiPolishScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun heroAndDetailsAtTwoWindowSizes() {
        listOf("normal" to (1280 to 720), "large" to (1600 to 900)).forEach { (name, size) ->
            capture(name, size.first, size.second, "hero") {
                HomeHeroSection(
                    items = listOf(preview()),
                    viewportHeight = size.second.dp,
                    modifier = Modifier.size(size.first.dp, size.second.dp),
                )
            }
            capture(name, size.first, 280, "hero-fade") {
                CompositionLocalProvider(LocalScrollEdgeInset provides 48.dp) {
                    Box(Modifier.size(size.first.dp, 280.dp).background(Color(0xFF1A2744))) {
                        QuietScrollEdge(
                            safeTop = scrollEdgeInset(),
                            contentTop = 220.dp,
                            color = Color(0xFF101418),
                        )
                        Text(
                            text = "Northwater",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
                        )
                    }
                }
            }
            capture(name, size.first, 220, "season-overview") {
                val rows = seasonOverviewRows(
                    episodes = listOf(
                        SeasonEpisodeRef(1, 1, "Cold open", "S1 E1"),
                        SeasonEpisodeRef(1, 2, "Harbour", "S1 E2"),
                    ),
                    marksFor = { episode ->
                        if (episode.episode == 1) mapOf("intro" to SkipMark(0L, 10_000L)) else emptyMap()
                    },
                    publicFor = { episode ->
                        if (episode.episode == 2) {
                            CatalogRead.Listed to listOf("intro", "outro")
                        } else {
                            CatalogRead.NotChecked to emptyList()
                        }
                    },
                )
                Column(
                    Modifier
                        .size(size.first.dp, 220.dp)
                        .background(Color(0xFF101418))
                        .padding(16.dp),
                ) {
                    Text(text = "Season 1", color = Color.White)
                    rows.forEach { row ->
                        Text(text = "${row.code}  ${row.title}", color = Color.White)
                        CatalogStatusLines(publicLine = row.publicLine, ownLine = row.ownLine)
                    }
                }
            }
            capture(name, size.first, size.second, "details-rest") {
                CompositionLocalProvider(LocalScrollEdgeInset provides 28.dp) {
                Box(Modifier.size(size.first.dp, 160.dp).background(Color(0xFF1A2744))) {
                    DetailFloatingHeader(
                        meta = details(),
                        isSaved = false,
                        progressProvider = { 0f },
                        interactive = false,
                        onBack = {},
                        onToggleSaved = {},
                    )
                }
                }
            }
            capture(name, size.first, size.second, "details-scrolled") {
                CompositionLocalProvider(LocalScrollEdgeInset provides 28.dp) {
                Box(Modifier.size(size.first.dp, 160.dp).background(Color(0xFF1A2744))) {
                    DetailFloatingHeader(
                        meta = details(),
                        isSaved = true,
                        progressProvider = { 1f },
                        interactive = true,
                        onBack = {},
                        onToggleSaved = {},
                    )
                }
                }
            }
        }
    }

    @Test
    fun downloadsSkipLineAtTwoWindowSizes() {
        val item = DownloadItem(
            id = "d1",
            contentType = "series",
            parentMetaId = "tt1",
            parentMetaType = "series",
            videoId = "tt1:1:2",
            title = "Show",
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Cold open",
            streamTitle = "stream",
            providerName = "addon",
            sourceUrl = "https://example.invalid/stream",
            fileName = "e.mkv",
            status = DownloadStatus.Completed,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )
        val lines = downloadSkipStatusLines(item)
        listOf("normal" to 1280, "large" to 1600).forEach { (name, width) ->
            capture(name, width, 120, "downloads-skip") {
                Column(
                    Modifier
                        .size(width.dp, 120.dp)
                        .background(Color(0xFF101418))
                        .padding(16.dp)
                        .testTag("skip-lines"),
                ) {
                    Text(text = lines.publicLine, color = Color.White)
                    Text(text = lines.ownLine, color = Color.White)
                }
            }
        }
    }

    private fun capture(sizeName: String, width: Int, height: Int, item: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE) {
                Box(
                    Modifier
                        .size(width.dp, height.dp)
                        .background(Color(0xFF101418))
                        .testTag("shot"),
                ) {
                    content()
                }
            }
        }
        compose.waitForIdle()
        val directory = File("build/reports/ui-polish").apply { mkdirs() }
        ImageIO.write(
            compose.onNodeWithTag("shot").captureToImage().toAwtImage(),
            "png",
            File(directory, "$item-$sizeName.png"),
        )
    }

    private fun preview() = MetaPreview(id = "tt1", type = "movie", name = "Northwater", description = "A quiet harbour.")

    private fun details() = MetaDetails(id = "tt1", type = "movie", name = "Northwater")
}
