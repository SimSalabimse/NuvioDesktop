package com.nuvio.app.core.perf

internal actual object StartupMarkers {
    actual fun homeFramePresented() = Unit
    actual fun cataloguesSettled() = Unit
    actual fun mark(name: String) = Unit
}
