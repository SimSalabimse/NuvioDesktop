package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadQueueStressTest {
    @Test
    fun rapidPauseResumeAndCancelDoesNotCrashOrReviveTheDownload() {
        val saved = DownloadsStorage.loadPayload()
        val served = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow.bin") { exchange ->
            served.incrementAndGet()
            val body = ByteArray(512 * 1024)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { output ->
                var offset = 0
                while (offset < body.size) {
                    val chunk = minOf(4_096, body.size - offset)
                    output.write(body, offset, chunk)
                    offset += chunk
                    Thread.sleep(20)
                }
            }
        }
        server.start()
        val port = server.address.port
        val scheme = "http"
        val url = "$scheme://127.0.0.1:$port/slow.bin"
        var fuzzed = 0
        try {
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload("")
            DownloadsRepository.ensureLoaded()
            val result = DownloadsRepository.enqueueFromStream(
                contentType = "movie",
                videoId = "stress-video",
                parentMetaId = "stress-meta",
                parentMetaType = "movie",
                title = "Stress",
                logo = null,
                poster = null,
                background = null,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                stream = StreamItem(
                    url = url,
                    addonName = "Simsalabim",
                    addonId = "simsalabim.stress",
                ),
            )
            assertEquals(DownloadEnqueueResult.Started, result)
            val id = DownloadsRepository.uiState.value.items.single().id

            repeat(24) {
                DownloadsRepository.pauseDownload(id)
                fuzzed += 1
                DownloadsRepository.resumeDownload(id)
                fuzzed += 1
            }
            Thread.sleep(400)
            val afterResume = DownloadsRepository.uiState.value.items.single { it.id == id }
            assertTrue(
                afterResume.status == DownloadStatus.Downloading || afterResume.status == DownloadStatus.Completed,
                "Resumed download was ${afterResume.status}",
            )

            DownloadsRepository.cancelDownload(id)
            fuzzed += 1
            Thread.sleep(400)
            assertTrue(DownloadsRepository.uiState.value.items.none { it.id == id })
            assertEquals(49, fuzzed)
            assertTrue(served.get() > 0)
        } finally {
            server.stop(0)
            DownloadsRepository.clearLocalState()
            DownloadsStorage.savePayload(saved.orEmpty())
            DownloadsRepository.onProfileChanged()
        }
    }
}
