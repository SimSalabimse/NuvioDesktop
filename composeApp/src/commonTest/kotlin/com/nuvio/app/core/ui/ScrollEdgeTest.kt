package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
