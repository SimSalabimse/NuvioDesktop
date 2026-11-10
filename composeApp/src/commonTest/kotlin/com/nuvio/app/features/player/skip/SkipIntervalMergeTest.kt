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
}
