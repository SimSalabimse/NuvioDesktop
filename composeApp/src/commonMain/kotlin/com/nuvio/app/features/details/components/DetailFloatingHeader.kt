package com.nuvio.app.features.details.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DetailChromeHitTarget
import com.nuvio.app.core.ui.DetailChromeRowHeight
import com.nuvio.app.core.ui.FullscreenActionButton
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.QuietScrollEdge
import com.nuvio.app.core.ui.fullscreenActionHorizontalInsetForWidth
import com.nuvio.app.core.ui.isFullscreenActionSupported
import com.nuvio.app.core.ui.scrollEdgeInset
import com.nuvio.app.features.details.MetaDetails
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailFloatingHeader(
    meta: MetaDetails,
    isSaved: Boolean,
    progressProvider: () -> Float,
    interactive: Boolean,
    backgroundColor: Color? = null,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inset = scrollEdgeInset()
    val showTitle = progressProvider() > 0.05f
    val edgeColor = backgroundColor ?: MaterialTheme.colorScheme.background
    var logoLoadError by remember(meta.id, meta.logo) {
        mutableStateOf(false)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val logoWidth = (maxWidth * 0.6f).coerceAtMost(240.dp)
        val rightActionsWidth = if (isFullscreenActionSupported) 96.dp else 48.dp
        val actionHorizontalInset = fullscreenActionHorizontalInsetForWidth(maxWidth.value)
        QuietScrollEdge(
            safeTop = inset,
            contentTop = inset + DetailChromeRowHeight,
            detailRow = true,
            color = edgeColor,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = inset, start = actionHorizontalInset, end = actionHorizontalInset)
                .heightIn(min = DetailChromeRowHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier.size(DetailChromeHitTarget),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                buttonSize = DetailChromeHitTarget,
                iconSize = 24.dp,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .graphicsLayer { alpha = if (showTitle) 1f else 0f },
                contentAlignment = Alignment.Center,
            ) {
                if (showTitle && !meta.logo.isNullOrBlank() && !logoLoadError) {
                    AsyncImage(
                        model = meta.logo,
                        contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                        modifier = Modifier
                            .width(logoWidth)
                            .widthIn(max = 240.dp)
                            .height(42.dp),
                        onError = { logoLoadError = true },
                    )
                } else if (showTitle) {
                    Text(
                        text = meta.name,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = com.nuvio.app.core.a11y.largeTextLineLimit(),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.width(rightActionsWidth),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailFloatingHeaderAction(
                    isSaved = isSaved,
                    enabled = interactive || !showTitle,
                    onClick = onToggleSaved,
                )
                if (isFullscreenActionSupported) {
                    Spacer(modifier = Modifier.width(4.dp))
                    FullscreenActionButton(
                        enabled = true,
                        buttonSize = DetailChromeHitTarget,
                        iconSize = 22.dp,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailFloatingHeaderAction(
    isSaved: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DetailChromeHitTarget)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isSaved) Icons.Default.Check else Icons.Default.Add,
            contentDescription = if (isSaved) {
                stringResource(Res.string.hero_remove_from_library)
            } else {
                stringResource(Res.string.hero_add_to_library)
            },
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}
