package com.nuvio.app.features.updater

/**
 * Build-time update channel for desktop packages.
 *
 * `release` may check this fork's GitHub releases. `integration` (pull-request
 * and other non-enhanced builds) does not check and does not prompt.
 * Gradle property: `-Pnuvio.updateChannel=release|integration`.
 * When the property is unset, branch `enhanced` is release and every other branch is integration.
 */
internal object DesktopUpdateChannel {
    const val RELEASE = "release"
    const val INTEGRATION = "integration"
    const val ENHANCED_BRANCH = "enhanced"

    fun resolve(property: String?, branch: String?): String {
        when (val explicit = property?.trim()?.lowercase()) {
            null, "" -> Unit
            RELEASE -> return RELEASE
            INTEGRATION -> return INTEGRATION
            else -> return INTEGRATION
        }
        return if (branch?.trim() == ENHANCED_BRANCH) RELEASE else INTEGRATION
    }

    fun checksEnabled(channel: String): Boolean = channel == RELEASE
}
