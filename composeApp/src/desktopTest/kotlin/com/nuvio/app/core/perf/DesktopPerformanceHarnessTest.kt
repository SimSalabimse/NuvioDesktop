package com.nuvio.app.core.perf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import coil3.Image
import coil3.memory.MemoryCache
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.CatalogRead
import com.nuvio.app.features.details.SeasonEpisodeRef
import com.nuvio.app.features.details.seasonOverviewRows
import com.nuvio.app.features.details.seasonOverviewSections
import com.nuvio.app.features.home.HomeCatalogParser
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.readGifDiskCache
import com.nuvio.app.features.home.components.writeGifDiskCache
import com.nuvio.app.features.player.skip.SkipMark
import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Canvas
import org.junit.Rule

/**
 * Headless timings. Warm up, then take [RUNS] samples and keep the median.
 * The result is also written under composeApp/build/reports/perf so a run can be archived.
 * This test does not assert a speed budget.
 */
class DesktopPerformanceHarnessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun measureDesktopHotPaths() {
        val catalogPayload = catalogPayload(itemCount = 200)
        val serverEntries = progressRecords(count = 4_000)
        val localEntries = progressEntries(count = 4_000)
        val dirtyKeys = serverEntries.take(400).map { it.progressKey }.toSet()
        val episodes = seasonEpisodes(seasons = 20, episodesPerSeason = 12)
        val marks = mapOf("op" to SkipMark(0L, 10_000L), "ed" to SkipMark(20_000L, 40_000L))
        val publicTypes = listOf("intro", "op", "credits")
        val imageCache = MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build()
        val imageKey = MemoryCache.Key("fixture-poster")
        imageCache[imageKey] = MemoryCache.Value(FixtureImage)
        val gifUrl = "fixture-gif-cache-hit"
        writeGifDiskCache(gifUrl, ByteArray(4096) { index -> (index and 0xff).toByte() })
        val heroItems = List(1) { index -> fixturePreview(index) }
        val rows = List(3) { section ->
            HomeCatalogSection(
                key = "row-$section",
                title = "Row $section",
                subtitle = "Fixture",
                addonName = "Fixture",
                target = CatalogTarget.Addon(
                    manifestUrl = "fixture-manifest",
                    contentType = "series",
                    catalogId = "fixture-$section",
                ),
                items = List(12) { item -> fixturePreview(section * 12 + item) },
            )
        }

        val lines = mutableListOf<String>()
        lines += timed("catalog_parse_200") {
            val parsed = HomeCatalogParser.parseCatalog(catalogPayload)
            check(parsed.size == 200)
        }
        lines += timed("watch_progress_merge_4000") {
            val merged = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
                serverEntries = serverEntries,
                localEntries = localEntries,
                dirtyProgressKeys = dirtyKeys,
            )
            check(merged.size == 4_000)
            check(merged["key-0"]?.lastPositionMs == 900L)
            check(merged["key-400"]?.lastPositionMs == 1_400L)
        }
        lines += timed("season_overview_20x12") {
            val sections = seasonOverviewSections(
                seasonOverviewRows(
                    episodes = episodes,
                    marksFor = { marks },
                    publicFor = { CatalogRead.Listed to publicTypes },
                ),
            )
            check(sections.size == 20)
        }
        lines += timed("image_memory_cache_hit_1000") {
            var hits = 0
            repeat(1_000) {
                if (imageCache[imageKey] != null) hits++
            }
            check(hits == 1_000)
        }
        lines += timed("image_disk_cache_hit_200") {
            var hits = 0
            repeat(200) {
                val bytes = readGifDiskCache(gifUrl)
                if (bytes != null && bytes.size == 4096) hits++
            }
            check(hits == 200)
        }
        lines += timed("home_first_frame_fixture") {
            compose.setContent {
                NuvioTheme {
                    Column(Modifier.fillMaxSize().testTag("home-frame")) {
                        HomeHeroSection(items = heroItems)
                        rows.forEach { section ->
                            HomeCatalogRowSection(section = section, sectionPadding = 24.dp)
                        }
                    }
                }
            }
            compose.onNodeWithTag("home-frame").assertExists()
        }

        val report = lines.joinToString(separator = "\n", postfix = "\n")
        val output = File(
            System.getProperty("nuvio.perf.output", "build/reports/perf/desktop-perf.txt"),
        )
        output.parentFile.mkdirs()
        output.writeText(report)
        println(report)
        assertEquals(6, lines.size)
        assertTrue(output.length() > 0L)
    }

    private fun timed(name: String, block: () -> Unit): String {
        repeat(WARMUP) { block() }
        val samples = LongArray(RUNS)
        repeat(RUNS) { index ->
            val started = System.nanoTime()
            block()
            samples[index] = System.nanoTime() - started
        }
        val ordered = samples.sorted()
        val median = ordered[RUNS / 2]
        return "metric=$name warmup=$WARMUP runs=$RUNS unit=ns median=$median samples=${samples.joinToString(",")}"
    }

    private fun catalogPayload(itemCount: Int): String = buildString {
        append("{\"metas\":[")
        repeat(itemCount) { index ->
            if (index > 0) append(',')
            append(
                """{"id":"fixture-$index","type":"series","name":"Show $index","poster":"fixture-poster-$index","background":"fixture-bg-$index","logo":"fixture-logo-$index","posterShape":"poster","description":"A fixture episode list for show $index.","releaseInfo":"2020","released":"2020-01-01","imdbRating":"7.2","genres":["Drama","Mystery"],"landscapePoster":"fixture-land-$index"}""",
            )
        }
        append("]}")
    }

    private fun progressRecords(count: Int): List<ProgressSyncRecord> =
        List(count) { index ->
            ProgressSyncRecord(
                contentId = "show-$index",
                contentType = "series",
                videoId = "show-$index:1:1",
                season = 1,
                episode = 1,
                position = 1000L + index,
                duration = 1_000_000L,
                lastWatched = 1_000L + index,
                progressKey = "key-$index",
            )
        }

    private fun progressEntries(count: Int): List<WatchProgressEntry> =
        List(count) { index ->
            WatchProgressEntry(
                contentType = "series",
                parentMetaId = "show-$index",
                parentMetaType = "series",
                videoId = "show-$index:1:1",
                title = "Show $index",
                seasonNumber = 1,
                episodeNumber = 1,
                lastPositionMs = 900L + index,
                durationMs = 1_000_000L,
                lastUpdatedEpochMs = 2_000L + index,
                progressKey = "key-$index",
            )
        }

    private fun seasonEpisodes(seasons: Int, episodesPerSeason: Int): List<SeasonEpisodeRef> =
        buildList {
            for (season in 1..seasons) {
                for (episode in 1..episodesPerSeason) {
                    add(SeasonEpisodeRef(season, episode, "Episode $episode", "S$season E$episode"))
                }
            }
        }

    private fun fixturePreview(index: Int) = MetaPreview(
        id = "fixture-$index",
        type = "series",
        name = "Show $index",
    )

    private object FixtureImage : Image {
        override val size: Long = 64L
        override val width: Int = 8
        override val height: Int = 8
        override val shareable: Boolean = true
        override fun draw(canvas: Canvas) = Unit
    }

    private companion object {
        const val WARMUP = 5
        const val RUNS = 5
    }
}
