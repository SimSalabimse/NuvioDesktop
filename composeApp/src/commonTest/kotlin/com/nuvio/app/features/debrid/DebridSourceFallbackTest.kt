package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class DebridSourceFallbackTest {
    @AfterTest
    fun clearOffer() {
        DebridAddOffer.dismiss()
    }

    @Test
    fun stateTable() = runTest {
        val problems = mutableListOf<String>()
        for (row in playbackRows()) {
            val actual = play(row)
            val expectedUrl = when (row.played) {
                null -> null
                "Http" -> "https://cdn.example/movie.mp4"
                else -> "https://cdn.example/${row.played}"
            }
            val mismatches = mutableListOf<String>()
            if (actual.played != row.played) mismatches += "played ${actual.played}"
            if (actual.playedUrl != expectedUrl) mismatches += "url ${actual.playedUrl}"
            if (actual.attempted != row.attempted) mismatches += "attempted ${actual.attempted}"
            if (actual.skipped != row.skipped) mismatches += "skipped ${actual.skipped}"
            if (actual.seen != row.seen) mismatches += "seen ${actual.seen}"
            if (actual.reload != row.reload) mismatches += "reload ${actual.reload}"
            if (mismatches.isNotEmpty()) {
                problems += "${row.cache}/${row.pick}/next=${row.next}: ${mismatches.joinToString("; ")} expected played=${row.played} skipped=${row.skipped} seen=${row.seen}"
            }
        }
        assertEquals(emptyList(), problems)
        assertEquals(12, playbackRows().count { !it.direct })
    }

    @Test
    fun directPlaybackDoesNotResolveOrSkip() = runTest {
        val rows = playbackRows().filter { it.direct }
        assertEquals(2, rows.size)
        rows.forEach { row ->
            val actual = play(row)
            assertEquals("Http", actual.played)
            assertEquals(emptyList(), actual.attempted)
            assertEquals(emptyList(), actual.skipped)
            assertEquals("playing Http", actual.seen)
        }
    }

    @Test
    fun fallbackStopsAfterThreeTriesAndSkipsKnownUncached() = runTest {
        val selected = source("Selected", StreamDebridCacheState.NOT_CACHED)
        val blocked = source("Blocked", StreamDebridCacheState.NOT_CACHED)
        val first = source("First", null)
        val second = source("Second", null)
        val third = source("Third", null)
        val fourth = source("Fourth", null)
        val attempted = mutableListOf<String>()
        val outcome = DebridSourceFallback.playable(
            stream = selected,
            season = 1,
            episode = 1,
            notify = true,
            resolve = script(attempted, fail = setOf("Selected", "First", "Second", "Third", "Fourth")),
            shouldResolve = ::needsResolve,
            onSwitched = {},
            reportFailure = { _, _ -> },
        ) {
            listOf(selected, blocked, first, second, third, fourth)
        }
        assertNull(outcome.stream)
        assertEquals(listOf("Selected", "First", "Second", "Third"), attempted)
        assertEquals(DebridPlayOutcome(null, DirectDebridPlayableResult.NotCached).failure, outcome.failure)
    }

    @Test
    fun thirdResolvableSourcePlaysWhenKnownUncachedIsSkipped() = runTest {
        val selected = source("Selected", StreamDebridCacheState.NOT_CACHED)
        val blocked = source("Blocked", StreamDebridCacheState.NOT_CACHED)
        val first = source("First", null)
        val second = source("Second", null)
        val third = source("Third", null)
        val attempted = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val outcome = DebridSourceFallback.playable(
            stream = selected,
            season = 1,
            episode = 1,
            notify = true,
            resolve = script(attempted, fail = setOf("Selected", "First", "Second"), succeed = setOf("Third")),
            shouldResolve = ::needsResolve,
            onSwitched = { notices += it },
            reportFailure = { _, _ -> },
        ) {
            listOf(selected, blocked, first, second, third)
        }
        assertEquals("Third", outcome.stream?.streamLabel)
        assertEquals("https://cdn.example/Third", outcome.stream?.playableDirectUrl)
        assertEquals(listOf("Selected", "First", "Second", "Third"), attempted)
        assertEquals(listOf("Addon · Third"), notices)
        assertFalse(attempted.contains("Blocked"))
    }

    @Test
    fun staleFallsThroughAndReloadsOnlyWhenNothingRemains() = runTest {
        val manualNext = play(
            PlaybackRow(
                cache = Cache.Stale,
                pick = Pick.Manual,
                next = true,
                played = "Next",
                attempted = listOf("Selected", "Next"),
                skipped = listOf("Selected"),
                seen = "playing Next; Not cached, switched to Addon · Next",
                reload = false,
            ),
        )
        assertEquals("Next", manualNext.played)
        assertEquals("playing Next; Not cached, switched to Addon · Next", manualNext.seen)
        assertFalse(manualNext.reload)

        val manualNone = play(
            PlaybackRow(
                cache = Cache.Stale,
                pick = Pick.Manual,
                next = false,
                played = null,
                attempted = listOf("Selected"),
                skipped = listOf("Selected"),
                seen = "This link expired. Refreshing results.",
                reload = true,
            ),
        )
        assertNull(manualNone.played)
        assertEquals("This link expired. Refreshing results.", manualNone.seen)
        assertTrue(manualNone.reload)
        assertTrue(DebridAddOffer.pending.value == null)

        val autoNone = play(
            PlaybackRow(
                cache = Cache.Stale,
                pick = Pick.AutoPlay,
                next = false,
                played = null,
                attempted = listOf("Selected"),
                skipped = listOf("Selected"),
                seen = "This link expired. Refreshing results.",
                reload = true,
            ),
        )
        assertEquals("This link expired. Refreshing results.", autoNone.seen)
        assertTrue(autoNone.reload)
    }

    @Test
    fun uncachedWithoutAddSupportShowsTheErrorInsteadOfAnOffer() = runTest {
        val actual = play(
            PlaybackRow(
                cache = Cache.Uncached,
                pick = Pick.Manual,
                next = false,
                played = null,
                attempted = listOf("Selected"),
                skipped = listOf("Selected"),
                seen = "Not cached on Torbox.",
                reload = false,
            ),
            canAdd = false,
        )
        assertEquals("Not cached on Torbox.", actual.seen)
        assertNull(DebridAddOffer.pending.value)
    }

    @Test
    fun addOfferConfirmUsesFakeClient() = runTest {
        val calls = mutableListOf<CreateCall>()
        val api = TorboxDebridProviderApi { apiKey, magnet, onlyIfCached ->
            calls += CreateCall(apiKey, magnet, onlyIfCached)
            acceptedCreate()
        }
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        val added = api.addToCache("placeholder-torbox-key", magnet)
        assertTrue(added)
        assertTrue(api.supportsAddToCache)
        assertEquals(false, calls.single().onlyIfCached)
        assertEquals("placeholder-torbox-key", calls.single().apiKey)
        assertEquals(magnet, calls.single().magnet)

        val stream = source("Selected", StreamDebridCacheState.NOT_CACHED)
        assertTrue(DebridAddOffer.offer(stream, "Torbox"))
        assertEquals(stream, DebridAddOffer.pending.value)
        assertEquals(
            "Added to Torbox. It will be ready to play later.",
            finishDebridAdd("Torbox") { added },
        )
        assertNull(DebridAddOffer.pending.value)
        assertEquals("Not cached on Torbox", debridAddOfferTitle("Torbox"))
        assertEquals("Add to Torbox", debridAddOfferConfirm("Torbox"))
        assertEquals(DEBRID_ADD_OFFER_DISMISS, "Not now")
    }

    @Test
    fun addOfferFailureUsesFakeClient() = runTest {
        val refused = TorboxDebridProviderApi { _, _, onlyIfCached ->
            assertFalse(onlyIfCached)
            DebridApiResponse(
                status = 400,
                body = TorboxEnvelopeDto(success = false, data = TorboxCreateTorrentDataDto()),
                rawBody = "",
            )
        }
        assertFalse(refused.addToCache("placeholder-torbox-key", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        val rejected = TorboxDebridProviderApi { _, _, _ ->
            DebridApiResponse(
                status = 200,
                body = TorboxEnvelopeDto(success = false, data = TorboxCreateTorrentDataDto()),
                rawBody = "",
            )
        }
        assertFalse(rejected.addToCache("placeholder-torbox-key", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        val broken = TorboxDebridProviderApi { _, _, _ -> error("down") }
        assertFalse(broken.addToCache("placeholder-torbox-key", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        val cancelled = TorboxDebridProviderApi { _, _, _ -> throw CancellationException("stopped") }
        var cancellationLeftTheClient = false
        try {
            cancelled.addToCache("placeholder-torbox-key", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
        } catch (error: CancellationException) {
            cancellationLeftTheClient = true
        }
        assertTrue(cancellationLeftTheClient)

        val stream = source("Selected", StreamDebridCacheState.NOT_CACHED)
        assertTrue(DebridAddOffer.offer(stream, "Torbox"))
        assertEquals(
            "Torbox didn't accept it. Try again later.",
            finishDebridAdd("Torbox") { false },
        )
        assertNull(DebridAddOffer.pending.value)
        assertFalse(DebridAddOffer.offer(stream, null))
        assertNull(DebridAddOffer.pending.value)
    }

    private suspend fun play(row: PlaybackRow, canAdd: Boolean = true): PlaybackView {
        DebridAddOffer.dismiss()
        val selected = if (row.direct) {
            httpSource()
        } else {
            source("Selected", row.cache.status)
        }
        val blocked = source("Blocked", StreamDebridCacheState.NOT_CACHED)
        val next = source("Next", StreamDebridCacheState.CACHED)
        val candidates = buildList {
            add(selected)
            if (row.cache == Cache.Uncached) add(blocked)
            if (row.next) add(next)
        }
        val attempted = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val offers = mutableListOf<String>()
        val report: (StreamItem, DirectDebridPlayableResult) -> Unit = { item, failure ->
            DebridSourceFallback.notifyFailure(
                stream = item,
                failure = failure,
                addProviderName = { if (canAdd) "Torbox" else null },
                offer = { candidate ->
                    val shown = DebridAddOffer.offer(candidate, if (canAdd) "Torbox" else null)
                    if (shown) offers += "Torbox"
                    shown
                },
                showMessage = { notices += it },
                messageFor = ::visibleFailure,
            )
        }
        val outcome = DebridSourceFallback.playable(
            stream = selected,
            season = 1,
            episode = 2,
            notify = row.pick == Pick.Manual,
            resolve = { item, _, _ ->
                attempted += item.streamLabel
                resolveFixture(item, row.cache)
            },
            shouldResolve = ::needsResolve,
            onSwitched = { label -> notices += "Not cached, switched to $label" },
            reportFailure = report,
        ) { candidates }
        val reload = if (outcome.stream == null && row.pick == Pick.AutoPlay) {
            DebridSourceFallback.handleUnresolved(
                stream = selected,
                failure = outcome.failure ?: DirectDebridPlayableResult.Error,
                hasLaterCandidate = row.next,
                notify = report,
            )
        } else {
            outcome.stream == null && outcome.failure == DirectDebridPlayableResult.Stale
        }
        val played = outcome.stream?.streamLabel
        val skipped = buildList {
            if (played != selected.streamLabel) add(selected.streamLabel)
            candidates.filter { it.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED && it.streamLabel != selected.streamLabel }
                .forEach { add(it.streamLabel) }
            if ((row.cache == Cache.Unknown || row.cache == Cache.Stale) && row.next && played == null) add("Next")
        }
        val seen = when {
            outcome.stream != null && notices.isEmpty() -> "playing ${outcome.stream.streamLabel}"
            outcome.stream != null -> "playing ${outcome.stream.streamLabel}; ${notices.joinToString()}"
            offers.isNotEmpty() -> "offer Add to ${offers.single()}"
            row.pick == Pick.AutoPlay && row.next && notices.isEmpty() -> "handoff"
            notices.isNotEmpty() -> notices.single()
            else -> "nothing"
        }
        return PlaybackView(
            played = played,
            playedUrl = outcome.stream?.playableDirectUrl,
            attempted = attempted,
            skipped = skipped,
            seen = seen,
            reload = reload,
        )
    }

    private fun resolveFixture(item: StreamItem, selectedCache: Cache): DirectDebridPlayableResult {
        if (item.playableDirectUrl != null && item.clientResolve == null) {
            return DirectDebridPlayableResult.Success(item)
        }
        val state = item.debridCacheStatus?.state
        return when {
            item.streamLabel == "Selected" && selectedCache == Cache.Unknown -> DirectDebridPlayableResult.Error
            item.streamLabel == "Selected" && selectedCache == Cache.Stale -> DirectDebridPlayableResult.Stale
            item.streamLabel == "Selected" && selectedCache == Cache.Uncached -> DirectDebridPlayableResult.NotCached
            state == StreamDebridCacheState.CACHED -> DirectDebridPlayableResult.Success(
                item.copy(url = "https://cdn.example/${item.streamLabel}"),
            )
            state == StreamDebridCacheState.NOT_CACHED -> DirectDebridPlayableResult.NotCached
            else -> DirectDebridPlayableResult.Error
        }
    }

    private fun script(
        attempted: MutableList<String>,
        fail: Set<String>,
        succeed: Set<String> = emptySet(),
    ): suspend (StreamItem, Int?, Int?) -> DirectDebridPlayableResult = { item, _, _ ->
        attempted += item.streamLabel
        when (item.streamLabel) {
            in succeed -> DirectDebridPlayableResult.Success(item.copy(url = "https://cdn.example/${item.streamLabel}"))
            in fail -> DirectDebridPlayableResult.NotCached
            else -> DirectDebridPlayableResult.Error
        }
    }

    private fun playbackRows(): List<PlaybackRow> {
        val cached = listOf(true, false).flatMap { next ->
            listOf(Pick.AutoPlay, Pick.Manual).map { pick ->
                PlaybackRow(
                    cache = Cache.Cached,
                    pick = pick,
                    next = next,
                    played = "Selected",
                    attempted = listOf("Selected"),
                    skipped = emptyList(),
                    seen = "playing Selected",
                    reload = false,
                )
            }
        }
        val uncached = listOf(
            PlaybackRow(Cache.Uncached, Pick.AutoPlay, next = true, played = "Next", attempted = listOf("Selected", "Next"), skipped = listOf("Selected", "Blocked"), seen = "playing Next", reload = false),
            PlaybackRow(Cache.Uncached, Pick.AutoPlay, next = false, played = null, attempted = listOf("Selected"), skipped = listOf("Selected", "Blocked"), seen = "offer Add to Torbox", reload = false),
            PlaybackRow(Cache.Uncached, Pick.Manual, next = true, played = "Next", attempted = listOf("Selected", "Next"), skipped = listOf("Selected", "Blocked"), seen = "playing Next; Not cached, switched to Addon · Next", reload = false),
            PlaybackRow(Cache.Uncached, Pick.Manual, next = false, played = null, attempted = listOf("Selected"), skipped = listOf("Selected", "Blocked"), seen = "offer Add to Torbox", reload = false),
        )
        val unknown = listOf(
            PlaybackRow(Cache.Unknown, Pick.AutoPlay, next = true, played = null, attempted = listOf("Selected"), skipped = listOf("Selected", "Next"), seen = "handoff", reload = false),
            PlaybackRow(Cache.Unknown, Pick.AutoPlay, next = false, played = null, attempted = listOf("Selected"), skipped = listOf("Selected"), seen = "Could not open this link.", reload = false),
            PlaybackRow(Cache.Unknown, Pick.Manual, next = true, played = null, attempted = listOf("Selected"), skipped = listOf("Selected", "Next"), seen = "Could not open this link.", reload = false),
            PlaybackRow(Cache.Unknown, Pick.Manual, next = false, played = null, attempted = listOf("Selected"), skipped = listOf("Selected"), seen = "Could not open this link.", reload = false),
        )
        val direct = listOf(Pick.AutoPlay, Pick.Manual).map { pick ->
            PlaybackRow(
                cache = Cache.Cached,
                pick = pick,
                next = true,
                played = "Http",
                attempted = emptyList(),
                skipped = emptyList(),
                seen = "playing Http",
                reload = false,
                direct = true,
            )
        }
        return cached + uncached + unknown + direct
    }
}

private enum class Cache(val status: StreamDebridCacheState?) {
    Cached(StreamDebridCacheState.CACHED),
    Uncached(StreamDebridCacheState.NOT_CACHED),
    Unknown(null),
    Stale(null),
}

private enum class Pick { AutoPlay, Manual }

private data class PlaybackRow(
    val cache: Cache,
    val pick: Pick,
    val next: Boolean,
    val played: String?,
    val attempted: List<String>,
    val skipped: List<String>,
    val seen: String,
    val reload: Boolean,
    val direct: Boolean = false,
)

private data class PlaybackView(
    val played: String?,
    val playedUrl: String?,
    val attempted: List<String>,
    val skipped: List<String>,
    val seen: String,
    val reload: Boolean,
)

private data class CreateCall(
    val apiKey: String,
    val magnet: String,
    val onlyIfCached: Boolean,
)

private fun needsResolve(stream: StreamItem): Boolean =
    stream.clientResolve != null && stream.playableDirectUrl == null

private fun visibleFailure(failure: DirectDebridPlayableResult): String? =
    when (failure) {
        DirectDebridPlayableResult.NotCached -> "Not cached on Torbox."
        DirectDebridPlayableResult.Error -> "Could not open this link."
        DirectDebridPlayableResult.Stale -> "This link expired. Refreshing results."
        DirectDebridPlayableResult.MissingApiKey -> "Connect an account in Settings."
        is DirectDebridPlayableResult.Success -> null
    }

private fun source(name: String, state: StreamDebridCacheState?): StreamItem =
    StreamItem(
        name = name,
        addonName = "Addon",
        addonId = "addon:test",
        infoHash = name.padEnd(40, 'a').take(40),
        clientResolve = StreamClientResolve(
            type = "debrid",
            service = "torbox",
            infoHash = name.padEnd(40, 'b').take(40),
            filename = "$name.mkv",
        ),
        debridCacheStatus = state?.let {
            StreamDebridCacheStatus(
                providerId = "torbox",
                providerName = "Torbox",
                state = it,
            )
        },
    )

private fun httpSource(): StreamItem =
    StreamItem(
        name = "Http",
        url = "https://cdn.example/movie.mp4",
        addonName = "Addon",
        addonId = "addon:http",
    )

private fun acceptedCreate() = DebridApiResponse(
    status = 200,
    body = TorboxEnvelopeDto(success = true, data = TorboxCreateTorrentDataDto(torrentId = 1)),
    rawBody = "",
)
