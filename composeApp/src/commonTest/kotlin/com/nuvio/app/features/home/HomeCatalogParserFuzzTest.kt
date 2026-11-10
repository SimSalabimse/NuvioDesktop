package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeCatalogParserFuzzTest {
    @Test
    fun malformedCatalogJsonDoesNotDropTheValidMetas() {
        var fuzzed = 0

        fun keep(payload: String, block: (ParsedCatalogResponse) -> Unit) {
            fuzzed += 1
            block(HomeCatalogParser.parseCatalogResponse(payload))
        }

        keep(
            """
            {"metas":[
              {"id":"good","type":"movie","name":"Kept"},
              {"id":{"nested":true},"type":"movie","name":"Broken id"},
              {"id":"also","type":"movie","name":{"text":"no"}}
            ]}
            """.trimIndent(),
        ) { parsed ->
            assertEquals(listOf("movie:good"), parsed.items.map { it.stableKey() })
            assertEquals(3, parsed.rawItemCount)
        }

        keep("""{"metas":[{"id":"g","type":"movie","name":"Kept","genres":["Drama",{"name":"no"},1]}]}""") { parsed ->
            assertEquals(listOf("Drama", "1"), parsed.items.single().genres)
        }

        keep("""{"metas":[{"id":"g","type":"movie","name":"Kept","poster":["not-a-string"],"description":{"html":"x"}}]}""") { parsed ->
            assertNull(parsed.items.single().poster)
            assertNull(parsed.items.single().description)
        }

        keep("""{"metas":{"id":"g","type":"movie","name":"Kept"}}""") { parsed ->
            assertTrue(parsed.items.isEmpty())
            assertEquals(0, parsed.rawItemCount)
        }

        keep("""{"metas":"nope"}""") { parsed ->
            assertTrue(parsed.items.isEmpty())
        }

        keep("""{"metas":null}""") { parsed ->
            assertTrue(parsed.items.isEmpty())
        }

        keep("""{"metas":[{"id":42,"type":"movie","name":"Numeric","imdbRating":8.6}]}""") { parsed ->
            assertEquals("42", parsed.items.single().id)
            assertEquals("8.6", parsed.items.single().imdbRating)
        }

        val many = buildString {
            append("""{"metas":[""")
            repeat(200) { index ->
                if (index > 0) append(',')
                append("""{"id":"id$index","type":"movie","name":"Title $index"}""")
            }
            append("]}")
        }
        keep(many) { parsed ->
            assertEquals(200, parsed.items.size)
        }

        keep(
            """
            {"metas":[
              {"id":" ","type":"movie","name":"Blank"},
              {"id":"once","type":"movie","name":"Once"},
              {"id":"bad"},
              {"id":"once","type":"movie","name":"Again"}
            ]}
            """.trimIndent(),
        ) { parsed ->
            assertEquals(listOf("movie:once"), parsed.items.map { it.stableKey() })
            assertEquals(4, parsed.rawItemCount)
        }

        keep(" \n\t{\"metas\":[]}") { parsed ->
            assertTrue(parsed.items.isEmpty())
        }

        listOf("", "{", "null", "[]", "\"hello\"", "123", "true", "metas").forEach { payload ->
            fuzzed += 1
            assertFails { HomeCatalogParser.parseCatalogResponse(payload) }
        }

        assertEquals(18, fuzzed)
    }
}
