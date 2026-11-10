package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * One id per [DownloadsPlatformDownloader.start] call. A callback from an older
 * pause or failure must not change the download that replaced it.
 */
internal class DownloadAttemptLedger {
    private val attempts = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var nextAttempt = 0L

    fun begin(downloadId: String): Long {
        var assigned = 0L
        attempts.update { current ->
            nextAttempt += 1L
            assigned = nextAttempt
            current + (downloadId to assigned)
        }
        return assigned
    }

    fun end(downloadId: String) {
        attempts.update { current -> current - downloadId }
    }

    fun clear() {
        attempts.value = emptyMap()
    }

    fun isCurrent(downloadId: String, attemptId: Long): Boolean =
        attempts.value[downloadId] == attemptId
}

internal fun DownloadItem.withPlatformPause(attemptIsCurrent: Boolean): DownloadItem =
    if (!attemptIsCurrent || status != DownloadStatus.Downloading) {
        this
    } else {
        copy(status = DownloadStatus.Paused, errorMessage = null)
    }

internal fun DownloadItem.withPlatformProgress(
    attemptIsCurrent: Boolean,
    downloadedBytes: Long,
    totalBytes: Long?,
    nowEpochMs: Long,
): DownloadItem {
    if (!attemptIsCurrent || status != DownloadStatus.Downloading) return this
    return copy(
        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
        totalBytes = totalBytes?.takeIf { it > 0L },
        updatedAtEpochMs = nowEpochMs,
        errorMessage = null,
    )
}

internal fun DownloadItem.withPlatformSuccess(
    attemptIsCurrent: Boolean,
    localFileUri: String,
    totalBytes: Long?,
    nowEpochMs: Long,
): DownloadItem {
    if (!attemptIsCurrent || status != DownloadStatus.Downloading) return this
    return copy(
        status = DownloadStatus.Completed,
        localFileUri = localFileUri,
        downloadedBytes = if (totalBytes != null && totalBytes > 0L) totalBytes else downloadedBytes,
        totalBytes = totalBytes?.takeIf { it > 0L } ?: this.totalBytes,
        errorMessage = null,
        updatedAtEpochMs = nowEpochMs,
    )
}

internal fun DownloadItem.withPlatformFailure(
    attemptIsCurrent: Boolean,
    message: String,
    nowEpochMs: Long,
): DownloadItem {
    if (!attemptIsCurrent || status != DownloadStatus.Downloading) return this
    return copy(
        status = DownloadStatus.Failed,
        errorMessage = message,
        updatedAtEpochMs = nowEpochMs,
    )
}
