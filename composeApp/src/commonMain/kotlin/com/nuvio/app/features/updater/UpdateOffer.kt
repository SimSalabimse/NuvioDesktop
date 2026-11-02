package com.nuvio.app.features.updater

internal fun releaseAssetBelongsTo(assetUrl: String, source: AppUpdateReleaseSource): Boolean {
    val prefix = "https://github.com/${source.owner}/${source.repo}/"
    return assetUrl.startsWith(prefix, ignoreCase = true)
}

internal fun isUpdateOffered(
    update: AppUpdate,
    source: AppUpdateReleaseSource,
    localVersion: String,
    legacyComparison: Boolean,
): Boolean {
    if (!releaseAssetBelongsTo(update.assetUrl, source)) return false
    return if (legacyComparison) {
        VersionUtils.isRemoteNewerLegacy(update.tag, localVersion)
    } else {
        VersionUtils.isRemoteNewer(update.tag, localVersion)
    }
}

internal fun parseListedSha256(digest: String?): String? {
    if (digest.isNullOrBlank()) return null
    val trimmed = digest.trim()
    val prefix = "sha256:"
    if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
    val hex = trimmed.substring(prefix.length).lowercase()
    if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return hex
}
