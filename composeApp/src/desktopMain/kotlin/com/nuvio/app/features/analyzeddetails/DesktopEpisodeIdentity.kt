package com.nuvio.app.features.analyzeddetails

/**
 * Desktop marks live in `nuvio_skip_marks` as `id:season:episode`.
 * The spec's episode is `movie` or `s{season}e{episode}`, and a show id is
 * `tmdb:`, `imdb:tt`, or `raw:`. `outro` on disk is `credits` on the wire.
 */
internal data class DesktopEpisodeRef(
    val parentMetaId: String,
    val season: Int?,
    val episode: Int?,
    val showId: String,
    val episodeKey: String,
    val desktopKey: String,
)

internal fun desktopMarkKey(parentMetaId: String, season: Int?, episode: Int?): String =
    if (season == null || episode == null) parentMetaId else "$parentMetaId:$season:$episode"

internal fun showIdForParent(parentMetaId: String): String? {
    val id = parentMetaId.trim()
    if (isShowId(id)) return id
    if (Regex("^[1-9][0-9]{0,9}$").matches(id)) return "tmdb:$id"
    if (id.startsWith("tt") && isShowId("imdb:$id")) return "imdb:$id"
    return null
}

/** Prefer the spelling Desktop already uses: `tmdb:100` and `tt0000100`. */
internal fun parentMetaIdForShow(showId: String): String? = when {
    showId.startsWith("tmdb:") && isShowId(showId) -> showId
    showId.startsWith("imdb:tt") && isShowId(showId) -> showId.removePrefix("imdb:")
    showId.startsWith("raw:") && isShowId(showId) -> showId.removePrefix("raw:")
    else -> null
}

internal fun parseDesktopMarkKey(key: String): DesktopEpisodeRef? {
    if (key.isBlank()) return null
    val split = splitEpisodeSuffix(key)
    if (split == null) {
        val showId = showIdForParent(key) ?: return null
        return DesktopEpisodeRef(key, null, null, showId, "movie", key)
    }
    val (parent, season, episode) = split
    val showId = showIdForParent(parent) ?: return null
    val episodeKey = episodeKey(season.toLong(), episode.toLong()) ?: return null
    return DesktopEpisodeRef(parent, season, episode, showId, episodeKey, desktopMarkKey(parent, season, episode))
}

internal fun wireSegment(local: String): String? = when (local.trim().lowercase()) {
    "intro" -> "intro"
    "recap" -> "recap"
    "outro", "credits" -> "credits"
    "preview" -> "preview"
    else -> null
}

internal fun localSegment(wire: String): String? = when (wire) {
    "intro" -> "intro"
    "recap" -> "recap"
    "credits" -> "outro"
    "preview" -> "preview"
    else -> null
}

private fun splitEpisodeSuffix(key: String): Triple<String, Int, Int>? {
    val episodeSep = key.lastIndexOf(':')
    if (episodeSep <= 0) return null
    val episode = key.substring(episodeSep + 1).toIntOrNull() ?: return null
    if (episode < 1) return null
    val rest = key.substring(0, episodeSep)
    val seasonSep = rest.lastIndexOf(':')
    if (seasonSep <= 0) return null
    val seasonText = rest.substring(seasonSep + 1)
    if (seasonText.length > 1 && seasonText.startsWith('0')) return null
    val season = seasonText.toIntOrNull() ?: return null
    if (season < 0) return null
    val parent = rest.substring(0, seasonSep)
    if (parent.isEmpty()) return null
    return Triple(parent, season, episode)
}
