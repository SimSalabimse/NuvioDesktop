package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException

/**
 * Checks a theintrodb.org user key.
 *
 * OpenAPI 4.0 documents no account or validate route. Frozen `/v2` and `/v3`
 * only serve `GET /media`, which accepts a missing key, so `GET /v3/user/stats`
 * cannot check one. `POST /v4/map` requires `Authorization: Bearer` and stores
 * nothing: 2xx means the key was accepted, 401 or 403 means it was refused.
 */
internal const val TheIntroDbKeyCheckUrl = "https://api.theintrodb.org/v4/map"

/** Documented movie dry-run. It carries no segment and `/map` does not store a row. */
internal const val TheIntroDbKeyCheckBody = """{"type":"movie","imdb_id":"tt0111161"}"""

internal data class TheIntroDbKeyRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
) {
    override fun toString(): String = "TheIntroDbKeyRequest(method=$method, url=$url)"
}

internal sealed interface TheIntroDbKeyExchange {
    data class Status(val code: Int) : TheIntroDbKeyExchange
    data object Network : TheIntroDbKeyExchange
    data object TimedOut : TheIntroDbKeyExchange
}

internal fun interface TheIntroDbKeyEngine {
    suspend fun exchange(request: TheIntroDbKeyRequest): TheIntroDbKeyExchange
}

internal sealed interface TheIntroDbKeyVerdict {
    val message: String

    data class Saved(val key: String) : TheIntroDbKeyVerdict {
        override val message: String get() = "Saved."
        override fun toString(): String = "Saved"
    }

    data class Rejected(val status: Int) : TheIntroDbKeyVerdict {
        override val message: String get() = "TheIntroDB rejected this key ($status)."
    }

    data class Unreachable(val detail: String) : TheIntroDbKeyVerdict {
        override val message: String get() = "Couldn't reach TheIntroDB ($detail)."
    }
}

private val bearerPrefix = Regex("""(?i)^bearer\b\s*""")

internal fun normalizeTheIntroDbApiKey(raw: String): String {
    var key = stripWrappingQuotes(raw.trim())
    while (true) {
        val stripped = bearerPrefix.replace(key, "")
        if (stripped == key) break
        key = stripWrappingQuotes(stripped.trim())
    }
    return key.replace(Regex("""\s+"""), "")
}

private fun stripWrappingQuotes(value: String): String {
    var current = value.trim()
    while (current.length >= 2) {
        val wrapped = (current.first() == '"' && current.last() == '"') ||
            (current.first() == '\'' && current.last() == '\'')
        if (!wrapped) return current
        current = current.substring(1, current.length - 1).trim()
    }
    return current
}

internal fun theIntroDbKeyRequest(normalizedKey: String): TheIntroDbKeyRequest =
    TheIntroDbKeyRequest(
        method = "POST",
        url = TheIntroDbKeyCheckUrl,
        headers = mapOf(
            "Authorization" to "Bearer $normalizedKey",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ),
        body = TheIntroDbKeyCheckBody,
    )

internal fun interpretTheIntroDbKeyExchange(
    normalizedKey: String,
    exchange: TheIntroDbKeyExchange,
): TheIntroDbKeyVerdict = when (exchange) {
    is TheIntroDbKeyExchange.Status -> when (exchange.code) {
        in 200..299 -> TheIntroDbKeyVerdict.Saved(normalizedKey)
        401, 403 -> TheIntroDbKeyVerdict.Rejected(exchange.code)
        else -> TheIntroDbKeyVerdict.Unreachable("HTTP ${exchange.code}")
    }
    TheIntroDbKeyExchange.Network -> TheIntroDbKeyVerdict.Unreachable("network")
    TheIntroDbKeyExchange.TimedOut -> TheIntroDbKeyVerdict.Unreachable("timeout")
}

internal suspend fun checkTheIntroDbApiKey(
    rawKey: String,
    engine: TheIntroDbKeyEngine = TheIntroDbLiveKeyEngine,
): TheIntroDbKeyVerdict {
    val key = normalizeTheIntroDbApiKey(rawKey)
    if (key.isEmpty()) return TheIntroDbKeyVerdict.Saved("")
    val exchange = try {
        engine.exchange(theIntroDbKeyRequest(key))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (isTheIntroDbTimeout(error)) TheIntroDbKeyExchange.TimedOut else TheIntroDbKeyExchange.Network
    }
    return interpretTheIntroDbKeyExchange(key, exchange)
}

private fun isTheIntroDbTimeout(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        val name = current::class.simpleName.orEmpty()
        if (name.contains("Timeout", ignoreCase = true)) return true
        val message = current.message?.lowercase().orEmpty()
        if ("timeout" in message || "timed out" in message) return true
        current = current.cause
    }
    return false
}

internal object TheIntroDbLiveKeyEngine : TheIntroDbKeyEngine {
    override suspend fun exchange(request: TheIntroDbKeyRequest): TheIntroDbKeyExchange {
        val response = httpRequestRaw(
            method = request.method,
            url = request.url,
            headers = request.headers,
            body = request.body,
        )
        return TheIntroDbKeyExchange.Status(response.status)
    }
}
