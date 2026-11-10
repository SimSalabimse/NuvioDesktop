package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.CatalogRead
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.SeasonEpisodeRef
import com.nuvio.app.features.details.components.CatalogStatusLines
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.features.details.components.SeasonOverviewContent
import com.nuvio.app.features.details.ownCatalogLine
import com.nuvio.app.features.details.publicCatalogLine
import com.nuvio.app.features.details.seasonOverviewRows
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.downloadSkipStatusLines
import com.nuvio.app.features.player.skip.SkipMark
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Renders each polish surface before and after, at a normal window and at a larger
 * window with a larger density. The large scene is a real bigger pixel buffer.
 */
@OptIn(ExperimentalTestApi::class)
class UiPolishScreenshotTest {
    @Test
    fun surfacesDifferAtALargerDensityAndWindow() {
        val shots = listOf(
            WindowShot(name = "normal", width = 1280, height = 720, density = 1f, uiScale = 1f),
            WindowShot(name = "large", width = 1920, height = 1080, density = 1.35f, uiScale = 1.18f),
        )
        val titleHeights = mutableMapOf<String, Float>()
        shots.forEach { shot ->
            titleHeights[shot.name] = render(shot)
        }
        assertTrue(
            titleHeights.getValue("large") > titleHeights.getValue("normal") * 1.25f,
            "Large title height ${titleHeights["large"]} should be a larger text scale than ${titleHeights["normal"]}",
        )
        listOf("hero-fade", "details-rest", "details-scrolled", "introdb", "downloads-skip", "season-overview").forEach { item ->
            shots.forEach { shot ->
                val before = png("before", item, shot.name).readBytes()
                val after = png("after", item, shot.name).readBytes()
                assertFalse(before.contentEquals(after), "$item ${shot.name} before matched after")
            }
            val normal = png("after", item, "normal").readBytes()
            val large = png("after", item, "large").readBytes()
            assertFalse(normal.contentEquals(large), "$item large matched normal")
        }
    }

    private fun render(shot: WindowShot): Float {
        var titleHeight = 0f
        DesktopComposeUiTest(
            width = shot.width,
            height = shot.height,
            density = Density(shot.density),
            useStandardTestDispatcherForComposition = false,
        ).runTest {
            fun capture(phase: String, item: String, content: @Composable () -> Unit) {
                setContent {
                    NuvioTheme(darkTheme = true, appTheme = AppTheme.WHITE, desktopUiScale = shot.uiScale) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF101418))
                                .testTag("shot"),
                        ) {
                            content()
                        }
                    }
                }
                waitForIdle()
                val image = captureToImage(onNodeWithTag("shot").fetchSemanticsNode()).toAwtImage()
                assertEquals(shot.width, image.width, "$item ${shot.name} width")
                assertEquals(shot.height, image.height, "$item ${shot.name} height")
                val directory = File("build/reports/ui-polish/$phase").apply { mkdirs() }
                ImageIO.write(image, "png", File(directory, "$item-${shot.name}.png"))
            }

            capture("before", "hero-fade") { HeavyTopBand() }
            capture("after", "hero-fade") { QuietTopFade() }
            capture("before", "details-rest") { DetailsArtwork {} }
            capture("after", "details-rest") { DetailsArtwork { RestingHeader() } }
            capture("before", "details-scrolled") { DetailsArtwork { SolidScrolledBar() } }
            capture("after", "details-scrolled") { DetailsArtwork { ScrolledHeader() } }
            capture("before", "introdb") { IntroDbBefore() }
            capture("after", "introdb") { IntroDbAfter() }
            capture("before", "downloads-skip") { DownloadsBefore() }
            capture("after", "downloads-skip") { DownloadsAfter() }
            capture("before", "season-overview") { PlainSeasonList() }
            capture("after", "season-overview") { SeasonOverviewContent(sampleRows(), Modifier.fillMaxWidth()) }

            val rows = listOf(1 to 1, 1 to 2, 2 to 1)
            rows.forEach { (season, episode) ->
                val title = onNodeWithTag("season-title-$season-$episode").fetchSemanticsNode().boundsInRoot
                val number = onNodeWithTag("season-number-$season-$episode").fetchSemanticsNode().boundsInRoot
                val publicChip = onNodeWithTag("season-chip-public-$season-$episode").fetchSemanticsNode().boundsInRoot
                val ownChip = onNodeWithTag("season-chip-own-$season-$episode").fetchSemanticsNode().boundsInRoot
                assertInside(title, shot)
                assertInside(number, shot)
                assertInside(publicChip, shot)
                assertInside(ownChip, shot)
                assertSeparated(title, publicChip, "title/public $season-$episode")
                assertSeparated(title, ownChip, "title/own $season-$episode")
                assertSeparated(number, title, "number/title $season-$episode")
                assertSeparated(publicChip, ownChip, "chips $season-$episode")
                assertTrue(publicChip.top + 0.5f >= title.bottom, "public chip overlaps the title $season-$episode")
            }
            onNodeWithTag("season-section-1").fetchSemanticsNode()
            onNodeWithTag("season-section-2").fetchSemanticsNode()
            titleHeight = onNodeWithTag("season-title-1-1").fetchSemanticsNode().boundsInRoot.height
        }
        return titleHeight
    }

    private fun png(phase: String, item: String, size: String): File =
        File("build/reports/ui-polish/$phase/$item-$size.png")

    private fun assertInside(bounds: androidx.compose.ui.geometry.Rect, shot: WindowShot) {
        assertTrue(bounds.left >= -0.5f && bounds.top >= -0.5f, "origin outside $bounds")
        assertTrue(bounds.right <= shot.width + 0.5f, "clipped on the right $bounds in ${shot.width}")
        assertTrue(bounds.bottom <= shot.height + 0.5f, "clipped on the bottom $bounds in ${shot.height}")
    }

    private fun assertSeparated(first: androidx.compose.ui.geometry.Rect, second: androidx.compose.ui.geometry.Rect, label: String) {
        val separated = first.right <= second.left + 0.5f ||
            second.right <= first.left + 0.5f ||
            first.bottom <= second.top + 0.5f ||
            second.bottom <= first.top + 0.5f
        assertTrue(separated, "$label overlap $first $second")
    }
}

private data class WindowShot(
    val name: String,
    val width: Int,
    val height: Int,
    val density: Float,
    val uiScale: Float,
)

private val Artwork = Color(0xFF1A2744)
private val Scrim = Color(0xFF101418)

@Composable
private fun HeavyTopBand() {
    Box(Modifier.fillMaxSize().background(Artwork)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Scrim.copy(alpha = 0.92f), Color.Transparent),
                    ),
                ),
        )
        Text(
            text = "Northwater",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
        )
    }
}

@Composable
private fun QuietTopFade() {
    CompositionLocalProvider(LocalScrollEdgeInset provides 48.dp) {
        Box(Modifier.fillMaxSize().background(Artwork)) {
            QuietScrollEdge(
                safeTop = scrollEdgeInset(),
                contentTop = 220.dp,
                color = Scrim,
            )
            Text(
                text = "Northwater",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
            )
        }
    }
}

@Composable
private fun DetailsArtwork(header: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Artwork)) {
        Text(
            text = "Northwater",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 48.dp),
        )
        header()
    }
}

@Composable
private fun RestingHeader() {
    CompositionLocalProvider(LocalScrollEdgeInset provides 28.dp) {
        DetailFloatingHeader(
            meta = MetaDetails(id = "tt1", type = "movie", name = "Northwater"),
            isSaved = false,
            progressProvider = { 0f },
            interactive = false,
            onBack = {},
            onToggleSaved = {},
        )
    }
}

@Composable
private fun ScrolledHeader() {
    CompositionLocalProvider(LocalScrollEdgeInset provides 28.dp) {
        DetailFloatingHeader(
            meta = MetaDetails(id = "tt1", type = "movie", name = "Northwater"),
            isSaved = true,
            progressProvider = { 1f },
            interactive = true,
            onBack = {},
            onToggleSaved = {},
        )
    }
}

@Composable
private fun SolidScrolledBar() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Northwater",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun IntroDbBefore() {
    Column(Modifier.padding(24.dp)) {
        Text(text = "TheIntroDB", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(text = "Public · not checked", color = Color(0xFFB7C0C8), style = MaterialTheme.typography.labelLarge)
        Text(text = "Yours · Not checked", color = Color(0xFFB7C0C8), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun IntroDbAfter() {
    Column(Modifier.padding(24.dp)) {
        Text(text = "TheIntroDB", color = Color.White, style = MaterialTheme.typography.titleMedium)
        CatalogStatusLines(
            publicLine = publicCatalogLine(CatalogRead.NotChecked, emptyList()),
            ownLine = ownCatalogLine(CatalogRead.NotChecked, hasOwnChips = false),
        )
    }
}

@Composable
private fun DownloadsBefore() {
    Column(Modifier.padding(24.dp)) {
        Text(text = "Cold open", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Intro: You",
            color = Color(0xFFB7C0C8),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun DownloadsAfter() {
    val lines = downloadSkipStatusLines(sampleDownload())
    Column(Modifier.padding(24.dp)) {
        Text(text = "Cold open", color = Color.White, style = MaterialTheme.typography.titleMedium)
        CatalogStatusLines(publicLine = lines.publicLine, ownLine = lines.ownLine)
    }
}

@Composable
private fun PlainSeasonList() {
    Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
        Text(text = "Season 1", color = Color.White, style = MaterialTheme.typography.titleLarge)
        PlainEpisode("S1 E1", "Cold open", "Public · not checked", "Yours · Intro")
        PlainEpisode("S1 E2", "Harbour lights along the wall", "Public · Intro, Credits", "Yours · not submitted")
        Text(
            text = "Season 2",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        PlainEpisode("S2 E1", "Return", "Public · none", "Yours · Not checked")
    }
}

@Composable
private fun PlainEpisode(code: String, title: String, publicLine: String, ownLine: String) {
    Text(text = "$code  $title", color = Color.White, style = MaterialTheme.typography.titleMedium)
    Text(text = publicLine, color = Color(0xFFB7C0C8), style = MaterialTheme.typography.labelLarge)
    Text(text = ownLine, color = Color(0xFFB7C0C8), style = MaterialTheme.typography.labelLarge)
}

private fun sampleRows() = seasonOverviewRows(
    episodes = listOf(
        SeasonEpisodeRef(1, 1, "Cold open", "S1 E1"),
        SeasonEpisodeRef(1, 2, "Harbour lights along the wall", "S1 E2"),
        SeasonEpisodeRef(2, 1, "Return", "S2 E1"),
    ),
    marksFor = { episode ->
        if (episode.season == 1 && episode.episode == 1) mapOf("intro" to SkipMark(0L, 10_000L)) else emptyMap()
    },
    publicFor = { episode ->
        when {
            episode.season == 1 && episode.episode == 2 -> CatalogRead.Listed to listOf("intro", "outro")
            episode.season == 2 -> CatalogRead.None to emptyList()
            else -> CatalogRead.NotChecked to emptyList()
        }
    },
)

private fun sampleDownload() = DownloadItem(
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
