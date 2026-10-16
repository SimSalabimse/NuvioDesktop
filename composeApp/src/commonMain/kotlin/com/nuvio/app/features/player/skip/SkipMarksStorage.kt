package com.nuvio.app.features.player.skip

/** Key/value storage for the user's own skip marks and the TheIntroDB import status. */
internal expect object SkipMarksStorage {
    fun load(key: String): String?
    fun save(key: String, value: String?)
}
