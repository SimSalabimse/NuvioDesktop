package com.nuvio.app.features.player.skip

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The TheIntroDB key in use: the TheIntroDB field, or a non-idb_ key in the IntroDB field. */
internal fun PlayerSettingsUiState.resolvedTheIntroDbKey(): String {
    val introDbAppKey = introDbApiKey.trim()
    val fallback = introDbAppKey.takeIf { it.isNotBlank() && !it.startsWith("idb_", ignoreCase = true) }.orEmpty()
    return theIntroDbApiKey.trim().ifBlank { fallback }
}

/**
 * Brings the user's earlier TheIntroDB submissions for a title onto this computer as their own
 * marks, so they are used for skipping. Runs at most once per title per app session. Rejected
 * submissions and ones without times are skipped; marks already here are kept.
 */
internal object SkipMarkImport {
    private val done = mutableSetOf<String>()

    suspend fun importForTitle(parentMetaId: String, videoId: String?, isMovie: Boolean): Int {
        if (parentMetaId.isBlank()) return 0
        val key = PlayerSettingsRepository.uiState.value.resolvedTheIntroDbKey()
        if (key.isBlank() || !done.add(parentMetaId)) return 0
        val mediaType = if (isMovie) "movie" else "tv"
        val tmdbId = runCatching {
            TmdbService.ensureTmdbId(
                videoId = videoId?.takeIf { it.isNotBlank() } ?: parentMetaId,
                mediaType = mediaType,
                fallbackImdbId = parentMetaId.takeIf { it.startsWith("tt", ignoreCase = true) },
            )?.toIntOrNull()
        }.getOrNull() ?: return 0
        val submissions = TheIntroDb.listMySubmissions(key, tmdbId, mediaType)
        val error = TheIntroDb.lastListError
        var imported = 0
        for (submission in submissions) {
            if (submission.status.equals("rejected", ignoreCase = true)) continue
            val segment = submission.segment ?: continue
            val start = submission.startMs ?: 0L
            val end = submission.endMs ?: submission.videoDurationMs ?: continue
            val season = if (isMovie) null else submission.season ?: continue
            val episode = if (isMovie) null else submission.episode ?: continue
            if (SkipMarks.import(parentMetaId, season, episode, segment, start, end)) imported++
        }
        if (imported > 0) SkipMarks.flushImports()
        Logger.withTag("SkipMarks").i { "import tmdb=$tmdbId submissions=${submissions.size} imported=$imported" }
        SkipImportStatus.record(found = submissions.size, imported = imported, error = error)
        return imported
    }
}

/** The last TheIntroDB import, shown in Playback settings. */
internal object SkipImportStatus {
    private const val KEY = "import_status"

    data class Status(
        val atMs: Long,
        val found: Int,
        val imported: Int,
        val totalImported: Int,
        val error: String?,
    )

    private val state = MutableStateFlow<Status?>(null)
    private var loaded = false

    val status: StateFlow<Status?>
        get() {
            ensureLoaded()
            return state
        }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        state.value = runCatching { SkipMarksStorage.load(KEY) }.getOrNull()?.let(::decode)
    }

    fun record(found: Int, imported: Int, error: String?) {
        ensureLoaded()
        val total = (state.value?.totalImported ?: 0) + imported
        val next = Status(kotlin.time.Clock.System.now().toEpochMilliseconds(), found, imported, total, error)
        state.value = next
        runCatching {
            SkipMarksStorage.save(
                KEY,
                listOf(next.atMs, next.found, next.imported, next.totalImported, next.error.orEmpty()).joinToString("|"),
            )
        }
    }

    private fun decode(raw: String): Status? {
        val parts = raw.split("|", limit = 5)
        if (parts.size < 5) return null
        return Status(
            atMs = parts[0].toLongOrNull() ?: return null,
            found = parts[1].toIntOrNull() ?: 0,
            imported = parts[2].toIntOrNull() ?: 0,
            totalImported = parts[3].toIntOrNull() ?: 0,
            error = parts[4].takeIf { it.isNotBlank() },
        )
    }

    fun describe(status: Status?, nowMs: Long): String {
        if (status == null) return "Not run yet. It runs when you play something with your TheIntroDB key set."
        val min = ((nowMs - status.atMs) / 60_000L).coerceAtLeast(0L)
        val ago = when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
        status.error?.let { return "$it · $ago" }
        val last = when {
            status.imported > 0 -> "Imported ${status.imported} of your TheIntroDB submissions"
            status.found > 0 -> "${status.found} submissions found, all already here"
            else -> "No submissions of yours for that title"
        }
        return "$last · $ago · ${status.totalImported} imported in total"
    }
}
