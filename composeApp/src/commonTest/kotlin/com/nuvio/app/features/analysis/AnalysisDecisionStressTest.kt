package com.nuvio.app.features.analysis

import com.nuvio.app.features.player.sameCut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisDecisionStressTest {
    @Test
    fun anEmptySeasonChangesNothing() {
        val saved = MediaAnalysisResult(key = "s1e1", analyzedAtMs = 0L, durationMs = 0L)
        assertTrue(seasonFingerprintPass(emptyList()).isEmpty())
        assertTrue(backfillSeasonOnFingerprintSave(saved, emptyList()).isEmpty())
    }

    @Test
    fun oddDurationsAndNegativeTimesStayInsideTheWindowRules() {
        val inverted = referenceSampleWindow(segmentStartMs = 8_000L, segmentEndMs = 1_000L, padMs = 500L)
        assertFalse(inverted.complete)
        assertTrue(inverted.endMs >= inverted.startMs)

        val negative = referenceSampleWindow(segmentStartMs = -5_000L, segmentEndMs = -1L, padMs = 1_000L)
        assertTrue(negative.startMs >= 0L)
        assertTrue(negative.endMs >= negative.startMs)

        val tooLong = referenceSampleWindow(segmentStartMs = 0L, segmentEndMs = 80_000L, padMs = 0L)
        assertFalse(tooLong.complete)
        assertEquals(tooLong.startMs, tooLong.endMs)

        val slices = referenceDecodeSlices(segmentStartMs = 0L, segmentEndMs = 120_000L, padMs = 0L)
        assertEquals(2, slices.size)
        assertTrue(slices.all { it.decodeEndMs - it.decodeStartMs <= REFERENCE_MAX_WINDOW_MS })

        assertTrue(clipDecodeLimits(0L).maxFrames > 0)
        assertTrue(clipDecodeLimits(-10L).maxFrames > 0)
        assertNull(verifiedSegmentBounds(0L, 10_000L, emptyList(), emptyList()))
        assertNull(provenSharedRun(0L, 20_000L, emptyList()))
    }

    @Test
    fun overlappingAndDuplicateRunsDoNotInventASharedMark() {
        val slice = ReferenceSlice(0L, 20_000L, 0L, 20_000L)
        val inside = provenSharedRun(
            segmentStartMs = 0L,
            segmentEndMs = 20_000L,
            matches = listOf(
                slice to (0L to 18_000L),
                slice to (2_000L to 9_000L),
            ),
        )
        assertEquals(0L to 18_000L, inside)

        val twoQualified = provenSharedRun(
            segmentStartMs = 0L,
            segmentEndMs = 20_000L,
            matches = listOf(
                slice to (0L to 18_000L),
                slice to (1_000L to 16_000L),
            ),
        )
        assertNull(twoQualified)

        val disagreeing = provenSharedRun(
            segmentStartMs = 0L,
            segmentEndMs = 40_000L,
            matches = listOf(
                slice to (0L to 20_000L),
                slice to (30_000L to 50_000L),
            ),
        )
        assertNull(disagreeing)
    }

    @Test
    fun zeroAndNegativeTimesAreNotAcceptedMarks() {
        assertFalse(userMarkDecision("intro", startMs = 5_000L, endMs = 5_000L).accepted)
        assertFalse(userMarkDecision("intro", startMs = 5_000L, endMs = -1L).accepted)
        assertFalse(chapterDecision("credits", startMs = -10L, endMs = null).accepted)
        assertFalse(isOpeningCreditsRange(startMs = -1L, endMs = 5_000L, durationMs = 1_000_000L))
        assertFalse(speechEnergyHigh(energy = null, stepMs = 0, startMs = -10L, endMs = -1L))
        assertTrue(silentRangesFromEnergy(energy = null, stepMs = -5).isEmpty())
        assertFalse(coldOpenLikely(startMs = 0L, siblings = emptyList()))
    }

    @Test
    fun aCutBoundaryOfThirtySecondsStaysTheSameCut() {
        assertTrue(sameCut(analysedMs = 1_800_000L, playingMs = 1_830_000L))
        assertFalse(sameCut(analysedMs = 1_800_000L, playingMs = 1_830_001L))
        assertTrue(sameCut(analysedMs = 1_800_000L, playingMs = 0L))
        assertTrue(sameCut(analysedMs = -1L, playingMs = 1_800_000L))
    }

    @Test
    fun aRecapMarkOnAnUnknownLengthStaysReady() {
        val ready = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = true,
            derivedCurrent = true,
            recapEndMs = null,
            userRecap = UserSegment(startMs = 0L, endMs = 20_000L, durationMs = 0L),
            durationMs = -1L,
            siblingsEmpty = true,
            outcome = null,
        )
        assertEquals(AnalysisPart.Ready, ready)

        val movie = recapAnalysisPart(
            recapEnabled = true,
            isEpisode = false,
            derivedCurrent = true,
            recapEndMs = 20_000L,
            userRecap = null,
            durationMs = 0L,
            siblingsEmpty = true,
        )
        assertEquals(AnalysisPart.NotFound, movie)
    }
}
