package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagSubmitTimesTest {
    @Test
    fun canonicalTypesCoverAliasesUsedBySkipIntervals() {
        assertEquals("intro", canonicalFlagSegmentType("mixed-op"))
        assertEquals("intro", canonicalFlagSegmentType("opening"))
        assertEquals("recap", canonicalFlagSegmentType("recap"))
        assertEquals("outro", canonicalFlagSegmentType("credits"))
        assertEquals("outro", canonicalFlagSegmentType("movie-credits"))
        assertEquals("preview", canonicalFlagSegmentType("Preview"))
        assertNull(canonicalFlagSegmentType("chapter"))
    }

    @Test
    fun disabledTypesIgnoreCommunityIntervalsAndKeepOwnSessionSubmits() {
        assertEquals(
            listOf("preview"),
            disabledFlagSegmentTypes(submittedInSession = setOf("preview", "chapter")),
        )
        assertEquals(
            emptyList(),
            disabledFlagSegmentTypes(submittedInSession = emptySet()),
        )
        assertEquals(
            listOf("intro", "outro"),
            disabledFlagSegmentTypes(submittedInSession = setOf("opening", "ending", "credits")),
        )
    }

    @Test
    fun flagShowsForASeriesWithoutAnIntroDbKey() {
        assertTrue(shouldShowSubmitIntroFlag(isSeries = true, introSubmitEnabled = true, imdbId = "tt0944947"))
        assertFalse(shouldShowSubmitIntroFlag(isSeries = false, introSubmitEnabled = true, imdbId = "tt0944947"))
        assertFalse(shouldShowSubmitIntroFlag(isSeries = true, introSubmitEnabled = false, imdbId = "tt0944947"))
        assertFalse(shouldShowSubmitIntroFlag(isSeries = true, introSubmitEnabled = true, imdbId = null))
        assertFalse(shouldShowSubmitIntroFlag(isSeries = true, introSubmitEnabled = true, imdbId = "  "))
    }

    @Test
    fun timestampAlwaysIncludesTheHour() {
        assertEquals("00:00:00", formatFlagTimestamp(0.0))
        assertEquals("00:01:05", formatFlagTimestamp(65.0))
        assertEquals("01:02:03", formatFlagTimestamp(3_723.9))
        assertEquals("00:00:00", formatFlagTimestamp(-4.0))
        assertEquals(EMPTY_FLAG_TIMESTAMP, formatFlagTimestamp(Double.NaN))
    }
}
