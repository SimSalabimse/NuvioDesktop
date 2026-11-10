package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.sync.ProgressSyncRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchProgressStressTest {
    @Test
    fun aShortPlaceholderAtNinetyFourPercentStaysIncomplete() {
        val decoded = decode(lastPositionMs = 940, durationMs = 1_000, lastUpdatedEpochMs = 100)
        assertEquals("show_s1e2", decoded.single().progressKey)
        assertEquals(940L, decoded.single().lastPositionMs)
        assertFalse(decoded.single().isCompleted)
    }

    @Test
    fun aNegativePositionBecomesZeroAndDoesNotComplete() {
        val decoded = decode(lastPositionMs = -50, durationMs = 1_000_000, lastUpdatedEpochMs = 100)
        assertEquals(0L, decoded.single().lastPositionMs)
        assertFalse(decoded.single().isCompleted)
    }

    @Test
    fun zeroAndNegativeDurationsDoNotCountAsFinished() {
        assertFalse(decode(lastPositionMs = 940, durationMs = 0, lastUpdatedEpochMs = 100).single().isCompleted)
        assertFalse(decode(lastPositionMs = 940, durationMs = -1, lastUpdatedEpochMs = 100).single().isCompleted)
        assertFalse(
            decode(lastPositionMs = 113_739, durationMs = 120_999, lastUpdatedEpochMs = 100).single().isCompleted,
        )
    }

    @Test
    fun duplicateKeysKeepTheNewerEntry() {
        val decoded = WatchProgressCodec.decodeEntries(
            """
            {
              "entries": [
                ${entryJson(lastPositionMs = 100, durationMs = 1_000_000, lastUpdatedEpochMs = 20)},
                ${entryJson(lastPositionMs = 500, durationMs = 1_000_000, lastUpdatedEpochMs = 10)}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, decoded.size)
        assertEquals(100L, decoded.single().lastPositionMs)
        assertEquals("show_s1e2", decoded.single().progressKey)
    }

    @Test
    fun anEmptySnapshotMergesToNothing() {
        val merged = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = emptyList(),
            localEntries = emptyList(),
            dirtyProgressKeys = emptySet(),
        )
        assertTrue(merged.isEmpty())
    }

    @Test
    fun aNewerDuplicateServerRowReplacesTheOlderOne() {
        val merged = WatchProgressRepository.mergeWatchProgressEntriesPreservingUnsynced(
            serverEntries = listOf(
                record(lastWatched = 10L, position = 100L),
                record(lastWatched = 30L, position = 400L),
            ),
            localEntries = emptyList(),
            dirtyProgressKeys = emptySet(),
        )
        assertEquals(1, merged.size)
        assertEquals(400L, merged.values.single().lastPositionMs)
        assertEquals("show_s1e2", merged.keys.single())
    }

    private fun decode(lastPositionMs: Long, durationMs: Long, lastUpdatedEpochMs: Long): List<WatchProgressEntry> =
        WatchProgressCodec.decodeEntries(
            """
            { "entries": [ ${entryJson(lastPositionMs, durationMs, lastUpdatedEpochMs)} ] }
            """.trimIndent(),
        )

    private fun entryJson(lastPositionMs: Long, durationMs: Long, lastUpdatedEpochMs: Long): String =
        """
        {
          "contentType": "series",
          "parentMetaId": "show",
          "parentMetaType": "series",
          "videoId": "show:1:2",
          "title": "Show",
          "seasonNumber": 1,
          "episodeNumber": 2,
          "lastPositionMs": $lastPositionMs,
          "durationMs": $durationMs,
          "lastUpdatedEpochMs": $lastUpdatedEpochMs
        }
        """.trimIndent()

    private fun record(lastWatched: Long, position: Long): ProgressSyncRecord =
        ProgressSyncRecord(
            contentId = "show",
            contentType = "series",
            videoId = "show:1:2",
            season = 1,
            episode = 2,
            position = position,
            duration = 1_000_000L,
            lastWatched = lastWatched,
            progressKey = "show_s1e2",
        )
}
