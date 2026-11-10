package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncRunPolicyFuzzTest {
    @Test
    fun oddPolicyInputsDoNotThrow() {
        var fuzzed = 0

        listOf(true, false).forEach { enabled ->
            fuzzed += 1
            assertEquals(
                if (enabled) AutoSyncStartAction.RUN else AutoSyncStartAction.ATTACH_ORIGINAL,
                decideAutoSyncStart(enabled),
            )
        }

        val lists = listOf(
            emptyList(),
            listOf(AutoSyncSubtitleCandidate(url = "", language = "")),
            listOf(
                AutoSyncSubtitleCandidate(url = "track-a", language = "en"),
                AutoSyncSubtitleCandidate(url = "track-a", language = "en"),
            ),
            List(1_000) { index -> AutoSyncSubtitleCandidate(url = "track-$index", language = "und") },
        )
        AutoSyncCandidateScope.entries.forEach { scope ->
            lists.forEach { candidates ->
                fuzzed += 1
                val alternatives = scope.alternativeCandidates(candidates)
                if (scope == AutoSyncCandidateScope.STARTUP_SEARCH) {
                    assertEquals(candidates, alternatives)
                    assertTrue(scope.usesAlternativeProvider)
                } else {
                    assertTrue(alternatives.isEmpty())
                    assertFalse(scope.usesAlternativeProvider)
                }
            }
        }

        listOf(null, "", " ", "track-1", "k".repeat(2_000)).forEach { key ->
            fuzzed += 1
            assertEquals(key == null, shouldRestoreOriginalSubtitle(key))
        }

        assertEquals(15, fuzzed)
    }
}
