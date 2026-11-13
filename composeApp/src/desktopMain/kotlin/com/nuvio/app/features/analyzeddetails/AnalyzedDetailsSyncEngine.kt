package com.nuvio.app.features.analyzeddetails

import java.security.SecureRandom
import java.util.UUID

/**
 * One profile's sync with the analyzed-details Worker.
 * The passphrase, enroll secret, and bearer token are used and then dropped.
 * None of them is written to the session, and none of them is written to a log.
 */
internal class AnalyzedDetailsSync(
    private val transport: SyncTransport,
    private val secrets: SyncSecretStore,
    private val sessions: SyncSessionStore,
    private val directory: MarkDirectory,
    private val profileId: () -> Int = { 1 },
    private val now: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    private val newDeviceId: () -> String = { newSyncDeviceId(random) },
    private val newRecordId: () -> String = { UUID.randomUUID().toString() },
) {
    private var cachedKeys: DerivedKeys? = null
    private var cachedFor: String? = null

    fun session(): SyncSession = sessions.load(profileId())

    fun keysForTest(): DerivedKeys? = cachedKeys

    fun create(baseUrl: String, enrollSecret: String, passphrase: String): SyncSession {
        val url = normalizeSyncBaseUrl(baseUrl) ?: return fail("Enter the server URL, including https.")
        if (enrollSecret.length < MIN_ENROLL_SECRET_LENGTH) return fail("The enroll secret was rejected.")
        if (SyncCrypto.normalizePassphrase(passphrase).length < MIN_PASSPHRASE_LENGTH) {
            return fail("Use at least 8 characters.")
        }
        val existing = session()
        if (existing.joined) return fail(existing, "This profile already has a space. Leave it before creating another.")
        val salt = SyncCrypto.randomSalt(random)
        val keys = SyncCrypto.deriveKeys(passphrase, salt)
        val spaceId = UUID.randomUUID().toString()
        val deviceId = existing.deviceId.ifBlank { newDeviceId() }
        val body = canonicalJson(
            jObj(
                "iterations" to JsonVal.Num(KDF_ITERATIONS.toLong()),
                "kdf" to JsonVal.Str("pbkdf2-sha256"),
                "keyCheck" to JsonVal.Str(SyncCrypto.encodeB64(keys.keyCheck)),
                "salt" to JsonVal.Str(SyncCrypto.encodeB64(salt)),
                "spaceId" to JsonVal.Str(spaceId),
            ),
        )
        val result = call { transport.createSpace(enrollSecret, SyncCrypto.bearerToken(keys.authToken), body) }
            ?: return fail("Couldn't reach the server.")
        if (result.status != 201 && result.status != 200) {
            return fail(explain(result, enroll = true), listOf(passphrase, enrollSecret, SyncCrypto.bearerToken(keys.authToken)))
        }
        remember(keys, SyncCrypto.encodeB64(salt))
        val saved = existing.copy(
            baseUrl = url,
            spaceId = spaceId,
            saltBase64 = SyncCrypto.encodeB64(salt),
            keyCheckBase64 = SyncCrypto.encodeB64(keys.keyCheck),
            deviceId = deviceId,
            pullCursor = 0,
            lastSuccessfulSyncMs = null,
            secretStoreKind = secrets.kind,
            statusText = "Joined. Not synced yet.",
            statusError = false,
            joined = true,
        )
        secrets.put(secretAccount(), passphrase)
        return save(saved)
    }

    fun join(baseUrl: String, joinCode: String, passphrase: String): SyncSession {
        val url = normalizeSyncBaseUrl(baseUrl) ?: return fail("Enter the server URL, including https.")
        val parsed = parseJoinCode(joinCode.trim()) ?: return fail("That join code isn't valid.")
        if (SyncCrypto.normalizePassphrase(passphrase).length < MIN_PASSPHRASE_LENGTH) {
            return fail("Use at least 8 characters.")
        }
        val existing = session()
        if (existing.joined) return fail(existing, "This profile already has a space. Leave it before joining another.")
        val keys = SyncCrypto.deriveKeys(passphrase, parsed.salt)
        if (!SyncCrypto.constantTimeEquals(keys.keyCheck, parsed.keyCheck)) {
            return fail("Wrong passphrase.")
        }
        val result = call { transport.join(parsed.spaceId, SyncCrypto.bearerToken(keys.authToken)) }
            ?: return fail("Couldn't reach the server.")
        if (result.status != 200) {
            return fail(explain(result, enroll = false), listOf(passphrase, SyncCrypto.bearerToken(keys.authToken)))
        }
        remember(keys, SyncCrypto.encodeB64(parsed.salt))
        val deviceId = existing.deviceId.ifBlank { newDeviceId() }
        secrets.put(secretAccount(), passphrase)
        return save(
            existing.copy(
                baseUrl = url,
                spaceId = parsed.spaceId,
                saltBase64 = SyncCrypto.encodeB64(parsed.salt),
                keyCheckBase64 = SyncCrypto.encodeB64(parsed.keyCheck),
                deviceId = deviceId,
                pullCursor = 0,
                lastSuccessfulSyncMs = null,
                secretStoreKind = secrets.kind,
                statusText = "Joined. Not synced yet.",
                statusError = false,
                joined = true,
            ),
        )
    }

    fun leave(): SyncSession {
        val existing = session()
        secrets.delete(secretAccount())
        cachedKeys = null
        cachedFor = null
        return save(
            SyncSession(
                deviceId = existing.deviceId,
                submissions = existing.submissions,
                clockWallMs = existing.clockWallMs,
                clockCounter = existing.clockCounter,
                secretStoreKind = secrets.kind,
                statusText = "Left this computer. Other devices keep the space.",
                statusError = false,
                joined = false,
            ),
        )
    }

    fun noteSubmissions(accountScope: String, notes: List<SubmissionNote>): SyncSession {
        var session = session()
        var clock = DeviceClock(session.clockWallMs, session.clockCounter)
        val deviceId = session.deviceId.ifBlank { newDeviceId() }.also { session = session.copy(deviceId = it) }
        val kept = session.submissions.toMutableList()
        for (note in notes) {
            val record = note.toRecord(accountScope, clock, deviceId, now()) ?: continue
            clock = DeviceClock(record.hlc.wallMs, record.hlc.counter)
            val canonical = canonicalJson(record.wire)
            val index = kept.indexOfFirst { existing ->
                val parsed = parseRecord(parseJsonOrNull(existing) ?: return@indexOfFirst false)
                parsed is SubmissionRecord && parsed.id == record.id
            }
            if (index >= 0) {
                val previous = parseRecord(parseJson(kept[index])) as? SubmissionRecord
                if (previous != null && sameSubmission(previous, record)) continue
                kept[index] = canonical
            } else {
                kept += canonical
            }
        }
        return save(session.copy(submissions = kept, clockWallMs = clock.wallMs, clockCounter = clock.counter, deviceId = deviceId))
    }

    fun sync(): SyncSession {
        var session = session()
        if (!session.joined) return fail(session, "Not joined.")
        val keys = unlock(session) ?: return fail(session, "The passphrase isn't in the secret store.")
        val nowMs = now()
        session = purgeExpired(session, nowMs)
        val mode = syncMode(session.lastSuccessfulSyncMs, nowMs)
        val cursor = if (mode == SyncMode.FullResync) 0L else session.pullCursor
        val pulled = pullAll(session, keys, cursor, nowMs) ?: return session()
        session = pulled.session
        save(session)
        session = capture(session, nowMs)
        val local = localRecords(session)
        val toUpload = recordsToUpload(local, session.lastSuccessfulSyncMs, nowMs) { it.hlc.wallMs }
        val known = if (mode == SyncMode.FullResync) pulled.canonical.toMutableSet() else (session.knownCanonical + pulled.canonical).toMutableSet()
        val fresh = toUpload.filter { canonicalJson(it.wire) !in known }
        if (!pushAll(session, keys, fresh)) return session()
        fresh.forEach { known += canonicalJson(it.wire) }
        val inputs = if (mode == SyncMode.FullResync) {
            pulled.records + fresh.map { it.wire }
        } else {
            known.mapNotNull { parseJsonOrNull(it) } + local.map { it.wire }
        }
        val merged = mergeRecords(inputs, nowMs)
        session = writeBack(session.copy(knownCanonical = known.toList()), merged, mode, nowMs)
        session = session.copy(
            lastSuccessfulSyncMs = nowMs,
            statusText = "Synced.",
            statusError = false,
        )
        return save(session)
    }

    private fun pullAll(session: SyncSession, keys: DerivedKeys, start: Long, nowMs: Long): PullOutcome? {
        var cursor = start
        var clock = DeviceClock(session.clockWallMs, session.clockCounter)
        val records = mutableListOf<JsonVal>()
        val canonical = mutableListOf<String>()
        var pages = 0
        while (pages < 10_000) {
            pages += 1
            val result = call { transport.pull(session.spaceId, SyncCrypto.bearerToken(keys.authToken), cursor, 100) }
                ?: return failPull("Couldn't reach the server.")
            if (result.status != 200) return failPull(explain(result, enroll = false))
            val page = parsePullPage(result.body, cursor) ?: return failPull("The server returned a sync page this app couldn't accept.")
            cursor = advancePullCursor(cursor, PullPage(page.cursor, page.seqs))
            for (blob in page.blobs) {
                val plain = openBlob(keys, session.spaceId, blob) ?: continue
                val record = parseRecord(plain) ?: continue
                if (record.kind != blob.kind) continue
                if (clockIsAcceptable(record.hlc.wallMs, nowMs)) {
                    clock = receive(clock, record.hlc, nowMs)
                }
                records += record.wire
                canonical += canonicalJson(record.wire)
            }
            if (!page.hasMore) break
        }
        return PullOutcome(
            session.copy(pullCursor = cursor, clockWallMs = clock.wallMs, clockCounter = clock.counter),
            records,
            canonical,
        )
    }

    private fun pushAll(session: SyncSession, keys: DerivedKeys, records: List<SyncRecord>): Boolean {
        if (records.isEmpty()) return true
        val bearer = SyncCrypto.bearerToken(keys.authToken)
        records.chunked(MAX_BLOBS_PER_BATCH).forEach { chunk ->
            val blobs = chunk.map { record ->
                val id = UUID.randomUUID().toString()
                val nonce = SyncCrypto.randomNonce(random)
                val aad = SyncCrypto.associatedData(
                    RoutingAad(SYNC_FORMAT_VERSION, session.spaceId, id, session.deviceId, record.kind),
                )
                val plain = canonicalJson(record.wire).encodeToByteArray()
                if (plain.size > MAX_PLAINTEXT_BYTES) {
                    fail(session, "A record is too large to sync.")
                    return false
                }
                val cipher = SyncCrypto.encrypt(keys.encKey, nonce, aad, plain)
                jObj(
                    "ciphertext" to JsonVal.Str(SyncCrypto.encodeB64(cipher)),
                    "id" to JsonVal.Str(id),
                    "kind" to JsonVal.Str(record.kind),
                    "nonce" to JsonVal.Str(SyncCrypto.encodeB64(nonce)),
                    "v" to JsonVal.Num(1),
                )
            }
            val body = canonicalJson(jObj("blobs" to JsonVal.Arr(blobs), "deviceId" to JsonVal.Str(session.deviceId)))
            val result = call { transport.push(session.spaceId, bearer, body) } ?: run {
                fail(session, "Couldn't reach the server.")
                return false
            }
            if (result.status != 200) {
                fail(session, explain(result, enroll = false))
                return false
            }
        }
        return true
    }

    private fun capture(session: SyncSession, nowMs: Long): SyncSession {
        var clock = DeviceClock(session.clockWallMs, session.clockCounter)
        val slots = session.slots.toMutableList()
        val mirrors = session.mirrors.toMutableList()
        val seen = mutableSetOf<String>()
        for (mark in directory.list()) {
            val ref = parseDesktopMarkKey(desktopMarkKey(mark.parentMetaId, mark.season, mark.episode)) ?: continue
            val wire = wireSegment(mark.segmentLocal) ?: continue
            val mirror = mirrors.find { it.desktopKey == ref.desktopKey && it.segmentLocal == mark.segmentLocal }
            if (mark.fromDevice != null && mark.fromDevice != session.deviceId) {
                if (mirror != null && mirror.content == directoryContent(mark)) continue
                if (mirror != null) {
                    mirrors.remove(mirror)
                    val stamp = tick(clock, nowMs, session.deviceId)
                    clock = DeviceClock(stamp.wallMs, stamp.counter)
                    val slot = slotFrom(mark, ref, wire, mirror.recordId, stamp, deleted = false)
                    replaceSlot(slots, slot)
                    seen += slot.recordId
                }
                continue
            }
            val existing = slots.find { !it.deleted && it.desktopKey == ref.desktopKey && it.segmentLocal == mark.segmentLocal }
            val recordId = when {
                mark.presence == "none" -> noneId(ref, wire)
                existing != null && existing.presence == "range" -> existing.recordId
                mirror != null -> mirror.recordId
                else -> "mark/${newRecordId()}"
            }
            if (!isUuidV4(recordId.removePrefix("mark/")) && mark.presence != "none") continue
            val unchanged = existing != null && !existing.deleted && existing.recordId == recordId && existing.presence == mark.presence &&
                existing.startMs == mark.startMs && existing.endMs == mark.endMs && existing.durationMs == mark.durationMs &&
                existing.segmentIndex == mark.segmentIndex
            if (unchanged) {
                seen += existing.recordId
                mirrors.removeAll { it.desktopKey == ref.desktopKey && it.segmentLocal == mark.segmentLocal }
                continue
            }
            if (existing != null && existing.recordId != recordId) {
                val stamp = tick(clock, nowMs, session.deviceId)
                clock = DeviceClock(stamp.wallMs, stamp.counter)
                replaceSlot(slots, existing.copy(deleted = true, hlcWallMs = stamp.wallMs, hlcCounter = stamp.counter, hlcDeviceId = stamp.deviceId))
            }
            val stamp = tick(clock, nowMs, session.deviceId)
            clock = DeviceClock(stamp.wallMs, stamp.counter)
            replaceSlot(slots, slotFrom(mark, ref, wire, recordId, stamp, deleted = false))
            seen += recordId
            mirrors.removeAll { it.desktopKey == ref.desktopKey && it.segmentLocal == mark.segmentLocal }
        }
        for (mirror in mirrors.toList()) {
            if (directory.list().any { desktopMarkKey(it.parentMetaId, it.season, it.episode) == mirror.desktopKey && it.segmentLocal == mirror.segmentLocal }) {
                continue
            }
            val stamp = tick(clock, nowMs, session.deviceId)
            clock = DeviceClock(stamp.wallMs, stamp.counter)
            replaceSlot(
                slots,
                SyncSlot(
                    desktopKey = mirror.desktopKey,
                    segmentLocal = mirror.segmentLocal,
                    recordId = mirror.recordId,
                    showId = "",
                    presence = "range",
                    deleted = true,
                    hlcWallMs = stamp.wallMs,
                    hlcCounter = stamp.counter,
                    hlcDeviceId = stamp.deviceId,
                ),
            )
            mirrors.remove(mirror)
            seen += mirror.recordId
        }
        for (slot in slots.toList()) {
            if (slot.deleted || slot.recordId in seen || slot.hlcDeviceId != session.deviceId) continue
            val stamp = tick(clock, nowMs, session.deviceId)
            clock = DeviceClock(stamp.wallMs, stamp.counter)
            replaceSlot(slots, slot.copy(deleted = true, hlcWallMs = stamp.wallMs, hlcCounter = stamp.counter, hlcDeviceId = stamp.deviceId))
        }
        val saved = session.copy(slots = slots.toList(), mirrors = mirrors.toList(), clockWallMs = clock.wallMs, clockCounter = clock.counter)
        return save(saved)
    }

    private fun writeBack(session: SyncSession, merged: MergedState, mode: SyncMode, nowMs: Long): SyncSession {
        val applied = mutableListOf<Applied>()
        for (show in merged.shows) {
            for (episode in show.episodes) {
                val grouped = episode.marks.filter { it.applied }.groupBy { it.segmentType }
                for ((segment, marks) in grouped) {
                    val chosen = marks.minWith(compareBy<MarkView> { it.segmentIndex ?: 0L }.thenBy { it.id })
                    applied += Applied(show.showId, episode.season, episode.episode, chosen)
                }
            }
        }
        val mirrors = mutableListOf<SyncMirror>()
        val liveIds = mutableSetOf<String>()
        for (item in applied) {
            val local = localSegment(item.mark.segmentType) ?: continue
            val season = item.season?.toInt()
            val episode = item.episode?.toInt()
            val parent = parentFor(item.showId, season, episode) ?: continue
            val fromDevice = item.mark.hlc.deviceId.takeIf { it != session.deviceId }
            val mark = DirectoryMark(
                parentMetaId = parent,
                season = season,
                episode = episode,
                segmentLocal = local,
                presence = item.mark.presence,
                segmentIndex = (item.mark.segmentIndex ?: 0L).toInt(),
                startMs = item.mark.startMs ?: 0L,
                endMs = item.mark.endMs ?: 0L,
                durationMs = item.mark.durationMs,
                fromDevice = fromDevice,
            )
            directory.upsert(mark)
            if (fromDevice != null) {
                mirrors += SyncMirror(desktopMarkKey(parent, season, episode), local, item.mark.id, directoryContent(mark))
            } else {
                liveIds += item.mark.id
            }
        }
        for (existing in directory.list()) {
            val ref = parseDesktopMarkKey(desktopMarkKey(existing.parentMetaId, existing.season, existing.episode)) ?: continue
            val still = applied.any { item ->
                localSegment(item.mark.segmentType) == existing.segmentLocal &&
                    item.season?.toInt() == existing.season && item.episode?.toInt() == existing.episode &&
                    normShowId(item.showId) == normShowId(ref.showId)
            }
            if (still) continue
            val tracked = session.slots.any { it.desktopKey == ref.desktopKey && it.segmentLocal == existing.segmentLocal } ||
                session.mirrors.any { it.desktopKey == ref.desktopKey && it.segmentLocal == existing.segmentLocal }
            if (tracked) directory.remove(existing.parentMetaId, existing.season, existing.episode, existing.segmentLocal)
        }
        val cutoff = nowMs - TOMBSTONE_KEEP_MS
        val slots = session.slots.mapNotNull { slot ->
            if (slot.deleted) {
                if (nowMs >= slot.hlcWallMs + TOMBSTONE_KEEP_MS) null else slot
            } else if (slot.recordId in liveIds) {
                slot
            } else if (mode == SyncMode.FullResync && slot.hlcWallMs <= cutoff) {
                null
            } else if (merged.tombstones.any { it.id == slot.recordId }) {
                val tomb = merged.tombstones.first { it.id == slot.recordId }
                slot.copy(deleted = true, hlcWallMs = tomb.hlc.wallMs, hlcCounter = tomb.hlc.counter, hlcDeviceId = tomb.hlc.deviceId)
            } else {
                slot
            }
        }
        return save(session.copy(slots = slots, mirrors = mirrors))
    }

    private fun parentFor(showId: String, season: Int?, episode: Int?): String? {
        val wanted = normShowId(showId)
        val existing = directory.list().firstOrNull { mark ->
            val ref = parseDesktopMarkKey(desktopMarkKey(mark.parentMetaId, mark.season, mark.episode)) ?: return@firstOrNull false
            mark.season == season && mark.episode == episode && normShowId(ref.showId) == wanted
        }
        if (existing != null) return existing.parentMetaId
        return parentMetaIdForShow(showId)
    }

    private fun localRecords(session: SyncSession): List<SyncRecord> {
        val fromSlots = session.slots.mapNotNull { slot ->
            val stamp = ClockStamp(slot.hlcWallMs, slot.hlcCounter, slot.hlcDeviceId.ifBlank { session.deviceId })
            if (slot.deleted) return@mapNotNull tombstoneRecord("mark", slot.recordId, stamp)
            val wire = wireSegment(slot.segmentLocal) ?: return@mapNotNull null
            if (slot.presence == "none") {
                noneMarkRecord(stamp, slot.showId, slot.season?.toLong(), slot.episode?.toLong(), wire, slot.durationMs)
            } else {
                rangeMarkRecord(
                    slot.recordId, stamp, slot.showId, slot.season?.toLong(), slot.episode?.toLong(), wire,
                    slot.segmentIndex, slot.startMs, slot.endMs, slot.durationMs,
                )
            }
        }
        val fromSubmissions = session.submissions.mapNotNull { parseRecord(parseJsonOrNull(it) ?: return@mapNotNull null) }
        return fromSlots + fromSubmissions
    }

    private fun unlock(session: SyncSession): DerivedKeys? {
        val salt = SyncCrypto.decodeB64(session.saltBase64) ?: return null
        val saltKey = session.saltBase64
        cachedKeys?.let { if (cachedFor == saltKey) return it }
        val passphrase = secrets.get(secretAccount()) ?: return null
        val keys = SyncCrypto.deriveKeys(passphrase, salt)
        val expected = SyncCrypto.decodeB64(session.keyCheckBase64) ?: return null
        if (!SyncCrypto.constantTimeEquals(keys.keyCheck, expected)) return null
        remember(keys, saltKey)
        return keys
    }

    private fun remember(keys: DerivedKeys, saltBase64: String) {
        cachedKeys = keys
        cachedFor = saltBase64
    }

    private fun secretAccount(): String = "profile/${profileId()}"

    private fun save(session: SyncSession): SyncSession {
        sessions.save(profileId(), session)
        return session
    }

    private fun fail(message: String, secrets: List<String> = emptyList()): SyncSession =
        fail(session(), message, secrets)

    private fun fail(session: SyncSession, message: String, secrets: List<String> = emptyList()): SyncSession =
        save(session.copy(statusText = sanitize(message, secrets), statusError = true))

    private fun failPull(message: String): PullOutcome? {
        fail(session(), message)
        return null
    }

    private fun call(block: () -> SyncHttpResult): SyncHttpResult? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private fun purgeExpired(session: SyncSession, nowMs: Long): SyncSession {
        val slots = session.slots.filter { slot -> !slot.deleted || nowMs < slot.hlcWallMs + TOMBSTONE_KEEP_MS }
        return if (slots.size == session.slots.size) session else save(session.copy(slots = slots))
    }
}

internal data class SubmissionNote(
    val remoteId: String,
    val tmdbId: Int,
    val mediaType: String,
    val season: Int?,
    val episode: Int?,
    val segment: String,
    val status: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
)

private data class ParsedJoin(val spaceId: String, val salt: ByteArray, val keyCheck: ByteArray)

private data class PullOutcome(val session: SyncSession, val records: List<JsonVal>, val canonical: List<String>)

private data class RemoteBlob(
    val seq: Long,
    val id: String,
    val kind: String,
    val deviceId: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

private data class ParsedPage(val cursor: Long, val seqs: List<Long>, val blobs: List<RemoteBlob>, val hasMore: Boolean)

private data class Applied(val showId: String, val season: Long?, val episode: Long?, val mark: MarkView)

private fun parseJoinCode(code: String): ParsedJoin? {
    if (!code.startsWith("nuvio-sync-v1:")) return null
    val rest = code.removePrefix("nuvio-sync-v1:")
    val spaceId = rest.substringBefore(':')
    if (!isUuidV4(spaceId)) return null
    val after = rest.removePrefix("$spaceId:")
    val saltPart = after.substringBefore(':')
    val checkPart = after.substringAfter(':', "")
    if (saltPart.isEmpty() || checkPart.isEmpty() || ':' in checkPart) return null
    val salt = SyncCrypto.decodeB64Url(saltPart) ?: return null
    val check = SyncCrypto.decodeB64Url(checkPart) ?: return null
    if (salt.size != SyncCrypto.SALT_BYTES || check.size != SyncCrypto.KEY_CHECK_BYTES) return null
    return ParsedJoin(spaceId, salt, check)
}

private fun parsePullPage(body: String, previous: Long): ParsedPage? {
    val root = parseJsonOrNull(body) as? JsonVal.Obj ?: return null
    val cursor = (root.value["cursor"] as? JsonVal.Num)?.value ?: return null
    val hasMore = (root.value["hasMore"] as? JsonVal.Bool)?.value ?: return null
    val blobs = root.value["blobs"] as? JsonVal.Arr ?: return null
    val parsed = mutableListOf<RemoteBlob>()
    val seqs = mutableListOf<Long>()
    for (item in blobs.value) {
        val obj = item as? JsonVal.Obj ?: return null
        val seq = (obj.value["seq"] as? JsonVal.Num)?.value ?: return null
        val id = (obj.value["id"] as? JsonVal.Str)?.value ?: return null
        val kind = (obj.value["kind"] as? JsonVal.Str)?.value ?: return null
        val device = (obj.value["deviceId"] as? JsonVal.Str)?.value ?: return null
        val nonce = SyncCrypto.decodeB64((obj.value["nonce"] as? JsonVal.Str)?.value ?: return null) ?: return null
        val cipher = SyncCrypto.decodeB64((obj.value["ciphertext"] as? JsonVal.Str)?.value ?: return null) ?: return null
        seqs += seq
        parsed += RemoteBlob(seq, id, kind, device, nonce, cipher)
    }
    if (blobs.value.isEmpty() && cursor != previous) return null
    return ParsedPage(cursor, seqs, parsed, hasMore)
}

private fun openBlob(keys: DerivedKeys, spaceId: String, blob: RemoteBlob): JsonVal? {
    val aad = SyncCrypto.associatedData(RoutingAad(SYNC_FORMAT_VERSION, spaceId, blob.id, blob.deviceId, blob.kind))
    val plain = SyncCrypto.decrypt(keys.encKey, blob.nonce, aad, blob.ciphertext) ?: return null
    return parseJsonOrNull(plain.decodeToString())
}

private fun explain(result: SyncHttpResult, enroll: Boolean): String = when (result.status) {
    401 -> when {
        result.body.contains("Revoked") -> "This space was revoked."
        enroll -> "The enroll secret was rejected."
        else -> "Wrong passphrase."
    }
    404 -> "The server doesn't have this space."
    409 -> if (result.body.contains("evoked")) "This space was revoked." else "The server rejected the sync."
    429 -> "The server asked us to wait ${result.retryAfterSeconds ?: 1} seconds."
    else -> "The server returned ${result.status}."
}

private fun sanitize(message: String, secrets: List<String>): String {
    if (secrets.any { secret -> secret.length >= 8 && secret in message }) return "Sync failed."
    return message
}

private fun newSyncDeviceId(random: SecureRandom): String {
    val bytes = ByteArray(8)
    random.nextBytes(bytes)
    return "desktop-" + SyncCrypto.encodeHex(bytes)
}

private fun noneId(ref: DesktopEpisodeRef, wire: String): String = "none/${ref.showId}/${ref.episodeKey}/$wire"

private fun slotFrom(mark: DirectoryMark, ref: DesktopEpisodeRef, wire: String, recordId: String, stamp: ClockStamp, deleted: Boolean): SyncSlot =
    SyncSlot(
        desktopKey = ref.desktopKey,
        segmentLocal = mark.segmentLocal,
        recordId = if (mark.presence == "none") noneId(ref, wire) else recordId,
        showId = ref.showId,
        season = ref.season,
        episode = ref.episode,
        presence = mark.presence,
        segmentIndex = mark.segmentIndex,
        startMs = mark.startMs,
        endMs = mark.endMs,
        durationMs = mark.durationMs,
        deleted = deleted,
        hlcWallMs = stamp.wallMs,
        hlcCounter = stamp.counter,
        hlcDeviceId = stamp.deviceId,
    )

private fun replaceSlot(slots: MutableList<SyncSlot>, slot: SyncSlot) {
    val index = slots.indexOfFirst { it.recordId == slot.recordId }
    if (index >= 0) slots[index] = slot else slots += slot
}

private fun sameSubmission(left: SubmissionRecord, right: SubmissionRecord): Boolean =
    left.id == right.id && left.showId == right.showId && left.mediaType == right.mediaType &&
        left.season == right.season && left.episode == right.episode && left.segmentType == right.segmentType &&
        left.status == right.status && left.startMs == right.startMs && left.endMs == right.endMs &&
        left.durationMs == right.durationMs && left.remoteId == right.remoteId

private fun SubmissionNote.toRecord(accountScope: String, clock: DeviceClock, deviceId: String, nowMs: Long): SubmissionRecord? {
    if (!Regex("^[0-9a-f]{64}$").matches(accountScope)) return null
    val showId = "tmdb:$tmdbId"
    if (!isShowId(showId)) return null
    val wire = wireSegment(segment) ?: return null
    val movie = mediaType == "movie"
    if (!movie && mediaType != "tv") return null
    val seasonValue = if (movie) null else season?.toLong()
    val episodeValue = if (movie) null else episode?.toLong()
    if (!movie && (seasonValue == null || episodeValue == null)) return null
    val stamp = tick(clock, nowMs, deviceId)
    return submissionRecord(
        hlc = stamp,
        accountScope = accountScope,
        remoteId = remoteId,
        showId = showId,
        mediaType = mediaType,
        season = seasonValue,
        episode = episodeValue,
        segmentType = wire,
        status = status,
        startMs = startMs,
        endMs = endMs,
        durationMs = durationMs,
    )
}

internal class SyncDebounce {
    private var generation: Int = 0

    fun schedule(): Int {
        generation += 1
        return generation
    }

    fun shouldRun(token: Int): Boolean = token == generation

    fun syncNow(): Int {
        generation += 1
        return generation
    }
}
