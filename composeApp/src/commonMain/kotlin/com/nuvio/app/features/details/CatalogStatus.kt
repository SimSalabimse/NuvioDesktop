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
    val publicNotChecked: String = "Public · Not checked",
    val publicNone: String = "Public · None",
    val publicUnavailable: String = "Public · Unavailable",
    val ownNotChecked: String = "Yours · Not checked",
    val ownNotSubmitted: String = "Yours · Not submitted",
    val ownUnavailable: String = "Yours · Unavailable",
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

/**
 * Labels for a listed read, in intro / recap / credits / preview order.
 * Aliases such as "op" and a repeated "intro" become one "Intro".
 * A type that cannot be marked is kept once, after the known types, with its trimmed text.
 */
internal fun listedTypeLabels(types: List<String>, labels: CatalogLabels = CatalogLabels()): List<String> {
    val canonical = linkedSetOf<String>()
    val extra = linkedSetOf<String>()
    for (type in types) {
        when (val key = SkipMarks.canonicalType(type)) {
            null -> {
                val raw = type.trim()
                if (raw.isNotEmpty()) extra += raw
            }
            else -> canonical += key
        }
    }
    val ordered = canonicalLabelOrder.filter { it in canonical }
    return ordered.map { catalogTypeLabel(it, labels) } + extra.toList()
}

internal fun publicCatalogLine(
    read: CatalogRead,
    types: List<String>,
    labels: CatalogLabels = CatalogLabels(),
): String = when (read) {
    CatalogRead.NotChecked -> labels.publicNotChecked
    CatalogRead.None -> labels.publicNone
    CatalogRead.Unavailable -> labels.publicUnavailable
    CatalogRead.Listed -> {
        val names = listedTypeLabels(types, labels)
        if (names.isEmpty()) labels.publicNone else "Public · " + names.joinToString(", ")
    }
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

/** Spoken form of a visible catalogue fragment. The painted text stays the short line. */
internal fun catalogStatusSentence(line: String, labels: CatalogLabels = CatalogLabels()): String {
    val names = line.substringAfter("·", missingDelimiterValue = "").trim().trimEnd('.')
    return when (line) {
        labels.publicNotChecked -> "The public catalogue has not been checked."
        labels.publicNone -> "The public catalogue lists no skip marks."
        labels.publicUnavailable -> "The public catalogue is unavailable."
        labels.ownNotChecked -> "Your skip marks have not been checked."
        labels.ownNotSubmitted -> "You have not submitted skip marks."
        labels.ownUnavailable -> "Your skip marks are unavailable."
        labels.ownReady -> "Your skip marks are ready."
        labels.ownNoRecap -> "No recap was found."
        else -> when {
            line.startsWith("Public · ") && names.isNotEmpty() -> "The public catalogue lists $names."
            line.startsWith("Yours · ") && names.isNotEmpty() -> "Your skip marks include $names."
            line.endsWith(".") -> line
            else -> "$line."
        }
    }
}

internal data class DownloadSkipLines(
    val publicLine: String,
    val ownLine: String,
)

/**
 * Two lines for a download. Public stays "Not checked" until a catalogue read exists.
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
    val names = listedTypeLabels(marks.keys.toList(), labels)
    return if (names.isEmpty()) labels.ownNotSubmitted else "Yours · " + names.joinToString(", ")
}

internal data class SeasonOverviewRow(
    val season: Int,
    val episode: Int,
    val code: String,
    val title: String,
    val publicLine: String,
    val ownLine: String?,
)

internal data class SeasonOverviewSection(
    val season: Int,
    val rows: List<SeasonOverviewRow>,
)

/** Episodes grouped under one header per season, in season order. */
internal fun seasonOverviewSections(rows: List<SeasonOverviewRow>): List<SeasonOverviewSection> =
    rows.groupBy { it.season }
        .entries
        .sortedBy { it.key }
        .map { (season, sectionRows) -> SeasonOverviewSection(season, sectionRows) }

internal fun seasonOverviewRows(
    episodes: List<SeasonEpisodeRef>,
    marksFor: (SeasonEpisodeRef) -> Map<String, SkipMark>,
    publicFor: (SeasonEpisodeRef) -> Pair<CatalogRead, List<String>> = { CatalogRead.NotChecked to emptyList() },
    labels: CatalogLabels = CatalogLabels(),
): List<SeasonOverviewRow> {
    // Neighbouring episodes usually share the unmarked read. Reuse those two sentences.
    var cachedMarks: Map<String, SkipMark>? = null
    var cachedPublicRead: CatalogRead? = null
    var cachedPublicTypes: List<String>? = null
    var cachedLines: Pair<String, String?>? = null
    return episodes.map { episode ->
        val marks = marksFor(episode)
        val (publicRead, publicTypes) = publicFor(episode)
        val lines = if (
            marks === cachedMarks &&
            publicRead == cachedPublicRead &&
            publicTypes === cachedPublicTypes
        ) {
            cachedLines!!
        } else {
            val canonicalMarks = marks.filterKeys { SkipMarks.canonicalType(it) != null }
            val ownRead = if (canonicalMarks.isEmpty()) CatalogRead.None else CatalogRead.Listed
            (
                publicCatalogLine(publicRead, publicTypes, labels) to (
                    ownCatalogLine(ownRead, hasOwnChips = canonicalMarks.isNotEmpty(), labels)
                        ?: ownMarkLine(canonicalMarks, labels)
                    )
                ).also { built ->
                cachedMarks = marks
                cachedPublicRead = publicRead
                cachedPublicTypes = publicTypes
                cachedLines = built
            }
        }
        SeasonOverviewRow(
            season = episode.season,
            episode = episode.episode,
            code = episode.code,
            title = episode.title,
            publicLine = lines.first,
            ownLine = lines.second,
        )
    }
}

private val canonicalLabelOrder = listOf("intro", "recap", "outro", "preview")

internal data class SeasonEpisodeRef(
    val season: Int,
    val episode: Int,
    val title: String,
    val code: String,
)
