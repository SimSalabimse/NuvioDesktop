package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object TheIntroDb {

    const val API_BASE = "https://api.theintrodb.org/v3"
    const val MEDIA_URL = "$API_BASE/media"
    const val SUBMIT_URL = "$API_BASE/submit"
    const val SUBMISSIONS_URL = "$API_BASE/submissions"
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

    /** This account's submissions for one title (GET /v3/submissions with the Bearer key). */
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
    ): Boolean {
        if (apiKey.isBlank() || tmdbId <= 0) return false
        val normalizedType = if (type.equals("movie", ignoreCase = true)) "movie" else "tv"
        val normalizedSegment = when (segment.lowercase()) {
            "intro", "op", "mixed-op" -> "intro"
            "recap" -> "recap"
            "outro", "ed", "credits", "ending", "movie-credits" -> "credits"
            "preview" -> "preview"
            else -> return false
        }
        val body = buildJsonObject {
            put("tmdb_id", tmdbId)
            if (!imdbId.isNullOrBlank()) put("imdb_id", imdbId)
            put("type", normalizedType)
            put("segment", normalizedSegment)
            if (normalizedType == "tv") {
                if (season != null) put("season", season)
                if (episode != null) put("episode", episode)
            }
            if (videoDurationMs != null && videoDurationMs > 0) {
                put("video_duration_ms", videoDurationMs)
            }
            if (startSec != null) put("start_ms", (startSec * 1000.0).toLong())
            if (endSec != null) put("end_ms", (endSec * 1000.0).toLong())
        }.toString()
        return try {
            val response = httpRequestRaw(
                method = "POST",
                url = SUBMIT_URL,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                ),
                body = body,
            )
            response.status == 200 || response.status == 201
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
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
