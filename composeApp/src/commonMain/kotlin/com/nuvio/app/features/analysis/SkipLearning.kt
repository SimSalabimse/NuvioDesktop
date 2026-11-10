package com.nuvio.app.features.analysis

/**
 * Learns from the user's own skip marks. A mark always wins on that episode. On an episode with
 * fingerprints, an intro or credits mark is also a trusted reference for its season.
 *
 * Saving a mark and teaching the rest of the season stays on the mobile analysis store.
 * Desktop has no analysis file store, so only the match itself is here.
 */
/**
 * The range a saved correction moved off, or null when nothing moved.
 * A Wrong tap does not call this: nothing is stored until a different range is saved.
 */
internal fun rejectedRangeForCorrection(
    category: String,
    previousStartMs: Long?,
    previousEndMs: Long?,
    savedStartMs: Long,
    savedEndMs: Long,
): RejectedSegment? {
    if (category != "intro" && category != "outro" && category != "recap") return null
    if (previousStartMs == null || previousEndMs == null) return null
    if (previousStartMs == savedStartMs && previousEndMs == savedEndMs) return null
    return RejectedSegment(category, previousStartMs, previousEndMs)
}

internal object SkipLearning {
    /** Provider name of skip intervals that come from the user's own marks. */
    const val PROVIDER = "you"
    /** A mark imported from the user's own TheIntroDB submissions. */
    const val PROVIDER_IMPORTED = "you-theintrodb"

    fun isUserProvider(provider: String): Boolean = provider == PROVIDER || provider == PROVIDER_IMPORTED

    private const val MIN_REFERENCE_MS = 8_000L
    private const val END_SNAP_MS = 10_000L

    /** intro / recap / outro / preview, or null for types that cannot be marked. */
    fun canonicalType(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op", "opening" -> "intro"
        "recap" -> "recap"
        "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "outro"
        "preview" -> "preview"
        else -> null
    }

    /**
     * [episode] with intro/credits taken from the best match against the user-marked windows of
     * [references]. Types the user marked on [episode] itself are left alone.
     */
    fun learnFromReferences(episode: MediaAnalysisResult, references: List<MediaAnalysisResult>): MediaAnalysisResult {
        var out = episode
        for (category in listOf("intro", "outro")) {
            if (episode.userSegments.containsKey(category)) continue
            var best: Triple<Long, Long, Int>? = null
            for (reference in references) {
                if (reference.key == episode.key) continue
                val match = matchReference(reference, category, episode) ?: continue
                if (match.third > (best?.third ?: 0)) best = match
            }
            val found = best ?: continue
            if (episode.rejectedSegments.covers(category, found.first, found.second)) continue
            out = when (category) {
                "intro" -> out.copy(
                    introStartMs = found.first,
                    introEndMs = found.second,
                    introConfidence = if (out.introStartMs != found.first || out.introEndMs != found.second) null else out.introConfidence,
                    derivedVersion = if (out.introStartMs != found.first || out.introEndMs != found.second) 0 else out.derivedVersion,
                )
                else -> out.copy(
                    creditsStartMs = found.first,
                    creditsEndMs = found.second,
                    creditsConfidence = if (out.creditsStartMs != found.first || out.creditsEndMs != found.second) null else out.creditsConfidence,
                )
            }
            out = out.copy(learnedTypes = out.learnedTypes + category)
        }
        return out
    }

    /** Start, end (ms in [target]) and matched frame count of [reference]'s marked window, or null. */
    internal fun matchReference(
        reference: MediaAnalysisResult,
        category: String,
        target: MediaAnalysisResult,
    ): Triple<Long, Long, Int>? {
        val mark = reference.userSegments[category]?.takeIf { it.canTeach } ?: return null
        if (!com.nuvio.app.features.player.sameCut(mark.durationMs, reference.durationMs)) return null
        if (reference.durationMs > 0L && target.durationMs > 0L &&
            !com.nuvio.app.features.player.sameCut(reference.durationMs, target.durationMs)
        ) {
            return null
        }
        if (reference.fingerprintStepMs != target.fingerprintStepMs) return null
        val step = reference.fingerprintStepMs.coerceAtLeast(1).toLong()
        val isIntro = category == "intro"
        val refFrames = decodeFingerprint(if (isIntro) reference.headFingerprint else reference.tailFingerprint)
        val refOffset = if (isIntro) 0L else reference.tailStartMs
        val from = ((mark.startMs - refOffset) / step).toInt().coerceIn(0, refFrames.size)
        val to = ((mark.endMs - refOffset) / step).toInt().coerceIn(0, refFrames.size)
        val minFrames = (MIN_REFERENCE_MS / step).toInt()
        if (to - from < minFrames) return null
        val window = refFrames.copyOfRange(from, to)
        val targetFrames = decodeFingerprint(if (isIntro) target.headFingerprint else target.tailFingerprint)
        if (targetFrames.isEmpty()) return null
        val needed = maxOf(minFrames, window.size / 2)
        val segment = FingerprintMatcher.longestSharedSegment(window, targetFrames, minFrames = needed) ?: return null
        if (segment.length * 2 < window.size) return null
        val startFrame = segment.startB
        val endFrame = segment.endB
        if (endFrame - startFrame < needed) return null
        val targetOffset = if (isIntro) 0L else target.tailStartMs
        val start = targetOffset + startFrame * step
        var end = targetOffset + endFrame * step
        if (!isIntro && target.durationMs > 0L && target.durationMs - end < END_SNAP_MS) end = target.durationMs
        return Triple(start, end, segment.length)
    }
}
