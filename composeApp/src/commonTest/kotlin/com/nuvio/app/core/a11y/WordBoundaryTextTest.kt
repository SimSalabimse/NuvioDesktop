package com.nuvio.app.core.a11y

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordBoundaryTextTest {
    private val title = "The Extraordinary Adventures of a Very Long Programme Title"
    private val words = title.split(" ")

    @Test
    fun longWordStaysWholeWhenASmallerStepFits() {
        val layout = layoutPosterTitle(title, maxWidthPx = 12f, maxLines = 4, measure = ::chars)
        assertEquals(PosterTitleSmallerStep, layout.scale)
        assertFalse(layout.wordWiderThanLine)
        assertTokensAreWholeWords(layout.text)
        assertTrue(layout.text.contains("Extraordinary"))
        assertFalse(layout.text.contains("Extraor\n"))
    }

    @Test
    fun longWordIsEllipsizedWholeWhenTheSmallerStepStillDoesNotFit() {
        val layout = layoutPosterTitle(title, maxWidthPx = 8f, maxLines = 3, measure = ::chars)
        assertEquals(PosterTitleSmallerStep, layout.scale)
        assertTrue(layout.wordWiderThanLine)
        assertEquals("The …", layout.text)
        assertFalse(layout.text.contains("Extra"))
        assertFalse(layout.text.contains("Adventures"))
    }

    @Test
    fun wordsThatFitDoNotShrink() {
        val layout = layoutPosterTitle("The Adventures", maxWidthPx = 20f, maxLines = 2, measure = ::chars)
        assertEquals(1f, layout.scale)
        assertEquals("The Adventures", layout.text)
    }

    @Test
    fun lastLineKeepsAWordThatFitsWithoutTheEllipsis() {
        val layout = layoutPosterTitle(title, maxWidthPx = 14f, maxLines = 2, measure = ::chars)
        assertEquals(1f, layout.scale)
        assertEquals("The\nExtraordinary", layout.text)
        assertTokensAreWholeWords(layout.text)
    }

    @Test
    fun lastLineStopsAtAWordBoundary() {
        val layout = layoutPosterTitle(title, maxWidthPx = 20f, maxLines = 1, measure = ::chars)
        assertEquals(1f, layout.scale)
        assertEquals("The Extraordinary …", layout.text)
        assertTokensAreWholeWords(layout.text)
    }

    private fun chars(sample: String, scale: Float): Float = sample.length * scale

    private fun assertTokensAreWholeWords(text: String) {
        text.split(Regex("\\s+|\n")).filter { it.isNotEmpty() && it != "…" }.forEach { token ->
            assertTrue(token in words, "Split token '$token' in '$text'")
        }
    }
}
