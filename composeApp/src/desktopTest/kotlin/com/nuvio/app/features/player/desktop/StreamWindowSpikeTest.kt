package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamWindowSpikeTest {
    @Test
    fun `gate stays closed unless macOS, the env switch, and a debug build all agree`() {
        assertFalse(streamWindowSpikeGate(macOS = true, envValue = null, compiled = true))
        assertFalse(streamWindowSpikeGate(macOS = true, envValue = "0", compiled = true))
        assertFalse(streamWindowSpikeGate(macOS = true, envValue = "1", compiled = false))
        assertFalse(streamWindowSpikeGate(macOS = false, envValue = "1", compiled = true))
        assertTrue(streamWindowSpikeGate(macOS = true, envValue = "1", compiled = true))
    }

    @Test
    fun `log line keeps the host hash and drops the url query and token`() {
        val url = "https://cdn.example:443/media/file.mkv?token=spike-secret-token&sig=abc"
        val host = streamWindowHostToken(url)
        assertEquals(streamWindowHostToken("https://cdn.example/other"), host)
        assertEquals(8, host.length)
        assertTrue(host.all { it.isDigit() || it in 'a'..'f' })
        assertFalse(host == streamWindowHostToken("https://other.example/file.mkv"))

        val line = streamWindowLogLine(
            window = "tail",
            startSec = 12.5,
            durationSec = 480.0,
            measurement = StreamWindowMeasurement(
                sampleRate = 8000,
                channels = 1,
                frames = 16000,
                bytesTransferred = 2048,
                wallMillis = 30,
                seekable = true,
                durationSec = 90.0,
                error = null,
            ),
            hostToken = host,
        )
        assertTrue(line.startsWith("{\"window\":\"tail\",\"startSec\":12.5,\"durationSec\":480,"))
        assertTrue(line.contains("\"frames\":16000"))
        assertTrue(line.contains("\"bytesTransferred\":2048"))
        assertTrue(line.contains("\"wallMillis\":30"))
        assertTrue(line.contains("\"seekable\":true"))
        assertTrue(line.contains("\"error\":null"))
        assertTrue(line.contains("\"host\":\"$host\""))
        assertFalse(line.contains("http"))
        assertFalse(line.contains("cdn.example"))
        assertFalse(line.contains("spike-secret-token"))
        assertFalse(line.contains("file.mkv"))
        assertFalse(line.contains("token"))
        assertFalse(line.contains("sig"))
    }

    @Test
    fun `local paths and bad urls do not echo the path`() {
        assertEquals("local", streamWindowHostToken("/tmp/window.mkv"))
        assertEquals("local", streamWindowHostToken("file:///tmp/window.mkv?token=spike-secret-token"))
        assertEquals("unknown", streamWindowHostToken("not a url ?token=spike-secret-token"))
        val line = streamWindowLogLine(
            window = "head",
            startSec = 0.0,
            durationSec = 600.0,
            measurement = StreamWindowMeasurement(error = "connection_refused"),
            hostToken = streamWindowHostToken("/tmp/window.mkv?token=spike-secret-token"),
        )
        assertTrue(line.contains("\"window\":\"head\""))
        assertTrue(line.contains("\"error\":\"connection_refused\""))
        assertTrue(line.contains("\"host\":\"local\""))
        assertFalse(line.contains("/tmp"))
        assertFalse(line.contains("spike-secret-token"))
        assertFalse(line.contains("window.mkv"))
    }

    @Test
    fun `parser reads the native result and rejects a leaked or broken payload`() {
        val parsed = parseStreamWindowMeasurement(
            "{\"sampleRate\":8000,\"channels\":1,\"frames\":16000,\"bytesTransferred\":99," +
                "\"wallMillis\":12,\"seekable\":1,\"durationSec\":45.500,\"error\":null}",
        )
        assertEquals(8000, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16000L, parsed.frames)
        assertEquals(99L, parsed.bytesTransferred)
        assertEquals(12L, parsed.wallMillis)
        assertTrue(parsed.seekable)
        assertEquals(45.5, parsed.durationSec)
        assertNull(parsed.error)

        val failed = parseStreamWindowMeasurement(
            "{\"sampleRate\":0,\"channels\":0,\"frames\":0,\"bytesTransferred\":0," +
                "\"wallMillis\":4,\"seekable\":0,\"durationSec\":-1.000,\"error\":\"not_seekable\"}",
        )
        assertEquals("not_seekable", failed.error)
        assertFalse(failed.seekable)
        assertEquals("native_error", parseStreamWindowMeasurement("not-json").error)
    }
}
