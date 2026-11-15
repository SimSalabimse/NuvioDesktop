package com.nuvio.app.features.updater

/**
 * Public releases of this desktop fork. The request is unauthenticated.
 * Upstream (NuvioMedia/NuvioDesktop) is never requested.
 */
internal object ForkUpdateFeed {
    const val OWNER = "SimSalabimse"
    const val REPO = "NuvioDesktop"
    const val RELEASES_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=100"

    fun requestUrl(updateChannel: String): String? =
        if (DesktopUpdateChannel.checksEnabled(updateChannel)) RELEASES_URL else null

    fun requestHeaders(userAgent: String): Map<String, String> = mapOf(
        "Accept" to "application/vnd.github+json",
        "User-Agent" to userAgent,
    )

    fun isForkAssetUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) return false
        val hostAndPath = trimmed
            .removePrefix("https://")
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()
        if ("nuviomedia" in hostAndPath) return false
        return hostAndPath.startsWith("github.com/${OWNER.lowercase()}/${REPO.lowercase()}/releases/download/")
    }
}
