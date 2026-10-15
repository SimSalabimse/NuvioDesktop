package com.nuvio.app.features.whatsnew

internal expect object WhatsNewStorage {
    fun loadLastSeen(): String?
    fun saveLastSeen(value: String)
}
