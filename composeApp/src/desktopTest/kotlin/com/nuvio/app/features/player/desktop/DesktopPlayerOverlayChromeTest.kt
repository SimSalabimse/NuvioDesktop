package com.nuvio.app.features.player.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlayerOverlayChromeTest {
    private val css = playerUiSource("controls.css")

    @Test
    fun seekBarKeepsAThinTrackAndA32pxHitArea() {
        val scrub = cssBlock(css, ".scrub")
        val track = cssBlock(css, ".scrub::-webkit-slider-runnable-track")
        val thumb = cssBlock(css, ".scrub::-webkit-slider-thumb")
        assertTrue(scrub.contains("height: 32px"), scrub)
        assertTrue(track.contains("height: 10px"), track)
        assertTrue(thumb.contains("height: 32px"), thumb)
    }

    @Test
    fun transportRowIsACenteredBarWithAMaxWidth() {
        val wideRow = cssBlocks(css, ".action-row").first { it.contains("max-width:") }
        assertTrue(wideRow.contains("justify-content: center"), wideRow)
        assertTrue(wideRow.contains("max-width: min(960px, 100%)"), wideRow)
        assertTrue(wideRow.contains("margin: 16px auto 0"), wideRow)
        val html = playerUiSource("controls.html")
        listOf(
            "aria-label=\"Play\"",
            "aria-label=\"Next episode\"",
            ">Fit<",
            ">1x<",
            ">Subs<",
            ">Audio<",
            ">Sources<",
            ">Episodes<",
        ).forEach { label ->
            assertTrue(html.contains(label), label)
        }
    }

    @Test
    fun elapsedTimeAndSkipActionsHaveA32pxHitHeight() {
        assertTrue(cssBlockContaining(css, ".time-label", "min-height: 32px").isNotEmpty())
        assertTrue(cssBlockContaining(css, ".skip-prompt", "min-height: 32px").isNotEmpty())
        assertTrue(cssBlockContaining(css, ".skip-prompt-row", "min-height: 32px").isNotEmpty())
        assertTrue(cssBlockContaining(css, ".skip-feedback-actions button", "min-height: 32px").isNotEmpty())
    }

    @Test
    fun volumeSliderHitAreaIs32pxAndTheTrackStays6px() {
        assertTrue(cssBlockContaining(css, ".volume-slider", "height: 32px").isNotEmpty())
        assertTrue(cssBlockContaining(css, ".volume-slider::-webkit-slider-runnable-track", "height: 6px").isNotEmpty())
        assertTrue(cssBlockContaining(css, ".volume-slider::-webkit-slider-thumb", "height: 22px").isNotEmpty())
    }

    @Test
    fun submitTimestampSegmentTypesUseATwoByTwoGrid() {
        val row = cssBlock(css, ".segment-row")
        assertTrue(row.contains("grid-template-columns: repeat(2, minmax(0, 1fr))"), row)
        assertFalse(row.contains("repeat(3"))
        assertEquals(4, Regex("""data-segment=""").findAll(playerUiSource("controls.html")).count())
    }
}

private fun playerUiSource(name: String): String {
    val candidates = listOf(
        File("src/desktopMain/resources/player-ui/$name"),
        File("composeApp/src/desktopMain/resources/player-ui/$name"),
    )
    val file = candidates.firstOrNull(File::isFile)
        ?: error("Missing player-ui/$name from ${File(".").absolutePath}")
    return file.readText()
}

private fun cssBlocks(css: String, selector: String): List<String> {
    val needle = "$selector {"
    val blocks = mutableListOf<String>()
    var from = 0
    while (from < css.length) {
        val index = css.indexOf(needle, from)
        if (index < 0) break
        val start = css.indexOf('{', index)
        var depth = 0
        var cursor = start
        while (cursor < css.length) {
            when (css[cursor]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        blocks += css.substring(start + 1, cursor)
                        from = cursor + 1
                        break
                    }
                }
            }
            cursor += 1
        }
        if (cursor >= css.length) error("Unclosed CSS block for $selector")
    }
    check(blocks.isNotEmpty()) { "Missing selector $selector" }
    return blocks
}

private fun cssBlock(css: String, selector: String): String = cssBlocks(css, selector).single()

private fun cssBlockContaining(css: String, selector: String, snippet: String): String =
    cssBlocks(css, selector).firstOrNull { it.contains(snippet) }
        ?: error("No $selector block contains $snippet")
