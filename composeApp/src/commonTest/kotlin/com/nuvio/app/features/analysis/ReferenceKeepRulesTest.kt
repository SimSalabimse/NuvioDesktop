package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The mobile host proves these through the download scheduler.
 * Desktop has no reference decoder, so the same decisions are called directly.
 */
class ReferenceKeepRulesTest {
    @Test
    fun aVerifiedPartialRunIsKeptAndNotWidened() {
        val sliceA = ReferenceSlice(20_000L, 60_000L, 15_000L, 65_000L)
        val sliceB = ReferenceSlice(60_000L, 100_000L, 55_000L, 105_000L)
        val kept = provenSharedRun(
            segmentStartMs = 20_000L,
            segmentEndMs = 100_000L,
            matches = listOf(
                sliceA to (20_000L to 70_000L),
                sliceB to (22_000L to 50_000L),
            ),
        )
        assertEquals(20_000L to 70_000L, kept)
        assertTrue(kept!!.second - kept.first >= 8_000L)
        assertTrue(kept.second - kept.first >= 40_000L)
        assertTrue(kept.second < 100_000L)
    }

    @Test
    fun aShorterSliceInsideTheQualifiedRunIsTheSameRun() {
        assertTrue(
            locatedRunInside(
                inner = 22_000L to 50_000L,
                outer = 20_000L to 70_000L,
            ),
        )
    }

    @Test
    fun disjointSlicesStayPartialAndAreNotPersisted() = runBlocking {
        val segmentStart = 20_000L
        val segmentEnd = 100_000L
        val frames = disagreeFrames()
        val aligned = alignReferenceSegment(
            segmentStartMs = segmentStart,
            segmentEndMs = segmentEnd,
            padMs = 5_000L,
            frames = frames,
            framesOffsetMs = 0L,
            stepMs = 1_000L,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
            decode = { start, end -> fingerprint(start, disagreeTone(start, end)) },
        )
        assertTrue(aligned is ReferenceAlignment.Partial, "$aligned")
        assertEquals("unverified-slice", (aligned as ReferenceAlignment.Partial).reason)
        val current = episode()
        val settled = settleReferenceRead(current, intro = aligned, credits = null, referenceEpisode = 6)
        assertEquals(SiblingClipAttempt.Finished, settled.attempt)
        assertNull(settled.result.introStartMs)
        val written = mergeReferenceWrite(current, settled.result, referenceWriteGuard(current), cancelled = false)
        assertNull(written?.introStartMs)
        assertNull(written?.introEndMs)
    }

    @Test
    fun aMatchedOpeningIsKeptWhenTheCreditsWindowDoesNotMatch() = runBlocking {
        val frames = IntArray(50) { openingLane(it, 14) }
        for (index in 10 until 40) frames[index] = openingLane(index - 10, 6)
        val intro = alignReferenceSegment(
            segmentStartMs = 10_000L,
            segmentEndMs = 40_000L,
            padMs = 5_000L,
            frames = frames,
            framesOffsetMs = 0L,
            stepMs = 1_000L,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
            decode = { start, end ->
                val count = ((end - start) / 1_000L).toInt().coerceAtLeast(1)
                fingerprint(
                    start,
                    IntArray(count) { index ->
                        val second = (start / 1_000L).toInt() + index
                        if (second in 10 until 40) openingLane(second - 10, 6) else openingLane(index, 2)
                    },
                )
            },
        )
        assertTrue(intro is ReferenceAlignment.Matched, "$intro")
        val credits = alignReferenceSegment(
            segmentStartMs = 2_000_000L,
            segmentEndMs = 2_020_000L,
            padMs = 5_000L,
            frames = IntArray(40) { openingLane(it, 9) },
            framesOffsetMs = 1_980_000L,
            stepMs = 1_000L,
            targetDurationMs = 2_400_000L,
            referenceDurationMs = 2_400_000L,
            decode = { start, end ->
                val count = ((end - start) / 1_000L).toInt().coerceAtLeast(1)
                fingerprint(start, IntArray(count) { openingLane(it, 3) })
            },
        )
        assertTrue(credits is ReferenceAlignment.Partial, "$credits")
        val current = episode()
        val settled = settleReferenceRead(current, intro, credits, referenceEpisode = 6)
        assertEquals(SiblingClipAttempt.Finished, settled.attempt)
        assertEquals(10_000L, settled.result.introStartMs)
        assertEquals(40_000L, settled.result.introEndMs)
        assertNull(settled.result.creditsStartMs)
        val written = mergeReferenceWrite(current, settled.result, referenceWriteGuard(current), cancelled = false)
        assertEquals(10_000L, written?.introStartMs)
        assertEquals(40_000L, written?.introEndMs)
        assertNull(written?.creditsStartMs)
    }

    @Test
    fun aCancelledRecapPassDropsAStaleCompletedMiss() {
        val stored = episode().copy(recapOutcome = "rejected-no-match")
        val cancelled = stored.withSegmentOutcomes(emptyList(), finished = false)
        assertNull(cancelled.recapOutcome)
        val finished = stored.withSegmentOutcomes(emptyList(), finished = true)
        assertEquals("rejected-no-match", finished.recapOutcome)
    }

    private fun episode() = MediaAnalysisResult(
        key = "show__s1e5",
        analyzedAtMs = 1L,
        durationMs = 2_400_000L,
        fileName = "e5.mkv",
        fingerprintStepMs = 1_000,
    )

    private fun disagreeFrames(): IntArray {
        val frames = IntArray(120) { openingLane(it, 14) }
        for (index in 20 until 60) frames[index] = openingLane(index - 20, 6)
        for (index in 80 until 100) frames[index] = openingLane(index - 80, 7)
        return frames
    }

    private fun disagreeTone(startMs: Long, endMs: Long): IntArray {
        val count = ((endMs - startMs) / 1_000L).toInt()
        return IntArray(count) { index ->
            val second = (startMs / 1_000L).toInt() + index
            when {
                second in 20 until 60 -> openingLane(second - 20, 6)
                second in 80 until 100 -> openingLane(second - 80, 7)
                else -> openingLane(index, 2)
            }
        }
    }

    private fun openingLane(index: Int, salt: Int): Int {
        var mixed = 0x12345678 xor (index * 0x45D9F3B) xor (salt * 0x6C8E9CF5.toInt())
        mixed = mixed xor (mixed ushr 16)
        mixed *= 0x7FEB352D
        mixed = mixed xor (mixed ushr 15)
        mixed *= 0x846CA68B.toInt()
        mixed = mixed xor (mixed ushr 16)
        return mixed or 1
    }

    private fun fingerprint(startMs: Long, values: IntArray) = RawClipFingerprint(
        startMs = startMs,
        stepMs = 1_000,
        decodedFrames = values.size,
        decodedSamples = values.size,
        fingerprint = encodeFingerprint(values),
        windowComplete = true,
        stopReason = "window-end",
    )
}
