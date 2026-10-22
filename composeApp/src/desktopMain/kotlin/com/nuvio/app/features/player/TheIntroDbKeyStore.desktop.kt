package com.nuvio.app.features.player

import com.nuvio.app.core.storage.ProfileScopedKey
import java.util.prefs.Preferences

internal actual object TheIntroDbKeyStore {
    private const val keyBase = "the_intro_db_api_key"
    private val prefs = Preferences.userRoot().node("com/nuvio/app")

    actual fun load(): String? {
        return prefs.get(ProfileScopedKey.of(keyBase), null)
    }

    actual fun save(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            prefs.remove(ProfileScopedKey.of(keyBase))
        } else {
            if (trimmed.length < 10 || !trimmed.matches(Regex("[a-zA-Z0-9_-]+"))) {
                return
            }
            prefs.put(ProfileScopedKey.of(keyBase), trimmed)
        }
        prefs.flush()
    }
}
