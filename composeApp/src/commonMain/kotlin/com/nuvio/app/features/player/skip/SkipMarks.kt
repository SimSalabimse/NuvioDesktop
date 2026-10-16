package com.nuvio.app.features.player.skip

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One of the user's own skip marks for an episode or movie. */
@Serializable
internal data class SkipMark(
    val startMs: Long,
    val endMs: Long,
    val atMs: Long = 0L,
    val imported: Boolean = false,
)

/**
 * The user's own skip marks (from Good / Wrong after Skip, Submit Timestamps, or imported from
 * their TheIntroDB submissions). A mark always wins over TheIntroDB, IntroDB and the anime
 * providers for its episode and type.
 */
internal object SkipMarks {
    const val PROVIDER = "you"
    const val PROVIDER_IMPORTED = "you-theintrodb"
    private const val KEY = "marks_v1"

    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false
    private val marks = mutableMapOf<String, Map<String, SkipMark>>()
    private val revisionState = MutableStateFlow(0L)
    val revision: StateFlow<Long> = revisionState.asStateFlow()

    fun isUserProvider(provider: String): Boolean = provider == PROVIDER || provider == PROVIDER_IMPORTED

    /** intro / recap / outro / preview, or null for types that cannot be marked. */
    fun canonicalType(type: String): String? = when (type.trim().lowercase()) {
        "intro", "op", "mixed-op", "opening" -> "intro"
        "recap" -> "recap"
        "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "outro"
        "preview" -> "preview"
        else -> null
    }

    fun keyFor(parentMetaId: String, season: Int?, episode: Int?): String =
        if (season == null || episode == null) parentMetaId else "$parentMetaId:$season:$episode"

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = runCatching { SkipMarksStorage.load(KEY) }.getOrNull() ?: return
        runCatching { json.decodeFromString<Map<String, Map<String, SkipMark>>>(raw) }
            .onSuccess { marks.putAll(it) }
    }

    private fun persist() {
        runCatching { SkipMarksStorage.save(KEY, json.encodeToString(marks.toMap())) }
        revisionState.value += 1
    }

    fun marksFor(parentMetaId: String, season: Int?, episode: Int?): Map<String, SkipMark> {
        if (parentMetaId.isBlank()) return emptyMap()
        ensureLoaded()
        return marks[keyFor(parentMetaId, season, episode)].orEmpty()
    }

    fun hasAnyForShow(parentMetaId: String): Boolean {
        ensureLoaded()
        return marks.keys.any { it == parentMetaId || it.startsWith("$parentMetaId:") }
    }

    @OptIn(ExperimentalTime::class)
    fun record(parentMetaId: String, season: Int?, episode: Int?, type: String, startMs: Long, endMs: Long): Boolean {
        val category = canonicalType(type) ?: return false
        if (parentMetaId.isBlank() || endMs <= startMs || startMs < 0L) return false
        ensureLoaded()
        val key = keyFor(parentMetaId, season, episode)
        marks[key] = marks[key].orEmpty() + (category to SkipMark(startMs, endMs, Clock.System.now().toEpochMilliseconds()))
        persist()
        Logger.withTag("SkipMarks").i { "mark key=$key type=$category $startMs-$endMs" }
        return true
    }

    /** Stores an imported mark unless the episode already has one of that type. */
    fun import(parentMetaId: String, season: Int?, episode: Int?, type: String, startMs: Long, endMs: Long): Boolean {
        val category = canonicalType(type) ?: return false
        if (parentMetaId.isBlank() || endMs <= startMs || startMs < 0L) return false
        ensureLoaded()
        val key = keyFor(parentMetaId, season, episode)
        val existing = marks[key].orEmpty()
        if (existing.containsKey(category)) return false
        marks[key] = existing + (category to SkipMark(startMs, endMs, 0L, imported = true))
        return true
    }

    fun flushImports() = persist()

    /**
     * [intervals] with the user's marks for this episode in place of the fetched times of the
     * same type. Movie credits keep the movie-credits type so post-credits handling still works.
     */
    fun applyTo(
        intervals: List<SkipInterval>,
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        isMovie: Boolean,
    ): List<SkipInterval> {
        val mine = marksFor(parentMetaId, season, episode)
        if (mine.isEmpty()) return intervals
        val kept = intervals.filter { interval ->
            val category = canonicalType(interval.type)
            category == null || category !in mine
        }
        val added = mine.map { (category, mark) ->
            val type = when {
                category == "outro" && isMovie -> "movie-credits"
                else -> category
            }
            SkipInterval(
                startTime = mark.startMs / 1000.0,
                endTime = mark.endMs / 1000.0,
                type = type,
                provider = if (mark.imported) PROVIDER_IMPORTED else PROVIDER,
            )
        }
        return (kept + added).sortedBy { it.startTime }
    }
}

/** Where a skip's times came from, for the Skip button and Downloads. */
internal fun skipSourceLabel(provider: String): String = when (provider.trim().lowercase()) {
    SkipMarks.PROVIDER -> "Your mark"
    SkipMarks.PROVIDER_IMPORTED -> "Your TheIntroDB mark"
    TheIntroDb.PROVIDER -> "TheIntroDB"
    "introdb" -> "IntroDB"
    "aniskip" -> "AniSkip"
    "animeskip" -> "Anime-Skip"
    else -> provider
}

/** "1:12–2:41" (or "1:02:03–1:04:00" past an hour). */
internal fun skipRangeLabel(startSec: Double, endSec: Double): String =
    "${formatSkipClock(startSec)}–${formatSkipClock(endSec)}"

internal fun formatSkipClock(seconds: Double): String {
    val total = seconds.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}
