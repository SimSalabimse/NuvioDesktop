package com.nuvio.app.features.whatsnew

import com.nuvio.app.core.storage.DesktopStorage

internal actual object WhatsNewStorage {
    private const val lastSeenKey = "whats_new_last_seen_v1"
    private const val liveCacheKey = "whats_new_live_cache_v1"
    private val store = DesktopStorage.store("nuvio_whats_new")

    actual fun loadLastSeen(): String? = store.getString(lastSeenKey)

    actual fun saveLastSeen(value: String) {
        store.putString(lastSeenKey, value)
    }

    actual fun loadCache(): String? = store.getString(liveCacheKey)

    actual fun saveCache(value: String) {
        store.putString(liveCacheKey, value)
    }
}
