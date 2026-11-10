package com.nuvio.app.features.details

import com.nuvio.app.features.analysis.RecapChipSentence
import com.nuvio.app.features.analysis.UserSegment
import com.nuvio.app.features.analysis.recapAnalysisPart
import com.nuvio.app.features.analysis.recapChipSentence
import com.nuvio.app.features.player.skip.SkipMark
import com.nuvio.app.features.player.skip.SkipMarks

/** Public catalogue versus this account. The two reads stay apart. */
internal enum class CatalogRead {
    NotChecked,
    None,
    Unavailable,
    Listed,
}

internal data class CatalogLabels(
    val intro: String = "Intro",
    val recap: String = "Recap",
    val credits: String = "Credits",
    val preview: String = "Preview",
    val publicNotChecked: String = "Public · not checked",
    val publicNone: String = "Public · none",
    val publicUnavailable: String = "Public · unavailable",
    val ownNotChecked: String = "Yours · Not checked",
    val ownNotSubmitted: String = "Yours · not submitted",
    val ownUnavailable: String = "Yours · unavailable",
    val ownReady: String = "Yours · Ready",
    val ownNoRecap: String = "Yours · No recap found",
)

internal fun catalogTypeLabel(type: String, labels: CatalogLabels): String = when (SkipMarks.canonicalType(type)) {
    "intro" -> labels.intro
    "recap" -> labels.recap
    "outro" -> labels.credits
    "preview" -> labels.preview
    else -> type
}

internal fun publicCatalogLine(
    read: CatalogRead,
    types: List<String>,
    labels: CatalogLabels = CatalogLabels(),
): String = when (read) {
    CatalogRead.NotChecked -> labels.publicNotChecked
    CatalogRead.None -> labels.publicNone
    CatalogRead.Unavailable -> labels.publicUnavailable
    CatalogRead.Listed -> "Public · " + types.joinToString(", ") { catalogTypeLabel(it, labels) }
}

/**
 * This account's line. A public listing never becomes "submitted".
 * [hasOwnChips] hides the empty sentence when the row already shows this account's chips.
 */
internal fun ownCatalogLine(
    read: CatalogRead,
    hasOwnChips: Boolean,
    labels: CatalogLabels = CatalogLabels(),
): String? = when (read) {
    CatalogRead.NotChecked -> labels.ownNotChecked
    CatalogRead.Unavailable -> labels.ownUnavailable
    CatalogRead.None -> if (hasOwnChips) null else labels.ownNotSubmitted
    CatalogRead.Listed -> null
}

internal data class DownloadSkipLines(
    val publicLine: String,
    val ownLine: String,
)

/**
 * Two lines for a download. Public stays "not checked" until a catalogue read exists.
 * The own line uses the recap token: a missing token is Not checked, a completed miss is
 * No recap found, and a same-cut mark is Ready.
 */
internal fun downloadSkipLines(
    isEpisode: Boolean,
    ownMarks: Map<String, SkipMark>,
    recapOutcome: String?,
    userRecap: UserSegment?,
    durationMs: Long,
    publicRead: CatalogRead = CatalogRead.NotChecked,
    publicTypes: List<String> = emptyList(),
    labels: CatalogLabels = CatalogLabels(),
): DownloadSkipLines {
    val sentence = if (isEpisode) {
        recapChipSentence(
            recapAnalysisPart(
                recapEnabled = true,
                isEpisode = true,
                derivedCurrent = true,
                recapEndMs = null,
                userRecap = userRecap,
                durationMs = durationMs,
                siblingsEmpty = false,
                outcome = recapOutcome,
            ),
        )
    } else {
        null
    }
    val own = when (sentence) {
        RecapChipSentence.Ready -> labels.ownReady
        RecapChipSentence.NoRecapFound -> labels.ownNoRecap
        RecapChipSentence.NotChecked -> if (ownMarks.isEmpty()) labels.ownNotChecked else ownMarkLine(ownMarks, labels)
        RecapChipSentence.Other -> ownMarkLine(ownMarks, labels)
        null -> ownMarkLine(ownMarks, labels)
    }
    return DownloadSkipLines(
        publicLine = publicCatalogLine(publicRead, publicTypes, labels),
        ownLine = own,
    )
}

private fun ownMarkLine(marks: Map<String, SkipMark>, labels: CatalogLabels): String {
    if (marks.isEmpty()) return labels.ownNotSubmitted
    val names = listOf("intro", "recap", "outro", "preview")
        .filter { it in marks }
        .joinToString(", ") { catalogTypeLabel(it, labels) }
    return if (names.isBlank()) labels.ownNotSubmitted else "Yours · $names"
}

internal data class SeasonOverviewRow(
    val code: String,
    val title: String,
    val publicLine: String,
    val ownLine: String?,
)

internal fun seasonOverviewRows(
    episodes: List<SeasonEpisodeRef>,
    marksFor: (SeasonEpisodeRef) -> Map<String, SkipMark>,
    publicFor: (SeasonEpisodeRef) -> Pair<CatalogRead, List<String>> = { CatalogRead.NotChecked to emptyList() },
    labels: CatalogLabels = CatalogLabels(),
): List<SeasonOverviewRow> = episodes.map { episode ->
    val marks = marksFor(episode)
    val (publicRead, publicTypes) = publicFor(episode)
    val ownRead = if (marks.isEmpty()) CatalogRead.None else CatalogRead.Listed
    SeasonOverviewRow(
        code = episode.code,
        title = episode.title,
        publicLine = publicCatalogLine(publicRead, publicTypes, labels),
        ownLine = ownCatalogLine(ownRead, hasOwnChips = marks.isNotEmpty(), labels)
            ?: "Yours · " + marks.keys.joinToString(", ") { catalogTypeLabel(it, labels) },
    )
}

internal data class SeasonEpisodeRef(
    val season: Int,
    val episode: Int,
    val title: String,
    val code: String,
)
