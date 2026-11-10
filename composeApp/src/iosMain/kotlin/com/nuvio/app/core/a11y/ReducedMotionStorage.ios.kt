package com.nuvio.app.core.a11y

import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

internal actual object ReducedMotionStorage {
    private const val key = "reduced_motion"

    actual fun loadOverride(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(key) == null) return null
        return defaults.boolForKey(key)
    }

    actual fun saveOverride(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = key)
    }

    actual fun clearOverride() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key)
    }

    actual fun osPrefersReducedMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
}
