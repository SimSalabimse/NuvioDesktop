package com.nuvio.app.features.analyzeddetails

import androidx.compose.runtime.Composable
import com.nuvio.app.features.player.skip.SkipInterval

internal expect val analyzedDetailsSyncSupported: Boolean

@Composable
internal expect fun AnalyzedDetailsSyncSettings(isTablet: Boolean)

internal expect fun overlayAnalyzedDetails(
    intervals: List<SkipInterval>,
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    fileDurationMs: Long,
    isMovie: Boolean,
): List<SkipInterval>

internal data class AnalyzedSubmissionNote(
    val remoteId: String,
    val tmdbId: Int,
    val mediaType: String,
    val season: Int?,
    val episode: Int?,
    val segment: String,
    val status: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
)

internal expect fun noteTheIntroDbSubmissions(accountKey: String, notes: List<AnalyzedSubmissionNote>)
