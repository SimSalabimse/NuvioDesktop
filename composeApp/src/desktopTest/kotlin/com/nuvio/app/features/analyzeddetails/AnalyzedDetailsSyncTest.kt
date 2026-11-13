package com.nuvio.app.features.analyzeddetails

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.nuvio.app.core.storage.DesktopStorage
import java.nio.file.Files
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        assertTrue(provenance.contains("6e8981caed73992f9c4e316723e2912ee2cb3efe"))
        assertIdNormalization(resource("id-normalization.json"))
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
        val mobile = parseMobileAnalysisKey("tmdb_100__s1e8")
        assertEquals(episode?.showId, mobile?.showId)
        assertEquals(episode?.episodeKey, mobile?.episodeKey)
        assertEquals("none/tmdb:100/s1e8/intro", noneRecordId(episode!!, "intro"))
        assertEquals(noneRecordId(episode, "intro"), noneRecordId(mobile!!, "intro"))
        assertEquals(
            "det/tmdb:100/s1e8/intro/0/3600000/a1",
            detectionRecordId(episode, "intro", 0, 3_600_000, 1),
        )
        val mobileImdb = parseMobileAnalysisKey("tt0000100__s1e8")
        assertEquals(imdb?.showId, mobileImdb?.showId)
        assertEquals("s1e8", mobileImdb?.episodeKey)
        assertEquals("none/imdb:tt0000100/s1e8/credits", noneRecordId(imdb!!, "credits"))
        val mobileBare = parseMobileAnalysisKey("100__s1e8")
        assertEquals("tmdb:100", mobileBare?.showId)
        assertEquals("s1e8", mobileBare?.episodeKey)
        assertEquals("tmdb:100", parseDesktopMarkKey("tmdb_100:1:8")?.showId)
        assertEquals("imdb:tt0000100", showIdForParent("tt100"))
        assertEquals("imdb:tt0000100", showIdForParent("imdb:tt100"))
        assertEquals("imdb:tt0000100", showIdForParent(" tt100 "))
        assertEquals("imdb:tt0000100", showIdForParent("imdb_tt100"))
        assertEquals("imdb:tt12345678", showIdForParent("tt12345678"))
        assertEquals("tmdb:100", showIdForParent(" 100 "))
        assertNull(showIdForParent("tmdb:0100"))
        val paddedEpisode = parseDesktopMarkKey("tt100:01:8")
        assertEquals("imdb:tt0000100", paddedEpisode?.showId)
        assertEquals("s1e8", paddedEpisode?.episodeKey)
        assertEquals("tt100:1:8", paddedEpisode?.desktopKey)
        val zeroPadded = parseDesktopMarkKey("tmdb:100:01:08")
        assertEquals("s1e8", zeroPadded?.episodeKey)
        assertEquals("tmdb:100:1:8", zeroPadded?.desktopKey)
        assertEquals(noneRecordId(episode, "intro"), noneRecordId(zeroPadded!!, "intro"))
        assertEquals("s0e1", parseDesktopMarkKey("tmdb:100:00:01")?.episodeKey)
        assertEquals("s1e8", parseMobileAnalysisKey("tmdb_100__s01e8")?.episodeKey)
        assertEquals("s1e8", parseMobileAnalysisKey("tt100__s01e08")?.episodeKey)
        assertEquals("imdb:tt0000100", parseMobileAnalysisKey("tt100__s01e08")?.showId)
        assertEquals("s0e1", parseMobileAnalysisKey("tmdb_100__s00e01")?.episodeKey)
        assertEquals("none/imdb:tt0000100/s1e8/intro", noneRecordId(paddedEpisode!!, "intro"))
        assertNull(parseDesktopMarkKey("tmdb:100:01:0"))
        assertNull(parseMobileAnalysisKey("tmdb_100__s01e0"))
        assertNull(normalizeSyncBaseUrl("http://example.com"))
        assertEquals("https://example.com", normalizeSyncBaseUrl("https://example.com"))
        assertEquals("http://127.0.0.1:9", normalizeSyncBaseUrl("http://127.0.0.1:9"))
    }

    @Test
    fun secretStoresDoNotPutThePassphraseOnTheCommandLine() {
        val secret = "test-passphrase-not-a-secret"
        assertEquals(listOf("/usr/bin/security", "-w", "***"), redactSecretArguments(listOf("/usr/bin/security", "-w", secret)))
        val argv = mutableListOf<List<String>>()
        var stdin: String? = null
        val mac = MacKeychainSecretStore { args, input ->
            argv += args
            stdin = input
            ProcessResult(0, "")
        }
        mac.put("profile/1", secret)
        assertEquals(listOf(listOf("/usr/bin/security", "-i")), argv)
        assertFalse(argv.single().any { secret in it })
        assertTrue(stdin!!.contains(secret))
        assertEquals("'it'\\''s'", securityQuote("it's"))
        assertFailsWith<IllegalStateException> { mac.put("profile/1", "line\nbreak") }
        val windows = WindowsCredentialSecretStore()
        val line = windows.commandLine()
        assertEquals("Hidden", line[line.indexOf("-WindowStyle") + 1])
        assertEquals("Bypass", line[line.indexOf("-ExecutionPolicy") + 1])
        assertFalse(windows.commandHasSecret(secret))
        val script = windows.previewScript("write", "profile/1", secret)
        assertFalse(script.contains(secret))
        assertFailsWith<IllegalArgumentException> { windows.previewScript("write", "foo';calc", secret) }
        val directory = Files.createTempDirectory("nuvio-sync-secrets")
        val store = EncryptedFileSecretStore(directory)
        store.put("profile/1", secret)
        assertEquals(secret, store.get("profile/1"))
        val saved = Files.readString(directory.resolve("values.json"))
        assertFalse(saved.contains(secret))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(directory.resolve("key")),
        )
        val owner = Files.getOwner(directory.resolve("key"))
        val entry = ownerOnlyAclEntry(owner)
        assertEquals(AclEntryType.ALLOW, entry.type())
        assertEquals(owner, entry.principal())
        assertTrue(entry.flags().isEmpty())
        assertTrue(entry.permissions().contains(AclEntryPermission.READ_DATA))
        val primary = object : SyncSecretStore {
            override val kind: String = "macos-keychain"
            override fun get(account: String): String? = null
            override fun put(account: String, secret: String) = error("Keychain update failed.")
            override fun delete(account: String) = Unit
        }
        val fallback = FallbackSecretStore(primary, store)
        fallback.put("profile/1", secret)
        assertEquals("encrypted-file", fallback.kind)
        assertEquals(secret, fallback.get("profile/1"))
        store.delete("profile/1")
        assertNull(store.get("profile/1"))
        val clipboard = ReadOnlyClipboard(object : ClipboardManager {
            override fun getText(): AnnotatedString = AnnotatedString("kept")
            override fun setText(annotatedString: AnnotatedString) = error("copied")
            override fun hasText(): Boolean = true
        })
        clipboard.setText(AnnotatedString(secret))
        assertEquals("kept", clipboard.getText()?.text)
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
            val pauses = mutableListOf<Long>()
            val left = device(server, "device-a", pause = { pauses += it }) { now[0] }
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
            assertEquals(listOf(3_000L, 3_000L, 3_000L), pauses.toList())
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
            assertTrue(plaintexts(server.calls, keys).none { it.contains("\"kind\":\"detection\"") })
        }
    }

    @Test
    fun paddedIdsAliasesAndImdbSubmissionsSync() {
        val scope = checkNotNull(accountScopeFor("  idb_test_key  "))
        assertEquals(SyncCrypto.sha256Hex("idb_test_key"), scope)
        assertNull(accountScopeFor(""))
        assertNull(accountScopeFor(" \n\t"))
        FakeSyncServer("test-enroll-secret").use { server ->
            val device = device(server, "device-a") { 1_700_000_000_000 }
            val noted = device.engine.noteSubmissions(
                scope,
                listOf(
                    submission("remote-imdb", tmdbId = null, imdbId = "tt100"),
                    submission("remote-tmdb", tmdbId = 100, imdbId = "tt100"),
                    submission("remote-drop", tmdbId = null, imdbId = "not-a-show"),
                ),
            )
            assertEquals(2, noted.submissions.size)
            assertTrue(noted.submissions.any { it.contains("\"showId\":\"imdb:tt0000100\"") && it.contains("remote-imdb") })
            assertTrue(noted.submissions.any { it.contains("\"showId\":\"tmdb:100\"") && it.contains("remote-tmdb") })
            assertTrue(noted.submissions.none { it.contains("remote-drop") })
            assertEquals(listOf("alias/imdb:tt0000100"), noted.aliases.map { (parseRecord(parseJson(it)) as AliasRecord).id })
            val linked = device.engine.noteShowLink("100", " tt100 ")
            val alias = parseRecord(parseJson(linked.aliases.single())) as AliasRecord
            assertEquals("alias/imdb:tt0000100", alias.id)
            assertEquals("imdb:tt0000100", alias.from)
            assertEquals("tmdb:100", alias.to)
            val merged = mergeRecords(
                listOf(alias.wire, foundDetection("imdb:tt0000100", "device-b")),
                1_700_000_000_000,
            )
            val overlay = overlaySyncedSkips(
                intervals = emptyList(),
                merged = merged,
                parentMetaId = "100",
                season = 1,
                episode = 8,
                fileDurationMs = 3_600_000,
                isMovie = false,
            )
            assertEquals(listOf("recap"), overlay.map { it.type })
            val created = device.create(server.baseUrl, "test-enroll-secret", "test-passphrase-not-a-secret")
            assertTrue(created.joined, created.statusText)
            assertEquals(2, created.submissions.size)
            assertEquals(1, created.aliases.size)
            assertEquals("Synced.", device.sync().statusText)
            val plain = plaintexts(server.calls, device.keysForTest()!!)
            assertTrue(plain.any { it.contains("\"id\":\"alias/imdb:tt0000100\"") && it.contains("\"to\":\"tmdb:100\"") })
            assertTrue(plain.any { it.contains("\"showId\":\"imdb:tt0000100\"") && it.contains("remote-imdb") })
            assertTrue(plain.none { it.contains("\"kind\":\"detection\"") })
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

    @Test
    fun firstSyncKeepsUnrelatedMarksAndDoesNotInventNone() {
        FakeSyncServer("test-enroll-secret").use { server ->
            val now = longArrayOf(1_700_000_000_000L)
            val device = device(server, "device-a") { now[0] }
            assertTrue(device.create(server.baseUrl, "test-enroll-secret", "test-passphrase-not-a-secret").joined)
            assertEquals("Synced.", device.sync().statusText)
            assertTrue(device.directory.list().isEmpty())
            device.directory.upsert(mark("tmdb:100", 1, 8, "intro", 10_000, 20_000))
            device.directory.upsert(mark("tmdb:200", 2, 3, "recap", 4_000, 5_000))
            device.directory.upsert(mark("not-a-show", 1, 1, "intro", 7, 8))
            val keys = device.keysForTest()!!
            assertEquals("Synced.", device.sync().statusText)
            assertEquals(setOf(10_000L, 4_000L, 7L), device.directory.list().map { it.startMs }.toSet())
            assertTrue(device.directory.list().none { it.presence == "none" })
            val localId = device.session().slots.single { it.startMs == 10_000L }.recordId
            server.putBlob(
                id = "22222222-2222-4222-8222-222222222221",
                kind = "mark",
                deviceId = "device-b",
                nonce = SyncCrypto.encodeB64(SyncCrypto.decodeB64("EBESExQVFhcYGRob")!!),
                ciphertext = encryptRecord(
                    keys,
                    device.session().spaceId,
                    "22222222-2222-4222-8222-222222222221",
                    "device-b",
                    rangeMarkRecord(
                        id = "mark/00000000-0000-4000-8000-000000000001",
                        hlc = ClockStamp(1_600_000_000_000, 0, "device-b"),
                        showId = "tmdb:100",
                        season = 1,
                        episode = 8,
                        segmentType = "intro",
                        segmentIndex = 0,
                        startMs = 30_000,
                        endMs = 40_000,
                        durationMs = 3_600_000,
                    )!!,
                ),
            )
            assertEquals("Synced.", device.sync().statusText)
            assertEquals(10_000L, device.directory.list().single { it.segmentLocal == "intro" && it.parentMetaId == "tmdb:100" }.startMs)
            assertFalse(device.session().slots.single { it.recordId == localId }.deleted)
            val before = server.calls.size
            assertEquals("Synced.", device.sync().statusText)
            assertTrue(plaintexts(server.calls.drop(before), keys).none { it.contains("\"deleted\":true") })
            server.putBlob(
                id = "22222222-2222-4222-8222-222222222222",
                kind = "mark",
                deviceId = "device-b",
                nonce = SyncCrypto.encodeB64(SyncCrypto.decodeB64("EBESExQVFhcYGRob")!!),
                ciphertext = encryptRecord(
                    keys,
                    device.session().spaceId,
                    "22222222-2222-4222-8222-222222222222",
                    "device-b",
                    noneMarkRecord(
                        hlc = ClockStamp(now[0] + 1_000, 0, "device-b"),
                        showId = "tmdb:100",
                        season = 1,
                        episode = 8,
                        segmentType = "intro",
                        durationMs = 3_600_000,
                    )!!,
                ),
            )
            assertEquals("Synced.", device.sync().statusText)
            assertEquals("none", device.directory.list().single { it.parentMetaId == "tmdb:100" && it.segmentLocal == "intro" }.presence)
            assertFalse(device.session().slots.single { it.recordId == localId }.deleted)
            assertEquals(4_000L, device.directory.list().single { it.parentMetaId == "tmdb:200" }.startMs)
            assertEquals(7L, device.directory.list().single { it.parentMetaId == "not-a-show" }.startMs)
            val afterNone = server.calls.size
            assertEquals("Synced.", device.sync().statusText)
            assertTrue(plaintexts(server.calls.drop(afterNone), keys).none { it.contains("\"deleted\":true") })
        }
    }

    @Test
    fun aFailedPushKeepsThePullAndAConflictRetriesTheSameBody() {
        FakeSyncServer("test-enroll-secret").use { server ->
            val device = device(server, "device-a") { 1_700_000_000_000 }
            val created = device.create(server.baseUrl, "test-enroll-secret", "test-passphrase-not-a-secret")
            assertTrue(created.joined, created.statusText)
            device.directory.upsert(mark("tmdb:100", 1, 8, "intro", 10_000, 20_000))
            server.failPushStatus = 409
            server.failPushRemaining = 1
            assertEquals("Synced.", device.sync().statusText)
            val pushes = server.calls.filter { it.method == "POST" && it.path.endsWith("/batches") }
            assertEquals(2, pushes.size)
            assertEquals(pushes[0].body, pushes[1].body)
            server.failPushStatus = null
            server.putBlob(
                id = "44444444-4444-4444-8444-444444444441",
                kind = "mark",
                deviceId = "device-b",
                nonce = SyncCrypto.encodeB64(SyncCrypto.decodeB64("EBESExQVFhcYGRob")!!),
                ciphertext = encryptRecord(
                    device.keysForTest()!!,
                    created.spaceId,
                    "44444444-4444-4444-8444-444444444441",
                    "device-b",
                    rangeMarkRecord(
                        id = "mark/11111111-1111-4111-8111-111111111111",
                        hlc = ClockStamp(1_700_000_000_000, 0, "device-b"),
                        showId = "tmdb:100",
                        season = 1,
                        episode = 9,
                        segmentType = "recap",
                        segmentIndex = 0,
                        startMs = 4_000,
                        endMs = 5_000,
                        durationMs = 3_600_000,
                    )!!,
                ),
            )
            device.directory.upsert(mark("tmdb:300", 1, 1, "recap", 8_000, 9_000))
            server.failPushStatus = 500
            server.failPushRemaining = 1
            val failed = device.sync()
            assertEquals("The server returned 500.", failed.statusText)
            assertEquals(server.highSeq(), failed.pullCursor)
            assertTrue(failed.knownCanonical.isNotEmpty())
            assertEquals(setOf(10_000L, 8_000L), device.directory.list().map { it.startMs }.toSet())
            server.failPushStatus = null
            assertEquals("Synced.", device.sync().statusText)
            assertEquals(setOf(10_000L, 8_000L, 4_000L), device.directory.list().map { it.startMs }.toSet())
            val batches = server.calls.count { it.method == "POST" && it.path.endsWith("/batches") }
            server.failPushStatus = 409
            server.failPushBody = """{"error":"conflict","message":"Nonce already used"}"""
            server.failPushRemaining = 5
            device.directory.upsert(mark("tmdb:300", 1, 1, "recap", 8_500, 9_000))
            val rejected = device.sync()
            assertEquals("The server rejected the sync.", rejected.statusText)
            assertEquals(batches + 1, server.calls.count { it.method == "POST" && it.path.endsWith("/batches") })
        }
    }

    @Test
    fun leavingDuringAPullDoesNotRestoreTheJoinedSession() {
        FakeSyncServer("test-enroll-secret").use { server ->
            val device = device(server, "device-a") { 1_700_000_000_000 }
            assertTrue(device.create(server.baseUrl, "test-enroll-secret", "test-passphrase-not-a-secret").joined)
            device.directory.upsert(mark("tmdb:100", 1, 8, "intro", 10_000, 20_000))
            val entered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            server.pullEntered = entered
            server.pullGate = gate
            val worker = Thread { device.sync() }
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            device.engine.abandon()
            val left = device.leave()
            gate.countDown()
            worker.join(10_000)
            assertFalse(worker.isAlive)
            assertFalse(left.joined)
            assertFalse(device.session().joined)
            assertEquals(listOf(10_000L), device.directory.list().map { it.startMs })
        }
    }

    @Test
    fun aCorruptStoreIsNotTreatedAsEmpty() {
        val root = Files.createTempDirectory("nuvio-sync-corrupt")
        val sessionFile = root.resolve("session.json")
        Files.writeString(sessionFile, "{")
        val sessions = FileSyncSessionStore { sessionFile }
        assertFalse(sessions.load(1).readable)
        sessions.save(1, SyncSession(joined = true, statusText = "wiped"))
        assertEquals("{", Files.readString(sessionFile))
        assertFalse(sessions.load(1).readable)
        val marks = root.resolve("marks.properties")
        Files.writeString(marks, "nuvio_skip_marks=\\uZZZZ\n")
        val store = DesktopStorage.Store(marks)
        store.putString("playback", "gone")
        assertEquals("nuvio_skip_marks=\\uZZZZ\n", Files.readString(marks))
        assertNull(store.getString("playback"))
    }

    private fun assertIdNormalization(text: String) {
        val root = parseJson(text) as JsonVal.Obj
        for (item in (root.value["imdb"] as JsonVal.Arr).value) {
            val obj = item as JsonVal.Obj
            val input = (obj.value["input"] as JsonVal.Str).value
            val show = obj.value["showId"]
            val expected = if (show is JsonVal.Str) show.value else null
            assertEquals(expected, showIdForParent(input), input)
        }
        for (name in listOf("detection", "none", "alias")) {
            val record = root.value[name] as JsonVal.Obj
            val parsed = parseRecord(record) ?: error("$name did not parse")
            assertEquals((record.value["id"] as JsonVal.Str).value, parsed.id)
            assertEquals((record.value["kind"] as JsonVal.Str).value, parsed.kind)
            assertEquals(canonicalJson(record), canonicalJson(parsed.wire))
        }
        val scope = root.value["accountScope"] as JsonVal.Obj
        val key = (scope.value["key"] as JsonVal.Str).value
        val trimmed = (scope.value["trimmed"] as JsonVal.Str).value
        val expectedScope = (scope.value["accountScope"] as JsonVal.Str).value
        assertEquals("cb93951ba45ba9797f45d13f9baf946300314407f7d59b98e8971118b565bbae", expectedScope)
        assertEquals(trimmed, trimAccountKey(key))
        assertEquals(expectedScope, accountScopeFor(key))
        assertNull(accountScopeFor(""))
        assertNull(accountScopeFor("   "))
        assertNull(accountScopeFor("\uFEFF\n"))
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

    private fun device(
        server: FakeSyncServer,
        deviceId: String,
        pause: (Long) -> Unit = {},
        now: () -> Long,
    ): TestDevice {
        val directory = MemoryMarkDirectory()
        val engine = AnalyzedDetailsSync(
            transport = SyncHttpClient(server.baseUrl),
            secrets = MemorySecretStore(),
            sessions = MemorySessionStore(),
            directory = directory,
            now = now,
            random = SecureRandom(),
            newDeviceId = { deviceId },
            pause = pause,
        )
        return TestDevice(engine, directory)
    }

    private fun submission(remoteId: String, tmdbId: Int?, imdbId: String?) = SubmissionNote(
        remoteId = remoteId,
        tmdbId = tmdbId,
        mediaType = "tv",
        season = 1,
        episode = 8,
        segment = "intro",
        status = "approved",
        startMs = 1_000,
        endMs = 2_000,
        durationMs = 3_600_000,
        imdbId = imdbId,
    )

    private fun foundDetection(showId: String, deviceId: String): JsonVal {
        val duration = 3_600_000L
        val fields = linkedMapOf<String, JsonVal>(
            "algorithm" to JsonVal.Str("nuvio-analysis-1"),
            "deleted" to JsonVal.Bool(false),
            "durationMs" to JsonVal.Num(duration),
            "endMs" to JsonVal.Num(2_000),
            "episode" to JsonVal.Num(8),
            "hlc" to hlcJson(ClockStamp(1_700_000_000_000, 0, deviceId)),
            "id" to JsonVal.Str("det/$showId/s1e8/recap/0/$duration/a1"),
            "kind" to JsonVal.Str("detection"),
            "outcome" to JsonVal.Str("found"),
            "season" to JsonVal.Num(1),
            "segmentIndex" to JsonVal.Num(0),
            "segmentType" to JsonVal.Str("recap"),
            "showId" to JsonVal.Str(showId),
            "startMs" to JsonVal.Num(1_000),
        )
        return (parseRecord(JsonVal.Obj(fields)) ?: error("detection did not parse")).wire
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

    private fun encryptRecord(keys: DerivedKeys, spaceId: String, blobId: String, deviceId: String, record: SyncRecord): String {
        val plain = canonicalJson(record.wire).encodeToByteArray()
        val nonce = SyncCrypto.decodeB64("EBESExQVFhcYGRob")!!
        val aad = SyncCrypto.associatedData(RoutingAad(1, spaceId, blobId, deviceId, record.kind))
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
