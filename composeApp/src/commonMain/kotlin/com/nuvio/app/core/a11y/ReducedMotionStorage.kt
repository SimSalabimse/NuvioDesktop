package com.nuvio.app.core.a11y

internal expect object ReducedMotionStorage {
    fun loadOverride(): Boolean?
    fun saveOverride(enabled: Boolean)
    fun clearOverride()
    fun osPrefersReducedMotion(): Boolean
}
