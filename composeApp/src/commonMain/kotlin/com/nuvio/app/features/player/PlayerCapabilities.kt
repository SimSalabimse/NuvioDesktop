package com.nuvio.app.features.player

internal expect object PlayerCapabilities {
    val supportsAutoSyncAudioCapture: Boolean
}

internal fun isAutoSyncControlShown(supportsAutoSyncAudioCapture: Boolean): Boolean =
    supportsAutoSyncAudioCapture
