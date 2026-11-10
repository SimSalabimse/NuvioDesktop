package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.SeasonOverviewRow
import com.nuvio.app.features.details.seasonOverviewSections
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import org.jetbrains.compose.resources.stringResource

internal const val SEASON_OVERVIEW_SHEET_TAG = "season-overview-sheet"

/** One catalogue sheet for the show page and Downloads. Rows are grouped under a header per season. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeasonOverviewSheet(
    rows: List<SeasonOverviewRow>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        SeasonOverviewContent(
            rows = rows,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = nuvioSafeBottomPadding(16.dp))
                .testTag(SEASON_OVERVIEW_SHEET_TAG),
        )
    }
}

@Composable
internal fun SeasonOverviewContent(
    rows: List<SeasonOverviewRow>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Season overview",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (rows.isEmpty()) {
            Text(
                text = "No episodes in this season.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        seasonOverviewSections(rows).forEach { section ->
            Text(
                text = if (section.season == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, section.season)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
                    .testTag("season-section-${section.season}"),
            )
            section.rows.forEachIndexed { index, row ->
                SeasonOverviewEpisodeRow(row)
                if (index != section.rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 60.dp, end = 16.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonOverviewEpisodeRow(row: SeasonOverviewRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("season-row-${row.season}-${row.episode}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.episode.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = 28.dp)
                .testTag("season-number-${row.season}-${row.episode}"),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = row.title.ifBlank { row.code },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("season-title-${row.season}-${row.episode}"),
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val chipMaxWidth = maxWidth
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CatalogStatusChip(
                        icon = Icons.Rounded.Public,
                        text = row.publicLine,
                        modifier = Modifier
                            .widthIn(max = chipMaxWidth)
                            .testTag("season-chip-public-${row.season}-${row.episode}"),
                    )
                    val ownLine = row.ownLine
                    if (ownLine != null) {
                        CatalogStatusChip(
                            icon = Icons.Rounded.Person,
                            text = ownLine,
                            modifier = Modifier
                                .widthIn(max = chipMaxWidth)
                                .testTag("season-chip-own-${row.season}-${row.episode}"),
                        )
                    }
                }
            }
        }
    }
}
