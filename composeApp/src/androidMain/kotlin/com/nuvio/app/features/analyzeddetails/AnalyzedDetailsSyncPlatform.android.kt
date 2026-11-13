package com.nuvio.app.features.analyzeddetails

import androidx.compose.runtime.Composable
import com.nuvio.app.features.player.skip.SkipInterval

internal actual val analyzedDetailsSyncSupported: Boolean = false

@Composable
internal actual fun AnalyzedDetailsSyncSettings(isTablet: Boolean) = Unit

internal actual fun overlayAnalyzedDetails(
    intervals: List<SkipInterval>,
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    fileDurationMs: Long,
    isMovie: Boolean,
): List<SkipInterval> = intervals

internal actual fun noteTheIntroDbSubmissions(accountKey: String, notes: List<AnalyzedSubmissionNote>) = Unit
