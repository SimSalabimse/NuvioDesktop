package com.nuvio.app.features.analyzeddetails

internal const val SYNC_FORMAT_VERSION: Int = 1
internal const val CUT_TOLERANCE_MS: Long = 30_000L
internal const val KDF_ITERATIONS: Int = 600_000
internal const val MIN_PASSPHRASE_LENGTH: Int = 8
internal const val MIN_ENROLL_SECRET_LENGTH: Int = 16
internal const val SYNC_DEBOUNCE_MS: Long = 2_000L
internal const val MAX_BLOBS_PER_BATCH: Int = 50
internal const val MAX_PLAINTEXT_BYTES: Int = 8192
internal const val MAX_SYNC_ATTEMPTS: Int = 4

/** `retry-after` is whole seconds, at least 1, and capped at the 10 minute rate window. */
internal fun retryDelayMs(retryAfterSeconds: Long?): Long = (retryAfterSeconds ?: 1L).coerceIn(1L, 600L) * 1000L

internal val SEGMENT_TYPES: List<String> = listOf("intro", "recap", "credits", "preview")

internal enum class SyncMode { Incremental, FullResync }

internal fun syncMode(lastSuccessfulSyncMs: Long?, nowMs: Long): SyncMode {
    if (lastSuccessfulSyncMs == null) return SyncMode.Incremental
    if (nowMs - lastSuccessfulSyncMs >= TOMBSTONE_KEEP_MS) return SyncMode.FullResync
    return SyncMode.Incremental
}

internal fun <T> recordsToUpload(local: List<T>, lastSuccessfulSyncMs: Long?, nowMs: Long, wallMs: (T) -> Long): List<T> {
    if (syncMode(lastSuccessfulSyncMs, nowMs) == SyncMode.Incremental) return local
    val cutoff = nowMs - TOMBSTONE_KEEP_MS
    return local.filter { wallMs(it) > cutoff }
}

internal data class PullPage(val cursor: Long, val seqs: List<Long>)

internal fun advancePullCursor(previous: Long, page: PullPage): Long {
    if (previous < 0L || page.cursor < 0L) throw IllegalArgumentException("bad cursor")
    if (page.seqs.isEmpty()) {
        if (page.cursor != previous) throw IllegalArgumentException("empty page must keep the cursor")
        return previous
    }
    var prevSeq = previous
    for (seq in page.seqs) {
        if (seq <= prevSeq) throw IllegalArgumentException("seq went backwards")
        prevSeq = seq
    }
    if (page.cursor != prevSeq) throw IllegalArgumentException("cursor must be the last pulled seq")
    return page.cursor
}
