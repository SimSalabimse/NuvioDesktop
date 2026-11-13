package com.nuvio.app.features.analyzeddetails

private val DEVICE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val TMDB = Regex("^tmdb:[1-9][0-9]{0,9}$")
private val IMDB = Regex("^imdb:tt[0-9]{1,10}$")
private val RAW = Regex("^raw:[A-Za-z0-9._-]{1,128}$")
private val ALGORITHM = Regex("^nuvio-analysis-([1-9][0-9]{0,8})$")
private val ACCOUNT = Regex("^[0-9a-f]{64}$")
private val REMOTE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
private val STATUS = Regex("^[A-Za-z0-9 ._-]{1,32}$")
private val EPISODE_KEY = Regex("^(movie|s[0-9]+e[1-9][0-9]*)$")

internal sealed interface SyncRecord {
    val kind: String
    val id: String
    val deleted: Boolean
    val hlc: ClockStamp
    val wire: JsonVal.Obj
}

internal data class TombstoneRecord(
    override val kind: String,
    override val id: String,
    override val hlc: ClockStamp,
    override val wire: JsonVal.Obj,
) : SyncRecord {
    override val deleted: Boolean = true
}

internal data class MarkRecord(
    override val id: String,
    override val hlc: ClockStamp,
    override val wire: JsonVal.Obj,
    val showId: String,
    val season: Long?,
    val episode: Long?,
    val segmentType: String,
    val presence: String,
    val segmentIndex: Int?,
    val startMs: Long?,
    val endMs: Long?,
    val durationMs: Long,
) : SyncRecord {
    override val kind: String = "mark"
    override val deleted: Boolean = false
}

internal data class DetectionRecord(
    override val id: String,
    override val hlc: ClockStamp,
    override val wire: JsonVal.Obj,
    val showId: String,
    val season: Long?,
    val episode: Long?,
    val segmentType: String,
    val segmentIndex: Int,
    val outcome: String,
    val startMs: Long?,
    val endMs: Long?,
    val durationMs: Long,
    val algorithm: String,
    val algorithmVersion: Int,
    val releaseId: String?,
) : SyncRecord {
    override val kind: String = "detection"
    override val deleted: Boolean = false
}

internal data class SubmissionRecord(
    override val id: String,
    override val hlc: ClockStamp,
    override val wire: JsonVal.Obj,
    val accountScope: String,
    val remoteId: String,
    val showId: String,
    val mediaType: String,
    val season: Long?,
    val episode: Long?,
    val segmentType: String,
    val status: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
) : SyncRecord {
    override val kind: String = "submission"
    override val deleted: Boolean = false
}

internal data class AliasRecord(
    override val id: String,
    override val hlc: ClockStamp,
    override val wire: JsonVal.Obj,
    val from: String,
    val to: String,
) : SyncRecord {
    override val kind: String = "alias"
    override val deleted: Boolean = false
}

internal fun parseRecord(value: JsonVal): SyncRecord? {
    val obj = value as? JsonVal.Obj ?: return null
    val fields = obj.value
    val hlc = parseHlc(fields["hlc"]) ?: return null
    val kind = (fields["kind"] as? JsonVal.Str)?.value ?: return null
    val id = (fields["id"] as? JsonVal.Str)?.value ?: return null
    val deleted = (fields["deleted"] as? JsonVal.Bool)?.value ?: return null
    if (deleted) return parseTombstone(fields, kind, id, hlc)
    return when (kind) {
        "mark" -> parseMark(fields, id, hlc)
        "detection" -> parseDetection(fields, id, hlc)
        "submission" -> parseSubmission(fields, id, hlc)
        "alias" -> parseAlias(fields, id, hlc)
        else -> null
    }
}

internal fun episodeKey(season: Long?, episode: Long?): String? {
    if (season == null && episode == null) return "movie"
    if (season == null || episode == null) return null
    if (season < 0L || episode < 1L) return null
    return "s${season}e${episode}"
}

internal fun isShowId(value: String): Boolean {
    if (TMDB.matches(value) || RAW.matches(value)) return true
    return IMDB.matches(value) && value.removePrefix("imdb:tt").any { it != '0' }
}

internal fun normShowId(showId: String): String = when {
    showId.startsWith("tmdb:") -> "tmdb:${showId.removePrefix("tmdb:").toLong()}"
    showId.startsWith("imdb:tt") -> "imdb:${showId.removePrefix("imdb:tt").toLong()}"
    else -> showId
}

internal fun isUuidV4(value: String): Boolean = UUID_V4.matches(value)

internal fun compareParsed(left: SyncRecord, right: SyncRecord): Int {
    val clock = compareHlc(left.hlc, right.hlc)
    if (clock != 0) return clock
    if (left.id != right.id) return left.id.compareTo(right.id)
    val leftWire = canonicalJson(left.wire)
    val rightWire = canonicalJson(right.wire)
    if (leftWire == rightWire) return 0
    return leftWire.compareTo(rightWire)
}

internal fun rangeMarkRecord(
    id: String,
    hlc: ClockStamp,
    showId: String,
    season: Long?,
    episode: Long?,
    segmentType: String,
    segmentIndex: Int,
    startMs: Long,
    endMs: Long,
    durationMs: Long,
): MarkRecord? {
    val draft = jObj(
        "deleted" to JsonVal.Bool(false),
        "durationMs" to JsonVal.Num(durationMs),
        "endMs" to JsonVal.Num(endMs),
        "episode" to jsonLongOrNull(episode),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str("mark"),
        "presence" to JsonVal.Str("range"),
        "season" to jsonLongOrNull(season),
        "segmentIndex" to JsonVal.Num(segmentIndex.toLong()),
        "segmentType" to JsonVal.Str(segmentType),
        "showId" to JsonVal.Str(showId),
        "startMs" to JsonVal.Num(startMs),
    )
    return parseRecord(draft) as? MarkRecord
}

internal fun noneMarkRecord(
    hlc: ClockStamp,
    showId: String,
    season: Long?,
    episode: Long?,
    segmentType: String,
    durationMs: Long,
): MarkRecord? {
    val key = episodeKey(season, episode) ?: return null
    val id = "none/$showId/$key/$segmentType"
    val draft = jObj(
        "deleted" to JsonVal.Bool(false),
        "durationMs" to JsonVal.Num(durationMs),
        "episode" to jsonLongOrNull(episode),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str("mark"),
        "presence" to JsonVal.Str("none"),
        "season" to jsonLongOrNull(season),
        "segmentType" to JsonVal.Str(segmentType),
        "showId" to JsonVal.Str(showId),
    )
    return parseRecord(draft) as? MarkRecord
}

internal fun tombstoneRecord(kind: String, id: String, hlc: ClockStamp): TombstoneRecord? {
    val draft = jObj(
        "deleted" to JsonVal.Bool(true),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str(kind),
    )
    return parseRecord(draft) as? TombstoneRecord
}

internal fun submissionRecord(
    hlc: ClockStamp,
    accountScope: String,
    remoteId: String,
    showId: String,
    mediaType: String,
    season: Long?,
    episode: Long?,
    segmentType: String,
    status: String,
    startMs: Long,
    endMs: Long,
    durationMs: Long,
): SubmissionRecord? {
    val id = "submission/$accountScope/$remoteId"
    val draft = jObj(
        "accountScope" to JsonVal.Str(accountScope),
        "deleted" to JsonVal.Bool(false),
        "durationMs" to JsonVal.Num(durationMs),
        "endMs" to JsonVal.Num(endMs),
        "episode" to jsonLongOrNull(episode),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str("submission"),
        "mediaType" to JsonVal.Str(mediaType),
        "remoteId" to JsonVal.Str(remoteId),
        "season" to jsonLongOrNull(season),
        "segmentType" to JsonVal.Str(segmentType),
        "showId" to JsonVal.Str(showId),
        "startMs" to JsonVal.Num(startMs),
        "status" to JsonVal.Str(status),
    )
    return parseRecord(draft) as? SubmissionRecord
}

private fun parseTombstone(fields: Map<String, JsonVal>, kind: String, id: String, hlc: ClockStamp): TombstoneRecord? {
    if (!keysExact(fields, setOf("deleted", "hlc", "id", "kind"))) return null
    if (kind != "mark" && kind != "detection" && kind != "submission" && kind != "alias") return null
    if (!tombstoneIdOk(kind, id)) return null
    val wire = jObj(
        "deleted" to JsonVal.Bool(true),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str(kind),
    )
    return TombstoneRecord(kind, id, hlc, wire)
}

private fun parseMark(fields: Map<String, JsonVal>, id: String, hlc: ClockStamp): MarkRecord? {
    val presence = (fields["presence"] as? JsonVal.Str)?.value ?: return null
    if (presence != "range" && presence != "none") return null
    val located = parseLocation(fields) ?: return null
    val segment = (fields["segmentType"] as? JsonVal.Str)?.value ?: return null
    if (!isSegment(segment)) return null
    val duration = (fields["durationMs"] as? JsonVal.Num)?.value ?: return null
    if (duration < 0L) return null
    if (presence == "none") {
        val expected = setOf("deleted", "durationMs", "episode", "hlc", "id", "kind", "presence", "season", "segmentType", "showId")
        if (!keysExact(fields, expected)) return null
        val noneId = "none/${located.showId}/${located.episodeKey}/$segment"
        if (id != noneId) return null
        val wire = jObj(
            "deleted" to JsonVal.Bool(false),
            "durationMs" to JsonVal.Num(duration),
            "episode" to jsonLongOrNull(located.episode),
            "hlc" to hlcJson(hlc),
            "id" to JsonVal.Str(noneId),
            "kind" to JsonVal.Str("mark"),
            "presence" to JsonVal.Str("none"),
            "season" to jsonLongOrNull(located.season),
            "segmentType" to JsonVal.Str(segment),
            "showId" to JsonVal.Str(located.showId),
        )
        return MarkRecord(noneId, hlc, wire, located.showId, located.season, located.episode, segment, "none", null, null, null, duration)
    }
    val expected = setOf(
        "deleted", "durationMs", "endMs", "episode", "hlc", "id", "kind", "presence",
        "season", "segmentIndex", "segmentType", "showId", "startMs",
    )
    if (!keysExact(fields, expected)) return null
    val range = parseRange(fields["startMs"], fields["endMs"]) ?: return null
    val index = fields["segmentIndex"] as? JsonVal.Num ?: return null
    if (!isIndex(index.value)) return null
    if (!id.startsWith("mark/") || !UUID_V4.matches(id.substring(5))) return null
    val wire = jObj(
        "deleted" to JsonVal.Bool(false),
        "durationMs" to JsonVal.Num(duration),
        "endMs" to JsonVal.Num(range.second),
        "episode" to jsonLongOrNull(located.episode),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str("mark"),
        "presence" to JsonVal.Str("range"),
        "season" to jsonLongOrNull(located.season),
        "segmentIndex" to JsonVal.Num(index.value),
        "segmentType" to JsonVal.Str(segment),
        "showId" to JsonVal.Str(located.showId),
        "startMs" to JsonVal.Num(range.first),
    )
    return MarkRecord(
        id, hlc, wire, located.showId, located.season, located.episode, segment, "range",
        index.value.toInt(), range.first, range.second, duration,
    )
}

private fun parseDetection(fields: Map<String, JsonVal>, id: String, hlc: ClockStamp): DetectionRecord? {
    val outcome = (fields["outcome"] as? JsonVal.Str)?.value ?: return null
    if (outcome != "found" && outcome != "not_found") return null
    val located = parseLocation(fields) ?: return null
    val algorithm = (fields["algorithm"] as? JsonVal.Str)?.value ?: return null
    val version = algorithmVersion(algorithm) ?: return null
    val segment = (fields["segmentType"] as? JsonVal.Str)?.value ?: return null
    if (!isSegment(segment)) return null
    val index = fields["segmentIndex"] as? JsonVal.Num ?: return null
    if (!isIndex(index.value)) return null
    val duration = (fields["durationMs"] as? JsonVal.Num)?.value ?: return null
    if (duration < 0L) return null
    val release = parseRelease(fields) ?: return null
    if (release is ReleaseBad) return null
    val releaseId = (release as ReleaseValue).id
    val required = mutableSetOf(
        "algorithm", "deleted", "durationMs", "episode", "hlc", "id", "kind", "outcome",
        "season", "segmentIndex", "segmentType", "showId",
    )
    if (releaseId != null) required += "releaseId"
    if (outcome == "found") {
        required += "startMs"
        required += "endMs"
    }
    if (!keysExact(fields, required)) return null
    var startMs: Long? = null
    var endMs: Long? = null
    if (outcome == "found") {
        val range = parseRange(fields["startMs"], fields["endMs"]) ?: return null
        startMs = range.first
        endMs = range.second
    }
    val expectedId = "det/${located.showId}/${located.episodeKey}/$segment/${index.value}/$duration/a$version"
    if (id != expectedId) return null
    val wireFields = linkedMapOf<String, JsonVal>(
        "algorithm" to JsonVal.Str(algorithm),
        "deleted" to JsonVal.Bool(false),
        "durationMs" to JsonVal.Num(duration),
        "episode" to jsonLongOrNull(located.episode),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str("detection"),
        "outcome" to JsonVal.Str(outcome),
        "season" to jsonLongOrNull(located.season),
        "segmentIndex" to JsonVal.Num(index.value),
        "segmentType" to JsonVal.Str(segment),
        "showId" to JsonVal.Str(located.showId),
    )
    if (outcome == "found") {
        wireFields["startMs"] = JsonVal.Num(startMs!!)
        wireFields["endMs"] = JsonVal.Num(endMs!!)
    }
    if (releaseId != null) wireFields["releaseId"] = JsonVal.Str(releaseId)
    return DetectionRecord(
        id, hlc, JsonVal.Obj(wireFields), located.showId, located.season, located.episode, segment,
        index.value.toInt(), outcome, startMs, endMs, duration, algorithm, version, releaseId,
    )
}

private fun parseSubmission(fields: Map<String, JsonVal>, id: String, hlc: ClockStamp): SubmissionRecord? {
    val expected = setOf(
        "accountScope", "deleted", "durationMs", "endMs", "episode", "hlc", "id", "kind",
        "mediaType", "remoteId", "season", "segmentType", "showId", "startMs", "status",
    )
    if (!keysExact(fields, expected)) return null
    val located = parseLocation(fields) ?: return null
    val segment = (fields["segmentType"] as? JsonVal.Str)?.value ?: return null
    if (!isSegment(segment)) return null
    val account = (fields["accountScope"] as? JsonVal.Str)?.value ?: return null
    if (!ACCOUNT.matches(account)) return null
    val remote = (fields["remoteId"] as? JsonVal.Str)?.value ?: return null
    if (!REMOTE_ID.matches(remote)) return null
    val status = (fields["status"] as? JsonVal.Str)?.value ?: return null
    if (!STATUS.matches(status)) return null
    val media = (fields["mediaType"] as? JsonVal.Str)?.value ?: return null
    if (media != "tv" && media != "movie") return null
    if (media == "movie" && located.episodeKey != "movie") return null
    if (media == "tv" && located.episodeKey == "movie") return null
    val start = (fields["startMs"] as? JsonVal.Num)?.value ?: return null
    val end = (fields["endMs"] as? JsonVal.Num)?.value ?: return null
    val duration = (fields["durationMs"] as? JsonVal.Num)?.value ?: return null
    if (start < 0L || duration < 0L || end < start) return null
    val expectedId = "submission/$account/$remote"
    if (id != expectedId) return null
    val record = SubmissionRecord(
        id, hlc, jObj(), account, remote, located.showId, media, located.season, located.episode,
        segment, status, start, end, duration,
    )
    val wire = jObj(
        "accountScope" to JsonVal.Str(account),
        "deleted" to JsonVal.Bool(false),
        "durationMs" to JsonVal.Num(duration),
        "endMs" to JsonVal.Num(end),
        "episode" to jsonLongOrNull(located.episode),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str("submission"),
        "mediaType" to JsonVal.Str(media),
        "remoteId" to JsonVal.Str(remote),
        "season" to jsonLongOrNull(located.season),
        "segmentType" to JsonVal.Str(segment),
        "showId" to JsonVal.Str(located.showId),
        "startMs" to JsonVal.Num(start),
        "status" to JsonVal.Str(status),
    )
    return record.copy(wire = wire)
}

private fun parseAlias(fields: Map<String, JsonVal>, id: String, hlc: ClockStamp): AliasRecord? {
    if (!keysExact(fields, setOf("deleted", "from", "hlc", "id", "kind", "to"))) return null
    val from = (fields["from"] as? JsonVal.Str)?.value ?: return null
    val to = (fields["to"] as? JsonVal.Str)?.value ?: return null
    if (!isShowId(from) || !isShowId(to) || from == to) return null
    if (id != "alias/$from") return null
    val wire = jObj(
        "deleted" to JsonVal.Bool(false),
        "from" to JsonVal.Str(from),
        "hlc" to hlcJson(hlc),
        "id" to JsonVal.Str(id),
        "kind" to JsonVal.Str("alias"),
        "to" to JsonVal.Str(to),
    )
    return AliasRecord(id, hlc, wire, from, to)
}

private data class Location(val showId: String, val season: Long?, val episode: Long?, val episodeKey: String)

private fun parseLocation(fields: Map<String, JsonVal>): Location? {
    val showId = (fields["showId"] as? JsonVal.Str)?.value ?: return null
    if (!isShowId(showId)) return null
    val season = fields["season"] ?: return null
    val episode = fields["episode"] ?: return null
    if (season is JsonVal.Null && episode is JsonVal.Null) return Location(showId, null, null, "movie")
    if (season !is JsonVal.Num || episode !is JsonVal.Num) return null
    val key = episodeKey(season.value, episode.value) ?: return null
    if (key == "movie") return null
    return Location(showId, season.value, episode.value, key)
}

private fun parseRange(start: JsonVal?, end: JsonVal?): Pair<Long, Long>? {
    if (start !is JsonVal.Num || end !is JsonVal.Num) return null
    if (start.value < 0L || end.value <= start.value) return null
    return start.value to end.value
}

private sealed interface ReleaseParse
private data class ReleaseValue(val id: String?) : ReleaseParse
private data object ReleaseBad : ReleaseParse

private fun parseRelease(fields: Map<String, JsonVal>): ReleaseParse? {
    if (!fields.containsKey("releaseId")) return ReleaseValue(null)
    val value = fields["releaseId"] as? JsonVal.Str ?: return ReleaseBad
    if (!isShowId(value.value)) return ReleaseBad
    return ReleaseValue(value.value)
}

private fun parseHlc(value: JsonVal?): ClockStamp? {
    val obj = value as? JsonVal.Obj ?: return null
    if (!keysExact(obj.value, setOf("counter", "deviceId", "wallMs"))) return null
    val wall = (obj.value["wallMs"] as? JsonVal.Num)?.value ?: return null
    val counter = (obj.value["counter"] as? JsonVal.Num)?.value ?: return null
    val device = (obj.value["deviceId"] as? JsonVal.Str)?.value ?: return null
    if (wall < 0L || counter < 0L || counter > MAX_HLC_COUNTER) return null
    if (!DEVICE_ID.matches(device)) return null
    return ClockStamp(wall, counter.toInt(), device)
}

private fun algorithmVersion(algorithm: String): Int? {
    val match = ALGORITHM.matchEntire(algorithm) ?: return null
    return match.groupValues[1].toIntOrNull()
}

private fun tombstoneIdOk(kind: String, id: String): Boolean = when (kind) {
    "mark" -> (Regex("^mark/[0-9a-f-]{36}$").matches(id) && UUID_V4.matches(id.substring(5))) || isNoneId(id)
    "alias" -> id.startsWith("alias/") && isShowId(id.removePrefix("alias/"))
    "submission" -> {
        val parts = id.split("/")
        parts.size == 3 && parts[0] == "submission" && ACCOUNT.matches(parts[1]) && REMOTE_ID.matches(parts[2])
    }
    "detection" -> {
        val parts = id.split("/")
        parts.size == 7 && parts[0] == "det" && isShowId(parts[1]) && EPISODE_KEY.matches(parts[2]) &&
            isSegment(parts[3]) && Regex("^[0-9]{1,2}$").matches(parts[4]) && parts[4].toInt() <= 99 &&
            Regex("^[0-9]+$").matches(parts[5]) && Regex("^a[1-9][0-9]{0,8}$").matches(parts[6])
    }
    else -> false
}

private fun isNoneId(id: String): Boolean {
    val parts = id.split("/")
    if (parts.size != 4 || parts[0] != "none") return false
    return isShowId(parts[1]) && EPISODE_KEY.matches(parts[2]) && isSegment(parts[3])
}

private fun isSegment(value: String?): Boolean = value != null && value in SEGMENT_TYPES

private fun isIndex(value: Long): Boolean = value in 0L..99L

private fun keysExact(fields: Map<String, JsonVal>, expected: Set<String>): Boolean =
    fields.size == expected.size && fields.keys.all { it in expected }

internal fun hlcJson(hlc: ClockStamp): JsonVal.Obj = jObj(
    "counter" to JsonVal.Num(hlc.counter.toLong()),
    "deviceId" to JsonVal.Str(hlc.deviceId),
    "wallMs" to JsonVal.Num(hlc.wallMs),
)

private fun jsonLongOrNull(value: Long?): JsonVal = if (value == null) JsonVal.Null else JsonVal.Num(value)
