package com.nuvio.app.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollProbeStatsTest {
    @Test
    fun medianAndP95UseTheOrderedSamples() {
        val stats = scrollFrameStats(
            frameNanos = listOf(30_000_000L, 10_000_000L, 20_000_000L, 40_000_000L, 15_000_000L),
            budgetNanos = 16_666_667L,
        )
        assertEquals(5, stats.frameCount)
        assertEquals(20_000_000L, stats.medianNanos)
        assertEquals(40_000_000L, stats.p95Nanos)
        assertEquals(3, stats.droppedFrames)
    }

    @Test
    fun emptyScrollHasNoFrames() {
        val stats = scrollFrameStats(emptyList())
        assertEquals(0, stats.frameCount)
        assertEquals(0L, stats.medianNanos)
        assertEquals(0, stats.droppedFrames)
    }
}
