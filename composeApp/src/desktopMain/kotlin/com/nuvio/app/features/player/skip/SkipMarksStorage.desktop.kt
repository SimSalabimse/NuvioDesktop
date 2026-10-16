package com.nuvio.app.features.player.skip

import com.nuvio.app.core.storage.DesktopStorage

internal actual object SkipMarksStorage {
    private val store = DesktopStorage.store("nuvio_skip_marks")

    actual fun load(key: String): String? = store.getString(key)

    actual fun save(key: String, value: String?) {
        store.putString(key, value?.takeIf { it.isNotBlank() })
    }
}
