package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** How far a quiet fade continues past the protected inset. A solid edge does not use it. */
internal const val SCROLL_EDGE_TAIL_DP = 28f

/**
 * How far the details chrome fade continues past the row.
 * The extra length is what lets the ease reach the art with a flat slope.
 */
internal const val DETAIL_SCROLL_EDGE_TAIL_DP = 96f

/**
 * Soft fade amount at [t] in 0..1. Smoothstep of the remaining distance:
 * opaque at the top, transparent at the bottom, and the slope is flat at both ends
 * so the fade does not meet the art on a visible line.
 */
internal fun quietFadeAlpha(t: Float): Float {
    val remaining = (1f - t).coerceIn(0f, 1f)
    return remaining * remaining * (3f - 2f * remaining)
}

internal enum class ScrollEdgeKind { None, QuietFade, Solid }

/** One protective edge. A suppressed surface, or a zero inset, draws nothing. Contrast makes it solid. */
internal fun scrollEdgeKind(
    safeTopDp: Float,
    suppressed: Boolean,
    reduceTransparency: Boolean,
    increaseContrast: Boolean,
): ScrollEdgeKind = when {
    suppressed || safeTopDp <= 0f -> ScrollEdgeKind.None
    reduceTransparency || increaseContrast -> ScrollEdgeKind.Solid
    else -> ScrollEdgeKind.QuietFade
}

/**
 * Quiet fade height. It covers [safeTopDp] and eases past it, and it stops at the next
 * title so the fade does not bury that title. A solid edge is only the inset.
 */
internal fun scrollEdgeHeightDp(
    safeTopDp: Float,
    contentTopDp: Float,
    solid: Boolean,
    tailDp: Float = SCROLL_EDGE_TAIL_DP,
): Float {
    val safe = safeTopDp.coerceAtLeast(0f)
    if (safe <= 0f) return 0f
    if (solid) return safe
    val limit = max(contentTopDp.coerceAtLeast(0f), safe)
    return (safe + tailDp).coerceAtMost(limit)
}

internal data class ScrollEdgeFlags(
    val reduceTransparency: Boolean = false,
    val increaseContrast: Boolean = false,
)

/** Test seam. Production reads the status bar. */
internal val LocalScrollEdgeInset = compositionLocalOf<Dp?> { null }

/** Test seam. Production leaves both false. Desktop has no system contrast switch. */
internal val LocalScrollEdgeFlags = staticCompositionLocalOf { ScrollEdgeFlags() }

internal const val SCROLL_EDGE_TAG = "scroll-edge"

@Composable
internal fun scrollEdgeInset(): Dp {
    LocalScrollEdgeInset.current?.let { return it.coerceAtLeast(0.dp) }
    return WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
}

/**
 * Soft scrim over artwork. It is not a bar: no blur and no hit target.
 * A zero inset draws nothing. Increase Contrast and Reduce Transparency use a solid band of the inset.
 */
@Composable
internal fun QuietScrollEdge(
    safeTop: Dp,
    contentTop: Dp,
    modifier: Modifier = Modifier,
    suppressed: Boolean = false,
    detailRow: Boolean = false,
    color: Color = MaterialTheme.colorScheme.background,
) {
    val flags = LocalScrollEdgeFlags.current
    val protected = if (detailRow) safeTop + DetailChromeRowHeight else safeTop
    val kind = scrollEdgeKind(
        safeTopDp = protected.value,
        suppressed = suppressed,
        reduceTransparency = flags.reduceTransparency,
        increaseContrast = flags.increaseContrast,
    )
    if (kind == ScrollEdgeKind.None) return
    val solid = kind == ScrollEdgeKind.Solid
    val edgeHeight = scrollEdgeHeightDp(
        safeTopDp = protected.value,
        contentTopDp = if (detailRow) protected.value + DETAIL_SCROLL_EDGE_TAIL_DP else contentTop.value,
        solid = solid,
        tailDp = if (detailRow) DETAIL_SCROLL_EDGE_TAIL_DP else SCROLL_EDGE_TAIL_DP,
    ).dp
    if (edgeHeight <= 0.dp) return
    Box(
        modifier
            .fillMaxWidth()
            .height(edgeHeight)
            .testTag(SCROLL_EDGE_TAG)
            .semantics { hideFromAccessibility() }
            .drawBehind {
                if (solid) {
                    drawRect(color)
                    return@drawBehind
                }
                if (!detailRow) {
                    drawRect(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to color.copy(alpha = 0.42f),
                                1f to color.copy(alpha = 0f),
                            ),
                        ),
                    )
                    return@drawBehind
                }
                drawRect(
                    Brush.verticalGradient(
                        colorStops = Array(17) { index ->
                            val t = index / 16f
                            t to color.copy(alpha = quietFadeAlpha(t))
                        },
                    ),
                )
            },
    )
}

internal val DetailChromeRowHeight = 56.dp

/** Back and library controls on the chrome row. */
internal val DetailChromeHitTarget = 44.dp
