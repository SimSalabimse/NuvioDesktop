package com.nuvio.app.features.analysis

import com.nuvio.app.features.player.sameCut
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.TheIntroDb
import kotlinx.serialization.Serializable

/**
 * Known intro (and credits, when there are any) of another episode of the season, in ms.
 * The mobile host also fetches that sibling's stream. Desktop has no reference decoder,
 * so this file keeps the locate, slice, and keep/drop decisions the host calls.
 */
internal data class SiblingTimes(
    val episode: Int,
    val intro: Pair<Long, Long>,
    val credits: Pair<Long, Long>? = null,
    val durationMs: Long = 0L,
)

/** A clip was read, or there was nothing to fetch. [Unavailable] is tried again. */
internal enum class SiblingClipAttempt { Finished, Unavailable, Preempted }

internal data class SiblingClipOutcome(val result: MediaAnalysisResult, val attempt: SiblingClipAttempt)

@Serializable
internal data class RawClipFingerprint(
    val startMs: Long = 0L,
    val stepMs: Int = 100,
    val decodedFrames: Int = 0,
    val decodedSamples: Int = 0,
    val fingerprint: String? = null,
    /** False when the decoder refused a window longer than the budget instead of truncating it. */
    val windowComplete: Boolean = true,
    /** Why decoding stopped. Anything other than window-end means the fingerprint is not the whole window. */
    val stopReason: String? = null,
)

internal object SiblingClipReference {
    private const val MAX_ONLINE_LOOKUPS = 4

    /** Episodes nearest to [target] first; the earlier one wins a tie. */
    fun nearestFirst(target: Int, episodes: List<Int>): List<Int> =
        episodes.filter { it != target }.distinct().sortedWith(compareBy({ kotlin.math.abs(it - target) }, { it }))

    /**
     * Your nearest marked episode, else the nearest of the next [MAX_ONLINE_LOOKUPS] episodes
     * with online times. [lookup] is called at most that many times.
     */
    suspend fun pickReference(
        target: Int,
        own: List<SiblingTimes>,
        episodes: List<Int>,
        lookup: suspend (Int) -> SiblingTimes?,
    ): SiblingTimes? {
        val marked = own.filter { it.episode != target }.associateBy { it.episode }
        nearestFirst(target, marked.keys.toList()).firstOrNull()?.let { return marked.getValue(it) }
        for (episode in nearestFirst(target, episodes).take(MAX_ONLINE_LOOKUPS)) {
            lookup(episode)?.let { return it }
        }
        return null
    }

    /** TheIntroDB/IntroDB times of one episode; null without an intro. */
    fun onlineTimes(intervals: List<SkipInterval>, episode: Int): SiblingTimes? {
        val trusted = intervals.filter { it.provider == TheIntroDb.PROVIDER || it.provider == "introdb" }
        fun range(types: Set<String>) = trusted.firstOrNull { it.type.lowercase() in types && it.endTime > it.startTime }
            ?.let { (it.startTime * 1000).toLong() to (it.endTime * 1000).toLong() }
        val intro = range(setOf("intro", "op", "mixed-op")) ?: return null
        return SiblingTimes(episode, intro, range(setOf("outro", "ed", "mixed-ed", "credits", "ending")))
    }

    /**
     * Shared audio of [segment] inside this episode. The returned range is that shared run in
     * [frames], not a copy of the sibling window. Under half the sibling window, or file lengths
     * more than 30 seconds apart, transfers nothing. A length of 0 does not prove a different cut.
     */
    fun locate(
        frames: IntArray,
        framesOffsetMs: Long,
        clip: IntArray,
        clipStartMs: Long,
        step: Long,
        segment: Pair<Long, Long>,
        targetDurationMs: Long = 0L,
        referenceDurationMs: Long = 0L,
    ): Pair<Long, Long>? {
        if (targetDurationMs > 0L && referenceDurationMs > 0L && !sameCut(referenceDurationMs, targetDurationMs)) {
            return null
        }
        val windowMs = (segment.second - segment.first).coerceAtLeast(0L)
        val minMs = maxOf(REFERENCE_MIN_MATCH_MS, windowMs / 2)
        val match = FingerprintMatcher.longestSharedSegment(frames, clip, minFrames = (minMs / step).toInt().coerceAtLeast(1))
            ?: return null
        val windowFrames = (windowMs / step).toInt()
        if (windowFrames > 0 && match.length * 2 < windowFrames) return null
        val start = framesOffsetMs + match.startA * step
        val end = framesOffsetMs + match.endA * step
        return if (end > start) start to end else null
    }
}

internal sealed interface ReferenceAlignment {
    data class Matched(val range: Pair<Long, Long>) : ReferenceAlignment
    data class Partial(val reason: String) : ReferenceAlignment
    data object Preempted : ReferenceAlignment
    data object Unreadable : ReferenceAlignment
}

/**
 * Fingerprints bounded slices of a known segment and maps a verified offset back onto the full segment.
 * The decoder is never asked for more than 50 seconds. A slice is not stored as the whole segment.
 */
internal suspend fun alignReferenceSegment(
    segmentStartMs: Long,
    segmentEndMs: Long,
    padMs: Long,
    frames: IntArray,
    framesOffsetMs: Long,
    stepMs: Long,
    targetDurationMs: Long,
    referenceDurationMs: Long,
    yieldToLocal: () -> Boolean = { false },
    onSlice: (seconds: Int) -> Unit = {},
    decode: suspend (decodeStartMs: Long, decodeEndMs: Long) -> RawClipFingerprint?,
): ReferenceAlignment {
    if (frames.isEmpty() || stepMs <= 0L) return ReferenceAlignment.Unreadable
    val slices = referenceDecodeSlices(segmentStartMs, segmentEndMs, padMs)
    if (slices.isEmpty()) return ReferenceAlignment.Partial("no-slice")
    val matches = mutableListOf<Pair<ReferenceSlice, Pair<Long, Long>>>()
    for (slice in slices) {
        if (yieldToLocal()) return ReferenceAlignment.Preempted
        val windowMs = slice.decodeEndMs - slice.decodeStartMs
        if (windowMs <= 0L || windowMs > REFERENCE_MAX_WINDOW_MS) {
            return ReferenceAlignment.Partial("slice-over-budget")
        }
        onSlice((windowMs / 1_000L).toInt().coerceAtLeast(1))
        val clip = decode(slice.decodeStartMs, slice.decodeEndMs)
            ?: return if (yieldToLocal()) ReferenceAlignment.Preempted else ReferenceAlignment.Unreadable
        if (!clipCoversRequestedWindow(clip.windowComplete, clip.stopReason)) {
            return ReferenceAlignment.Partial(clip.stopReason ?: "incomplete-window")
        }
        if (reportedClipExceedsBudget(clip.decodedFrames, clip.decodedSamples, windowMs)) {
            return ReferenceAlignment.Partial("over-budget")
        }
        if (clip.stepMs.toLong() != stepMs) return ReferenceAlignment.Partial("step-mismatch")
        val range = SiblingClipReference.locate(
            frames,
            framesOffsetMs,
            decodeFingerprint(clip.fingerprint),
            clip.startMs,
            stepMs,
            slice.contentStartMs to slice.contentEndMs,
            targetDurationMs,
            referenceDurationMs,
        ) ?: continue
        matches += slice to range
    }
    val bounds = verifiedSegmentBounds(segmentStartMs, segmentEndMs, slices, matches)
    if (bounds != null) return ReferenceAlignment.Matched(bounds)
    val shared = provenSharedRun(segmentStartMs, segmentEndMs, matches)
        ?: return ReferenceAlignment.Partial("unverified-slice")
    return ReferenceAlignment.Matched(shared)
}

/** A finished comparison that found no shared run is not a failed read. */
internal fun referenceReadFailed(reason: String): Boolean =
    reason != "unverified-slice" && reason != "no-slice"

/**
 * Applies one intro alignment and one credits alignment the way the mobile reference read does.
 * A credits miss does not clear an intro that already matched. A partial slice is not written.
 * Preemption returns the original result, so a half-finished read is not stored.
 */
internal fun settleReferenceRead(
    result: MediaAnalysisResult,
    intro: ReferenceAlignment?,
    credits: ReferenceAlignment?,
    referenceEpisode: Int,
): SiblingClipOutcome {
    var updated = result
    var readFailed = false
    var matched = false
    var preempted = false
    if (result.introStartMs == null && intro != null) {
        when (intro) {
            is ReferenceAlignment.Matched -> {
                matched = true
                updated = updated.copy(
                    introStartMs = intro.range.first,
                    introEndMs = intro.range.second,
                    foundUsing = updated.foundUsing + ("intro" to referenceEpisode),
                )
            }
            is ReferenceAlignment.Partial -> {
                if (referenceReadFailed(intro.reason)) readFailed = true
            }
            ReferenceAlignment.Preempted -> preempted = true
            ReferenceAlignment.Unreadable -> readFailed = true
        }
    }
    if (!preempted && credits != null && result.creditsStartMs == null) {
        when (credits) {
            is ReferenceAlignment.Matched -> {
                matched = true
                updated = updated.copy(
                    creditsStartMs = credits.range.first,
                    creditsEndMs = credits.range.second,
                    foundUsing = updated.foundUsing + ("outro" to referenceEpisode),
                )
            }
            is ReferenceAlignment.Partial -> {
                if (referenceReadFailed(credits.reason)) readFailed = true
            }
            ReferenceAlignment.Preempted -> preempted = true
            ReferenceAlignment.Unreadable -> readFailed = true
        }
    }
    if (preempted) return SiblingClipOutcome(result, SiblingClipAttempt.Preempted)
    if (readFailed && !matched) return SiblingClipOutcome(result, SiblingClipAttempt.Unavailable)
    return SiblingClipOutcome(updated, SiblingClipAttempt.Finished)
}
