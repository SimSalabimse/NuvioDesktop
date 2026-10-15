package com.nuvio.app.features.whatsnew

/** The bundled What's new sheet is a Desktop feature; other targets never show it. */
internal actual object WhatsNewStorage {
    actual fun loadLastSeen(): String? = null

    actual fun saveLastSeen(value: String) = Unit
}
