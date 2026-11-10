package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonManifestParserFuzzTest {
    @Test
    fun malformedManifestFieldsDoNotRejectTheWholeAddon() {
        var fuzzed = 0
        val manifestUrl = "local-manifest"

        fun keep(payload: String, block: (AddonManifest) -> Unit) {
            fuzzed += 1
            block(AddonManifestParser.parse(manifestUrl, payload))
        }

        keep("""{"id":"addon.one","name":"One","version":"1.0.0"}""") { manifest ->
            assertEquals("addon.one", manifest.id)
            assertEquals("One", manifest.name)
            assertTrue(manifest.catalogs.isEmpty())
        }

        keep(
            """
            {"id":"addon.one","name":"One","version":"1.0.0",
             "types":["movie",{"name":"no"},""],
             "idPrefixes":[{"bad":true},"tt"],
             "resources":["stream",["nested"],{"types":["movie"]}],
             "catalogs":["nope",{"type":"movie","id":"top","extra":["bad",{"name":"genre","isRequired":true,"options":["Drama",{"x":1}],"optionsLimit":"nope"}]},{"id":"missing-type"}],
             "behaviorHints":["nope"]}
            """.trimIndent(),
        ) { manifest ->
            assertEquals(listOf("movie"), manifest.types)
            assertEquals(listOf("tt"), manifest.idPrefixes)
            assertEquals(listOf("stream"), manifest.resources.map { it.name })
            assertEquals(1, manifest.catalogs.size)
            assertEquals("top", manifest.catalogs.single().id)
            val genre = manifest.catalogs.single().extra.single()
            assertEquals("genre", genre.name)
            assertTrue(genre.isRequired)
            assertEquals(listOf("Drama"), genre.options)
            assertEquals(null, genre.optionsLimit)
            assertFalse(manifest.behaviorHints.adult)
        }

        keep(
            """
            {"id":"addon.one","name":"One","version":1,
             "behaviorHints":{"adult":{"nested":true},"configurable":true}}
            """.trimIndent(),
        ) { manifest ->
            assertEquals("1", manifest.version)
            assertFalse(manifest.behaviorHints.adult)
            assertTrue(manifest.behaviorHints.configurable)
        }

        listOf(
            """{"name":"One","version":"1"}""",
            """{"id":"addon.one","version":"1"}""",
            """{"id":"addon.one","name":"One"}""",
            """{"id":{"nested":true},"name":"One","version":"1"}""",
            "{",
            "[]",
            "null",
            "",
        ).forEach { payload ->
            fuzzed += 1
            assertFails { AddonManifestParser.parse(manifestUrl, payload) }
        }

        assertEquals(11, fuzzed)
    }
}
