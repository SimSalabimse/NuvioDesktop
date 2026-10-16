package com.nuvio.app.features.player.skip

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SkipMarksStorage {
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("nuvio_skip_marks", Context.MODE_PRIVATE)
    }

    actual fun load(key: String): String? =
        preferences?.getString(ProfileScopedKey.of(key), null)

    actual fun save(key: String, value: String?) {
        preferences?.edit()?.apply {
            if (value.isNullOrBlank()) remove(ProfileScopedKey.of(key)) else putString(ProfileScopedKey.of(key), value)
            apply()
        }
    }
}
