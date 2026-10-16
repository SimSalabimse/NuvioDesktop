package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.SkipMarkImport
import com.nuvio.app.features.player.skip.SkipMarks
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.resolvedTheIntroDbKey
import com.nuvio.app.features.player.skip.skipRangeLabel
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val SKIP_FEEDBACK_DELAY_MS = 4_000L
internal const val SKIP_FEEDBACK_VISIBLE_MS = 8_000L

private val PlayerScreenRuntime.skipIsMovie: Boolean
    get() = (contentType ?: parentMetaType).equals("movie", ignoreCase = true)

/** [intervals] with the user's own marks for the playing episode or movie in place. */
internal fun PlayerScreenRuntime.withUserSkipMarks(intervals: List<SkipInterval>): List<SkipInterval> =
    SkipMarks.applyTo(
        intervals = intervals,
        parentMetaId = parentMetaId,
        season = if (skipIsMovie) null else activeSeasonNumber,
        episode = if (skipIsMovie) null else activeEpisodeNumber,
        isMovie = skipIsMovie,
    )

/** Imports the user's TheIntroDB submissions for this title once, then re-applies marks. */
internal suspend fun PlayerScreenRuntime.importUserSkipMarks() {
    val imported = runCatching {
        SkipMarkImport.importForTitle(parentMetaId, activeVideoId, skipIsMovie)
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        0
    }
    if (imported > 0) skipIntervals = withUserSkipMarks(skipIntervals)
}

internal fun PlayerScreenRuntime.clearSkipFeedback() {
    skipFeedbackJob?.cancel()
    skipFeedbackJob = null
    skipFeedbackInterval = null
    skipFeedbackWrong = false
}

/** After a tap on Skip: ask Good / Wrong 4 s after the skip lands, for 8 s. Own marks are not asked about. */
internal fun PlayerScreenRuntime.offerSkipFeedback(interval: SkipInterval) {
    clearSkipFeedback()
    if (SkipMarks.isUserProvider(interval.provider)) return
    if (SkipMarks.canonicalType(interval.type) == null) return
    skipFeedbackJob = scope.launch {
        delay(SKIP_FEEDBACK_DELAY_MS)
        skipFeedbackInterval = interval
        skipFeedbackWrong = false
        delay(SKIP_FEEDBACK_VISIBLE_MS)
        if (!skipFeedbackWrong) skipFeedbackInterval = null
    }
}

internal fun PlayerScreenRuntime.skipFeedbackLabel(): String {
    val interval = skipFeedbackInterval ?: return ""
    val name = when (SkipMarks.canonicalType(interval.type)) {
        "intro" -> "intro"
        "recap" -> "recap"
        "outro" -> "credits"
        "preview" -> "preview"
        else -> "skip"
    }
    return if (skipFeedbackWrong) {
        "Play to where the $name really ends, then press End is here"
    } else {
        "Good $name skip? ${skipRangeLabel(interval.startTime, interval.endTime)}"
    }
}

/** Handles the Good / Wrong prompt actions from the player controls. Returns true when handled. */
internal fun PlayerScreenRuntime.handleSkipFeedbackAction(action: String): Boolean {
    val interval = skipFeedbackInterval
    when (action) {
        "skipFeedbackGood" -> {
            if (interval == null) return true
            clearSkipFeedback()
            saveUserSkipMark(interval.type, interval.startTime, interval.endTime, submit = true)
        }
        "skipFeedbackWrong" -> {
            if (interval == null) return true
            skipFeedbackJob?.cancel()
            skipFeedbackWrong = true
        }
        "skipFeedbackRewind" -> {
            val target = (playbackSnapshot.positionMs - 5_000L).coerceAtLeast(0L)
            playerController?.seekTo(target)
            scheduleProgressSyncAfterSeek()
        }
        "skipFeedbackEndHere" -> {
            if (interval == null) return true
            val endSec = playbackSnapshot.positionMs / 1000.0
            clearSkipFeedback()
            if (endSec > interval.startTime + 1.0) {
                saveUserSkipMark(interval.type, interval.startTime, endSec, submit = true)
            } else {
                showSkipNotice("The end has to be after the start")
            }
        }
        "skipFeedbackDismiss" -> clearSkipFeedback()
        else -> return false
    }
    return true
}

private fun PlayerScreenRuntime.showSkipNotice(message: String) {
    playerNotificationMessage = message
    playerNotificationToken += 1L
}

/**
 * Saves the user's own mark for the playing episode, uses it right away, and (with [submit],
 * Submit turned on and a key set) sends it to TheIntroDB and, for idb_ keys, IntroDB.
 */
internal fun PlayerScreenRuntime.saveUserSkipMark(type: String, startSec: Double, endSec: Double, submit: Boolean) {
    val category = SkipMarks.canonicalType(type) ?: return
    val isMovie = skipIsMovie
    val season = if (isMovie) null else activeSeasonNumber
    val episode = if (isMovie) null else activeEpisodeNumber
    val saved = SkipMarks.record(
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
        type = category,
        startMs = (startSec * 1000).toLong(),
        endMs = (endSec * 1000).toLong(),
    )
    if (!saved) return
    skipIntervals = withUserSkipMarks(skipIntervals)
    val alreadySubmitted = category in submittedSegmentTypesInSession ||
        (category == "outro" && "credits" in submittedSegmentTypesInSession)
    scope.launch {
        var sent = false
        val settings = PlayerSettingsRepository.uiState.value
        if (submit && settings.introSubmitEnabled && !alreadySubmitted) {
            sent = runCatching { submitUserSkipMark(category, startSec, endSec, season, episode, isMovie) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    false
                }
            if (sent) submittedSegmentTypesInSession.add(if (category == "outro") "credits" else category)
            Logger.withTag("Player/SkipFeedback").i { "submit type=$category sent=$sent" }
        }
        showSkipNotice(if (sent) "Saved and sent. Thanks!" else "Saved on this computer")
    }
}

private suspend fun PlayerScreenRuntime.submitUserSkipMark(
    category: String,
    startSec: Double,
    endSec: Double,
    season: Int?,
    episode: Int?,
    isMovie: Boolean,
): Boolean {
    val settings = PlayerSettingsRepository.uiState.value
    val imdbId = activeVideoId?.split(":")?.firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: parentMetaId.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.id?.takeIf { it.startsWith("tt") }
    var sent = false
    val introDbAppKey = settings.introDbApiKey.trim()
    if (!isMovie && introDbAppKey.startsWith("idb_", ignoreCase = true) && imdbId != null && season != null && episode != null) {
        sent = SkipIntroRepository.submitIntro(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            segmentType = if (category == "outro") "credits" else category,
        ) || sent
    }
    val theIntroKey = settings.resolvedTheIntroDbKey()
    if (theIntroKey.isNotBlank()) {
        val mediaType = if (isMovie) "movie" else "tv"
        val tmdbId = TmdbService.ensureTmdbId(
            videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: (imdbId ?: parentMetaId),
            mediaType = mediaType,
            fallbackImdbId = imdbId,
        )?.toIntOrNull()
        if (tmdbId != null && tmdbId > 0) {
            sent = TheIntroDb.submitTimestamp(
                apiKey = theIntroKey,
                tmdbId = tmdbId,
                imdbId = imdbId,
                type = mediaType,
                segment = if (category == "outro") "credits" else category,
                season = season?.takeIf { !isMovie && it > 0 },
                episode = episode?.takeIf { !isMovie && it > 0 },
                startSec = startSec,
                endSec = endSec,
                videoDurationMs = playbackSnapshot.durationMs.takeIf { it > 0L },
            ) || sent
        }
    }
    return sent
}
