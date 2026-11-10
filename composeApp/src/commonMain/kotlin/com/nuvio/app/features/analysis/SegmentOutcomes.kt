package com.nuvio.app.features.analysis

/**
 * A recaptured print is a new object. Known intro, credits, and recap ranges move with it.
 * A later pass replaces a range only when the new detection is strictly better.
 */
internal fun MediaAnalysisResult.carryingKnownSegments(previous: MediaAnalysisResult?): MediaAnalysisResult {
    if (previous == null) return this
    if (previous.fileName != null && fileName != null && previous.fileName != fileName) return this
    return copy(
        introStartMs = previous.introStartMs,
        introEndMs = previous.introEndMs,
        introConfidence = previous.introConfidence,
        introOutcome = previous.introOutcome,
        creditsStartMs = previous.creditsStartMs,
        creditsEndMs = previous.creditsEndMs,
        creditsConfidence = previous.creditsConfidence,
        creditsOutcome = previous.creditsOutcome,
        recapStartMs = previous.recapStartMs,
        recapEndMs = previous.recapEndMs,
        recapOutcome = previous.recapOutcome,
    )
}

/** Writes the intro, credits, and recap tokens from this pass. A finished pass also marks derived data current. */
internal fun MediaAnalysisResult.withSegmentOutcomes(
    decisions: List<SegmentDecision>,
    finished: Boolean,
): MediaAnalysisResult {
    val next = copy(
        introOutcome = introCreditsOutcomeToPersist("intro", introOutcome, decisions, finished),
        creditsOutcome = introCreditsOutcomeToPersist("credits", creditsOutcome, decisions, finished),
        recapOutcome = recapOutcomeToPersist(recapOutcome, decisions, finished),
    )
    return if (finished) next.copy(derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION) else next
}
