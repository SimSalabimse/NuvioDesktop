package com.nuvio.app.features.player.skip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TheIntroDbKeyCheckTest {
    @Test
    fun `200 saves the key and posts the documented map dry-run`() = runTest {
        val engine = FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(200) }

        val verdict = checkTheIntroDbApiKey("obviously-fake-key", engine)

        val saved = assertIs<TheIntroDbKeyVerdict.Saved>(verdict)
        assertEquals("obviously-fake-key", saved.key)
        assertEquals("Saved.", saved.message)
        val request = engine.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.theintrodb.org/v4/map", request.url)
        assertEquals("Bearer obviously-fake-key", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals(TheIntroDbKeyCheckBody, request.body)
        assertFalse(request.body.contains("segment"))
        assertNull(request.headers.keys.firstOrNull { it.equals("X-API-Key", ignoreCase = true) })
        assertFalse(request.url.contains("introdb.app"))
        assertFalse(request.url.contains("/v3/"))
    }

    @Test
    fun `401 is key rejected`() = runTest {
        val engine = FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(401) }

        val verdict = checkTheIntroDbApiKey("obviously-fake-key", engine)

        val rejected = assertIs<TheIntroDbKeyVerdict.Rejected>(verdict)
        assertEquals(401, rejected.status)
        assertEquals("TheIntroDB rejected this key (401).", rejected.message)
    }

    @Test
    fun `403 is key rejected with its status`() = runTest {
        val engine = FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(403) }

        val verdict = checkTheIntroDbApiKey("obviously-fake-key", engine)

        val rejected = assertIs<TheIntroDbKeyVerdict.Rejected>(verdict)
        assertEquals(403, rejected.status)
        assertEquals("TheIntroDB rejected this key (403).", rejected.message)
    }

    @Test
    fun `404 is not treated as an invalid key`() = runTest {
        val verdict = checkTheIntroDbApiKey(
            "obviously-fake-key",
            FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(404) },
        )

        val unreachable = assertIs<TheIntroDbKeyVerdict.Unreachable>(verdict)
        assertEquals("Couldn't reach TheIntroDB (HTTP 404).", unreachable.message)
        assertFalse(unreachable.message.contains("rejected"))
        assertFalse(unreachable.message.contains("Invalid"))
    }

    @Test
    fun `405 from a get on the post route is not an invalid key`() = runTest {
        val verdict = checkTheIntroDbApiKey(
            "obviously-fake-key",
            FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(405) },
        )

        assertEquals(
            "Couldn't reach TheIntroDB (HTTP 405).",
            assertIs<TheIntroDbKeyVerdict.Unreachable>(verdict).message,
        )
    }

    @Test
    fun `400 is not treated as an invalid key`() = runTest {
        val verdict = checkTheIntroDbApiKey(
            "obviously-fake-key",
            FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(400) },
        )

        assertEquals(
            "Couldn't reach TheIntroDB (HTTP 400).",
            assertIs<TheIntroDbKeyVerdict.Unreachable>(verdict).message,
        )
    }

    @Test
    fun `server error reports the HTTP code`() = runTest {
        val verdict = checkTheIntroDbApiKey(
            "obviously-fake-key",
            FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(503) },
        )

        assertEquals(
            "Couldn't reach TheIntroDB (HTTP 503).",
            assertIs<TheIntroDbKeyVerdict.Unreachable>(verdict).message,
        )
    }

    @Test
    fun `network error is unreachable`() = runTest {
        val engine = FakeTheIntroDbKeyEngine { throw FakeConnectionReset() }

        val verdict = checkTheIntroDbApiKey("obviously-fake-key", engine)

        assertEquals(
            "Couldn't reach TheIntroDB (network).",
            assertIs<TheIntroDbKeyVerdict.Unreachable>(verdict).message,
        )
        assertEquals("https://api.theintrodb.org/v4/map", engine.requests.single().url)
    }

    @Test
    fun `timeout is not reported as an invalid key`() = runTest {
        val verdict = checkTheIntroDbApiKey(
            "obviously-fake-key",
            FakeTheIntroDbKeyEngine { throw FakeSocketTimeout() },
        )

        assertEquals(
            "Couldn't reach TheIntroDB (timeout).",
            assertIs<TheIntroDbKeyVerdict.Unreachable>(verdict).message,
        )
    }

    @Test
    fun `whitespace bearer prefix quotes and newlines are stripped`() = runTest {
        val engine = FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(200) }
        val pasted = " \n\"Bearer obviously-fake-key\"\n "

        val verdict = checkTheIntroDbApiKey(pasted, engine)

        val saved = assertIs<TheIntroDbKeyVerdict.Saved>(verdict)
        assertEquals("obviously-fake-key", saved.key)
        assertEquals("Bearer obviously-fake-key", engine.requests.single().headers["Authorization"])
        assertEquals("obviously-fake-key", normalizeTheIntroDbApiKey("Bearer\nobviously-fake-key"))
        assertEquals("obviously-fake-key", normalizeTheIntroDbApiKey("'Bearer obviously-fake-key'"))
        assertEquals("", normalizeTheIntroDbApiKey("  Bearer  "))
    }

    @Test
    fun `a blank key is saved without a request`() = runTest {
        val engine = FakeTheIntroDbKeyEngine { TheIntroDbKeyExchange.Status(500) }

        val verdict = checkTheIntroDbApiKey("  \n", engine)

        assertEquals("", assertIs<TheIntroDbKeyVerdict.Saved>(verdict).key)
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun `cancellation is not reported as a bad key`() = runTest {
        assertFailsWith<CancellationException> {
            checkTheIntroDbApiKey(
                "obviously-fake-key",
                FakeTheIntroDbKeyEngine { throw CancellationException("stopped") },
            )
        }
    }
}

private class FakeTheIntroDbKeyEngine(
    private val handler: suspend (TheIntroDbKeyRequest) -> TheIntroDbKeyExchange,
) : TheIntroDbKeyEngine {
    val requests = mutableListOf<TheIntroDbKeyRequest>()

    override suspend fun exchange(request: TheIntroDbKeyRequest): TheIntroDbKeyExchange {
        requests += request
        return handler(request)
    }
}

private class FakeConnectionReset : Exception("connection reset")

private class FakeSocketTimeout : Exception("deadline")
