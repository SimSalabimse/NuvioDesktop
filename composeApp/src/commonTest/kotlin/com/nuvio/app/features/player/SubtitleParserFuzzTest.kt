package com.nuvio.app.features.player

import com.nuvio.app.features.autosync.AutoSyncSubtitleCueParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleParserFuzzTest {
    @Test
    fun brokenTimestampsAndOddTextDoNotBecomeCuesOrThrow() {
        var fuzzed = 0
        val overflowHours = 5_124_095_576_030_432L
        val hoursPastLong = "9999999999999999999"

        fun srt(hours: String) = """
            1
            $hours:00:00,000 --> $hours:00:01,000
            Hello

            2
            00:00:01,000 --> 00:00:02,500
            Kept
        """.trimIndent()

        fuzzed += 1
        val overflow = PlayerSubtitleCueParser.parse(srt(overflowHours.toString()), "captions.srt")
        assertEquals(listOf(1_000L to 2_500L), overflow.map { it.startTimeMs to it.endTimeMs })

        fuzzed += 1
        val pastLong = PlayerSubtitleCueParser.parse(srt(hoursPastLong), "captions.srt")
        assertEquals(listOf(1_000L to 2_500L), pastLong.map { it.startTimeMs to it.endTimeMs })

        fuzzed += 1
        val autoSyncOverflow = AutoSyncSubtitleCueParser.parse(srt(overflowHours.toString()), "captions.srt")
        assertEquals(listOf(1_000L to 2_500L), autoSyncOverflow.map { it.startTimeMs to it.endTimeMs })

        fuzzed += 1
        val autoSyncPastLong = AutoSyncSubtitleCueParser.parse(srt(hoursPastLong), "captions.srt")
        assertEquals(listOf(1_000L to 2_500L), autoSyncPastLong.map { it.startTimeMs to it.endTimeMs })

        fuzzed += 1
        val broken = PlayerSubtitleCueParser.parse(
            """
            1
            not-a-time --> 00:00:01,000
            Drop

            2
            00:00:03.250 --> 00:00:04.000
            Kept
            """.trimIndent(),
            "captions.srt",
        )
        assertEquals(listOf(3_250L to 4_000L), broken.map { it.startTimeMs to it.endTimeMs })

        fuzzed += 1
        val vtt = PlayerSubtitleCueParser.parse(
            """
            WEBVTT

            NOTE this block is not a cue

            00:00:00.000 --> nope
            Drop

            00:01:02.004 --> 00:01:03.000
            Kept
            """.trimIndent(),
            "captions.vtt",
        )
        assertEquals(listOf(62_004L to 63_000L), vtt.map { it.startTimeMs to it.endTimeMs })

        fuzzed += 1
        val ttml = PlayerSubtitleCueParser.parse(
            """
            <tt>
              <p begin="999999999999h" end="999999999999h">Huge</p>
              <p begin="00:00:01.000" end="00:00:02.000">Kept</p>
            </tt>
            """.trimIndent(),
            "captions.ttml",
        )
        assertEquals(listOf(1_000L to 2_000L), ttml.map { it.startTimeMs to it.endTimeMs })

        fuzzed += 1
        val odd = PlayerSubtitleCueParser.parse(
            "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nCaf\u00e9 \u0000line\r\n",
            "captions.srt",
        )
        assertEquals(1, odd.size)
        assertTrue(odd.single().text.contains("Caf\u00e9"))

        fuzzed += 1
        val longLine = "1\n00:00:01,000 --> 00:00:02,000\n" + "a".repeat(100_000) + "\n"
        val longCue = PlayerSubtitleCueParser.parse(longLine, "captions.srt")
        assertEquals(1, longCue.size)
        assertEquals(100_000, longCue.single().text.length)

        fuzzed += 1
        val huge = buildString {
            repeat(2_000) { index ->
                val start = index + 1
                val end = start + 1
                append(index + 1)
                append('\n')
                append(cueStamp(start))
                append(" --> ")
                append(cueStamp(end))
                append("\nCue ")
                append(index)
                append("\n\n")
            }
        }
        assertEquals(2_000, PlayerSubtitleCueParser.parse(huge, "captions.srt").size)
        assertEquals(2_000, AutoSyncSubtitleCueParser.parse(huge, "captions.srt").size)

        listOf("", "   ", "-->", "WEBVTT\n", "\u0000\u0001\u0002").forEach { payload ->
            fuzzed += 1
            assertTrue(PlayerSubtitleCueParser.parse(payload, "captions.srt").isEmpty())
            assertTrue(AutoSyncSubtitleCueParser.parse(payload, "captions.vtt").isEmpty())
        }

        assertEquals(15, fuzzed)
    }

    private fun cueStamp(totalSeconds: Int): String {
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return listOf(hours, minutes, seconds).joinToString(":") { it.toString().padStart(2, '0') } + ",000"
    }
}
