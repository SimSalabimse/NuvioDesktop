package com.nuvio.app.features.player

import kotlin.math.abs

/** True unless both lengths are known and differ by more than 30 s (another cut of the episode). */
internal fun sameCut(analysedMs: Long, playingMs: Long): Boolean =
    analysedMs <= 0L || playingMs <= 0L || abs(analysedMs - playingMs) <= SAME_CUT_TOLERANCE_MS

private const val SAME_CUT_TOLERANCE_MS = 30_000L
