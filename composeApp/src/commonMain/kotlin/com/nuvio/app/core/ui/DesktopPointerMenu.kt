package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal data class DesktopPointerMenuEntry(
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Last pointer, in root coordinates, for a desktop poster menu.
 * The card stashes it inside the click callback; the host consumes it immediately.
 */
internal object DesktopPointerMenuAnchor {
    private var pending: Offset? = null

    fun stash(offset: Offset) {
        pending = offset
    }

    fun consume(): Offset? = pending.also { pending = null }
}

internal enum class ContinueWatchingPointerAction {
    Details,
    PlayManually,
    StartFromBeginning,
    Remove,
}

internal fun continueWatchingPointerActions(
    showDetails: Boolean,
    showManualPlay: Boolean,
    showStartFromBeginning: Boolean,
): List<ContinueWatchingPointerAction> = buildList {
    if (showDetails) add(ContinueWatchingPointerAction.Details)
    if (showManualPlay) add(ContinueWatchingPointerAction.PlayManually)
    if (showStartFromBeginning) add(ContinueWatchingPointerAction.StartFromBeginning)
    add(ContinueWatchingPointerAction.Remove)
}

@Composable
internal fun DesktopPointerMenu(
    anchorInRoot: Offset,
    entries: List<DesktopPointerMenuEntry>,
    onDismiss: () -> Unit,
) {
    if (entries.isEmpty()) return
    val density = LocalDensity.current
    val edgeMarginPx = with(density) { 8.dp.roundToPx() }
    val menuShape = RoundedCornerShape(8.dp)
    Popup(
        popupPositionProvider = remember(anchorInRoot, edgeMarginPx) {
            PointerMenuPopupPositionProvider(
                pointerPosition = IntOffset(
                    x = anchorInRoot.x.roundToInt(),
                    y = anchorInRoot.y.roundToInt(),
                ),
                edgeMarginPx = edgeMarginPx,
            )
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(244.dp)
                .shadow(elevation = 14.dp, shape = menuShape)
                .clip(menuShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    shape = menuShape,
                )
                .padding(4.dp),
        ) {
            entries.forEach { entry ->
                DesktopPointerMenuRow(
                    entry = entry,
                    onSelected = {
                        onDismiss()
                        entry.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun DesktopPointerMenuRow(
    entry: DesktopPointerMenuEntry,
    onSelected: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val rowShape = RoundedCornerShape(5.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(rowShape)
            .background(
                if (isHovered) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
                } else {
                    Color.Transparent
                },
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelected,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal class PointerMenuPopupPositionProvider(
    private val pointerPosition: IntOffset,
    private val edgeMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = positionPointerMenuAxis(
            pointer = pointerPosition.x,
            popupExtent = popupContentSize.width,
            windowExtent = windowSize.width,
            edgeMargin = edgeMarginPx,
        ),
        y = positionPointerMenuAxis(
            pointer = pointerPosition.y,
            popupExtent = popupContentSize.height,
            windowExtent = windowSize.height,
            edgeMargin = edgeMarginPx,
        ),
    )
}

internal fun positionPointerMenuAxis(
    pointer: Int,
    popupExtent: Int,
    windowExtent: Int,
    edgeMargin: Int,
): Int {
    val minimum = edgeMargin
    val maximum = (windowExtent - popupExtent - edgeMargin).coerceAtLeast(minimum)
    val preferred = if (pointer + popupExtent <= windowExtent - edgeMargin) {
        pointer
    } else {
        pointer - popupExtent
    }
    return preferred.coerceIn(minimum, maximum)
}
