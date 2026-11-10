package com.nuvio.app.core.a11y

import com.nuvio.app.core.storage.DesktopStorage
import java.util.concurrent.TimeUnit

internal actual object ReducedMotionStorage {
    private val store = DesktopStorage.store("nuvio_reduced_motion")
    private const val key = "reduced_motion"

    actual fun loadOverride(): Boolean? = when (store.getString(key)) {
        "true" -> true
        "false" -> false
        else -> null
    }

    actual fun saveOverride(enabled: Boolean) {
        store.putString(key, enabled.toString())
    }

    actual fun clearOverride() {
        store.putString(key, null)
    }

    actual fun osPrefersReducedMotion(): Boolean {
        val os = System.getProperty("os.name").orEmpty()
        val probe = when {
            os.contains("linux", ignoreCase = true) ->
                readProcess("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")
            os.contains("mac", ignoreCase = true) ->
                readProcess("defaults", "read", "com.apple.universalaccess", "reduceMotion")
            os.contains("windows", ignoreCase = true) ->
                readProcess("reg", "query", "HKCU\\Control Panel\\Desktop", "/v", "MinAnimate")
            else -> null
        }
        return prefersReducedMotion(os, probe)
    }

    private fun readProcess(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}
