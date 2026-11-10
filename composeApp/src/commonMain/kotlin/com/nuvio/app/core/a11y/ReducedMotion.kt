package com.nuvio.app.core.a11y

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fades and poster hovers become instant when this is on.
 * A saved choice wins. With no saved choice, the desktop OS setting is used.
 */
object ReducedMotion {
    private val state = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        val saved = runCatching { ReducedMotionStorage.loadOverride() }.getOrNull()
        val os = runCatching { ReducedMotionStorage.osPrefersReducedMotion() }.getOrDefault(false)
        state.value = saved ?: os
        loaded = true
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        state.value = value
        runCatching { ReducedMotionStorage.saveOverride(value) }
    }

    fun duration(millis: Int): Int {
        ensureLoaded()
        return if (state.value) 0 else millis
    }

    fun resetForTest(enabled: Boolean = false) {
        state.value = enabled
        loaded = true
        runCatching { ReducedMotionStorage.clearOverride() }
    }
}

internal fun prefersReducedMotion(osName: String, probe: String?): Boolean {
    val name = osName.lowercase()
    val output = probe?.trim()?.trim('\'', '"')?.lowercase() ?: return false
    return when {
        "linux" in name -> output == "false"
        "mac" in name -> output == "1" || output == "true"
        "win" in name -> output.lineSequence().any { line ->
            "minanimate" in line && line.trim().substringAfterLast(' ').removePrefix("0x").all { it == '0' }
        }
        else -> false
    }
}
