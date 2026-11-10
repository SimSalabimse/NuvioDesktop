package com.nuvio.app.core.perf

import java.lang.management.ManagementFactory
import kotlin.system.exitProcess

internal object DesktopStartupProbe {
    private const val EnabledProperty = "nuvio.desktop.startupProbe"
    private const val FileProperty = "nuvio.desktop.startupProbeFile"

    private val enabled = System.getProperty(EnabledProperty) == "true"
    private val recorder = StartupProbeRecorder(System::nanoTime)

    fun arm() {
        if (!enabled) return
        recorder.arm()
    }

    fun <T> phase(name: String, block: () -> T): T {
        if (!enabled) return block()
        return recorder.phase(name, block)
    }

    fun homeFramePresented() {
        if (!enabled) return
        synchronized(this) {
            recorder.homeFramePresented()
            finishIfReady()
        }
    }

    fun cataloguesSettled() {
        if (!enabled) return
        synchronized(this) {
            recorder.cataloguesSettled()
            finishIfReady()
        }
    }

    fun mark(name: String) {
        if (!enabled) return
        synchronized(this) { recorder.mark(name) }
    }

    private fun finishIfReady() {
        val report = recorder.reportOrNull() ?: return
        System.gc()
        val heapBytes = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        val line = formatStartupProbeLine(report, heapBytes)
        val path = System.getProperty(FileProperty)?.takeIf { it.isNotBlank() }
        if (path != null) {
            java.io.File(path).apply { parentFile?.mkdirs() }.writeText(line + "\n")
        } else {
            println(line)
        }
        exitProcess(0)
    }
}

internal actual object StartupMarkers {
    actual fun homeFramePresented() = DesktopStartupProbe.homeFramePresented()
    actual fun cataloguesSettled() = DesktopStartupProbe.cataloguesSettled()
    actual fun mark(name: String) = DesktopStartupProbe.mark(name)
}
