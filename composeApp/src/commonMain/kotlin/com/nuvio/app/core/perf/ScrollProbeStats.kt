package com.nuvio.app.core.perf

internal object ScrollProbeCounters {
    var enabled: Boolean = false
    var posterCompositions: Int = 0

    fun posterComposed() {
        posterCompositions += 1
    }

    fun reset() {
        posterCompositions = 0
    }
}

internal const val ScrollFrameBudgetNanos = 16_666_667L

internal data class ScrollFrameStats(
    val frameCount: Int,
    val medianNanos: Long,
    val p95Nanos: Long,
    val droppedFrames: Int,
)

internal fun scrollFrameStats(
    frameNanos: List<Long>,
    budgetNanos: Long = ScrollFrameBudgetNanos,
): ScrollFrameStats {
    if (frameNanos.isEmpty()) {
        return ScrollFrameStats(frameCount = 0, medianNanos = 0L, p95Nanos = 0L, droppedFrames = 0)
    }
    val ordered = frameNanos.sorted()
    val median = ordered[ordered.size / 2]
    val p95Index = (((ordered.size * 95) + 99) / 100) - 1
    val p95 = ordered[p95Index.coerceIn(0, ordered.lastIndex)]
    return ScrollFrameStats(
        frameCount = ordered.size,
        medianNanos = median,
        p95Nanos = p95,
        droppedFrames = frameNanos.count { nanos -> nanos > budgetNanos },
    )
}
