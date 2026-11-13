package com.nuvio.app.features.player.skip

import com.nuvio.app.core.network.retryAfterDelayMillis
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val THE_INTRO_DB_MIN_VIDEO_DURATION_MS = 300_000L
internal const val THE_INTRO_DB_MAX_VIDEO_DURATION_MS = 21_600_000L

internal fun acceptableTheIntroDbVideoDurationMs(durationMs: Long?): Long? =
    durationMs?.takeIf { it in THE_INTRO_DB_MIN_VIDEO_DURATION_MS..THE_INTRO_DB_MAX_VIDEO_DURATION_MS }

internal data class SubmitIntroTitleRef(
    val imdbId: String? = null,
    val tmdbId: Int? = null,
) {
    val hasId: Boolean get() = !imdbId.isNullOrBlank() || (tmdbId != null && tmdbId > 0)
}

/** IMDb (`tt…`) or TMDB (`tmdb:123`) from player ids. Either one is enough to offer submit. */
internal fun submitIntroTitleRef(candidates: List<String?>): SubmitIntroTitleRef {
    var imdbId: String? = null
    var tmdbId: Int? = null
    for (raw in candidates) {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) continue
        if (imdbId == null) {
            imdbId = value.split(':', '/', '?', '&')
                .firstOrNull { part -> part.length > 2 && part.startsWith("tt", ignoreCase = true) }
        }
        if (tmdbId == null && value.startsWith("tmdb:", ignoreCase = true)) {
            tmdbId = value.substringAfter(':')
                .substringBefore(':')
                .substringBefore('/')
                .toIntOrNull()
                ?.takeIf { it > 0 }
        }
    }
    return SubmitIntroTitleRef(imdbId = imdbId, tmdbId = tmdbId)
}

internal fun canShowManualIntroSubmit(canSubmit: Boolean, title: SubmitIntroTitleRef): Boolean =
    canSubmit && title.hasId

internal data class TheIntroDbSubmitDraft(
    val tmdbId: Int,
    val imdbId: String? = null,
    val type: String,
    val segment: String,
    val season: Int? = null,
    val episode: Int? = null,
    val startSec: Double? = null,
    val endSec: Double? = null,
    val videoDurationMs: Long? = null,
)

/**
 * One POST /v4/submit body. Movies never include season or episode.
 * A TV episode with no season or episode stops here.
 * Times are milliseconds only (`start_ms` / `end_ms`), never mixed with seconds.
 * Null start or end omits that millisecond field (from start / to end).
 * [videoDurationMs] is sent only inside 5 minutes..6 hours.
 */
internal fun theIntroDbSubmitBody(draft: TheIntroDbSubmitDraft): String? {
    if (draft.tmdbId <= 0) return null
    val normalizedType = if (draft.type.equals("movie", ignoreCase = true)) "movie" else "tv"
    val normalizedSegment = when (draft.segment.lowercase()) {
        "intro", "op", "mixed-op" -> "intro"
        "recap" -> "recap"
        "outro", "ed", "credits", "ending", "movie-credits" -> "credits"
        "preview" -> "preview"
        else -> return null
    }
    val tvSeason = draft.season?.takeIf { it > 0 }
    val tvEpisode = draft.episode?.takeIf { it > 0 }
    if (normalizedType == "tv" && (tvSeason == null || tvEpisode == null)) return null
    val startMs = draft.startSec?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1000.0).toLong() }
    val endMs = draft.endSec?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1000.0).toLong() }
    return buildJsonObject {
        put("tmdb_id", draft.tmdbId)
        if (!draft.imdbId.isNullOrBlank()) put("imdb_id", draft.imdbId)
        put("type", normalizedType)
        put("segment", normalizedSegment)
        if (normalizedType == "tv") {
            put("season", tvSeason!!)
            put("episode", tvEpisode!!)
        }
        acceptableTheIntroDbVideoDurationMs(draft.videoDurationMs)?.let { put("video_duration_ms", it) }
        if (startMs != null) put("start_ms", startMs)
        if (endMs != null) put("end_ms", endMs)
    }.toString()
}

internal sealed interface ManualIntroTimeCheck {
    data object Ok : ManualIntroTimeCheck
    data class Warn(val message: String) : ManualIntroTimeCheck
    data class Block(val message: String) : ManualIntroTimeCheck
}

/**
 * Intro and recap may omit the start (from 0). Credits and preview may omit the end (to the end).
 * Length uses the playback duration when an end is open.
 */
internal fun checkManualIntroTimes(
    segment: String,
    startSec: Double?,
    endSec: Double?,
    playbackDurationMs: Long,
): ManualIntroTimeCheck {
    val normalized = when (segment.lowercase()) {
        "intro", "op", "mixed-op" -> "intro"
        "recap" -> "recap"
        "outro", "ed", "credits", "ending", "movie-credits" -> "credits"
        "preview" -> "preview"
        else -> return ManualIntroTimeCheck.Block("Check the start and end times.")
    }
    val openStart = normalized == "intro" || normalized == "recap"
    val openEnd = normalized == "credits" || normalized == "preview"
    val start = startSec?.takeIf { it.isFinite() && it >= 0.0 }
    val end = endSec?.takeIf { it.isFinite() && it >= 0.0 }
    if (start == null && !openStart) return ManualIntroTimeCheck.Block("Check the start and end times.")
    if (end == null && !openEnd) return ManualIntroTimeCheck.Block("Check the start and end times.")
    if (start != null && end != null && end <= start) {
        return ManualIntroTimeCheck.Block("Check the start and end times.")
    }
    val durationSec = playbackDurationMs.takeIf { it > 0L }?.div(1000.0)
    val resolvedStart = start ?: 0.0
    val resolvedEnd = end ?: durationSec
    if (resolvedEnd == null) return ManualIntroTimeCheck.Ok
    val length = resolvedEnd - resolvedStart
    if (length <= 0.0) return ManualIntroTimeCheck.Block("Check the start and end times.")
    val minSec = 5.0
    val maxSec = when (normalized) {
        "intro" -> 300.0
        "recap" -> 1200.0
        else -> 1800.0
    }
    val label = when (normalized) {
        "intro" -> "Intro"
        "recap" -> "Recap"
        "credits" -> "Outro"
        else -> "Preview"
    }
    if (length < minSec || length > maxSec) {
        return ManualIntroTimeCheck.Block("$label must be between 5 and ${maxSec.toInt()} seconds.")
    }
    if (normalized == "intro" && length > 200.0) {
        return ManualIntroTimeCheck.Warn("Intro is longer than 200 seconds.")
    }
    return ManualIntroTimeCheck.Ok
}

/** Result of one manual POST /v4/submit. A catalogue guess is never sent here. */
internal sealed interface TheIntroDbSubmitResult {
    val accepted: Boolean

    data object Sent : TheIntroDbSubmitResult {
        override val accepted: Boolean = true
    }

    /** HTTP 409: this account already submitted this segment. */
    data object AlreadySubmitted : TheIntroDbSubmitResult {
        override val accepted: Boolean = true
    }

    data object Failed : TheIntroDbSubmitResult {
        override val accepted: Boolean = false
    }
}

internal fun theIntroDbSubmitResult(status: Int): TheIntroDbSubmitResult = when (status) {
    200, 201 -> TheIntroDbSubmitResult.Sent
    409 -> TheIntroDbSubmitResult.AlreadySubmitted
    else -> TheIntroDbSubmitResult.Failed
}

/** How long to wait before one retry of a 429. Longer than two minutes is not waited out. */
internal fun theIntroDbRetryWaitMillis(status: Int, retryAfterHeader: String?, nowEpochMs: Long): Long? {
    if (status != 429) return null
    val wait = retryAfterDelayMillis(
        retryAfterHeader = retryAfterHeader,
        nowEpochMs = nowEpochMs,
        fallbackDelayMs = 1_000L,
    )
    return wait.takeIf { it in 0L..THE_INTRO_DB_MAX_RETRY_WAIT_MS }
}

internal const val THE_INTRO_DB_MAX_RETRY_WAIT_MS = 120_000L

internal fun retryAfterHeaderValue(headers: Map<String, String>): String? =
    headers.entries.firstOrNull { (name, _) -> name.equals("retry-after", ignoreCase = true) }?.value

/**
 * Segment buttons the account has submitted. Public catalogue types are not passed in.
 * `outro` and `credits` share the credits button. A type stays in the set so a second
 * segment of that type can still be submitted; the label only gains "Submitted".
 */
internal fun ownSubmittedSegmentTypes(
    storedCanonicalTypes: Collection<String>,
    submittedThisSession: Collection<String>,
): Set<String> {
    fun submitType(type: String): String? = when (SkipMarks.canonicalType(type)) {
        "intro" -> "intro"
        "recap" -> "recap"
        "outro" -> "credits"
        "preview" -> "preview"
        else -> null
    }
    return (storedCanonicalTypes + submittedThisSession).mapNotNull(::submitType).toSet()
}

internal fun submittedSegmentButtonLabel(base: String, type: String, ownSubmittedTypes: Set<String>): String =
    if (type in ownSubmittedTypes) "$base · Submitted" else base

internal object TheIntroDb {

    /** Catalogue reads. Frozen v3 still returns the media shape this client parses. */
    const val API_BASE = "https://api.theintrodb.org/v3"
    const val MEDIA_URL = "$API_BASE/media"

    /**
     * Account routes. Current package docs set the base to
     * https://api.theintrodb.org/v4 and say frozen v3 only serves GET /media.
     * Submit and user stats share this base.
     */
    const val ACCOUNT_API_BASE = "https://api.theintrodb.org/v4"
    const val SUBMIT_URL = "$ACCOUNT_API_BASE/submit"
    const val USER_STATS_URL = "$ACCOUNT_API_BASE/user/stats"
    const val SUBMISSIONS_URL = "$ACCOUNT_API_BASE/submissions"
    const val PROVIDER = "theintrodb"

    suspend fun fetchTvIntervals(
        imdbId: String? = null,
        tmdbId: Int? = null,
        season: Int,
        episode: Int,
        durationMs: Long? = null,
    ): List<SkipInterval> {
        val data = getMedia(
            tmdbId = tmdbId,
            imdbId = imdbId,
            season = season,
            episode = episode,
            durationMs = durationMs,
        ) ?: return emptyList()
        return data.tvSkipIntervals(durationMs)
    }

    suspend fun fetchMovieIntervals(
        imdbId: String? = null,
        tmdbId: Int? = null,
        durationMs: Long? = null,
    ): List<SkipInterval> {
        val data = getMedia(
            tmdbId = tmdbId,
            imdbId = imdbId,
            durationMs = durationMs,
        ) ?: return emptyList()
        return data.movieSkipIntervals(durationMs)
    }

    suspend fun getMedia(
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        durationMs: Long? = null,
        apiKey: String? = null,
    ): TheIntroDbMediaResponse? {
        if (tmdbId == null && imdbId.isNullOrBlank()) return null
        val params = buildList {
            if (tmdbId != null && tmdbId > 0) add("tmdb_id=$tmdbId")
            else imdbId?.takeIf { it.isNotBlank() }?.let { add("imdb_id=$it") }
            if (season != null && episode != null) {
                add("season=$season")
                add("episode=$episode")
            }
            if (durationMs != null && durationMs > 0) add("duration_ms=$durationMs")
        }
        val url = "$MEDIA_URL?${params.joinToString("&")}"
        return try {
            val text = if (apiKey.isNullOrBlank()) {
                httpGetText(url)
            } else {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                    body = "",
                ).body
            }
            parseTheIntroDbMedia(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** Why the last [listMySubmissions] call stopped early, or null when it went through. */
    var lastListError: String? = null
        private set

    /** This account's submissions for one title (GET /v4/submissions with the Bearer key). */
    suspend fun listMySubmissions(
        apiKey: String,
        tmdbId: Int,
        mediaType: String,
    ): List<TheIntroDbUserSubmission> {
        lastListError = null
        if (apiKey.isBlank() || tmdbId <= 0) return emptyList()
        val type = if (mediaType.equals("movie", ignoreCase = true)) "movie" else "tv"
        val collected = mutableListOf<TheIntroDbUserSubmission>()
        var offset = 0
        val pageSize = 100
        repeat(10) {
            val url = "$SUBMISSIONS_URL?tmdb_id=$tmdbId&type=$type&limit=$pageSize&offset=$offset"
            val response = try {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = mapOf(
                        "Authorization" to "Bearer $apiKey",
                        "Accept" to "application/json",
                    ),
                    body = "",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lastListError = "Couldn't reach TheIntroDB"
                return collected
            }
            if (response.status == 401 || response.status == 403) {
                lastListError = "TheIntroDB refused the API key (${response.status})"
                return collected
            }
            if (response.status !in 200..299) {
                lastListError = "TheIntroDB error ${response.status}"
                return collected
            }
            if (response.body.isBlank()) return collected
            val page = parseTheIntroDbSubmissions(response.body) ?: return collected
            collected += page.submissions
            val next = offset + page.submissions.size
            if (page.submissions.isEmpty() || page.submissions.size < pageSize || (page.total > 0 && next >= page.total)) {
                return collected
            }
            offset = next
        }
        return collected
    }

    suspend fun submitTimestamp(
        apiKey: String,
        tmdbId: Int,
        imdbId: String? = null,
        type: String,
        segment: String,
        season: Int? = null,
        episode: Int? = null,
        startSec: Double?,
        endSec: Double?,
        videoDurationMs: Long? = null,
        nowEpochMs: () -> Long = { GMTDate().timestamp },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): TheIntroDbSubmitResult {
        if (apiKey.isBlank()) return TheIntroDbSubmitResult.Failed
        val body = theIntroDbSubmitBody(
            TheIntroDbSubmitDraft(
                tmdbId = tmdbId,
                imdbId = imdbId,
                type = type,
                segment = segment,
                season = season,
                episode = episode,
                startSec = startSec,
                endSec = endSec,
                videoDurationMs = videoDurationMs,
            ),
        ) ?: return TheIntroDbSubmitResult.Failed
        return try {
            postSubmitOnce(body, apiKey, nowEpochMs, pause)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TheIntroDbSubmitResult.Failed
        }
    }

    private suspend fun postSubmitOnce(
        body: String,
        apiKey: String,
        nowEpochMs: () -> Long,
        pause: suspend (Long) -> Unit,
    ): TheIntroDbSubmitResult {
        var retried = false
        while (true) {
            val response = httpRequestRaw(
                method = "POST",
                url = SUBMIT_URL,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                ),
                body = body,
            )
            val result = theIntroDbSubmitResult(response.status)
            if (result !is TheIntroDbSubmitResult.Failed) return result
            val wait = if (retried) {
                null
            } else {
                theIntroDbRetryWaitMillis(
                    status = response.status,
                    retryAfterHeader = retryAfterHeaderValue(response.headers),
                    nowEpochMs = nowEpochMs(),
                )
            }
            if (wait == null) return TheIntroDbSubmitResult.Failed
            retried = true
            pause(wait)
        }
    }
}

@Serializable
data class TheIntroDbMediaResponse(
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("intro") val intro: List<TheIntroDbSegment>? = null,
    @SerialName("recap") val recap: List<TheIntroDbSegment>? = null,
    @SerialName("credits") val credits: List<TheIntroDbSegment>? = null,
    @SerialName("preview") val preview: List<TheIntroDbSegment>? = null,
)

@Serializable
data class TheIntroDbSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("submission_count") val submissionCount: Int? = null,
)

internal fun TheIntroDbMediaResponse.tvSkipIntervals(durationMs: Long? = null): List<SkipInterval> = buildList {
    addAll(intro.toTheIntroDbIntervals("intro", durationMs))
    addAll(recap.toTheIntroDbIntervals("recap", durationMs))
    addAll(credits.toTheIntroDbIntervals("outro", durationMs))
    addAll(preview.toTheIntroDbIntervals("preview", durationMs))
}

internal fun TheIntroDbMediaResponse.movieSkipIntervals(durationMs: Long? = null): List<SkipInterval> {
    val creditIntervals = credits.toTheIntroDbIntervals("movie-credits", durationMs)
    val firstCredits = creditIntervals.firstOrNull()
    val laterCredits = creditIntervals.drop(1).map { it.copy(type = "post-credits") }
    val introIntervals = intro.toTheIntroDbIntervals("intro", durationMs)
    // Every segment type TheIntroDB has gets its own Skip button, movies included.
    return listOfNotNull(firstCredits) + laterCredits + introIntervals +
        recap.toTheIntroDbIntervals("recap", durationMs) + preview.toTheIntroDbIntervals("preview", durationMs)
}

@Serializable
internal data class TheIntroDbSubmissionsPage(
    val submissions: List<TheIntroDbUserSubmission> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
internal data class TheIntroDbUserSubmission(
    val id: String? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val segment: String? = null,
    val status: String? = null,
    @kotlinx.serialization.json.JsonNames("start_ms", "startMs") val startMs: Long? = null,
    @kotlinx.serialization.json.JsonNames("end_ms", "endMs") val endMs: Long? = null,
    @kotlinx.serialization.json.JsonNames("video_duration_ms", "videoDurationMs") val videoDurationMs: Long? = null,
)

private fun List<TheIntroDbSegment>?.toTheIntroDbIntervals(
    type: String,
    durationMs: Long?,
): List<SkipInterval> {
    if (this.isNullOrEmpty()) return emptyList()
    val durationSec = durationMs?.takeIf { it > 0L }?.div(1000.0)
    return mapNotNull { segment ->
        val start = (segment.startMs ?: 0L) / 1000.0
        // A null end means the segment runs to the end of the known duration.
        val end = segment.endMs?.div(1000.0) ?: durationSec ?: return@mapNotNull null
        if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) null
        else SkipInterval(start, end, type, TheIntroDb.PROVIDER)
    }
}
