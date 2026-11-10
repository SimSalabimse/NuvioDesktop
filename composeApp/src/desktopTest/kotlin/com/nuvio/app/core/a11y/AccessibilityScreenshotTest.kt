package com.nuvio.app.core.a11y

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.features.details.CatalogRead
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.SeasonEpisodeRef
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.features.details.components.SeasonOverviewContent
import com.nuvio.app.features.details.seasonOverviewRows
import com.nuvio.app.features.player.PlayPauseControlButton
import com.nuvio.app.features.player.PlayerLayoutMetrics
import com.nuvio.app.features.player.skip.SkipMark
import com.nuvio.app.features.settings.SettingsSwitchRow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccessibilityScreenshotTest {
    @Test
    fun focusRingAndLargeTextAtNormalAndLargeWindows() {
        val shots = listOf(
            Shot("normal", 1280, 720, Density(1f, fontScale = 1f), 1f),
            Shot("large", 1920, 1080, Density(1.35f, fontScale = 1f), 1.18f),
            Shot("large-text", 1280, 720, Density(1f, fontScale = 1.5f), 1f),
        )
        shots.forEach { shot ->
            DesktopComposeUiTest(
                width = shot.width,
                height = shot.height,
                density = shot.density,
                useStandardTestDispatcherForComposition = false,
            ).runTest {
                fun capture(name: String, content: @Composable () -> Unit) {
                    setContent {
                        NuvioTheme(darkTheme = true, appTheme = com.nuvio.app.core.ui.AppTheme.WHITE, desktopUiScale = shot.uiScale) {
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
                    assertEquals(shot.width, image.width)
                    assertEquals(shot.height, image.height)
                    val directory = File("build/reports/a11y").apply { mkdirs() }
                    ImageIO.write(image, "png", File(directory, "$name-${shot.name}.png"))
                }

                capture("focus-before") { FocusBefore() }
                capture("focus-after") {
                    PlayPauseControlButton(
                        isPlaying = true,
                        isBuffering = false,
                        metrics = metrics(),
                        onClick = {},
                        modifier = Modifier.padding(24.dp),
                    )
                }
                onNode(hasContentDescription("Pause")).requestFocus()
                waitForIdle()
                val focused = captureToImage(onNodeWithTag("shot").fetchSemanticsNode()).toAwtImage()
                ImageIO.write(focused, "png", File("build/reports/a11y").resolve("focus-after-${shot.name}.png"))

                capture("poster-before") { PosterTitleBefore() }
                capture("poster-after") { PosterTitleAfter() }
                if (shot.name == "large-text") {
                    assertPosterWordsStayWhole(onNodeWithTag("shot").fetchSemanticsNode())
                }
                capture("season") { SeasonOverviewContent(sampleRows(), Modifier.width(640.dp).padding(24.dp)) }
                capture("settings") { SettingsSample() }
                capture("details-header") { HeaderSample() }
                capture("downloads") { DownloadsSample() }

                val oneLine = onNodeWithTag("download-one-line").fetchSemanticsNode().boundsInRoot
                val wrapped = onNodeWithTag("download-title").fetchSemanticsNode().boundsInRoot
                val header = onNodeWithTag("shot").fetchSemanticsNode().boundsInRoot
                assertTrue(oneLine.top >= 0f && wrapped.bottom <= header.height)
                if (shot.name == "large-text") {
                    assertTrue(wrapped.height > oneLine.height * 1.4f, "Download title did not wrap")
                } else {
                    assertTrue(wrapped.height < oneLine.height * 1.4f, "Download title wrapped at ${shot.name}")
                }
            }
        }
        listOf("normal", "large", "large-text").forEach { size ->
            val before = File("build/reports/a11y/focus-before-$size.png").readBytes()
            val after = File("build/reports/a11y/focus-after-$size.png").readBytes()
            assertFalse(before.contentEquals(after), "Focus ring matched the old button at $size")
            val posterBefore = File("build/reports/a11y/poster-before-$size.png").readBytes()
            val posterAfter = File("build/reports/a11y/poster-after-$size.png").readBytes()
            assertFalse(posterBefore.contentEquals(posterAfter), "Poster title matched at $size")
        }
    }
}

private data class Shot(
    val name: String,
    val width: Int,
    val height: Int,
    val density: Density,
    val uiScale: Float,
)

private const val LongTitle = "The Extraordinary Adventures of a Very Long Programme Title"
private val PosterWords = LongTitle.split(" ").toSet()

private fun assertPosterWordsStayWhole(node: SemanticsNode) {
    val texts = mutableListOf<String>()
    fun walk(current: SemanticsNode) {
        current.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
        current.children.forEach(::walk)
    }
    walk(node)
    assertTrue(texts.isNotEmpty(), "Poster title was not in the semantics tree")
    texts.forEach { block ->
        val flat = block.replace("\n", " ")
        if (!flat.contains(LongTitle)) {
            assertTrue(
                block.lines().last().trimEnd().endsWith("…"),
                "Truncated poster title missed the ellipsis: '$block'",
            )
        }
        block.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "…" }.forEach { token ->
            val stem = token.removeSuffix("…")
            val whole = token in PosterWords || (token.endsWith("…") && PosterWords.any { it.startsWith(stem) && stem.isNotEmpty() })
            assertTrue(whole, "Poster title broke '$token' out of '$block'")
        }
    }
}

@Composable
private fun FocusBefore() {
    Box(
        Modifier.padding(24.dp).clip(CircleShape).testTag("play-before"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = appIconPainter(AppIconResource.PlayerPause),
            contentDescription = "Pause",
            tint = Color.White,
            modifier = Modifier.padding(18.dp).size(44.dp),
        )
    }
}

@Composable
private fun PosterTitleBefore() {
    Text(
        text = LongTitle,
        modifier = Modifier.width(176.dp).padding(24.dp).testTag("poster-before-title"),
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PosterTitleAfter() {
    NuvioPosterCard(
        title = LongTitle,
        imageUrl = null,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun SettingsSample() {
    Column(Modifier.width(520.dp).padding(24.dp)) {
        SettingsSwitchRow(
            title = "Reduce motion",
            description = "Play fades and poster hovers immediately. This follows the system setting until you change it.",
            checked = false,
            isTablet = true,
            onCheckedChange = {},
        )
    }
}

@Composable
private fun HeaderSample() {
    DetailFloatingHeader(
        meta = MetaDetails(id = "fixture-show", type = "series", name = LongTitle),
        isSaved = false,
        progressProvider = { 1f },
        interactive = false,
        onBack = {},
        onToggleSaved = {},
    )
}

@Composable
private fun DownloadsSample() {
    Column(Modifier.width(360.dp).padding(24.dp)) {
        Text(
            text = LongTitle,
            modifier = Modifier.testTag("download-one-line"),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = LongTitle,
            modifier = Modifier.testTag("download-title"),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = largeTextLineLimit(),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun sampleRows() = seasonOverviewRows(
    episodes = listOf(
        SeasonEpisodeRef(1, 1, LongTitle, "S1 E1"),
        SeasonEpisodeRef(1, 2, "Harbour lights along the wall", "S1 E2"),
    ),
    marksFor = { episode ->
        if (episode.episode == 1) mapOf("intro" to SkipMark(0L, 10_000L)) else emptyMap()
    },
    publicFor = { episode ->
        if (episode.episode == 2) CatalogRead.Listed to listOf("intro", "outro") else CatalogRead.NotChecked to emptyList()
    },
)

private fun metrics() = PlayerLayoutMetrics(
    horizontalPadding = 24.dp,
    verticalPadding = 20.dp,
    titleSize = 24.sp,
    episodeInfoSize = 15.sp,
    metadataSize = 13.sp,
    centerGap = 88.dp,
    centerLift = 18.dp,
    sliderBottomOffset = 24.dp,
    sliderTouchHeight = 26.dp,
    sliderScaleY = 0.72f,
    timeSize = 14.sp,
    headerIconSize = 24.dp,
    sideButtonPadding = 14.dp,
    sideIconSize = 34.dp,
    playButtonPadding = 18.dp,
    playIconSize = 44.dp,
)
