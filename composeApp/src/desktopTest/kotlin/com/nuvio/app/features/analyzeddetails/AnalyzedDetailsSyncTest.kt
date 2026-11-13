package com.nuvio.app.features.analyzeddetails

import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzedDetailsSyncTest {
    @Test
    fun vectorsMatchThePublishedBytes() {
        val provenance = resource("PROVENANCE.txt")
        assertTrue(provenance.contains("5a9eae0539bdf274469b6bb46905b45c93025c3c"))
        assertCrypto(resource("crypto.json"))
        assertPassphrase(resource("passphrase-nfc.json"))
        for (name in listOf(
            "alias-merge.json",
            "clock-json-tie.json",
            "clock-skew.json",
            "clock-tie.json",
            "mark-beats-detection.json",
            "not-found-vs-none.json",
            "rejected.json",
            "submission-scope.json",
            "two-cuts.json",
            "zero-duration-mark.json",
        )) {
            val root = parseJson(resource(name)) as JsonVal.Obj
            val scenarios = root.value["scenarios"] as? JsonVal.Arr
            if (scenarios != null) {
                scenarios.value.forEach { assertScenario(it as JsonVal.Obj) }
            } else {
                assertScenario(root)
            }
        }
        val tombstone = parseJson(resource("tombstone.json")) as JsonVal.Obj
        val records = (tombstone.value["records"] as JsonVal.Arr).value
        for (case in (tombstone.value["cases"] as JsonVal.Arr).value) {
            val obj = case as JsonVal.Obj
            val now = (obj.value["nowMs"] as JsonVal.Num).value
            assertEquals((obj.value["mergedCanonical"] as JsonVal.Str).value, mergedCanonical(records, now))
        }
        assertOfflineVector(parseJson(resource("offline-resync.json")) as JsonVal.Obj)
    }

    @Test
    fun clockRejectsADayInTheFutureAndKeepsTheCounter() {
        val first = tick(DeviceClock(0, 0), 1_000, "device-a")
        assertEquals(1_000, first.wallMs)
        assertEquals(0, first.counter)
        val same = tick(DeviceClock(first.wallMs, first.counter), 1_000, "device-a")
        assertEquals(1, same.counter)
        val reset = tick(DeviceClock(1_000 + MAX_HLC_SKEW_MS + 1, 4), 1_000, "device-a")
        assertEquals(ClockStamp(1_000, 0, "device-a"), reset)
        val ignored = receive(
            DeviceClock(1_000, 0),
            ClockStamp(1_000 + MAX_HLC_SKEW_MS + 1, 9, "device-b"),
            1_000,
        )
        assertEquals(DeviceClock(1_000, 0), ignored)
        val error = assertFailsWith<SyncClockException> {
            tick(DeviceClock(5, MAX_HLC_COUNTER), 5, "device-a")
        }
        assertEquals("counter_exhausted", error.code)
    }

    @Test
    fun pullCursorMovesOnlyFromPullPages() {
        assertEquals(5, advancePullCursor(5, PullPage(5, emptyList())))
        assertFailsWith<IllegalArgumentException> { advancePullCursor(5, PullPage(6, emptyList())) }
        assertEquals(8, advancePullCursor(4, PullPage(8, listOf(5, 8))))
        assertFailsWith<IllegalArgumentException> { advancePullCursor(4, PullPage(9, listOf(5, 8))) }
        assertEquals(SyncMode.FullResync, syncMode(1_000_000, 1_000_000 + TOMBSTONE_KEEP_MS))
        assertEquals(SyncMode.Incremental, syncMode(1_000_000, 1_000_000 + TOMBSTONE_KEEP_MS - 1))
        assertEquals(SyncMode.Incremental, syncMode(null, 9_000))
    }

    @Test
    fun desktopKeysUseTheSpecEpisodeIdentity() {
        val episode = parseDesktopMarkKey("tmdb:100:1:8")
        assertEquals("tmdb:100", episode?.showId)
        assertEquals("s1e8", episode?.episodeKey)
        val bare = parseDesktopMarkKey("100:1:8")
        assertEquals("tmdb:100", bare?.showId)
        assertEquals("100:1:8", bare?.desktopKey)
        val imdb = parseDesktopMarkKey("tt0000100:1:8")
        assertEquals("imdb:tt0000100", imdb?.showId)
        assertEquals("tt0000100", imdb?.parentMetaId)
        assertEquals("movie", parseDesktopMarkKey("tmdb:100")?.episodeKey)
        assertEquals("credits", wireSegment("outro"))
        assertEquals("outro", localSegment("credits"))
        assertEquals(normShowId("imdb:tt0000100"), normShowId("imdb:tt100"))
        assertEquals("tt0000100", parentMetaIdForShow("imdb:tt0000100"))
        assertEquals("tmdb:100", parentMetaIdForShow("tmdb:100"))
    }

    @Test
    fun secretStoresDoNotPutThePassphraseOnTheCommandLine() {
        val secret = "test-passphrase-not-a-secret"
        assertEquals(listOf("/usr/bin/security", "-w", "***"), redactSecretArguments(listOf("/usr/bin/security", "-w", secret)))
        assertFalse(WindowsCredentialSecretStore().commandHasSecret(secret))
        val directory = Files.createTempDirectory("nuvio-sync-secrets")
        val store = EncryptedFileSecretStore(directory)
        store.put("profile/1", secret)
        assertEquals(secret, store.get("profile/1"))
        val saved = Files.readString(directory.resolve("values.json"))
        assertFalse(saved.contains(secret))
        store.delete("profile/1")
        assertNull(store.get("profile/1"))
    }

    @Test
    fun debounceKeepsOnlyTheLatestEdit() {
        val debounce = SyncDebounce()
        val first = debounce.schedule()
        val second = debounce.schedule()
        assertFalse(debounce.shouldRun(first))
        assertTrue(debounce.shouldRun(second))
        debounce.syncNow()
        assertFalse(debounce.shouldRun(second))
        assertEquals(2_000L, SYNC_DEBOUNCE_MS)
    }

    @Test
    fun twoDevicesShareMarksAndTheCursorStaysBehindThePush() {
        val passphrase = "test-passphrase-not-a-secret"
        val enroll = "test-enroll-secret"
        FakeSyncServer(enroll).use { server ->
            val now = longArrayOf(1_700_000_000_000)
            val left = device(server, "device-a") { now[0] }
            val right = device(server, "device-b") { now[0] }
            val created = left.create(server.baseUrl, enroll, passphrase)
            assertTrue(created.joined, created.statusText)
            assertFalse(server.calls.any { it.body.contains(passphrase) || it.headers.values.any { value -> passphrase in value } })
            val enrollCalls = server.calls.filter { it.headers["x-nuvio-enroll"] != null }
            assertEquals(1, enrollCalls.size)
            server.failPullStatus = 429
            server.retryAfter = 3
            val limited = left.sync()
            assertEquals("The server asked us to wait 3 seconds.", limited.statusText)
            server.failPullStatus = null
            right.join(server.baseUrl, created.joinCode(), "not-the-passphrase")
            assertEquals("Wrong passphrase.", right.session().statusText)
            assertTrue(server.calls.none { it.path.endsWith("/join") })
            val joined = right.join(server.baseUrl, created.joinCode(), passphrase)
            assertTrue(joined.joined, joined.statusText)
            right.directory.upsert(mark("tmdb:100", 1, 8, "intro", 10_000, 20_000))
            val rightFirst = right.sync()
            assertEquals("Synced.", rightFirst.statusText)
            assertEquals(0, rightFirst.pullCursor)
            assertEquals(1, server.highSeq())
            left.directory.upsert(mark("tmdb:100", 1, 8, "recap", 1_000, 2_000))
            val leftSync = left.sync()
            assertEquals("Synced.", leftSync.statusText)
            assertEquals(1, leftSync.pullCursor)
            assertEquals(2, server.highSeq())
            assertEquals(setOf(10_000L, 1_000L), left.directory.list().map { it.startMs }.toSet())
            assertEquals("device-b", left.directory.list().first { it.startMs == 10_000L }.fromDevice)
            val rightSecond = right.sync()
            assertEquals("Synced.", rightSecond.statusText)
            assertEquals(setOf(10_000L, 1_000L), right.directory.list().map { it.startMs }.toSet())
            assertEquals("device-a", right.directory.list().first { it.startMs == 1_000L }.fromDevice)
            val beforeLeave = server.calls.size
            left.leave()
            assertEquals(beforeLeave, server.calls.size)
            assertFalse(left.session().joined)
            assertTrue(server.calls.none { it.method == "DELETE" })
        }
    }

    @Test
    fun fullResyncAfterNinetyDaysDropsTheStaleMark() {
        val passphrase = "test-passphrase-not-a-secret"
        FakeSyncServer("test-enroll-secret").use { server ->
            val now = longArrayOf(1_000_000L)
            val device = device(server, "device-a") { now[0] }
            assertTrue(device.create(server.baseUrl, "test-enroll-secret", passphrase).joined)
            device.directory.upsert(mark("tmdb:100", 1, 8, "intro", 10_000, 20_000))
            assertEquals("Synced.", device.sync().statusText)
            assertEquals(0, device.session().pullCursor)
            assertEquals(1, server.highSeq())
            server.clearBlobs()
            now[0] = 7_778_000_000L
            device.directory.upsert(mark("tmdb:100", 1, 8, "recap", 1_000, 2_000))
            val callsBefore = server.calls.size
            val synced = device.sync()
            assertEquals("Synced.", synced.statusText)
            val later = server.calls.drop(callsBefore)
            val pull = later.first { it.path.endsWith("/blobs") && it.method == "GET" }
            assertTrue(pull.query.startsWith("cursor=0"))
            val plain = plaintexts(later, device.keysForTest()!!)
            assertTrue(plain.any { it.contains("\"startMs\":1000") })
            assertFalse(plain.any { it.contains("\"startMs\":10000") })
            assertEquals(listOf(1_000L), device.directory.list().map { it.startMs })
        }
    }

    @Test
    fun notFoundDoesNotBecomeNoneHere() {
        val passphrase = "test-passphrase-not-a-secret"
        FakeSyncServer("test-enroll-secret").use { server ->
            val device = device(server, "device-a") { 1_700_000_000_000 }
            val created = device.create(server.baseUrl, "test-enroll-secret", passphrase)
            assertTrue(created.joined, created.statusText)
            val keys = device.keysForTest()!!
            server.putBlob(
                id = "33333333-3333-4333-8333-333333333331",
                kind = "detection",
                deviceId = "device-b",
                nonce = SyncCrypto.encodeB64(SyncCrypto.decodeB64("EBESExQVFhcYGRob")!!),
                ciphertext = encryptDetection(keys, created.spaceId, "33333333-3333-4333-8333-333333333331", "device-b", notFound = true),
            )
            server.putBlob(
                id = "33333333-3333-4333-8333-333333333332",
                kind = "detection",
                deviceId = "device-b",
                nonce = SyncCrypto.encodeB64(SyncCrypto.decodeB64("EBESExQVFhcYGRob")!!),
                ciphertext = encryptDetection(keys, created.spaceId, "33333333-3333-4333-8333-333333333332", "device-b", notFound = false),
            )
            assertEquals("Synced.", device.sync().statusText)
            assertTrue(device.directory.list().none { it.presence == "none" })
            val overlay = overlaySyncedSkips(
                intervals = emptyList(),
                merged = mergedFromSession(device.session(), 1_700_000_000_000),
                parentMetaId = "tmdb:100",
                season = 1,
                episode = 8,
                fileDurationMs = 3_600_000,
                isMovie = false,
            )
            assertEquals(listOf("recap"), overlay.map { it.type })
            assertTrue(overlay.none { it.type == "intro" })
        }
    }

    @Test
    fun ownMarkBeatsADetectionOnTheSameCut() {
        val root = parseJson(resource("mark-beats-detection.json")) as JsonVal.Obj
        val records = (root.value["records"] as JsonVal.Arr).value
        val now = (root.value["nowMs"] as JsonVal.Num).value
        val merged = mergeRecords(records, now)
        val intro = merged.shows.single().episodes.single().marks.filter { it.segmentType == "intro" && it.applied }
        assertTrue(intro.any { it.presence == "range" })
        val overlay = overlaySyncedSkips(
            intervals = emptyList(),
            merged = merged,
            parentMetaId = "tmdb:100",
            season = 1,
            episode = 8,
            fileDurationMs = 3_600_000,
            isMovie = false,
        )
        val shown = overlay.filter { it.type == "intro" }
        assertEquals(listOf(30.0, 50.0), shown.map { it.startTime })
        assertTrue(shown.all { it.provider == "sync:device-a" })
        assertTrue(overlay.none { it.type == "intro" && it.startTime == 1.0 })
        assertTrue(overlay.any { it.type == "recap" && it.provider == "sync:device-a" })
    }

    private fun assertScenario(root: JsonVal.Obj) {
        val records = (root.value["records"] as JsonVal.Arr).value
        val now = (root.value["nowMs"] as JsonVal.Num).value
        assertEquals((root.value["mergedCanonical"] as JsonVal.Str).value, mergedCanonical(records, now), root.value["name"].toString())
        val merged = mergeRecords(records, now)
        val queries = root.value["queries"] as? JsonVal.Arr ?: return
        for (query in queries.value) {
            val obj = query as JsonVal.Obj
            val analysed = shouldAnalyse(
                merged,
                AnalyseQuery(
                    showId = (obj.value["showId"] as JsonVal.Str).value,
                    season = (obj.value["season"] as? JsonVal.Num)?.value,
                    episode = (obj.value["episode"] as? JsonVal.Num)?.value,
                    segmentType = (obj.value["segmentType"] as JsonVal.Str).value,
                    segmentIndex = (obj.value["segmentIndex"] as JsonVal.Num).value.toInt(),
                    fileDurationMs = (obj.value["fileDurationMs"] as JsonVal.Num).value,
                    localAlgorithm = (obj.value["localAlgorithm"] as JsonVal.Num).value.toInt(),
                ),
            )
            assertEquals((obj.value["expectAnalyse"] as JsonVal.Bool).value, analysed)
        }
    }

    private fun assertOfflineVector(root: JsonVal.Obj) {
        val last = (root.value["lastSuccessfulSyncMs"] as JsonVal.Num).value
        val now = (root.value["nowMs"] as JsonVal.Num).value
        assertEquals("full-resync", (root.value["mode"] as JsonVal.Str).value)
        assertEquals(SyncMode.FullResync, syncMode(last, now))
        assertEquals(SyncMode.Incremental, syncMode(last, last + TOMBSTONE_KEEP_MS - 1))
        val stale = (root.value["staleLocalRecords"] as JsonVal.Arr).value
        val uploaded = recordsToUpload(stale, last, now) { record ->
            val hlc = ((record as JsonVal.Obj).value["hlc"] as JsonVal.Obj).value
            (hlc["wallMs"] as JsonVal.Num).value
        }
        val expectedPush = (root.value["pushLocal"] as JsonVal.Arr).value.map { canonicalJson(it) }
        assertEquals(expectedPush, uploaded.map { canonicalJson(it) })
        val remote = (root.value["remoteRecords"] as JsonVal.Arr).value
        assertEquals((root.value["adoptedCanonical"] as JsonVal.Str).value, mergedCanonical(remote + uploaded, now))
        val dropped = stale.filter { canonicalJson(it) !in expectedPush.toSet() }
        assertEquals((root.value["resurrectedCanonical"] as JsonVal.Str).value, mergedCanonical(dropped, now))
    }

    private fun assertCrypto(text: String) {
        val root = parseJson(text) as JsonVal.Obj
        val salt = SyncCrypto.decodeB64((root.value["saltBase64"] as JsonVal.Str).value)!!
        val keys = SyncCrypto.deriveKeys((root.value["passphrase"] as JsonVal.Str).value, salt)
        assertEquals((root.value["masterKeyHex"] as JsonVal.Str).value, SyncCrypto.encodeHex(keys.master))
        assertEquals((root.value["encKeyHex"] as JsonVal.Str).value, SyncCrypto.encodeHex(keys.encKey))
        assertEquals((root.value["authTokenHex"] as JsonVal.Str).value, SyncCrypto.encodeHex(keys.authToken))
        assertEquals((root.value["authTokenBase64Url"] as JsonVal.Str).value, SyncCrypto.bearerToken(keys.authToken))
        assertEquals((root.value["keyCheckBase64"] as JsonVal.Str).value, SyncCrypto.encodeB64(keys.keyCheck))
        assertEquals((root.value["tokenHashHex"] as JsonVal.Str).value, SyncCrypto.encodeHex(SyncCrypto.sha256(keys.authToken)))
        assertEquals(
            (root.value["joinCode"] as JsonVal.Str).value,
            SyncCrypto.joinCode((root.value["spaceId"] as JsonVal.Str).value, salt, keys.keyCheck),
        )
        val nonce = SyncCrypto.decodeB64((root.value["nonceBase64"] as JsonVal.Str).value)!!
        val aad = (root.value["aad"] as JsonVal.Str).value.encodeToByteArray()
        val plain = (root.value["plaintext"] as JsonVal.Str).value.encodeToByteArray()
        val cipher = SyncCrypto.encrypt(keys.encKey, nonce, aad, plain)
        assertEquals((root.value["ciphertextBase64"] as JsonVal.Str).value, SyncCrypto.encodeB64(cipher))
        assertEquals(
            (root.value["plaintext"] as JsonVal.Str).value,
            SyncCrypto.decrypt(keys.encKey, nonce, aad, cipher)!!.decodeToString(),
        )
        val wrong = SyncCrypto.deriveKeys((root.value["wrongPassphrase"] as JsonVal.Str).value, salt)
        assertEquals((root.value["wrongKeyCheckBase64"] as JsonVal.Str).value, SyncCrypto.encodeB64(wrong.keyCheck))
        assertNull(SyncCrypto.decrypt(wrong.encKey, nonce, aad, cipher))
    }

    private fun assertPassphrase(text: String) {
        val root = parseJson(text) as JsonVal.Obj
        val salt = SyncCrypto.decodeB64((root.value["saltBase64"] as JsonVal.Str).value)!!
        val nfd = SyncCrypto.deriveKeys((root.value["nfdPassphrase"] as JsonVal.Str).value, salt)
        val nfc = SyncCrypto.deriveKeys((root.value["nfcPassphrase"] as JsonVal.Str).value, salt)
        val expected = (root.value["masterKeyHex"] as JsonVal.Str).value
        assertEquals(expected, SyncCrypto.encodeHex(nfd.master))
        assertEquals(expected, SyncCrypto.encodeHex(nfc.master))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("sync/v1/$name")) { name }.bufferedReader().readText()

    private fun device(server: FakeSyncServer, deviceId: String, now: () -> Long): TestDevice {
        val directory = MemoryMarkDirectory()
        val engine = AnalyzedDetailsSync(
            transport = SyncHttpClient(server.baseUrl),
            secrets = MemorySecretStore(),
            sessions = MemorySessionStore(),
            directory = directory,
            now = now,
            random = SecureRandom(),
            newDeviceId = { deviceId },
        )
        return TestDevice(engine, directory)
    }

    private fun mark(parent: String, season: Int, episode: Int, segment: String, start: Long, end: Long) = DirectoryMark(
        parentMetaId = parent,
        season = season,
        episode = episode,
        segmentLocal = segment,
        presence = "range",
        segmentIndex = 0,
        startMs = start,
        endMs = end,
        durationMs = 3_600_000,
        fromDevice = null,
    )

    private fun encryptDetection(keys: DerivedKeys, spaceId: String, blobId: String, deviceId: String, notFound: Boolean): String {
        val segment = if (notFound) "intro" else "recap"
        val duration = 3_600_000L
        val id = "det/tmdb:100/s1e8/$segment/0/$duration/a1"
        val fields = linkedMapOf<String, JsonVal>(
            "algorithm" to JsonVal.Str("nuvio-analysis-1"),
            "deleted" to JsonVal.Bool(false),
            "durationMs" to JsonVal.Num(duration),
            "episode" to JsonVal.Num(8),
            "hlc" to hlcJson(ClockStamp(1_700_000_000_000, 0, deviceId)),
            "id" to JsonVal.Str(id),
            "kind" to JsonVal.Str("detection"),
            "outcome" to JsonVal.Str(if (notFound) "not_found" else "found"),
            "season" to JsonVal.Num(1),
            "segmentIndex" to JsonVal.Num(0),
            "segmentType" to JsonVal.Str(segment),
            "showId" to JsonVal.Str("tmdb:100"),
        )
        if (!notFound) {
            fields["startMs"] = JsonVal.Num(1_000)
            fields["endMs"] = JsonVal.Num(2_000)
        }
        val record = parseRecord(JsonVal.Obj(fields)) ?: error("detection did not parse")
        val plain = canonicalJson(record.wire).encodeToByteArray()
        val nonce = SyncCrypto.decodeB64("EBESExQVFhcYGRob")!!
        val aad = SyncCrypto.associatedData(RoutingAad(1, spaceId, blobId, deviceId, "detection"))
        return SyncCrypto.encodeB64(SyncCrypto.encrypt(keys.encKey, nonce, aad, plain))
    }

    private fun plaintexts(calls: List<FakeSyncServer.Call>, keys: DerivedKeys): List<String> {
        return calls.filter { it.path.endsWith("/batches") }.map { call ->
            val root = parseJson(call.body) as JsonVal.Obj
            val blobs = root.value["blobs"] as JsonVal.Arr
            blobs.value.map { item ->
                val obj = item as JsonVal.Obj
                val id = (obj.value["id"] as JsonVal.Str).value
                val kind = (obj.value["kind"] as JsonVal.Str).value
                val deviceId = (root.value["deviceId"] as JsonVal.Str).value
                val nonce = SyncCrypto.decodeB64((obj.value["nonce"] as JsonVal.Str).value)!!
                val cipher = SyncCrypto.decodeB64((obj.value["ciphertext"] as JsonVal.Str).value)!!
                val spaceId = call.path.removePrefix("/v1/spaces/").substringBefore("/batches")
                val aad = SyncCrypto.associatedData(RoutingAad(1, spaceId, id, deviceId, kind))
                SyncCrypto.decrypt(keys.encKey, nonce, aad, cipher)!!.decodeToString()
            }
        }.flatten()
    }

    private class TestDevice(
        val engine: AnalyzedDetailsSync,
        val directory: MemoryMarkDirectory,
    ) {
        fun create(url: String, enroll: String, passphrase: String) = engine.create(url, enroll, passphrase)
        fun join(url: String, code: String, passphrase: String) = engine.join(url, code, passphrase)
        fun sync() = engine.sync()
        fun leave() = engine.leave()
        fun session() = engine.session()
        fun keysForTest() = engine.keysForTest()
    }
}

private fun SyncSession.joinCode(): String {
    val salt = SyncCrypto.decodeB64(saltBase64) ?: return ""
    val check = SyncCrypto.decodeB64(keyCheckBase64) ?: return ""
    return SyncCrypto.joinCode(spaceId, salt, check)
}
