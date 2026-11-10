package com.nuvio.app.features.details

import com.nuvio.app.features.analysis.UserSegment
import com.nuvio.app.features.player.skip.SkipMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogStatusTest {
    @Test
    fun publicAndOwnStayOnSeparateLines() {
        assertEquals("Public · not checked", publicCatalogLine(CatalogRead.NotChecked, emptyList()))
        assertEquals("Public · none", publicCatalogLine(CatalogRead.None, emptyList()))
        assertEquals("Public · unavailable", publicCatalogLine(CatalogRead.Unavailable, emptyList()))
        assertEquals("Public · Intro, Credits", publicCatalogLine(CatalogRead.Listed, listOf("intro", "outro")))
        assertEquals("Yours · not submitted", ownCatalogLine(CatalogRead.None, hasOwnChips = false))
        assertNull(ownCatalogLine(CatalogRead.None, hasOwnChips = true))
        assertEquals("Yours · Not checked", ownCatalogLine(CatalogRead.NotChecked, hasOwnChips = false))
    }

    @Test
    fun aMissingRecapTokenIsNotChecked() {
        val lines = downloadSkipLines(
            isEpisode = true,
            ownMarks = emptyMap(),
            recapOutcome = null,
            userRecap = null,
            durationMs = 1_200_000L,
        )
        assertEquals("Public · not checked", lines.publicLine)
        assertEquals("Yours · Not checked", lines.ownLine)
    }

    @Test
    fun aCompletedMissSaysNoRecapFound() {
        val lines = downloadSkipLines(
            isEpisode = true,
            ownMarks = emptyMap(),
            recapOutcome = "rejected-no-match",
            userRecap = null,
            durationMs = 1_200_000L,
        )
        assertEquals("Yours · No recap found", lines.ownLine)
    }

    @Test
    fun aSameCutMarkIsReady() {
        val lines = downloadSkipLines(
            isEpisode = true,
            ownMarks = emptyMap(),
            recapOutcome = "rejected-no-match",
            userRecap = UserSegment(startMs = 0L, endMs = 40_000L, durationMs = 1_200_000L),
            durationMs = 1_200_000L,
        )
        assertEquals("Yours · Ready", lines.ownLine)
    }

    @Test
    fun everyCatalogueEpisodeKeepsBothLines() {
        val rows = seasonOverviewRows(
            episodes = listOf(
                SeasonEpisodeRef(1, 1, "Cold open", "S1 E1"),
                SeasonEpisodeRef(1, 2, "Harbour", "S1 E2"),
            ),
            marksFor = { episode ->
                if (episode.episode == 1) mapOf("intro" to SkipMark(0L, 10_000L)) else emptyMap()
            },
            publicFor = { episode ->
                if (episode.episode == 2) CatalogRead.Listed to listOf("intro") else CatalogRead.NotChecked to emptyList()
            },
        )
        assertEquals(2, rows.size)
        assertEquals("Public · not checked", rows[0].publicLine)
        assertEquals("Yours · Intro", rows[0].ownLine)
        assertEquals("Public · Intro", rows[1].publicLine)
        assertEquals("Yours · not submitted", rows[1].ownLine)
    }
}
