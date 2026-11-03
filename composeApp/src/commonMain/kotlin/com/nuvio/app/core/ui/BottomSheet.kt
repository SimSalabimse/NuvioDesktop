package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.isDesktop
import kotlin.math.min

private val SheetShape = RoundedCornerShape(NuvioTokens.Space.s28)

internal enum class NuvioSheetPresentation {
    BottomSheet,
    CenteredDialog,
}

internal fun nuvioSheetPresentation(desktop: Boolean): NuvioSheetPresentation =
    if (desktop) NuvioSheetPresentation.CenteredDialog else NuvioSheetPresentation.BottomSheet

internal fun nuvioSheetShowsDragHandle(desktop: Boolean, requested: Boolean): Boolean =
    requested && !desktop

internal fun centeredSheetWidthPx(windowWidthPx: Int, sheetMaxWidthPx: Int, horizontalMarginPx: Int): Int {
    if (windowWidthPx <= 0 || windowWidthPx == Int.MAX_VALUE) return sheetMaxWidthPx.coerceAtLeast(0)
    val available = (windowWidthPx - horizontalMarginPx * 2).coerceAtLeast(0)
    if (available <= 0) return windowWidthPx.coerceAtLeast(0)
    return min(sheetMaxWidthPx.coerceAtLeast(0), available)
}

internal fun centeredSheetMaxHeightPx(windowHeightPx: Int, fullHeight: Boolean): Int {
    if (windowHeightPx <= 0 || windowHeightPx == Int.MAX_VALUE) return windowHeightPx.coerceAtLeast(0)
    val fraction = if (fullHeight) 0.92f else 0.9f
    return (windowHeightPx * fraction).toInt().coerceAtLeast(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.nuvio.colors.surfaceSheet,
    contentColor: Color = MaterialTheme.nuvio.colors.textPrimary,
    showDragHandle: Boolean = true,
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (usesNativeNuvioBottomSheet) {
        NuvioNativeModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            containerColor = containerColor,
            contentColor = contentColor,
            showDragHandle = showDragHandle,
            fullHeight = fullHeight,
            content = content,
        )
    } else if (nuvioSheetPresentation(isDesktop) == NuvioSheetPresentation.CenteredDialog) {
        DesktopCenteredSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            containerColor = containerColor,
            contentColor = contentColor,
            fullHeight = fullHeight,
            content = content,
        )
    } else {
        val tokens = MaterialTheme.nuvio
        val dragHandle = nuvioSheetShowsDragHandle(isDesktop, showDragHandle)
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            shape = RectangleShape,
            containerColor = Color.Transparent,
            contentColor = contentColor,
            scrimColor = tokens.colors.overlayScrim,
            dragHandle = null,
        ) {
            CompositionLocalProvider(LocalBottomInsetsConsumed provides true) {
                Column(
                    modifier = modifier
                        .padding(NuvioTokens.Space.s10)
                        .clip(SheetShape)
                        .background(containerColor)
                        .border(tokens.borders.thin, SurfaceEdge, SheetShape),
                ) {
                    if (dragHandle) {
                        NuvioBottomSheetDragHandle(Modifier.align(Alignment.CenterHorizontally))
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun DesktopCenteredSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    fullHeight: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val density = LocalDensity.current
    val sheetMaxPx = with(density) { tokens.components.sheetMaxWidth.roundToPx() }
    val marginPx = with(density) { NuvioTokens.Space.s24.roundToPx() }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Layout(
            modifier = modifier,
            content = {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(SheetShape)
                            .background(containerColor)
                            .border(tokens.borders.thin, SurfaceEdge, SheetShape),
                    ) {
                        content()
                    }
                }
            },
        ) { measurables, constraints ->
            val width = centeredSheetWidthPx(constraints.maxWidth, sheetMaxPx, marginPx)
            val maxHeight = centeredSheetMaxHeightPx(constraints.maxHeight, fullHeight)
            val boundedHeight = maxHeight > 0 && maxHeight != Int.MAX_VALUE
            val childConstraints = Constraints(
                minWidth = width,
                maxWidth = width,
                minHeight = if (fullHeight && boundedHeight) maxHeight else 0,
                maxHeight = if (boundedHeight) maxHeight else constraints.maxHeight,
            )
            val placeable = measurables.first().measure(childConstraints)
            layout(placeable.width, placeable.height) {
                placeable.place(0, 0)
            }
        }
    }
}

@Composable
fun NuvioBottomSheetDivider(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    HorizontalDivider(
        modifier = modifier.padding(horizontal = tokens.spacing.screenHorizontal),
        thickness = tokens.borders.hairline,
        color = tokens.colors.borderDefault,
    )
}

@Composable
fun NuvioBottomSheetActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s8)
            .heightIn(min = NuvioTokens.Space.s56)
            .clip(tokens.shapes.compactCard)
            .background(if (selected) tokens.colors.accent.copy(alpha = tokens.opacity.hover) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(NuvioTokens.Space.s8),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(NuvioTokens.Space.s36)
                    .clip(tokens.shapes.compactCard)
                    .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(NuvioTokens.Icon.md),
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailingContent?.invoke(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
suspend fun dismissNuvioBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    if (usesNativeNuvioBottomSheet) {
        dismissNativeNuvioBottomSheet()
    } else if (sheetState.isVisible) {
        sheetState.hide()
    }
    onDismiss()
}

@Composable
private fun NuvioBottomSheetDragHandle(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .padding(top = NuvioTokens.Space.s10, bottom = NuvioTokens.Space.s4)
            .size(width = NuvioTokens.Space.s36, height = NuvioTokens.Space.s4)
            .clip(tokens.shapes.chip)
            .background(tokens.colors.borderStrong),
    )
}
