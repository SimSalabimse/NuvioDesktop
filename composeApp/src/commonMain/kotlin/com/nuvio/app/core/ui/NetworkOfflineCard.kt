package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.isDesktop
import com.nuvio.app.core.network.messageForEmptyState
import com.nuvio.app.core.network.titleForEmptyState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import org.jetbrains.compose.resources.stringResource

@Composable
fun NuvioNetworkOfflineCard(
    condition: NetworkCondition,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val body: @Composable ColumnScope.() -> Unit = {
        Text(
            text = condition.titleForEmptyState(),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(tokens.spacing.controlGap))
        Text(
            text = condition.messageForEmptyState(),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textMuted,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(tokens.spacing.screenHorizontal))
            NuvioPrimaryButton(
                text = stringResource(Res.string.action_retry),
                onClick = onRetry,
            )
        }
    }
    if (isDesktop) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            NuvioSurfaceCard(
                modifier = Modifier.widthIn(max = tokens.components.dialogMaxWidth),
                content = body,
            )
        }
    } else {
        NuvioSurfaceCard(modifier = modifier, content = body)
    }
}

internal fun cappedOfflineCardWidth(
    availableWidth: Dp,
    dialogMaxWidth: Dp,
    desktop: Boolean,
): Dp = if (desktop) availableWidth.coerceAtMost(dialogMaxWidth) else availableWidth
