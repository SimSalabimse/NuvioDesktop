package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.a11y.largeTextLineLimit
import com.nuvio.app.features.details.catalogStatusSentence
import androidx.compose.ui.unit.dp
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
            modifier = Modifier.clearAndSetSemantics {
                set(SemanticsProperties.TestTag, CATALOG_PUBLIC_LINE_TAG)
                set(SemanticsProperties.Text, listOf(AnnotatedString(catalogStatusSentence(publicLine))))
            },
        )
        if (ownLine != null) {
            Text(
                text = ownLine,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {
                    set(SemanticsProperties.TestTag, CATALOG_OWN_LINE_TAG)
                    set(SemanticsProperties.Text, listOf(AnnotatedString(catalogStatusSentence(ownLine))))
                },
            )
        }
    }
}

/** Small secondary chip for a public or own catalogue status. The text is sentence case. */
@Composable
internal fun CatalogStatusChip(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 420.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = largeTextLineLimit(),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics {
                    set(SemanticsProperties.Text, listOf(AnnotatedString(catalogStatusSentence(text))))
                },
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
