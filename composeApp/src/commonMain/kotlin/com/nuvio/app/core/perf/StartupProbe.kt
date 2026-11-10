package com.nuvio.app.core.perf

/**
 * Records cold-start markers. Production calls are no-ops unless the desktop
 * probe is armed. The first Home frame and the first settled catalogue publish
 * are each recorded once.
 */
internal class StartupProbeRecorder(
    private val nanoTime: () -> Long,
) {
    private var originNanos: Long? = null
    private var lastPhaseNanos: Long? = null
    private val phasesNanos = linkedMapOf<String, Long>()
    private val marksMs = linkedMapOf<String, Long>()
    private var homeFrameMs: Long? = null
    private var cataloguesMs: Long? = null

    fun arm() {
        if (originNanos != null) return
        val now = nanoTime()
        originNanos = now
        lastPhaseNanos = now
    }

    fun <T> phase(name: String, block: () -> T): T {
        arm()
        val began = nanoTime()
        try {
            return block()
        } finally {
            val ended = nanoTime()
            phasesNanos[name] = ended - began
            lastPhaseNanos = ended
        }
    }

    fun homeFramePresented() {
        if (homeFrameMs != null) return
        homeFrameMs = elapsedMs()
    }

    fun cataloguesSettled() {
        if (cataloguesMs != null) return
        cataloguesMs = elapsedMs()
    }

    fun mark(name: String) {
        if (marksMs.containsKey(name)) return
        marksMs[name] = elapsedMs()
    }

    fun reportOrNull(): StartupProbeReport? {
        val home = homeFrameMs ?: return null
        val catalogues = cataloguesMs ?: return null
        return StartupProbeReport(
            homeFirstFrameMs = home,
            cataloguesLoadedMs = catalogues,
            phasesMs = phasesNanos.mapValues { (_, nanos) -> nanos / 1_000_000L },
            marksMs = marksMs.toMap(),
        )
    }

    private fun elapsedMs(): Long {
        arm()
        val origin = originNanos ?: return 0L
        return (nanoTime() - origin) / 1_000_000L
    }
}

internal data class StartupProbeReport(
    val homeFirstFrameMs: Long,
    val cataloguesLoadedMs: Long,
    val phasesMs: Map<String, Long>,
    val marksMs: Map<String, Long> = emptyMap(),
)

internal fun formatStartupProbeLine(report: StartupProbeReport, heapAfterGcBytes: Long): String =
    buildString {
        append("home_first_frame_ms=")
        append(report.homeFirstFrameMs)
        append(" catalogues_loaded_ms=")
        append(report.cataloguesLoadedMs)
        append(" heap_after_gc_bytes=")
        append(heapAfterGcBytes)
        report.phasesMs.forEach { (name, millis) ->
            append(" phase_")
            append(name)
            append("_ms=")
            append(millis)
        }
        report.marksMs.forEach { (name, millis) ->
            append(" mark_")
            append(name)
            append("_ms=")
            append(millis)
        }
    }

internal fun medianLong(values: List<Long>): Long {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2
    }
}

internal expect object StartupMarkers {
    fun homeFramePresented()
    fun cataloguesSettled()
    fun mark(name: String)
}
