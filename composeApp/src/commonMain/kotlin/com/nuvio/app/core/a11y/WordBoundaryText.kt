package com.nuvio.app.core.a11y

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnitType

/**
 * Poster titles wrap on spaces.
 *
 * Compose 1.12 exposes [androidx.compose.ui.text.style.Hyphens] on [TextStyle]. The desktop
 * paragraph layouter in ui-text-desktop 1.12.0 does not read that value (the Skiko `LineBreak`
 * type only has Simple, Heading, Paragraph, and Unspecified), so hyphenation is not available
 * here. A word wider than the line is retried once at [PosterTitleSmallerStep]. If it still
 * does not fit, the word is left out and the line ends with an ellipsis.
 */
internal const val PosterTitleSmallerStep = 0.85f

private const val Ellipsis = "…"

internal data class PosterTitleLayout(
    val text: String,
    val scale: Float,
    val wordWiderThanLine: Boolean,
)

internal fun layoutPosterTitle(
    text: String,
    maxWidthPx: Float,
    maxLines: Int,
    measure: (sample: String, scale: Float) -> Float,
): PosterTitleLayout {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || maxWidthPx <= 0f || maxLines < 1) {
        return PosterTitleLayout(trimmed, scale = 1f, wordWiderThanLine = false)
    }
    val words = trimmed.split(Regex("\\s+"))
    val full = layoutAt(words, maxWidthPx, maxLines, scale = 1f, measure = measure)
    if (!full.wordWiderThanLine) return full
    return layoutAt(words, maxWidthPx, maxLines, scale = PosterTitleSmallerStep, measure = measure)
}

private fun layoutAt(
    words: List<String>,
    maxWidthPx: Float,
    maxLines: Int,
    scale: Float,
    measure: (sample: String, scale: Float) -> Float,
): PosterTitleLayout {
    fun fits(value: String) = measure(value, scale) <= maxWidthPx
    val lines = mutableListOf<String>()
    var index = 0
    var wordWiderThanLine = false
    while (index < words.size && lines.size < maxLines) {
        val lineWords = mutableListOf<String>()
        var endLine = false
        while (index < words.size && !endLine) {
            val word = words[index]
            val candidate = if (lineWords.isEmpty()) word else lineWords.joinToString(" ") + " " + word
            if (fits(candidate)) {
                lineWords += word
                index += 1
            } else if (lineWords.isEmpty()) {
                wordWiderThanLine = true
                if (lines.isEmpty()) {
                    if (fits(Ellipsis)) lines += Ellipsis
                } else {
                    val previous = lines.removeAt(lines.lastIndex)
                    val withEllipsis = "$previous $Ellipsis"
                    lines += if (fits(withEllipsis)) withEllipsis else previous
                }
                index = words.size
                endLine = true
            } else {
                endLine = true
            }
        }
        if (wordWiderThanLine || lineWords.isEmpty()) break
        val moreRemain = index < words.size
        val lastLine = lines.size + 1 >= maxLines
        lines += if (moreRemain && lastLine) ellipsizeWords(lineWords, ::fits) else lineWords.joinToString(" ")
        if (moreRemain && lastLine) break
    }
    return PosterTitleLayout(
        text = lines.joinToString("\n"),
        scale = scale,
        wordWiderThanLine = wordWiderThanLine,
    )
}

private fun ellipsizeWords(words: List<String>, fits: (String) -> Boolean): String {
    var kept = words
    while (kept.isNotEmpty()) {
        val withEllipsis = kept.joinToString(" ") + " " + Ellipsis
        if (fits(withEllipsis)) return withEllipsis
        // A word that fills the last line stays whole. The ellipsis is only added when it fits beside it.
        if (kept.size == 1 && fits(kept.single())) return kept.single()
        kept = kept.dropLast(1)
    }
    return if (fits(Ellipsis)) Ellipsis else ""
}

private fun TextStyle.scaled(scale: Float): TextStyle {
    if (scale == 1f) return this
    return copy(
        fontSize = if (fontSize.type == TextUnitType.Unspecified) fontSize else fontSize * scale,
        lineHeight = if (lineHeight.type == TextUnitType.Unspecified) lineHeight else lineHeight * scale,
    )
}

@Composable
internal fun WordBoundaryText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    if (maxLines <= 1) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        if (width <= 0f) {
            Text(
                text = text,
                style = style,
                color = color,
                textAlign = textAlign,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            return@BoxWithConstraints
        }
        val layout = remember(text, width, maxLines, style) {
            layoutPosterTitle(text, width, maxLines) { sample, scale ->
                measurer.measure(
                    text = sample,
                    style = style.scaled(scale),
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    maxLines = 1,
                ).size.width.toFloat()
            }
        }
        Text(
            text = layout.text,
            style = style.scaled(layout.scale),
            color = color,
            textAlign = textAlign,
            maxLines = maxLines,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
