package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal const val STREAM_WINDOW_HEAD_SEC = 600.0
internal const val STREAM_WINDOW_TAIL_SEC = 480.0

internal data class StreamWindowMeasurement(
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val frames: Long = 0,
    val bytesTransferred: Long = 0,
    val wallMillis: Long = 0,
    val seekable: Boolean = false,
    val durationSec: Double = -1.0,
    val error: String? = null,
)

internal fun streamWindowSpikeGate(macOS: Boolean, envValue: String?, compiled: Boolean): Boolean =
    macOS && envValue == "1" && compiled

internal fun streamWindowHostToken(url: String): String {
    val candidate = when {
        url.contains("://") -> url
        url.startsWith("/") -> "file://$url"
        else -> url
    }
    val host = runCatching { URI(candidate).host }.getOrNull()?.trim()?.lowercase(Locale.US)
    if (host.isNullOrEmpty()) {
        return if (candidate.startsWith("file:", ignoreCase = true)) "local" else "unknown"
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(host.toByteArray(StandardCharsets.UTF_8))
    return digest.take(4).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun streamWindowLogLine(
    window: String,
    startSec: Double,
    durationSec: Double,
    measurement: StreamWindowMeasurement,
    hostToken: String,
): String {
    val error = measurement.error?.takeIf { code -> code.matches(ERROR_CODE) } ?: measurement.error?.let { "native_error" }
    return buildString {
        append("{\"window\":\"")
        append(if (window == "tail") "tail" else "head")
        append("\",\"startSec\":")
        append(jsonDecimal(startSec))
        append(",\"durationSec\":")
        append(jsonDecimal(durationSec))
        append(",\"frames\":")
        append(measurement.frames.coerceAtLeast(0))
        append(",\"bytesTransferred\":")
        append(measurement.bytesTransferred.coerceAtLeast(0))
        append(",\"wallMillis\":")
        append(measurement.wallMillis.coerceAtLeast(0))
        append(",\"seekable\":")
        append(if (measurement.seekable) "true" else "false")
        append(",\"error\":")
        if (error == null) append("null") else {
            append('"')
            append(error)
            append('"')
        }
        append(",\"host\":\"")
        append(if (hostToken.matches(HOST_TOKEN)) hostToken else "unknown")
        append("\"}")
    }
}

internal fun parseStreamWindowMeasurement(json: String): StreamWindowMeasurement {
    if (!json.startsWith("{") || !json.endsWith("}")) {
        return StreamWindowMeasurement(error = "native_error")
    }
    val errorMatch = ERROR_FIELD.find(json)
    val error = when (errorMatch?.groupValues?.getOrNull(1)) {
        null -> "native_error"
        "null" -> null
        else -> errorMatch.groupValues.getOrNull(2)?.takeIf { it.matches(ERROR_CODE) } ?: "native_error"
    }
    return StreamWindowMeasurement(
        sampleRate = jsonLong(json, "sampleRate").toInt(),
        channels = jsonLong(json, "channels").toInt(),
        frames = jsonLong(json, "frames"),
        bytesTransferred = jsonLong(json, "bytesTransferred"),
        wallMillis = jsonLong(json, "wallMillis"),
        seekable = jsonLong(json, "seekable") != 0L,
        durationSec = jsonDouble(json, "durationSec"),
        error = error,
    )
}

internal object StreamWindowSpike {
    private val generation = AtomicInteger(0)
    private val nativeReady = AtomicBoolean(false)
    private val commandLineOwned = AtomicBoolean(false)
    private val tiedToPlayback = AtomicBoolean(false)
    private val hookInstalled = AtomicBoolean(false)
    private val logLock = Any()

    fun consumeLaunchArgs(args: Array<String>) {
        if (!enableIfRequested()) return
        val fromArg = args.firstNotNullOfOrNull { arg ->
            arg.removePrefix("--stream-window-spike-url=")
                .takeIf { arg.startsWith("--stream-window-spike-url=") && it.isNotBlank() }
        }
        val url = fromArg ?: System.getenv("NUVIO_STREAM_WINDOW_SPIKE_URL")?.takeIf { it.isNotBlank() } ?: return
        commandLineOwned.set(true)
        start(url, playbackTied = false)
    }

    fun onPlaybackStarted(url: String) {
        if (commandLineOwned.get()) return
        if (!enableIfRequested()) return
        if (url.isBlank()) return
        start(url, playbackTied = true)
    }

    fun onPlaybackInterrupted() {
        if (!tiedToPlayback.get()) return
        generation.incrementAndGet()
        if (nativeReady.get()) {
            runCatching { NativePlayerBridge.abortStreamWindow() }
        }
    }

    private fun enableIfRequested(): Boolean {
        val allowed = streamWindowSpikeGate(
            macOS = DesktopHostOs.current == DesktopHostOs.MACOS,
            envValue = System.getenv("NUVIO_STREAM_WINDOW_SPIKE"),
            compiled = true,
        )
        if (!allowed) return false
        val compiled = runCatching {
            NativePlayerBridge.ensureLoaded()
            NativePlayerBridge.streamWindowSpikeCompiled()
        }.getOrDefault(false)
        if (!compiled) return false
        nativeReady.set(true)
        return true
    }

    private fun start(url: String, playbackTied: Boolean) {
        ensureShutdownHook()
        tiedToPlayback.set(playbackTied)
        val gen = generation.incrementAndGet()
        if (nativeReady.get()) {
            runCatching { NativePlayerBridge.abortStreamWindow() }
        }
        thread(name = "nuvio-stream-window-spike", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (NativePlayerBridge.streamWindowSpikeBusy() && System.nanoTime() < deadline) {
                if (generation.get() != gen) return@thread
                Thread.sleep(20)
            }
            if (generation.get() != gen) return@thread
            if (NativePlayerBridge.streamWindowSpikeBusy()) {
                val host = streamWindowHostToken(url)
                appendLog(streamWindowLogLine("head", 0.0, STREAM_WINDOW_HEAD_SEC, StreamWindowMeasurement(error = "abort_timeout"), host))
                appendLog(streamWindowLogLine("tail", 0.0, STREAM_WINDOW_TAIL_SEC, StreamWindowMeasurement(error = "abort_timeout"), host))
                return@thread
            }
            NativePlayerBridge.clearStreamWindowAbort()
            if (generation.get() != gen) {
                NativePlayerBridge.abortStreamWindow()
                return@thread
            }
            runWindows(url, gen)
        }
    }

    private fun runWindows(url: String, gen: Int) {
        val host = streamWindowHostToken(url)
        val head = measure(url, 0.0, STREAM_WINDOW_HEAD_SEC)
        appendLog(streamWindowLogLine("head", 0.0, STREAM_WINDOW_HEAD_SEC, head, host))
        if (generation.get() != gen || head.error == "aborted") {
            appendLog(
                streamWindowLogLine(
                    "tail",
                    0.0,
                    STREAM_WINDOW_TAIL_SEC,
                    StreamWindowMeasurement(error = "aborted"),
                    host,
                ),
            )
            return
        }
        if (head.error in SKIP_TAIL) {
            appendLog(
                streamWindowLogLine(
                    "tail",
                    0.0,
                    STREAM_WINDOW_TAIL_SEC,
                    StreamWindowMeasurement(error = head.error),
                    host,
                ),
            )
            return
        }
        val duration = head.durationSec
        if (duration <= 0.0) {
            appendLog(
                streamWindowLogLine(
                    "tail",
                    0.0,
                    STREAM_WINDOW_TAIL_SEC,
                    StreamWindowMeasurement(error = "duration_unknown", seekable = head.seekable),
                    host,
                ),
            )
            return
        }
        if (generation.get() != gen) return
        val tailDuration = minOf(STREAM_WINDOW_TAIL_SEC, duration)
        val tailStart = (duration - STREAM_WINDOW_TAIL_SEC).coerceAtLeast(0.0)
        val tail = measure(url, tailStart, tailDuration)
        if (generation.get() != gen && tail.error == null) {
            appendLog(
                streamWindowLogLine(
                    "tail",
                    tailStart,
                    tailDuration,
                    tail.copy(error = "aborted"),
                    host,
                ),
            )
            return
        }
        appendLog(streamWindowLogLine("tail", tailStart, tailDuration, tail, host))
    }

    private fun measure(url: String, startSec: Double, durationSec: Double): StreamWindowMeasurement =
        runCatching {
            parseStreamWindowMeasurement(NativePlayerBridge.analyzeStreamWindow(url, startSec, durationSec))
        }.getOrDefault(StreamWindowMeasurement(error = "native_error"))

    private fun ensureShutdownHook() {
        if (!hookInstalled.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(
            Thread({
                generation.incrementAndGet()
                if (nativeReady.get()) runCatching { NativePlayerBridge.abortStreamWindow() }
            }, "nuvio-stream-window-spike-shutdown"),
        )
    }

    private fun appendLog(line: String) {
        val file = logFile()
        synchronized(logLock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    private fun logFile(): File {
        val override = System.getenv("NUVIO_STREAM_WINDOW_SPIKE_LOG")?.takeIf { it.isNotBlank() }
        if (override != null) return File(override)
        return DesktopStorage.rootDir.resolve("logs").resolve("stream-window-spike.log").toFile()
    }
}

private val ERROR_CODE = Regex("[a-z0-9_]+")
private val HOST_TOKEN = Regex("[a-z0-9]+")
private val ERROR_FIELD = Regex("\"error\":(null|\"([a-z0-9_]*)\")")

private fun jsonLong(json: String, key: String): Long =
    Regex("\"$key\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

private fun jsonDouble(json: String, key: String): Double =
    Regex("\"$key\":(-?[0-9]+(?:\\.[0-9]+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0

private fun jsonDecimal(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val text = String.format(Locale.US, "%.3f", value)
    return text.trimEnd('0').trimEnd('.')
}

private val SKIP_TAIL = setOf(
    "connection_refused",
    "connection_reset",
    "timeout",
    "network_unreachable",
    "http_forbidden",
    "http_not_found",
    "http_error",
    "protocol_not_found",
    "empty_url",
    "bad_args",
    "ffmpeg_abi_mismatch",
    "spike_not_in_build",
    "hls_or_nofile",
    "no_audio",
    "demuxer_not_found",
    "decoder_not_found",
    "out_of_memory",
    "native_error",
)
