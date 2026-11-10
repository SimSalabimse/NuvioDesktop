package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TheIntroDbParserFuzzTest {
    @Test
    fun oddIntroDbBodiesKeepTheSegmentsThatAreStillReadable() {
        var fuzzed = 0

        val documented = """
            {
              "tmdb_id": 12345,
              "type": "movie",
              "intro": [{"start_ms": null, "end_ms": 23000}],
              "credits": [
                {"start_ms": 5801777, "end_ms": 6371111},
                {"start_ms": 6408000, "end_ms": null}
              ]
            }
        """.trimIndent()

        fuzzed += 1
        val withDuration = parseTheIntroDbMedia(documented)!!.movieSkipIntervals(7_200_000L)
        assertEquals(0.0, withDuration.first { it.type == "intro" }.startTime)
        assertEquals(23.0, withDuration.first { it.type == "intro" }.endTime)
        assertEquals("movie-credits", withDuration.first { it.type == "movie-credits" }.type)
        val postCredits = withDuration.first { it.type == "post-credits" }
        assertEquals(6408.0, postCredits.startTime)
        assertEquals(7200.0, postCredits.endTime)

        fuzzed += 1
        val withoutDuration = parseTheIntroDbMedia(documented)!!.movieSkipIntervals()
        assertTrue(withoutDuration.none { it.type == "post-credits" })
        assertEquals(1, withoutDuration.count { it.type == "movie-credits" })

        fuzzed += 1
        val singleObject = parseTheIntroDbMedia(
            """{"intro":{"start_ms":"1500","end_ms":9000},"credits":{"start_sec":10.5,"end_sec":12}}""",
        )!!
        assertEquals(listOf(1.5 to 9.0), singleObject.tvSkipIntervals().filter { it.type == "intro" }.map { it.startTime to it.endTime })
        assertEquals(10.5, singleObject.movieSkipIntervals().first { it.type == "movie-credits" }.startTime)

        fuzzed += 1
        val mixedRow = parseTheIntroDbMedia(
            """{"intro":[null,{"start_ms":{"bad":true},"end_ms":5},{"start_ms":0,"end_ms":1000},{"start_ms":8,"end_ms":4}]}""",
        )!!.tvSkipIntervals()
        assertEquals(listOf(0.0 to 1.0), mixedRow.map { it.startTime to it.endTime })

        fuzzed += 1
        val negative = parseTheIntroDbMedia("""{"intro":[{"start_ms":-1000,"end_ms":5000}]}""")!!.tvSkipIntervals()
        assertTrue(negative.isEmpty())

        fuzzed += 1
        val weirdIntro = parseTheIntroDbMedia("""{"intro":"none","recap":[{"start_ms":0,"end_ms":2000}]}""")!!
        assertTrue(weirdIntro.intro.orEmpty().isEmpty())
        assertEquals(1, weirdIntro.tvSkipIntervals().size)

        fuzzed += 1
        val hugeId = parseTheIntroDbMedia("""{"tmdb_id":999999999999,"intro":[{"start_ms":0,"end_ms":1000}]}""")!!
        assertNull(hugeId.tmdbId)
        assertEquals(1, hugeId.tvSkipIntervals().size)

        fuzzed += 1
        val emptyObject = parseTheIntroDbMedia("{}")
        assertTrue(emptyObject != null)
        assertTrue(emptyObject.tvSkipIntervals().isEmpty())

        listOf("", "{", "null", "[]", "\"no\"", "12").forEach { payload ->
            fuzzed += 1
            assertNull(parseTheIntroDbMedia(payload))
        }

        fuzzed += 1
        val page = parseTheIntroDbSubmissions(
            """
            {"total":"2","limit":10,"offset":0,"submissions":[
              "bad",
              {"id":7,"segment":"intro","startMs":1000,"endMs":2000,"tmdbId":"15"},
              {"start_ms":{"no":1}}
            ]}
            """.trimIndent(),
        )!!
        assertEquals(2, page.total)
        assertEquals(2, page.submissions.size)
        assertEquals("7", page.submissions.first().id)
        assertEquals(15, page.submissions.first().tmdbId)
        assertEquals(1000L, page.submissions.first().startMs)
        assertNull(page.submissions[1].startMs)

        fuzzed += 1
        assertNull(parseTheIntroDbSubmissions("["))

        fuzzed += 1
        val notAnObject = parseTheIntroDbSubmissions("""{"submissions":{"id":"x"}}""")!!
        assertTrue(notAnObject.submissions.isEmpty())

        assertEquals(17, fuzzed)
    }
}
