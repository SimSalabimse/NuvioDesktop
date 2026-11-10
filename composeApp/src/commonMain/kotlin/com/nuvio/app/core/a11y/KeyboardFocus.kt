package com.nuvio.app.core.a11y

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.keyboardFocusRing(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    color: Color = Color.White,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    return if (focused) border(2.dp, color, shape) else this
}

/** One line at the normal desktop size. A second line once large text would ellipsize a title. */
@Composable
fun largeTextLineLimit(compact: Int = 1, expanded: Int = 2): Int {
    return if (LocalDensity.current.fontScale >= 1.3f) expanded else compact
}
