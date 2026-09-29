package com.nuvio.app.features.player.skip

internal const val EMPTY_FLAG_TIMESTAMP = "00:00:00"

private const val MAX_FLAG_TIMESTAMP_SECONDS = 99 * 3600 + 59 * 60 + 59

internal fun canonicalFlagSegmentType(raw: String?): String? {
    return when (raw?.trim()?.lowercase()) {
        "intro", "op", "mixed-op", "opening" -> "intro"
        "recap" -> "recap"
        "credits", "outro", "ed", "mixed-ed", "ending", "movie-credits" -> "outro"
        "preview" -> "preview"
        else -> null
    }
}

/**
 * Segment buttons to gray out: types already stored for this title, plus types
 * flagged earlier in this playback session.
 */
/**
 * Player header flag. A saved IntroDB key is not required to show the button;
 * the submit dialog explains when the key is missing.
 */
internal fun shouldShowSubmitIntroFlag(
    isSeries: Boolean,
    introSubmitEnabled: Boolean,
    imdbId: String?,
): Boolean = isSeries && introSubmitEnabled && !imdbId.isNullOrBlank()

internal fun disabledFlagSegmentTypes(
    skipIntervals: List<SkipInterval>,
    submittedInSession: Set<String>,
): List<String> {
    val types = LinkedHashSet<String>()
    skipIntervals.mapNotNullTo(types) { canonicalFlagSegmentType(it.type) }
    submittedInSession.mapNotNullTo(types) { canonicalFlagSegmentType(it) }
    return types.toList()
}

internal fun formatFlagTimestamp(seconds: Double): String {
    val total = seconds
        .takeIf { it.isFinite() && it >= 0.0 }
        ?.toInt()
        ?.coerceAtMost(MAX_FLAG_TIMESTAMP_SECONDS)
        ?: 0
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return listOf(hours, minutes, secs).joinToString(":") { it.toString().padStart(2, '0') }
}
