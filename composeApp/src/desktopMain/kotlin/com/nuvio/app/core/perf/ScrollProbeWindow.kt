package com.nuvio.app.core.perf

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.AppEnvironment
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.catalog.CatalogScreen
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.search.DiscoverUiState
import com.nuvio.app.features.search.discoverContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val ProbeProperty = "nuvio.desktop.scrollProbe"
private const val ProbeFileProperty = "nuvio.desktop.scrollProbeFile"
private const val HomeRowCount = 8
private const val PostersPerRow = 20
private const val CatalogItemCount = 72
private const val DiscoverItemCount = 60
private const val DiscoverColumns = 6
private const val ScrollDistance = 1400f
private const val ScrollDurationMillis = 1200

internal fun runScrollProbe() {
    val output = File(System.getProperty(ProbeFileProperty) ?: "scroll-probe.txt")
    Thread {
        Thread.sleep(90_000)
        output.parentFile?.mkdirs()
        output.writeText("error=timeout\n")
        exitProcess(2)
    }.apply {
        isDaemon = true
        name = "nuvio-scroll-probe-watchdog"
        start()
    }
    if (System.getProperty("os.name", "").lowercase().contains("linux")) {
        runCatching { NativePlayerBridge.initGtkEarly() }
    }
    val posters = writePosterFixtures(HomeRowCount * PostersPerRow + CatalogItemCount + DiscoverItemCount)
    ScrollProbeCounters.enabled = true
    application {
        Window(
            onCloseRequest = { exitProcess(0) },
            title = "Nuvio",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            AppEnvironment {
                ScrollProbeHost(
                    posters = posters,
                    onReport = { report ->
                        output.parentFile?.mkdirs()
                        output.writeText(report)
                        exitProcess(0)
                    },
                )
            }
        }
    }
}

internal fun scrollProbeRequested(): Boolean =
    System.getProperty(ProbeProperty) == "true"

@Composable
private fun ScrollProbeHost(
    posters: List<String>,
    onReport: (String) -> Unit,
) {
    val homeRows = remember(posters) { homeRows(posters) }
    val catalogItems = remember(posters) {
        postersItems(
            posters = posters.subList(HomeRowCount * PostersPerRow, HomeRowCount * PostersPerRow + CatalogItemCount),
            count = CatalogItemCount,
            prefix = "catalog",
        )
    }
    val discoverItems = remember(posters) {
        val start = HomeRowCount * PostersPerRow + CatalogItemCount
        postersItems(
            posters = posters.subList(start, start + DiscoverItemCount),
            count = DiscoverItemCount,
            prefix = "discover",
        )
    }
    val homeListState = rememberLazyListState()
    val shelfState = rememberLazyListState()
    val catalogGridState = rememberLazyGridState()
    val discoverListState = rememberLazyListState()
    val catalogTarget = remember {
        CatalogTarget.Addon(
            manifestUrl = "fixture-catalog",
            contentType = "series",
            catalogId = "fixture",
        )
    }
    val phase = remember { mutableStateOf(ScrollProbePhase.Home) }

    when (phase.value) {
        ScrollProbePhase.Home -> {
            HomeScrollFixture(
                rows = homeRows,
                listState = homeListState,
                shelfState = shelfState,
            )
        }
        ScrollProbePhase.Catalog -> {
            remember(catalogItems) {
                CatalogRepository.installProbePage(catalogTarget, catalogItems)
                true
            }
            CatalogScreen(
                title = "Fixture",
                subtitle = "Series",
                target = catalogTarget,
                onBack = {},
                modifier = Modifier.fillMaxSize(),
                probeGridState = catalogGridState,
            )
        }
        ScrollProbePhase.Discover -> {
            DiscoverScrollFixture(
                items = discoverItems,
                listState = discoverListState,
            )
        }
    }

    LaunchedEffect(homeRows, catalogItems, discoverItems) {
        val homeShelf = scrollPhase(
            state = shelfState,
            firstRow = { shelfState.layoutInfo.visibleItemsInfo.isNotEmpty() },
            offset = { shelfState.probeOffset() },
        )
        val homeVertical = scrollPhase(
            state = homeListState,
            firstRow = { homeListState.layoutInfo.visibleItemsInfo.isNotEmpty() },
            offset = { homeListState.probeOffset() },
        )
        phase.value = ScrollProbePhase.Catalog
        val catalog = scrollPhase(
            state = catalogGridState,
            firstRow = { catalogGridState.layoutInfo.visibleItemsInfo.isNotEmpty() },
            offset = { catalogGridState.probeOffset() },
        )
        phase.value = ScrollProbePhase.Discover
        val discover = scrollPhase(
            state = discoverListState,
            firstRow = { discoverListState.layoutInfo.visibleItemsInfo.isNotEmpty() },
            offset = { discoverListState.probeOffset() },
        )
        onReport(
            buildString {
                appendLine(formatPhase("home_vertical", homeVertical))
                appendLine(formatPhase("home_shelf", homeShelf))
                appendLine(formatPhase("catalog_grid", catalog))
                appendLine(formatPhase("discover_grid", discover))
            },
        )
    }
}

private enum class ScrollProbePhase {
    Home,
    Catalog,
    Discover,
}

@Composable
private fun HomeScrollFixture(
    rows: List<HomeCatalogSection>,
    listState: LazyListState,
    shelfState: LazyListState,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(rows.size) { index ->
            val section = rows[index]
            if (index == 0) {
                HomeCatalogRowSection(
                    section = section,
                    sectionPadding = 24.dp,
                    rowState = shelfState,
                )
            } else {
                HomeCatalogRowSection(
                    section = section,
                    sectionPadding = 24.dp,
                )
            }
        }
    }
}

@Composable
private fun DiscoverScrollFixture(
    items: List<MetaPreview>,
    listState: LazyListState,
) {
    val state = remember(items) {
        DiscoverUiState(items = items)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        discoverContent(
            state = state,
            isSourceLoading = false,
            columns = DiscoverColumns,
            networkCondition = NetworkCondition.Unknown,
            onTypeSelected = {},
            onCatalogSelected = {},
            onGenreSelected = {},
        )
    }
}

private class PhaseResult(
    val firstRowMs: Long,
    val frames: List<Long>,
    val posterCompositions: Int,
    val traveledPx: Int,
)

private fun LazyListState.probeOffset(): Int =
    firstVisibleItemIndex * 1_000_000 + firstVisibleItemScrollOffset

private fun LazyGridState.probeOffset(): Int =
    firstVisibleItemIndex * 1_000_000 + firstVisibleItemScrollOffset

private suspend fun scrollPhase(
    state: androidx.compose.foundation.gestures.ScrollableState,
    firstRow: () -> Boolean,
    offset: () -> Int,
): PhaseResult {
    ScrollProbeCounters.reset()
    val started = System.nanoTime()
    snapshotFlow { firstRow() }.first { visible -> visible }
    withFrameNanos { }
    val firstRowMs = (System.nanoTime() - started) / 1_000_000L
    repeat(2) { withFrameNanos { } }
    val before = offset()
    val frames = recordScrollFrames {
        state.animateScrollBy(
            ScrollDistance,
            tween(durationMillis = ScrollDurationMillis, easing = LinearEasing),
        )
    }
    return PhaseResult(
        firstRowMs = firstRowMs,
        frames = frames,
        posterCompositions = ScrollProbeCounters.posterCompositions,
        traveledPx = offset() - before,
    )
}

private suspend fun recordScrollFrames(scroll: suspend () -> Unit): List<Long> = coroutineScope {
    val frames = mutableListOf<Long>()
    val finished = CompletableDeferred<Unit>()
    val reader = launch {
        var previous = 0L
        while (!finished.isCompleted) {
            withFrameNanos { now ->
                if (previous != 0L) frames.add(now - previous)
                previous = now
            }
        }
    }
    try {
        scroll()
    } finally {
        finished.complete(Unit)
        withFrameNanos { }
        reader.cancel()
        reader.join()
    }
    frames.drop(1)
}

private fun formatPhase(name: String, result: PhaseResult): String {
    val stats = scrollFrameStats(result.frames)
    return buildString {
        append(name)
        append("_first_row_ms=")
        append(result.firstRowMs)
        append(' ')
        append(name)
        append("_median_ms=")
        append(stats.medianNanos / 1_000_000L)
        append(' ')
        append(name)
        append("_p95_ms=")
        append(stats.p95Nanos / 1_000_000L)
        append(' ')
        append(name)
        append("_dropped=")
        append(stats.droppedFrames)
        append(' ')
        append(name)
        append("_frames=")
        append(stats.frameCount)
        append(' ')
        append(name)
        append("_poster_compositions=")
        append(result.posterCompositions)
        append(' ')
        append(name)
        append("_traveled_px=")
        append(result.traveledPx)
        append(' ')
        append(name)
        append("_raw_ns=")
        append(result.frames.joinToString(","))
    }
}

private fun homeRows(posters: List<String>): List<HomeCatalogSection> =
    List(HomeRowCount) { row ->
        HomeCatalogSection(
            key = "row-$row",
            title = "Row $row",
            subtitle = "Fixture",
            addonName = "Fixture",
            target = CatalogTarget.Addon(
                manifestUrl = "fixture-catalog",
                contentType = "series",
                catalogId = "row-$row",
            ),
            items = postersItems(
                posters = posters.subList(row * PostersPerRow, (row + 1) * PostersPerRow),
                count = PostersPerRow,
                prefix = "row-$row",
            ),
        )
    }

private fun postersItems(posters: List<String>, count: Int, prefix: String): List<MetaPreview> =
    List(count) { index ->
        val poster = posters[index]
        MetaPreview(
            id = "$prefix-$index",
            type = "series",
            name = "Show $index",
            poster = poster,
            releaseInfo = "2020",
        )
    }

private fun writePosterFixtures(count: Int): List<String> {
    val directory = File(System.getProperty("java.io.tmpdir"), "nuvio-scroll-probe-posters")
    directory.mkdirs()
    return List(count) { index ->
        val file = File(directory, "poster-$index.jpg")
        if (!file.isFile || file.length() == 0L) {
            val image = BufferedImage(280, 420, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color((20 + index * 12) % 256, (48 + index * 7) % 256, (96 + index * 5) % 256)
            graphics.fillRect(0, 0, 280, 420)
            graphics.color = Color((210 + index) % 256, (170 + index * 3) % 256, (90 + index * 11) % 256)
            graphics.fillRoundRect(36, 48, 208, 300, 24, 24)
            graphics.dispose()
            ImageIO.write(image, "jpg", file)
        }
        file.toURI().toString()
    }
}
