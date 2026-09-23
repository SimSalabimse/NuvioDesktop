package com.nuvio.app.features.player.skip

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class SkipInterval(
    val startTime: Double,
    val endTime: Double,
    val type: String,
    val provider: String,
)

data class NextEpisodeInfo(
    val videoId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
    val hasAired: Boolean,
    val isWatched: Boolean,
    val unairedMessage: String?,
)

enum class NextEpisodeThresholdMode {
    PERCENTAGE,
    MINUTES_BEFORE_END,
}

// --- IntroDb v3 API response models ---

@Serializable
data class IntroDbMediaResponse(
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("intro") val intro: IntroDbSegment? = null,
    @SerialName("recap") val recap: IntroDbSegment? = null,
    @SerialName("credits") val credits: IntroDbSegment? = null,
    @SerialName("preview") val preview: IntroDbSegment? = null,
)

internal fun IntroDbMediaResponse.movieSkipIntervals(): List<SkipInterval> {
    val creditsInterval = credits.movieIntervalOrNull("movie-credits")
    val previewInterval = preview.movieIntervalOrNull("preview")
    // End-credit skipping must stop before the preview/post-credits scene, even if data overlaps.
    val safeCredits = if (creditsInterval != null && previewInterval != null &&
        previewInterval.startTime < creditsInterval.endTime && previewInterval.endTime > creditsInterval.startTime
    ) {
        creditsInterval.copy(endTime = previewInterval.startTime).takeIf { it.endTime > it.startTime }
    } else creditsInterval
    return listOfNotNull(safeCredits, previewInterval)
}

// Backward compatibility alias
@Deprecated("Use IntroDbMediaResponse", ReplaceWith("IntroDbMediaResponse"))
typealias IntroDbSegmentsResponse = IntroDbMediaResponse

private fun IntroDbSegment?.movieIntervalOrNull(type: String): SkipInterval? {
    if (this == null) return null
    val start = startSec ?: startMs?.let { it / 1000.0 } ?: return null
    val end = endSec ?: endMs?.let { it / 1000.0 } ?: return null
    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return null
    return SkipInterval(start, end, type, "introdb")
}

@Serializable
data class IntroDbSegment(
    @SerialName("start_sec") val startSec: Double? = null,
    @SerialName("end_sec") val endSec: Double? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("submission_count") val submissionCount: Int? = null,
    @SerialName("accepted_count") val acceptedCount: Int? = null,
    @SerialName("pending_count") val pendingCount: Int? = null,
    @SerialName("rejected_count") val rejectedCount: Int? = null,
    @SerialName("coverage_percent") val coveragePercent: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SubmitIntroRequest(
    @SerialName("imdb_id") val imdbId: String,
    @SerialName("season") val season: Int,
    @SerialName("episode") val episode: Int,
    @SerialName("start_sec") val startSec: Double,
    @SerialName("end_sec") val endSec: Double,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("segment_type") val segmentType: String,
)

// --- AniSkip API response models ---

@Serializable
data class AniSkipResponse(
    @SerialName("found") val found: Boolean = false,
    @SerialName("results") val results: List<AniSkipResult>? = null,
)

@Serializable
data class AniSkipResult(
    @SerialName("interval") val interval: AniSkipInterval,
    @SerialName("skipType") val skipType: String,
    @SerialName("skipId") val skipId: String? = null,
)

@Serializable
data class AniSkipInterval(
    @SerialName("startTime") val startTime: Double,
    @SerialName("endTime") val endTime: Double,
)

// --- Anime-Skip GraphQL API response models ---

@Serializable
data class AnimeSkipGraphqlResponse(
    @SerialName("data") val data: AnimeSkipData? = null,
)

@Serializable
data class AnimeSkipData(
    @SerialName("findShowsByExternalId") val findShowsByExternalId: List<AnimeSkipShow>? = null,
    @SerialName("findEpisodesByShowId") val findEpisodesByShowId: List<AnimeSkipEpisode>? = null,
)

@Serializable
data class AnimeSkipShow(
    @SerialName("id") val id: String,
)

@Serializable
data class AnimeSkipEpisode(
    @SerialName("season") val season: String? = null,
    @SerialName("number") val number: String? = null,
    @SerialName("timestamps") val timestamps: List<AnimeSkipTimestamp>? = null,
)

@Serializable
data class AnimeSkipTimestamp(
    @SerialName("at") val at: Double,
    @SerialName("type") val type: AnimeSkipTimestampType,
)

@Serializable
data class AnimeSkipTimestampType(
    @SerialName("name") val name: String,
)
