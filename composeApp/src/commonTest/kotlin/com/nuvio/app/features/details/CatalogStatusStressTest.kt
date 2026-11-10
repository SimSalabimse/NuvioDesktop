package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.SkipMark
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogStatusStressTest {
    private val mark = SkipMark(startMs = 0L, endMs = 10_000L)

    @Test
    fun duplicateAndAliasTypesCollapseToOneLabelInOrder() {
        assertEquals(
            "Public · Intro, Recap, Credits",
            publicCatalogLine(
                CatalogRead.Listed,
                listOf("credits", "op", "intro", "ed", "recap", "intro"),
            ),
        )
    }

    @Test
    fun aListedReadWithNoTypesIsNone() {
        assertEquals("Public · None", publicCatalogLine(CatalogRead.Listed, emptyList()))
        assertEquals("Public · None", publicCatalogLine(CatalogRead.Listed, listOf("  ", "")))
    }

    @Test
    fun anAliasMarkIsTheOwnIntroLineOnce() {
        val lines = downloadSkipLines(
            isEpisode = true,
            ownMarks = mapOf("op" to mark, "ed" to mark),
            recapOutcome = null,
            userRecap = null,
            durationMs = 1_000_000L,
        )
        assertEquals("Yours · Intro, Credits", lines.ownLine)
    }

    @Test
    fun emptySeasonAndDuplicateAliasesStayOneRow() {
        assertEquals(emptyList(), seasonOverviewSections(emptyList()))
        val episode = SeasonEpisodeRef(season = 1, episode = 2, title = "Cold open", code = "S1 E2")
        val rows = seasonOverviewRows(
            episodes = listOf(episode),
            marksFor = { mapOf("op" to mark, "intro" to mark) },
            publicFor = { CatalogRead.Listed to listOf("intro", "intro", "op") },
        )
        assertEquals("Public · Intro", rows.single().publicLine)
        assertEquals("Yours · Intro", rows.single().ownLine)
        assertEquals(listOf(1), seasonOverviewSections(rows).map { it.season })
    }

    @Test
    fun zeroAndNegativeDurationsDoNotThrow() {
        val zero = downloadSkipLines(
            isEpisode = true,
            ownMarks = emptyMap(),
            recapOutcome = "rejected-no-match",
            userRecap = null,
            durationMs = 0L,
        )
        val negative = downloadSkipLines(
            isEpisode = true,
            ownMarks = emptyMap(),
            recapOutcome = null,
            userRecap = null,
            durationMs = -1L,
        )
        assertEquals("Public · Not checked", zero.publicLine)
        assertEquals("Yours · No recap found", zero.ownLine)
        assertEquals("Yours · Not checked", negative.ownLine)
    }
}
