package com.nuvio.app.features.details

import com.nuvio.app.features.analysis.UserSegment
import com.nuvio.app.features.player.skip.SkipMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogStatusTest {
    @Test
    fun publicAndOwnStayOnSeparateLines() {
        assertEquals("Public · Not checked", publicCatalogLine(CatalogRead.NotChecked, emptyList()))
        assertEquals("Public · None", publicCatalogLine(CatalogRead.None, emptyList()))
        assertEquals("Public · Unavailable", publicCatalogLine(CatalogRead.Unavailable, emptyList()))
        assertEquals("Public · Intro, Credits", publicCatalogLine(CatalogRead.Listed, listOf("intro", "outro")))
        assertEquals("Yours · Not submitted", ownCatalogLine(CatalogRead.None, hasOwnChips = false))
        assertEquals("Yours · Unavailable", ownCatalogLine(CatalogRead.Unavailable, hasOwnChips = false))
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
        assertEquals("Public · Not checked", lines.publicLine)
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
        assertEquals(1, rows[0].season)
        assertEquals(1, rows[0].episode)
        assertEquals("Public · Not checked", rows[0].publicLine)
        assertEquals("Yours · Intro", rows[0].ownLine)
        assertEquals("Public · Intro", rows[1].publicLine)
        assertEquals("Yours · Not submitted", rows[1].ownLine)
    }

    @Test
    fun seasonsGetTheirOwnSectionInOrder() {
        val rows = seasonOverviewRows(
            episodes = listOf(
                SeasonEpisodeRef(2, 1, "Return", "S2 E1"),
                SeasonEpisodeRef(1, 3, "Harbour", "S1 E3"),
                SeasonEpisodeRef(1, 1, "Cold open", "S1 E1"),
            ),
            marksFor = { emptyMap() },
        )
        val sections = seasonOverviewSections(rows)
        assertEquals(listOf(1, 2), sections.map { it.season })
        assertEquals(listOf(3, 1), sections[0].rows.map { it.episode })
        assertEquals(listOf(1), sections[1].rows.map { it.episode })
    }

    @Test
    fun catalogueLinesReadAsSentences() {
        assertEquals(
            "The public catalogue has not been checked.",
            catalogStatusSentence("Public · Not checked"),
        )
        assertEquals(
            "The public catalogue lists no skip marks.",
            catalogStatusSentence("Public · None"),
        )
        assertEquals(
            "The public catalogue is unavailable.",
            catalogStatusSentence("Public · Unavailable"),
        )
        assertEquals(
            "The public catalogue lists Intro, Credits.",
            catalogStatusSentence("Public · Intro, Credits"),
        )
        assertEquals(
            "You have not submitted skip marks.",
            catalogStatusSentence("Yours · Not submitted"),
        )
        assertEquals(
            "Your skip marks have not been checked.",
            catalogStatusSentence("Yours · Not checked"),
        )
        assertEquals(
            "Your skip marks are unavailable.",
            catalogStatusSentence("Yours · Unavailable"),
        )
        assertEquals("Your skip marks are ready.", catalogStatusSentence("Yours · Ready"))
        assertEquals("No recap was found.", catalogStatusSentence("Yours · No recap found"))
        assertEquals(
            "Your skip marks include Intro.",
            catalogStatusSentence("Yours · Intro"),
        )
        assertEquals("Public · Not checked", publicCatalogLine(CatalogRead.NotChecked, emptyList()))
    }
}
