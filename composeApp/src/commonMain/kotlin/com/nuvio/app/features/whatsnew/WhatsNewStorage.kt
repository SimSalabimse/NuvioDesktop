package com.nuvio.app.features.whatsnew

internal expect object WhatsNewStorage {
    fun loadLastSeen(): String?
    fun saveLastSeen(value: String)
    /** Cached live GitHub release notes (JSON); null when nothing is cached. */
    fun loadCache(): String?
    fun saveCache(value: String)
}
