package com.nuvio.app.features.analyzeddetails

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class SyncSlot(
    val desktopKey: String,
    val segmentLocal: String,
    val recordId: String,
    val showId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val presence: String,
    val segmentIndex: Int = 0,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val durationMs: Long = 0,
    val deleted: Boolean = false,
    val hlcWallMs: Long = 0,
    val hlcCounter: Int = 0,
    val hlcDeviceId: String = "",
)

@Serializable
internal data class SyncMirror(
    val desktopKey: String,
    val segmentLocal: String,
    val recordId: String,
    val content: String,
)

/**
 * Everything persisted for one profile except the passphrase.
 * The passphrase, the bearer token, and the enroll secret are not fields here.
 */
@Serializable
internal data class SyncSession(
    val baseUrl: String = "",
    val spaceId: String = "",
    val saltBase64: String = "",
    val keyCheckBase64: String = "",
    val deviceId: String = "",
    val pullCursor: Long = 0,
    val lastSuccessfulSyncMs: Long? = null,
    val clockWallMs: Long = 0,
    val clockCounter: Int = 0,
    val slots: List<SyncSlot> = emptyList(),
    val mirrors: List<SyncMirror> = emptyList(),
    val knownCanonical: List<String> = emptyList(),
    val submissions: List<String> = emptyList(),
    val secretStoreKind: String = "",
    val statusText: String = "Not joined.",
    val statusError: Boolean = false,
    val joined: Boolean = false,
)

internal interface SyncSessionStore {
    fun load(profileId: Int): SyncSession
    fun save(profileId: Int, session: SyncSession)
}

internal class MemorySessionStore : SyncSessionStore {
    private val values = mutableMapOf<Int, SyncSession>()
    override fun load(profileId: Int): SyncSession = values[profileId] ?: SyncSession()
    override fun save(profileId: Int, session: SyncSession) {
        values[profileId] = session
    }
}

internal class FileSyncSessionStore(
    private val fileFor: (Int) -> Path,
) : SyncSessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(profileId: Int): SyncSession {
        val file = fileFor(profileId)
        if (!Files.exists(file)) return SyncSession()
        return runCatching { json.decodeFromString<SyncSession>(Files.readString(file)) }.getOrDefault(SyncSession())
    }

    override fun save(profileId: Int, session: SyncSession) {
        val file = fileFor(profileId)
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(session))
    }
}

internal fun desktopSyncSessionStore(): SyncSessionStore = FileSyncSessionStore { profileId ->
    DesktopStorage.rootDir.resolve("analyzed-details-sync-$profileId.json")
}

internal data class DirectoryMark(
    val parentMetaId: String,
    val season: Int?,
    val episode: Int?,
    val segmentLocal: String,
    val presence: String,
    val segmentIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val fromDevice: String?,
)

internal interface MarkDirectory {
    fun list(): List<DirectoryMark>
    fun upsert(mark: DirectoryMark)
    fun remove(parentMetaId: String, season: Int?, episode: Int?, segmentLocal: String)
}

internal class MemoryMarkDirectory : MarkDirectory {
    private val marks = linkedMapOf<String, DirectoryMark>()

    override fun list(): List<DirectoryMark> = marks.values.toList()

    override fun upsert(mark: DirectoryMark) {
        marks[key(mark.parentMetaId, mark.season, mark.episode, mark.segmentLocal)] = mark
    }

    override fun remove(parentMetaId: String, season: Int?, episode: Int?, segmentLocal: String) {
        marks.remove(key(parentMetaId, season, episode, segmentLocal))
    }

    private fun key(parentMetaId: String, season: Int?, episode: Int?, segmentLocal: String): String =
        desktopMarkKey(parentMetaId, season, episode) + "\n" + segmentLocal
}

internal fun directoryContent(mark: DirectoryMark): String =
    listOf(mark.presence, mark.segmentIndex, mark.startMs, mark.endMs, mark.durationMs).joinToString("|")
