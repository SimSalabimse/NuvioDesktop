package com.nuvio.app.features.analyzeddetails

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class FakeSyncServer(
    enrollSecret: String,
) : AutoCloseable {
    data class Call(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class Space(val tokenHash: String, val salt: String, val keyCheck: String)

    private data class Blob(
        val seq: Long,
        val id: String,
        val kind: String,
        val deviceId: String,
        val nonce: String,
        val ciphertext: String,
    )

    private val enroll = enrollSecret
    private val spaces = ConcurrentHashMap<String, Space>()
    private val blobs = CopyOnWriteArrayList<Blob>()
    private var nextSeq = 0L
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val calls = CopyOnWriteArrayList<Call>()
    var failPullStatus: Int? = null
    var retryAfter: Long? = null
    var failPushStatus: Int? = null
    var failPushBody: String = """{"error":"conflict","message":"Retry the batch"}"""
    var failPushRemaining: Int = 0
    var pullEntered: CountDownLatch? = null
    var pullGate: CountDownLatch? = null

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun highSeq(): Long = blobs.maxOfOrNull { it.seq } ?: 0L

    fun clearBlobs() {
        blobs.clear()
        nextSeq = 0L
    }

    fun putBlob(id: String, kind: String, deviceId: String, nonce: String, ciphertext: String) {
        nextSeq += 1
        blobs += Blob(nextSeq, id, kind, deviceId, nonce, ciphertext)
    }

    override fun close() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        val headers = exchange.requestHeaders.entries.associate { (key, values) ->
            key.lowercase() to values.joinToString(",")
        }
        val path = exchange.requestURI.path
        val query = exchange.requestURI.rawQuery.orEmpty()
        calls += Call(exchange.requestMethod, path, query, headers, body)
        val response = route(exchange.requestMethod, path, query, headers, body)
        val bytes = response.body.encodeToByteArray()
        if (response.retryAfter != null) exchange.responseHeaders.add("retry-after", response.retryAfter.toString())
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun route(method: String, path: String, query: String, headers: Map<String, String>, body: String): Response {
        if (method == "POST" && path == "/v1/spaces") return create(headers, body)
        val join = Regex("^/v1/spaces/([^/]+)/join$").matchEntire(path)
        if (method == "POST" && join != null) return join(join.groupValues[1], headers, body)
        val batches = Regex("^/v1/spaces/([^/]+)/batches$").matchEntire(path)
        if (method == "POST" && batches != null) return push(batches.groupValues[1], headers, body)
        val pull = Regex("^/v1/spaces/([^/]+)/blobs$").matchEntire(path)
        if (method == "GET" && pull != null) return pull(pull.groupValues[1], headers, query)
        if (method == "DELETE") return Response(405, """{"error":"method_not_allowed","message":"no"}""")
        return Response(404, """{"error":"not_found","message":"Not found"}""")
    }

    private fun create(headers: Map<String, String>, body: String): Response {
        if (headers["x-nuvio-enroll"] != enroll) return Response(401, """{"error":"unauthorized","message":"Unauthorized"}""")
        val token = bearer(headers) ?: return Response(401, """{"error":"unauthorized","message":"Unauthorized"}""")
        val root = parseJsonOrNull(body) as? JsonVal.Obj ?: return Response(400, """{"error":"bad_request","message":"Invalid space"}""")
        val spaceId = (root.value["spaceId"] as? JsonVal.Str)?.value ?: return Response(400, """{"error":"bad_request","message":"Invalid space"}""")
        val salt = (root.value["salt"] as? JsonVal.Str)?.value ?: return Response(400, """{"error":"bad_request","message":"Invalid space"}""")
        val keyCheck = (root.value["keyCheck"] as? JsonVal.Str)?.value ?: return Response(400, """{"error":"bad_request","message":"Invalid space"}""")
        spaces[spaceId] = Space(SyncCrypto.encodeHex(SyncCrypto.sha256(token)), salt, keyCheck)
        return Response(201, """{"v":1,"spaceId":"$spaceId","kdf":"pbkdf2-sha256","iterations":600000,"salt":"$salt","keyCheck":"$keyCheck"}""")
    }

    private fun join(spaceId: String, headers: Map<String, String>, body: String): Response {
        if (body.isNotEmpty()) return Response(400, """{"error":"bad_request","message":"Invalid join"}""")
        val space = spaces[spaceId] ?: return Response(404, """{"error":"not_found","message":"Not found"}""")
        if (!authorized(space, headers)) return Response(401, """{"error":"unauthorized","message":"Unauthorized"}""")
        return Response(200, """{"v":1,"spaceId":"$spaceId","kdf":"pbkdf2-sha256","iterations":600000,"salt":"${space.salt}","keyCheck":"${space.keyCheck}"}""")
    }

    private fun push(spaceId: String, headers: Map<String, String>, body: String): Response {
        val forced = failPushStatus
        if (forced != null && failPushRemaining > 0) {
            failPushRemaining -= 1
            return Response(forced, failPushBody, if (forced == 429) retryAfter else null)
        }
        val space = spaces[spaceId] ?: return Response(404, """{"error":"not_found","message":"Not found"}""")
        if (!authorized(space, headers)) return Response(401, """{"error":"unauthorized","message":"Unauthorized"}""")
        val root = parseJsonOrNull(body) as? JsonVal.Obj ?: return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
        val items = root.value["blobs"] as? JsonVal.Arr ?: return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
        val stored = mutableListOf<String>()
        for (item in items.value) {
            val obj = item as? JsonVal.Obj ?: return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
            val id = (obj.value["id"] as? JsonVal.Str)?.value ?: return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
            val kind = (obj.value["kind"] as? JsonVal.Str)?.value ?: return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
            val nonce = (obj.value["nonce"] as? JsonVal.Str)?.value ?: return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
            val ciphertext = (obj.value["ciphertext"] as? JsonVal.Str)?.value ?: return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
            val deviceId = (root.value["deviceId"] as? JsonVal.Str)?.value ?: return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
            if ((obj.value["v"] as? JsonVal.Num)?.value != 1L) return Response(400, """{"error":"bad_request","message":"Invalid batch"}""")
            nextSeq += 1
            blobs += Blob(nextSeq, id, kind, deviceId, nonce, ciphertext)
            stored += """{"id":"$id","seq":$nextSeq,"status":"stored"}"""
        }
        return Response(200, """{"v":1,"results":[${stored.joinToString(",")}]}""")
    }

    private fun pull(spaceId: String, headers: Map<String, String>, query: String): Response {
        pullEntered?.countDown()
        pullGate?.await(5, TimeUnit.SECONDS)
        val forced = failPullStatus
        if (forced != null) return Response(forced, """{"error":"rate_limited","message":"slow down"}""", retryAfter)
        val space = spaces[spaceId] ?: return Response(404, """{"error":"not_found","message":"Not found"}""")
        if (!authorized(space, headers)) return Response(401, """{"error":"unauthorized","message":"Unauthorized"}""")
        val params = query.split("&").mapNotNull { part ->
            val bits = part.split("=", limit = 2)
            if (bits.size == 2) bits[0] to bits[1] else null
        }.toMap()
        val cursor = params["cursor"]?.toLongOrNull() ?: return Response(400, """{"error":"bad_request","message":"Invalid cursor"}""")
        val limit = params["limit"]?.toIntOrNull() ?: 100
        val page = blobs.filter { it.seq > cursor }.sortedBy { it.seq }.take(limit + 1)
        val hasMore = page.size > limit
        val shown = if (hasMore) page.dropLast(1) else page
        val last = shown.lastOrNull()?.seq ?: cursor
        val json = shown.joinToString(",") { blob ->
            """{"seq":${blob.seq},"id":"${blob.id}","v":1,"kind":"${blob.kind}","deviceId":"${blob.deviceId}","nonce":"${blob.nonce}","ciphertext":"${blob.ciphertext}","receivedAtMs":0}"""
        }
        return Response(200, """{"v":1,"blobs":[$json],"cursor":$last,"hasMore":$hasMore}""")
    }

    private fun authorized(space: Space, headers: Map<String, String>): Boolean {
        val token = bearer(headers) ?: return false
        return SyncCrypto.encodeHex(SyncCrypto.sha256(token)) == space.tokenHash
    }

    private fun bearer(headers: Map<String, String>): ByteArray? {
        val header = headers["authorization"] ?: return null
        if (!header.startsWith("Bearer ")) return null
        return SyncCrypto.decodeB64Url(header.removePrefix("Bearer "))
    }

    private data class Response(val status: Int, val body: String, val retryAfter: Long? = null)
}
