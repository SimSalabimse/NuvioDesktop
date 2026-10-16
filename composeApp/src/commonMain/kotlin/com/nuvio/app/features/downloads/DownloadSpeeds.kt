package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Smoothed speed per running download for the Downloads page. */
internal object DownloadSpeeds {
    private data class Sample(val bytes: Long, val atMs: Long, val speed: Double?)

    private val samples = mutableMapOf<String, Sample>()
    private val state = MutableStateFlow<Map<String, Double>>(emptyMap())
    val speeds: StateFlow<Map<String, Double>> = state.asStateFlow()

    fun onItems(items: List<DownloadItem>, nowMs: Long) {
        val running = items.filter { it.status == DownloadStatus.Downloading }
        samples.keys.retainAll(running.mapTo(mutableSetOf()) { it.id })
        val next = mutableMapOf<String, Double>()
        running.forEach { item ->
            val last = samples[item.id]
            if (last == null || item.downloadedBytes < last.bytes) {
                samples[item.id] = Sample(item.downloadedBytes, nowMs, null)
                return@forEach
            }
            val elapsedMs = nowMs - last.atMs
            if (elapsedMs < 1_000L) {
                last.speed?.let { next[item.id] = it }
                return@forEach
            }
            val instant = (item.downloadedBytes - last.bytes) * 1000.0 / elapsedMs
            val smoothed = last.speed?.let { it * 0.7 + instant * 0.3 } ?: instant
            samples[item.id] = Sample(item.downloadedBytes, nowMs, smoothed)
            next[item.id] = smoothed
        }
        if (next != state.value) state.value = next
    }
}

internal fun DownloadItem.remainingBytesOrNull(): Long? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    return (total - downloadedBytes.coerceIn(0L, total)).coerceAtLeast(0L)
}

/** "2.4 MB/s · 6 min left", or null without a speed yet. */
internal fun speedAndEta(bytesPerSecond: Double?, remainingBytes: Long?, formatBytes: (Long) -> String): String? {
    val speed = bytesPerSecond?.takeIf { it >= 1024.0 } ?: return null
    val parts = mutableListOf("${formatBytes(speed.toLong())}/s")
    if (remainingBytes != null) parts += "${formatEta((remainingBytes / speed).toLong())} left"
    return parts.joinToString(" · ")
}

internal fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "<1 min"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 24 * 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    else -> "${seconds / 86400} d"
}
