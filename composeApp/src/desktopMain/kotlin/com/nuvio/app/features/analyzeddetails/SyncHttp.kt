package com.nuvio.app.features.analyzeddetails

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal data class SyncHttpResult(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Long?,
)

internal interface SyncTransport {
    val baseUrl: String
    fun createSpace(enrollSecret: String, bearer: String, body: String): SyncHttpResult
    fun join(spaceId: String, bearer: String): SyncHttpResult
    fun push(spaceId: String, bearer: String, body: String): SyncHttpResult
    fun pull(spaceId: String, bearer: String, cursor: Long, limit: Int): SyncHttpResult
}

internal class SyncHttpClient(
    override val baseUrl: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : SyncTransport {
    override fun createSpace(enrollSecret: String, bearer: String, body: String): SyncHttpResult =
        send(
            path = "/v1/spaces",
            method = "POST",
            bearer = bearer,
            enrollSecret = enrollSecret,
            body = body,
        )

    override fun join(spaceId: String, bearer: String): SyncHttpResult =
        send(path = "/v1/spaces/$spaceId/join", method = "POST", bearer = bearer, body = "")

    override fun push(spaceId: String, bearer: String, body: String): SyncHttpResult =
        send(path = "/v1/spaces/$spaceId/batches", method = "POST", bearer = bearer, body = body)

    override fun pull(spaceId: String, bearer: String, cursor: Long, limit: Int): SyncHttpResult =
        send(path = "/v1/spaces/$spaceId/blobs?cursor=$cursor&limit=$limit", method = "GET", bearer = bearer, body = null)

    private fun send(
        path: String,
        method: String,
        bearer: String?,
        enrollSecret: String? = null,
        body: String?,
    ): SyncHttpResult {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (enrollSecret != null) builder.header("X-Nuvio-Enroll", enrollSecret)
        when {
            body == null -> builder.GET()
            body.isEmpty() -> builder.POST(HttpRequest.BodyPublishers.noBody())
            else -> {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            }
        }
        if (body == null && method != "GET") builder.method(method, HttpRequest.BodyPublishers.noBody())
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val retry = response.headers().firstValue("retry-after").orElse(null)?.toLongOrNull()
        return SyncHttpResult(response.statusCode(), response.body().orEmpty(), retry)
    }
}

internal fun normalizeSyncBaseUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null) return null
    return trimmed
}

internal fun syncServerHost(baseUrl: String): String =
    runCatching { URI(baseUrl).host }.getOrNull().orEmpty().ifBlank { baseUrl }
