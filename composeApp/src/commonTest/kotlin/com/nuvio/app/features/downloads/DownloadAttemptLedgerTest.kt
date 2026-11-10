package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadAttemptLedgerTest {
    @Test
    fun aCallbackFromThePausedAttemptDoesNotChangeTheResumedDownload() {
        val ledger = DownloadAttemptLedger()
        val first = ledger.begin("episode")
        val item = sample(DownloadStatus.Downloading)
        ledger.end("episode")
        val resumed = item.copy(status = DownloadStatus.Downloading, downloadedBytes = 20L)
        val second = ledger.begin("episode")

        val afterStalePause = resumed.withPlatformPause(ledger.isCurrent("episode", first))
        assertEquals(DownloadStatus.Downloading, afterStalePause.status)
        assertEquals(20L, afterStalePause.downloadedBytes)

        val afterStaleSuccess = afterStalePause.withPlatformSuccess(
            attemptIsCurrent = ledger.isCurrent("episode", first),
            localFileUri = "local-file",
            totalBytes = 99L,
            nowEpochMs = 5L,
        )
        assertEquals(DownloadStatus.Downloading, afterStaleSuccess.status)
        assertNull(afterStaleSuccess.localFileUri)

        val afterStaleFailure = afterStaleSuccess.withPlatformFailure(
            attemptIsCurrent = ledger.isCurrent("episode", first),
            message = "old failure",
            nowEpochMs = 6L,
        )
        assertEquals(DownloadStatus.Downloading, afterStaleFailure.status)
        assertNull(afterStaleFailure.errorMessage)

        val paused = afterStaleFailure.withPlatformPause(ledger.isCurrent("episode", second))
        assertEquals(DownloadStatus.Paused, paused.status)
        assertTrue(ledger.isCurrent("episode", second))
        assertFalse(ledger.isCurrent("episode", first))
    }

    @Test
    fun progressFromAnOldAttemptDoesNotRewriteBytes() {
        val ledger = DownloadAttemptLedger()
        val first = ledger.begin("movie")
        val second = ledger.begin("movie")
        val item = sample(DownloadStatus.Downloading).copy(downloadedBytes = 40L, totalBytes = 80L)
        val stale = item.withPlatformProgress(
            attemptIsCurrent = ledger.isCurrent("movie", first),
            downloadedBytes = -25L,
            totalBytes = 10L,
            nowEpochMs = 1L,
        )
        assertEquals(40L, stale.downloadedBytes)
        assertEquals(80L, stale.totalBytes)
        val current = item.withPlatformProgress(
            attemptIsCurrent = ledger.isCurrent("movie", second),
            downloadedBytes = -25L,
            totalBytes = 0L,
            nowEpochMs = 2L,
        )
        assertEquals(0L, current.downloadedBytes)
        assertNull(current.totalBytes)
        assertFalse(ledger.isCurrent("movie", first))
    }

    @Test
    fun rapidPauseResumeAndCancelStayInAKnownState() {
        val ledger = DownloadAttemptLedger()
        var item: DownloadItem? = sample(DownloadStatus.Downloading)
        var attempt = ledger.begin(item!!.id)
        var fuzzed = 0
        repeat(300) { step ->
            val current = item ?: sample(DownloadStatus.Downloading).copy(id = "again-$step")
            when (step % 4) {
                0 -> {
                    val stale = attempt
                    ledger.end(current.id)
                    fuzzed += 1
                    val ignored = current.withPlatformPause(ledger.isCurrent(current.id, stale))
                    item = if (current.status == DownloadStatus.Downloading) {
                        current.copy(status = DownloadStatus.Paused, errorMessage = null)
                    } else {
                        ignored
                    }
                }
                1 -> {
                    fuzzed += 1
                    if (current.status == DownloadStatus.Paused || current.status == DownloadStatus.Failed) {
                        item = current.copy(status = DownloadStatus.Downloading, errorMessage = null, localFileUri = null)
                        attempt = ledger.begin(current.id)
                    }
                }
                2 -> {
                    fuzzed += 1
                    item = current.withPlatformFailure(
                        attemptIsCurrent = ledger.isCurrent(current.id, attempt),
                        message = "stopped",
                        nowEpochMs = step.toLong(),
                    )
                }
                else -> {
                    fuzzed += 1
                    ledger.end(current.id)
                    item = null
                }
            }
            item?.let { latest ->
                assertTrue(latest.downloadedBytes >= 0L)
                assertTrue(latest.status in DownloadStatus.entries)
            }
        }
        assertEquals(300, fuzzed)
        assertNull(item)
    }

    private fun sample(status: DownloadStatus) = DownloadItem(
        id = "episode",
        contentType = "series",
        parentMetaId = "meta",
        parentMetaType = "series",
        videoId = "video",
        title = "Episode",
        streamTitle = "Stream",
        providerName = "Simsalabim",
        sourceUrl = "local-source",
        fileName = "episode.mp4",
        status = status,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )
}
