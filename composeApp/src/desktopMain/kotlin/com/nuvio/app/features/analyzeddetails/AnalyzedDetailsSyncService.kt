package com.nuvio.app.features.analyzeddetails

import com.nuvio.app.features.player.skip.SkipMarks
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.player.skip.SkipInterval
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal actual val analyzedDetailsSyncSupported: Boolean = true

@Composable
internal actual fun AnalyzedDetailsSyncSettings(isTablet: Boolean) {
    AnalyzedDetailsSyncSettingsPage(isTablet)
}

internal actual fun overlayAnalyzedDetails(
    intervals: List<SkipInterval>,
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    fileDurationMs: Long,
    isMovie: Boolean,
): List<SkipInterval> = AnalyzedDetailsSyncService.overlay(intervals, parentMetaId, season, episode, fileDurationMs, isMovie)

internal actual fun noteTheIntroDbSubmissions(accountKey: String, notes: List<AnalyzedSubmissionNote>) {
    AnalyzedDetailsSyncService.note(
        accountScopeFor(accountKey),
        notes.map { note ->
            SubmissionNote(
                remoteId = note.remoteId,
                tmdbId = note.tmdbId,
                mediaType = note.mediaType,
                season = note.season,
                episode = note.episode,
                segment = note.segment,
                status = note.status,
                startMs = note.startMs,
                endMs = note.endMs,
                durationMs = note.durationMs,
                imdbId = note.imdbId,
            )
        },
    )
}

internal actual fun noteLinkedShowIds(tmdbId: String, imdbId: String) {
    AnalyzedDetailsSyncService.noteShowLink(tmdbId, imdbId)
}

internal object AnalyzedDetailsSyncService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val debounce = SyncDebounce()
    private val transport = SwitchingSyncTransport()
    private val engine by lazy {
        AnalyzedDetailsSync(
            transport = transport,
            secrets = openSyncSecretStore(com.nuvio.app.core.storage.DesktopStorage.rootDir.resolve("analyzed-details-secrets")),
            sessions = desktopSyncSessionStore(),
            directory = SkipMarkDirectory(),
            profileId = { ProfileRepository.activeProfileId },
            now = { System.currentTimeMillis() },
        )
    }
    private val stateFlow = MutableStateFlow(SyncSession())
    val state: StateFlow<SyncSession> = stateFlow
    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow
    @Volatile private var mergedCache: MergedState? = null

    fun start() {
        scope.launch {
            SkipMarks.revision.drop(1).collect {
                schedule()
            }
        }
        scope.launch {
            gate.withLock {
                val current = withContext(Dispatchers.IO) { engine.session() }
                publish(current)
                if (current.joined) {
                    transport.baseUrl = current.baseUrl
                    publish(withContext(Dispatchers.IO) { engine.sync() })
                }
            }
        }
    }

    /** Stops queued syncs and tells an in-flight sync to drop its writes. */
    fun shutdown() {
        engine.abandon()
        scope.cancel()
    }

    fun create(baseUrl: String, enrollSecret: String, passphrase: String) = runBusy {
        val url = normalizeSyncBaseUrl(baseUrl) ?: baseUrl.trim()
        transport.baseUrl = url
        engine.create(baseUrl, enrollSecret, passphrase)
    }

    fun join(baseUrl: String, joinCode: String, passphrase: String) = runBusy {
        val url = normalizeSyncBaseUrl(baseUrl) ?: baseUrl.trim()
        transport.baseUrl = url
        engine.join(baseUrl, joinCode, passphrase)
    }

    fun syncNow() {
        debounce.syncNow()
        runBusy {
            transport.baseUrl = engine.session().baseUrl
            engine.sync()
        }
    }

    fun leave() {
        engine.abandon()
        runBusy { engine.leave() }
    }

    fun note(accountScope: String, notes: List<SubmissionNote>) {
        scope.launch {
            gate.withLock {
                val saved = withContext(Dispatchers.IO) { engine.noteSubmissions(accountScope, notes) }
                publish(saved)
                if (saved.joined) schedule()
            }
        }
    }

    fun noteShowLink(tmdbId: String, imdbId: String) {
        scope.launch {
            gate.withLock {
                val saved = withContext(Dispatchers.IO) { engine.noteShowLink(tmdbId, imdbId) }
                publish(saved)
                if (saved.joined) schedule()
            }
        }
    }

    fun overlay(
        intervals: List<SkipInterval>,
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        fileDurationMs: Long,
        isMovie: Boolean,
    ): List<SkipInterval> {
        val current = stateFlow.value
        val merged = mergedCache
        if (!current.joined || merged == null) return intervals
        return overlaySyncedSkips(
            intervals = intervals,
            merged = merged,
            parentMetaId = parentMetaId,
            season = season,
            episode = episode,
            fileDurationMs = fileDurationMs,
            isMovie = isMovie,
        )
    }

    private fun schedule() {
        val token = debounce.schedule()
        scope.launch {
            delay(SYNC_DEBOUNCE_MS)
            if (!debounce.shouldRun(token)) return@launch
            gate.withLock {
                if (!debounce.shouldRun(token)) return@withLock
                val current = engine.session()
                if (!current.joined) return@withLock
                transport.baseUrl = current.baseUrl
                publish(withContext(Dispatchers.IO) { engine.sync() })
            }
        }
    }

    private fun runBusy(block: () -> SyncSession) {
        scope.launch {
            gate.withLock {
                busyFlow.value = true
                try {
                    publish(withContext(Dispatchers.IO) { block() })
                } finally {
                    busyFlow.value = false
                }
            }
        }
    }

    private fun publish(session: SyncSession) {
        stateFlow.value = session
        mergedCache = if (session.joined) mergedFromSession(session, System.currentTimeMillis()) else null
    }
}

private class SwitchingSyncTransport : SyncTransport {
    override var baseUrl: String = ""

    override fun createSpace(enrollSecret: String, bearer: String, body: String): SyncHttpResult =
        SyncHttpClient(baseUrl).createSpace(enrollSecret, bearer, body)

    override fun join(spaceId: String, bearer: String): SyncHttpResult =
        SyncHttpClient(baseUrl).join(spaceId, bearer)

    override fun push(spaceId: String, bearer: String, body: String): SyncHttpResult =
        SyncHttpClient(baseUrl).push(spaceId, bearer, body)

    override fun pull(spaceId: String, bearer: String, cursor: Long, limit: Int): SyncHttpResult =
        SyncHttpClient(baseUrl).pull(spaceId, bearer, cursor, limit)
}

private class SkipMarkDirectory : MarkDirectory {
    override fun list(): List<DirectoryMark> = SkipMarks.snapshot().flatMap { (key, types) ->
        val ref = parseDesktopMarkKey(key) ?: return@flatMap emptyList()
        types.mapNotNull { (segment, mark) ->
            DirectoryMark(
                parentMetaId = ref.parentMetaId,
                season = ref.season,
                episode = ref.episode,
                segmentLocal = segment,
                presence = mark.presence,
                segmentIndex = mark.segmentIndex,
                startMs = mark.startMs,
                endMs = mark.endMs,
                durationMs = mark.durationMs,
                fromDevice = mark.fromDevice,
            )
        }
    }

    override fun upsert(mark: DirectoryMark) {
        SkipMarks.upsert(
            parentMetaId = mark.parentMetaId,
            season = mark.season,
            episode = mark.episode,
            type = mark.segmentLocal,
            mark = com.nuvio.app.features.player.skip.SkipMark(
                startMs = mark.startMs,
                endMs = mark.endMs,
                atMs = 0L,
                imported = false,
                presence = mark.presence,
                durationMs = mark.durationMs,
                segmentIndex = mark.segmentIndex,
                fromDevice = mark.fromDevice,
            ),
        )
    }

    override fun remove(parentMetaId: String, season: Int?, episode: Int?, segmentLocal: String) {
        SkipMarks.remove(parentMetaId, season, episode, segmentLocal)
    }
}
