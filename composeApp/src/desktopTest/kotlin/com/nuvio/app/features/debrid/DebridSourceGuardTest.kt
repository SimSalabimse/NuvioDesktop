package com.nuvio.app.features.debrid

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebridSourceGuardTest {
    private val allowed = mapOf(
        "DirectDebridResolver.kt" to "defines it",
        "DebridSourceFallback.kt" to "the shared fallback path",
        "DirectDebridStreamPreparer.kt" to "background pre-resolve, plays nothing",
        "SiblingClipReference.kt" to "intro/credits analysis, not play or download",
        "DownloadLinkRefresh.kt" to "refreshes the same file's link; another source would not resume",
    )

    @Test
    fun noEntryPointBypassesTheFallback() {
        val root = commonMainRoot()
        assertTrue(root.isDirectory, "commonMain sources at ${root.absolutePath}")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { it.readText().contains("resolveToPlayableStream(") }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), offenders, "use DebridSourceFallback.playable instead of resolveToPlayableStream")
    }

    private fun commonMainRoot(): File {
        val start = File(System.getProperty("user.dir"))
        return generateSequence(start) { it.parentFile }
            .take(6)
            .flatMap { dir ->
                sequenceOf(
                    File(dir, "src/commonMain/kotlin"),
                    File(dir, "composeApp/src/commonMain/kotlin"),
                )
            }
            .first { it.isDirectory }
    }
}
