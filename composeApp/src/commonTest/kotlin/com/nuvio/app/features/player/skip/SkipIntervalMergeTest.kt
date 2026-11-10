package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipIntervalMergeTest {
    @Test
    fun movieCreditsAndPostCreditsKeepTheirOwnSlots() {
        val merged = mergeSkipIntervalsByPriority(
            listOf(
                listOf(
                    SkipInterval(100.0, 200.0, "movie-credits", "theintrodb"),
                    SkipInterval(210.0, 240.0, "post-credits", "theintrodb"),
                ),
                listOf(SkipInterval(90.0, 180.0, "credits", "introdb")),
            ),
        )
        val ending = merged.firstOrNull { segmentCategory(it.type) == "ending" }
        assertEquals("movie-credits", ending?.type)
        assertEquals("theintrodb", ending?.provider)
        assertEquals("post-credits", merged.firstOrNull { it.type == "post-credits" }?.type)
        assertEquals(2, merged.size)
        assertNull(merged.firstOrNull { it.provider == "introdb" })
    }

    @Test
    fun theFirstProviderWinsASharedCategory() {
        val merged = mergeSkipIntervalsByPriority(
            listOf(
                listOf(SkipInterval(10.0, 70.0, "intro", "theintrodb")),
                listOf(SkipInterval(12.0, 80.0, "op", "aniskip")),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("theintrodb", merged.single().provider)
    }

    @Test
    fun aRangeThatEndsBeforeTheFileDoesNotHideARealMark() {
        val merged = mergeSkipIntervalsByPriority(
            listOf(
                listOf(SkipInterval(-20.0, -1.0, "intro", "junk")),
                listOf(SkipInterval(10.0, 70.0, "op", "theintrodb")),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("theintrodb", merged.single().provider)
        assertEquals(10.0, merged.single().startTime)
    }

    @Test
    fun aRangeThatStartsBeforeTheFileKeepsThePartInsideIt() {
        val merged = mergeSkipIntervalsByPriority(
            listOf(listOf(SkipInterval(-5.0, 40.0, "recap", "theintrodb"))),
        )
        assertEquals(0.0, merged.single().startTime)
        assertEquals(40.0, merged.single().endTime)
    }

    @Test
    fun zeroLengthAndInvertedMarksAreDropped() {
        val merged = mergeSkipIntervalsByPriority(
            listOf(
                listOf(
                    SkipInterval(10.0, 10.0, "intro", "zero"),
                    SkipInterval(30.0, 10.0, "credits", "backwards"),
                ),
                listOf(SkipInterval(12.0, 80.0, "intro", "later")),
            ),
        )
        assertEquals("later", merged.single().provider)
    }

    @Test
    fun duplicateOverlappingMarksStayOnePerCategory() {
        val merged = mergeSkipIntervalsByPriority(
            listOf(
                listOf(
                    SkipInterval(1.0, 20.0, "intro", "first"),
                    SkipInterval(1.0, 20.0, "intro", "dup"),
                    SkipInterval(5.0, 25.0, "op", "overlap"),
                    SkipInterval(200.0, 240.0, "preview", "preview"),
                ),
            ),
        )
        assertEquals(listOf("first", "preview"), merged.map { it.provider })
    }
}
