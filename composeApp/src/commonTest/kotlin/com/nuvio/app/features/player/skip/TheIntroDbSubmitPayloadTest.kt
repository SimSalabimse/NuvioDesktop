package com.nuvio.app.features.player.skip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TheIntroDbSubmitPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun moviePayloadOmitsSeasonAndEpisode() {
        val body = theIntroDbSubmitBody(
            TheIntroDbSubmitDraft(
                tmdbId = 550,
                imdbId = "tt0137523",
                type = "movie",
                segment = "credits",
                season = 1,
                episode = 2,
                startSec = 5400.0,
                endSec = null,
                videoDurationMs = 7_200_000L,
            ),
        )
        val payload = json.parseToJsonElement(body!!).jsonObject
        assertEquals("movie", payload.getValue("type").jsonPrimitive.content)
        assertEquals("credits", payload.getValue("segment").jsonPrimitive.content)
        assertFalse(payload.containsKey("season"))
        assertFalse(payload.containsKey("episode"))
        assertEquals(5_400_000L, payload.getValue("start_ms").jsonPrimitive.long)
        assertFalse(payload.containsKey("end_ms"))
        assertEquals(7_200_000L, payload.getValue("video_duration_ms").jsonPrimitive.long)
    }

    @Test
    fun fromStartOmitsStartMs() {
        val body = theIntroDbSubmitBody(
            TheIntroDbSubmitDraft(
                tmdbId = 1396,
                imdbId = "tt0903747",
                type = "tv",
                segment = "intro",
                season = 1,
                episode = 1,
                startSec = null,
                endSec = 90.0,
                videoDurationMs = 3_600_000L,
            ),
        )
        val payload = json.parseToJsonElement(body!!).jsonObject
        assertFalse(payload.containsKey("start_ms"))
        assertEquals(90_000L, payload.getValue("end_ms").jsonPrimitive.long)
        assertEquals(1L, payload.getValue("season").jsonPrimitive.long)
        assertEquals(1L, payload.getValue("episode").jsonPrimitive.long)
    }

    @Test
    fun toEndOmitsEndMs() {
        val body = theIntroDbSubmitBody(
            TheIntroDbSubmitDraft(
                tmdbId = 1396,
                type = "tv",
                segment = "preview",
                season = 2,
                episode = 4,
                startSec = 2400.0,
                endSec = null,
                videoDurationMs = 2_700_000L,
            ),
        )
        val payload = json.parseToJsonElement(body!!).jsonObject
        assertEquals(2_400_000L, payload.getValue("start_ms").jsonPrimitive.long)
        assertFalse(payload.containsKey("end_ms"))
        assertFalse(payload.containsKey("imdb_id"))
    }

    @Test
    fun durationOutsideFiveMinutesToSixHoursIsOmitted() {
        assertFalse(payload(videoDurationMs = 299_999L).containsKey("video_duration_ms"))
        assertFalse(payload(videoDurationMs = 21_600_001L).containsKey("video_duration_ms"))
        assertFalse(payload(videoDurationMs = 0L).containsKey("video_duration_ms"))
        assertFalse(payload(videoDurationMs = null).containsKey("video_duration_ms"))
        assertEquals(300_000L, payload(videoDurationMs = 300_000L).getValue("video_duration_ms").jsonPrimitive.long)
        assertEquals(21_600_000L, payload(videoDurationMs = 21_600_000L).getValue("video_duration_ms").jsonPrimitive.long)
    }

    @Test
    fun tvMissingSeasonOrEpisodeStopsBeforeABody() {
        assertNull(
            theIntroDbSubmitBody(
                sampleDraft(type = "tv", season = null, episode = 1),
            ),
        )
        assertNull(
            theIntroDbSubmitBody(
                sampleDraft(type = "tv", season = 1, episode = null),
            ),
        )
        assertNull(
            theIntroDbSubmitBody(
                sampleDraft(type = "movie", tmdbId = 0),
            ),
        )
    }

    @Test
    fun introWarnsAbove200SecondsAndBlocksAbove300() {
        assertIs<ManualIntroTimeCheck.Ok>(checkManualIntroTimes("intro", null, 90.0, 7_200_000L))
        assertIs<ManualIntroTimeCheck.Warn>(checkManualIntroTimes("intro", 0.0, 201.0, 7_200_000L))
        assertIs<ManualIntroTimeCheck.Block>(checkManualIntroTimes("intro", 0.0, 301.0, 7_200_000L))
        assertIs<ManualIntroTimeCheck.Block>(checkManualIntroTimes("intro", 0.0, 4.0, 7_200_000L))
        assertIs<ManualIntroTimeCheck.Block>(checkManualIntroTimes("recap", 0.0, 1201.0, 7_200_000L))
        assertIs<ManualIntroTimeCheck.Ok>(checkManualIntroTimes("credits", 7000.0, null, 7_200_000L))
        assertIs<ManualIntroTimeCheck.Block>(checkManualIntroTimes("credits", null, 200.0, 7_200_000L))
        assertIs<ManualIntroTimeCheck.Block>(checkManualIntroTimes("preview", 10.0, 14.0, 7_200_000L))
        assertIs<ManualIntroTimeCheck.Block>(checkManualIntroTimes("intro", 40.0, 30.0, 7_200_000L))
    }

    @Test
    fun flagAllowsMoviesWithImdbOrTmdb() {
        assertTrue(canShowManualIntroSubmit(true, submitIntroTitleRef(listOf("tmdb:550"))))
        assertTrue(canShowManualIntroSubmit(true, submitIntroTitleRef(listOf("tt0137523"))))
        assertTrue(canShowManualIntroSubmit(true, submitIntroTitleRef(listOf("tmdb:1396", "tt0903747"))))
        assertFalse(canShowManualIntroSubmit(false, submitIntroTitleRef(listOf("tt0137523"))))
        assertFalse(canShowManualIntroSubmit(true, submitIntroTitleRef(listOf("kitsu:1"))))
    }

    private fun payload(videoDurationMs: Long?) =
        json.parseToJsonElement(
            theIntroDbSubmitBody(sampleDraft(videoDurationMs = videoDurationMs))!!,
        ).jsonObject

    private fun sampleDraft(
        tmdbId: Int = 550,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        videoDurationMs: Long? = 7_200_000L,
    ) = TheIntroDbSubmitDraft(
        tmdbId = tmdbId,
        imdbId = "tt0137523",
        type = type,
        segment = "intro",
        season = season,
        episode = episode,
        startSec = 12.0,
        endSec = 40.0,
        videoDurationMs = videoDurationMs,
    )
}
