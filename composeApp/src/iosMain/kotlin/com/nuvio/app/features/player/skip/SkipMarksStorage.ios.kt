package com.nuvio.app.features.player.skip

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object SkipMarksStorage {
    private fun scoped(key: String) = ProfileScopedKey.of("nuvio_skip_marks_$key")

    actual fun load(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(scoped(key))

    actual fun save(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(scoped(key))
        } else {
            NSUserDefaults.standardUserDefaults.setObject(value, forKey = scoped(key))
        }
    }
}
