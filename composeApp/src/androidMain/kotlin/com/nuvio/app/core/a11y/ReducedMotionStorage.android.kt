package com.nuvio.app.core.a11y

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

internal actual object ReducedMotionStorage {
    private const val preferencesName = "nuvio_reduced_motion"
    private const val key = "reduced_motion"

    private var appContext: Context? = null
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        preferences = appContext?.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadOverride(): Boolean? {
        val prefs = preferences ?: return null
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, false)
    }

    actual fun saveOverride(enabled: Boolean) {
        preferences?.edit()?.putBoolean(key, enabled)?.apply()
    }

    actual fun clearOverride() {
        preferences?.edit()?.remove(key)?.apply()
    }

    actual fun osPrefersReducedMotion(): Boolean {
        val context = appContext ?: return false
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        return scale == 0f
    }
}
