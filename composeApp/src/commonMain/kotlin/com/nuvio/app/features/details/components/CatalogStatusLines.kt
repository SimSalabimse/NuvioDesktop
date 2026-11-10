package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.nuvio.app.features.details.CatalogLabels
import com.nuvio.app.features.details.CatalogRead
import com.nuvio.app.features.details.catalogTypeLabel
import com.nuvio.app.features.details.publicCatalogLine
import com.nuvio.app.features.player.skip.SkipMarks
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.submit_intro_segment_intro
import nuvio.composeapp.generated.resources.submit_intro_segment_preview
import nuvio.composeapp.generated.resources.submit_intro_segment_recap
import org.jetbrains.compose.resources.stringResource

internal const val CATALOG_PUBLIC_LINE_TAG = "catalog-public-line"
internal const val CATALOG_OWN_LINE_TAG = "catalog-own-line"

@Composable
internal fun catalogLabels(): CatalogLabels = CatalogLabels(
    intro = stringResource(Res.string.submit_intro_segment_intro),
    recap = stringResource(Res.string.submit_intro_segment_recap),
    preview = stringResource(Res.string.submit_intro_segment_preview),
)

/** Public line and this account's line. The fade and the sheet both use this column. */
@Composable
internal fun CatalogStatusLines(
    publicLine: String,
    ownLine: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = publicLine,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(CATALOG_PUBLIC_LINE_TAG),
        )
        if (ownLine != null) {
            Text(
                text = ownLine,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(CATALOG_OWN_LINE_TAG),
            )
        }
    }
}

@Composable
internal fun IntroDbStatusBlock(
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    modifier: Modifier = Modifier,
) {
    val labels = catalogLabels()
    val marks = SkipMarks.marksFor(parentMetaId, season, episode)
    val own = if (marks.isEmpty()) {
        labels.ownNotSubmitted
    } else {
        "Yours · " + marks.keys.joinToString(", ") { catalogTypeLabel(it, labels) }
    }
    CatalogStatusLines(
        publicLine = publicCatalogLine(CatalogRead.NotChecked, emptyList(), labels),
        ownLine = own,
        modifier = modifier,
    )
}
