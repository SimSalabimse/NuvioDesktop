package com.nuvio.app.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupProbeTest {
    @Test
    fun reportWaitsForTheHomeFrameAndTheCatalogueSettle() {
        var now = 0L
        val recorder = StartupProbeRecorder { now }
        recorder.arm()
        now = 5_000_000L
        recorder.homeFramePresented()
        assertNull(recorder.reportOrNull())
        now = 8_000_000L
        recorder.phase("quickjs") { now = 15_000_000L }
        now = 20_000_000L
        recorder.cataloguesSettled()
        now = 40_000_000L
        recorder.cataloguesSettled()
        recorder.homeFramePresented()
        val report = recorder.reportOrNull()
        assertEquals(5L, report?.homeFirstFrameMs)
        assertEquals(20L, report?.cataloguesLoadedMs)
        assertEquals(7L, report?.phasesMs?.get("quickjs"))
        recorder.mark("window_content")
        assertEquals(40L, recorder.reportOrNull()?.marksMs?.get("window_content"))
    }

    @Test
    fun medianOfFiveRunsIsTheMiddleSample() {
        assertEquals(30L, medianLong(listOf(50L, 10L, 30L, 40L, 20L)))
        assertEquals(25L, medianLong(listOf(10L, 20L, 30L, 40L)))
    }

    @Test
    fun probeLineKeepsTheHeapAndThePhaseTimes() {
        val line = formatStartupProbeLine(
            StartupProbeReport(
                homeFirstFrameMs = 11L,
                cataloguesLoadedMs = 22L,
                phasesMs = mapOf("quickjs" to 3L),
            ),
            heapAfterGcBytes = 99L,
        )
        assertTrue(line.contains("home_first_frame_ms=11"))
        assertTrue(line.contains("catalogues_loaded_ms=22"))
        assertTrue(line.contains("heap_after_gc_bytes=99"))
        assertTrue(line.contains("phase_quickjs_ms=3"))
    }
}
