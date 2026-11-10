package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollEdgeTest {
    @Test
    fun aZeroInsetDrawsNothing() {
        assertEquals(ScrollEdgeKind.None, scrollEdgeKind(0f, false, false, false))
        assertEquals(0f, scrollEdgeHeightDp(0f, 400f, solid = false))
    }

    @Test
    fun theQuietFadeCoversTheInsetAndStopsBeforeTheTitle() {
        assertEquals(ScrollEdgeKind.QuietFade, scrollEdgeKind(59f, false, false, false))
        assertEquals(87f, scrollEdgeHeightDp(59f, 400f, solid = false))
        assertEquals(80f, scrollEdgeHeightDp(59f, 80f, solid = false))
    }

    @Test
    fun contrastAndReducedTransparencyAreASolidInsetBand() {
        assertEquals(ScrollEdgeKind.Solid, scrollEdgeKind(59f, false, reduceTransparency = true, increaseContrast = false))
        assertEquals(ScrollEdgeKind.Solid, scrollEdgeKind(59f, false, reduceTransparency = false, increaseContrast = true))
        assertEquals(59f, scrollEdgeHeightDp(59f, 400f, solid = true))
    }

    @Test
    fun aCardOrSuppressedSurfaceDrawsNothing() {
        assertEquals(ScrollEdgeKind.None, scrollEdgeKind(59f, suppressed = true, false, false))
    }

    @Test
    fun theDetailFadeEndsFlatSoItDoesNotDrawALine() {
        assertEquals(1f, quietFadeAlpha(0f))
        assertEquals(0f, quietFadeAlpha(1f))
        val steps = (0 until 20).map { index ->
            val start = index / 20f
            val end = (index + 1) / 20f
            quietFadeAlpha(start) - quietFadeAlpha(end)
        }
        assertTrue(steps.all { it >= -0.0001f })
        val tail = steps.takeLast(3).sum()
        val middle = steps.subList(8, 12).sum()
        assertTrue(tail < middle / 4f, "tail step $tail should stay well under the middle step $middle")
        val chrome = 28f + 56f
        assertEquals(
            chrome + DETAIL_SCROLL_EDGE_TAIL_DP,
            scrollEdgeHeightDp(chrome, chrome + DETAIL_SCROLL_EDGE_TAIL_DP, solid = false, tailDp = DETAIL_SCROLL_EDGE_TAIL_DP),
        )
        assertEquals(chrome, scrollEdgeHeightDp(chrome, 400f, solid = true))
    }
}
