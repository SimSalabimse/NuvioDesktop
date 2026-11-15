package com.nuvio.app.features.analyzeddetails

internal data class MarkView(
    val applied: Boolean,
    val durationMs: Long,
    val endMs: Long?,
    val hlc: ClockStamp,
    val id: String,
    val presence: String,
    val segmentIndex: Long?,
    val segmentType: String,
    val startMs: Long?,
    val suppressedBy: String?,
)

internal data class DetectionView(
    val algorithm: String,
    val algorithmVersion: Int,
    val applied: Boolean,
    val durationMs: Long,
    val endMs: Long?,
    val hlc: ClockStamp,
    val id: String,
    val outcome: String,
    val releaseId: String?,
    val segmentIndex: Int,
    val segmentType: String,
    val startMs: Long?,
    val suppressedBy: String?,
)

internal data class EffectiveSegment(
    val algorithm: String?,
    val durationMs: Long,
    val endMs: Long?,
    val id: String,
    val presence: String,
    val segmentIndex: Long?,
    val segmentType: String,
    val source: String,
    val startMs: Long?,
)

internal data class EpisodeState(
    val detections: List<DetectionView>,
    val effective: List<EffectiveSegment>,
    val episode: Long?,
    val episodeKey: String,
    val marks: List<MarkView>,
    val season: Long?,
    val userNoneTypes: List<String>,
)

internal data class ShowState(
    val aliases: List<String>,
    val episodes: List<EpisodeState>,
    val showId: String,
)

internal data class SubmissionState(
    val accountScope: String,
    val durationMs: Long,
    val endMs: Long,
    val episode: Long?,
    val hlc: ClockStamp,
    val id: String,
    val mediaType: String,
    val remoteId: String,
    val season: Long?,
    val segmentType: String,
    val showId: String,
    val startMs: Long,
    val status: String,
)

internal data class TombstoneState(
    val hlc: ClockStamp,
    val id: String,
    val kind: String,
    val purgeAfterMs: Long,
)

internal data class MergedState(
    val rejected: Int,
    val shows: List<ShowState>,
    val submissions: List<SubmissionState>,
    val tombstones: List<TombstoneState>,
)

internal data class AnalyseQuery(
    val showId: String,
    val season: Long?,
    val episode: Long?,
    val segmentType: String,
    val segmentIndex: Int,
    val fileDurationMs: Long,
    val localAlgorithm: Int,
)

internal fun mergeRecords(inputs: List<JsonVal>, nowMs: Long): MergedState {
    val parsed = mutableListOf<SyncRecord>()
    var rejected = 0
    for (input in inputs) {
        val record = parseRecord(input)
        if (record == null || !clockIsAcceptable(record.hlc.wallMs, nowMs)) rejected += 1
        else parsed += record
    }
    val winners = collapse(parsed)
    val graph = buildGraph(winners)
    val tombstones = mutableListOf<TombstoneState>()
    val marks = mutableListOf<MarkRecord>()
    val detections = mutableListOf<DetectionRecord>()
    val submissions = mutableListOf<SubmissionRecord>()
    for (winner in winners) {
        if (winner.deleted) {
            val purgeAfterMs = winner.hlc.wallMs + TOMBSTONE_KEEP_MS
            if (nowMs < purgeAfterMs) {
                tombstones += TombstoneState(winner.hlc, winner.id, winner.kind, purgeAfterMs)
            }
            continue
        }
        when (winner) {
            is MarkRecord -> marks += winner
            is DetectionRecord -> detections += winner
            is SubmissionRecord -> submissions += winner
            else -> Unit
        }
    }
    data class Bucket(
        val showId: String,
        val season: Long?,
        val episode: Long?,
        val marks: MutableList<MarkRecord> = mutableListOf(),
        val detections: MutableList<DetectionRecord> = mutableListOf(),
    )
    val episodes = linkedMapOf<String, Bucket>()
    fun addEpisode(showId: String, season: Long?, episode: Long?): Bucket {
        val canonical = graph.canonicalOf(showId)
        val key = "$canonical\n${season ?: ""}\n${episode ?: ""}"
        return episodes.getOrPut(key) { Bucket(canonical, season, episode) }
    }
    for (mark in marks) addEpisode(mark.showId, mark.season, mark.episode).marks += mark
    for (detection in detections) addEpisode(detection.showId, detection.season, detection.episode).detections += detection

    val shows = linkedMapOf<String, ShowState>()
    for (bucket in episodes.values) {
        val existing = shows[bucket.showId]
        val markResult = resolveMarks(bucket.marks)
        val detectionResult = resolveDetections(bucket.detections, markResult.views)
        val effective = (markResult.effective + detectionResult.effective).sortedWith(::compareEffective)
        val episode = EpisodeState(
            detections = detectionResult.views,
            effective = effective,
            episode = bucket.episode,
            episodeKey = episodeKey(bucket.season, bucket.episode) ?: "movie",
            marks = markResult.views,
            season = bucket.season,
            userNoneTypes = SEGMENT_TYPES.filter { type ->
                markResult.views.any { it.applied && it.presence == "none" && it.segmentType == type }
            },
        )
        val episodesSoFar = (existing?.episodes ?: emptyList()) + episode
        shows[bucket.showId] = ShowState(
            aliases = graph.aliasesOf(bucket.showId),
            episodes = episodesSoFar,
            showId = bucket.showId,
        )
    }
    val sortedShows = shows.values.map { show ->
        show.copy(episodes = show.episodes.sortedWith(compareBy<EpisodeState> { episodeRank(it) }.thenBy { it.episodeKey }))
    }.sortedBy { it.showId }
    val submissionStates = submissions.map { submission ->
        SubmissionState(
            accountScope = submission.accountScope,
            durationMs = submission.durationMs,
            endMs = submission.endMs,
            episode = submission.episode,
            hlc = submission.hlc,
            id = submission.id,
            mediaType = submission.mediaType,
            remoteId = submission.remoteId,
            season = submission.season,
            segmentType = submission.segmentType,
            showId = graph.canonicalOf(submission.showId),
            startMs = submission.startMs,
            status = submission.status,
        )
    }.sortedWith(compareBy<SubmissionState> { it.accountScope }.thenBy { it.id })
    tombstones.sortWith(compareBy<TombstoneState> { it.kind }.thenBy { it.id })
    return MergedState(rejected, sortedShows, submissionStates, tombstones.toList())
}

internal fun shouldAnalyse(state: MergedState, query: AnalyseQuery): Boolean {
    val show = state.shows.find { it.showId == query.showId || query.showId in it.aliases } ?: return true
    val episode = show.episodes.find { it.season == query.season && it.episode == query.episode } ?: return true
    if (query.segmentType in episode.userNoneTypes) return false
    val markCovers = episode.marks.any { mark ->
        mark.applied && mark.presence == "range" && mark.segmentType == query.segmentType &&
            mark.segmentIndex == query.segmentIndex.toLong() && durationCovers(mark.durationMs, query.fileDurationMs)
    }
    if (markCovers) return false
    val detectionCovers = episode.detections.any { detection ->
        detection.applied && detection.outcome == "found" && detection.segmentType == query.segmentType &&
            detection.segmentIndex == query.segmentIndex && detection.algorithmVersion >= query.localAlgorithm &&
            cutsMatch(detection.durationMs, query.fileDurationMs)
    }
    return !detectionCovers
}

internal fun mergedCanonical(inputs: List<JsonVal>, nowMs: Long): String = canonicalJson(mergeRecords(inputs, nowMs).toJson())

internal fun adoptFullResync(remoteRecords: List<JsonVal>, freshLocal: List<JsonVal>, nowMs: Long): MergedState =
    mergeRecords(remoteRecords + freshLocal, nowMs)

internal fun cutsMatch(left: Long, right: Long): Boolean {
    if (left <= 0L || right <= 0L) return false
    val delta = if (left > right) left - right else right - left
    return delta <= CUT_TOLERANCE_MS
}

internal fun durationCovers(markDurationMs: Long, fileDurationMs: Long): Boolean {
    if (markDurationMs == 0L) return true
    return cutsMatch(markDurationMs, fileDurationMs)
}

private fun collapse(parsed: List<SyncRecord>): List<SyncRecord> {
    val winners = linkedMapOf<String, SyncRecord>()
    for (record in parsed) {
        val key = "${record.kind}\n${record.id}"
        val previous = winners[key]
        if (previous == null || compareParsed(record, previous) > 0) winners[key] = record
    }
    return winners.values.toList()
}

private class ShowGraph(
    private val canonicalByNorm: Map<String, String>,
    private val aliasesByNorm: Map<String, List<String>>,
) {
    fun canonicalOf(showId: String): String = canonicalByNorm[normShowId(showId)] ?: showId
    fun aliasesOf(showId: String): List<String> = aliasesByNorm[normShowId(showId)] ?: listOf(showId)
}

private fun buildGraph(winners: List<SyncRecord>): ShowGraph {
    val parent = linkedMapOf<String, String>()
    val spellings = linkedMapOf<String, MutableSet<String>>()
    fun find(norm: String): String {
        val current = parent[norm]
        if (current == null) {
            parent[norm] = norm
            return norm
        }
        if (current == norm) return norm
        val root = find(current)
        parent[norm] = root
        return root
    }
    fun addNorm(norm: String): String {
        if (!parent.containsKey(norm)) parent[norm] = norm
        return find(norm)
    }
    fun addSpelling(showId: String) {
        val norm = normShowId(showId)
        addNorm(norm)
        spellings.getOrPut(norm) { linkedSetOf() }.add(showId)
    }
    fun union(left: String, right: String) {
        val a = addNorm(left)
        val b = addNorm(right)
        if (a != b) parent[a] = b
    }
    for (winner in winners) {
        if (winner.deleted) continue
        when (winner) {
            is AliasRecord -> {
                addSpelling(winner.from)
                addSpelling(winner.to)
                union(normShowId(winner.from), normShowId(winner.to))
            }
            is MarkRecord -> addSpelling(winner.showId)
            is DetectionRecord -> addSpelling(winner.showId)
            is SubmissionRecord -> addSpelling(winner.showId)
            is TombstoneRecord -> Unit
        }
    }
    val byRoot = linkedMapOf<String, MutableList<String>>()
    for (norm in parent.keys) {
        val root = find(norm)
        byRoot.getOrPut(root) { mutableListOf() }.add(norm)
    }
    val canonicalByNorm = linkedMapOf<String, String>()
    val aliasesByNorm = linkedMapOf<String, List<String>>()
    for (norms in byRoot.values) {
        val canonical = canonicalShow(norms, spellings)
        val aliases = norms.flatMap { spellings[it].orEmpty() }.distinct().sorted()
        for (norm in norms) {
            canonicalByNorm[norm] = canonical
            aliasesByNorm[norm] = aliases
        }
    }
    return ShowGraph(canonicalByNorm, aliasesByNorm)
}

private fun canonicalShow(norms: List<String>, spellings: Map<String, Set<String>>): String {
    val tmdb = norms.filter { it.startsWith("tmdb:") }.map { it.removePrefix("tmdb:").toLong() }
    if (tmdb.isNotEmpty()) return "tmdb:${tmdb.min()}"
    val imdb = norms.filter { it.startsWith("imdb:") }.map { it.removePrefix("imdb:").toLong() }
    if (imdb.isNotEmpty()) {
        val best = imdb.min()
        val options = spellings["imdb:$best"].orEmpty().toMutableList()
        options.sortWith { left, right ->
            val leftDigits = left.removePrefix("imdb:tt")
            val rightDigits = right.removePrefix("imdb:tt")
            if (leftDigits.length != rightDigits.length) return@sortWith rightDigits.length - leftDigits.length
            left.compareTo(right)
        }
        return options.firstOrNull() ?: "imdb:tt$best"
    }
    val raw = norms.filter { it.startsWith("raw:") }.sorted()
    return raw.firstOrNull() ?: norms.firstOrNull().orEmpty()
}

private data class MarkResolve(val views: List<MarkView>, val effective: List<EffectiveSegment>)

private fun resolveMarks(marks: List<MarkRecord>): MarkResolve {
    val views = mutableListOf<MarkView>()
    val effective = mutableListOf<EffectiveSegment>()
    for (type in SEGMENT_TYPES) {
        val group = marks.filter { it.segmentType == type }
        val nones = group.filter { it.presence == "none" }
        val ranges = group.filter { it.presence == "range" }
        val winningNone = maxRecord(nones)
        for (none in nones) {
            if (winningNone != null && none.id != winningNone.id) views += markView(none, false, "newer-none")
        }
        val appliedRanges = mutableListOf<MarkRecord>()
        for (range in ranges) {
            if (winningNone != null && compareParsed(winningNone, range) > 0) views += markView(range, false, "none")
            else {
                appliedRanges += range
                views += markView(range, true, null)
            }
        }
        if (winningNone != null) {
            val applied = appliedRanges.isEmpty()
            views += markView(winningNone, applied, if (applied) null else "range")
            if (applied) effective += effectiveFromMark(winningNone)
        }
        for (range in appliedRanges) effective += effectiveFromMark(range)
    }
    views.sortBy { it.id }
    return MarkResolve(views, effective)
}

private data class DetectionResolve(val views: List<DetectionView>, val effective: List<EffectiveSegment>)

private fun resolveDetections(detections: List<DetectionRecord>, marks: List<MarkView>): DetectionResolve {
    val groups = linkedMapOf<String, MutableList<DetectionRecord>>()
    for (detection in detections) {
        groups.getOrPut("${detection.segmentType}\n${detection.segmentIndex}") { mutableListOf() }.add(detection)
    }
    val views = mutableListOf<DetectionView>()
    val effective = mutableListOf<EffectiveSegment>()
    for (group in groups.values) {
        val found = group.filter { it.outcome == "found" }.sortedWith(compareBy<DetectionRecord> { it.durationMs }.thenBy { it.id })
        val clusters = mutableListOf<MutableList<DetectionRecord>>()
        for (detection in found) {
            val current = clusters.lastOrNull()
            val minimum = current?.firstOrNull()
            if (current == null || minimum == null || detection.durationMs - minimum.durationMs > CUT_TOLERANCE_MS) {
                clusters += mutableListOf(detection)
            } else {
                current += detection
            }
        }
        val winners = mutableSetOf<String>()
        val shadowed = mutableSetOf<String>()
        for (cluster in clusters) {
            val winner = maxDetection(cluster) ?: continue
            winners += winner.id
            for (other in cluster) if (other.id != winner.id) shadowed += other.id
        }
        for (detection in group) {
            var applied = false
            var suppressedBy: String? = null
            val marked = markSuppresses(detection, marks)
            if (detection.outcome == "not_found") {
                suppressedBy = if (marked) "mark" else null
            } else if (detection.id in shadowed) {
                suppressedBy = "newer-detection"
            } else if (detection.id in winners) {
                if (marked) suppressedBy = "mark" else applied = true
            }
            views += detectionView(detection, applied, suppressedBy)
            if (applied) effective += effectiveFromDetection(detection)
        }
    }
    views.sortBy { it.id }
    return DetectionResolve(views, effective)
}

private fun markSuppresses(detection: DetectionRecord, marks: List<MarkView>): Boolean = marks.any { mark ->
    if (!mark.applied || mark.segmentType != detection.segmentType) return@any false
    if (mark.presence == "none") return@any true
    if (mark.segmentIndex != detection.segmentIndex.toLong()) return@any false
    if (mark.durationMs == 0L) return@any true
    cutsMatch(mark.durationMs, detection.durationMs)
}

private fun markView(mark: MarkRecord, applied: Boolean, suppressedBy: String?): MarkView = MarkView(
    applied = applied,
    durationMs = mark.durationMs,
    endMs = mark.endMs,
    hlc = mark.hlc,
    id = mark.id,
    presence = mark.presence,
    segmentIndex = mark.segmentIndex?.toLong(),
    segmentType = mark.segmentType,
    startMs = mark.startMs,
    suppressedBy = suppressedBy,
)

private fun detectionView(detection: DetectionRecord, applied: Boolean, suppressedBy: String?): DetectionView = DetectionView(
    algorithm = detection.algorithm,
    algorithmVersion = detection.algorithmVersion,
    applied = applied,
    durationMs = detection.durationMs,
    endMs = detection.endMs,
    hlc = detection.hlc,
    id = detection.id,
    outcome = detection.outcome,
    releaseId = detection.releaseId,
    segmentIndex = detection.segmentIndex,
    segmentType = detection.segmentType,
    startMs = detection.startMs,
    suppressedBy = suppressedBy,
)

private fun effectiveFromMark(mark: MarkRecord): EffectiveSegment = EffectiveSegment(
    algorithm = null,
    durationMs = mark.durationMs,
    endMs = mark.endMs,
    id = mark.id,
    presence = mark.presence,
    segmentIndex = mark.segmentIndex?.toLong(),
    segmentType = mark.segmentType,
    source = "mark",
    startMs = mark.startMs,
)

private fun effectiveFromDetection(detection: DetectionRecord): EffectiveSegment = EffectiveSegment(
    algorithm = detection.algorithm,
    durationMs = detection.durationMs,
    endMs = detection.endMs,
    id = detection.id,
    presence = "range",
    segmentIndex = detection.segmentIndex.toLong(),
    segmentType = detection.segmentType,
    source = "detection",
    startMs = detection.startMs,
)

private fun compareEffective(left: EffectiveSegment, right: EffectiveSegment): Int {
    val type = SEGMENT_TYPES.indexOf(left.segmentType) - SEGMENT_TYPES.indexOf(right.segmentType)
    if (type != 0) return type
    val index = (left.segmentIndex ?: -1L).compareTo(right.segmentIndex ?: -1L)
    if (index != 0) return index
    if (left.source != right.source) return left.source.compareTo(right.source)
    return left.id.compareTo(right.id)
}

private fun episodeRank(episode: EpisodeState): Long {
    if (episode.season == null) return -1L
    return episode.season * 1_000_000L + (episode.episode ?: 0L)
}

private fun maxRecord(marks: List<MarkRecord>): MarkRecord? {
    var best: MarkRecord? = null
    for (mark in marks) {
        if (best == null || compareParsed(mark, best) > 0) best = mark
    }
    return best
}

private fun maxDetection(detections: List<DetectionRecord>): DetectionRecord? {
    var best: DetectionRecord? = null
    for (detection in detections) {
        if (best == null || compareDetections(detection, best) > 0) best = detection
    }
    return best
}

private fun compareDetections(left: DetectionRecord, right: DetectionRecord): Int {
    if (left.algorithmVersion != right.algorithmVersion) return left.algorithmVersion.compareTo(right.algorithmVersion)
    return compareParsed(left, right)
}

internal fun MergedState.toJson(): JsonVal = jObj(
    "rejected" to JsonVal.Num(rejected.toLong()),
    "shows" to JsonVal.Arr(shows.map { it.toJson() }),
    "submissions" to JsonVal.Arr(submissions.map { it.toJson() }),
    "tombstones" to JsonVal.Arr(tombstones.map { it.toJson() }),
)

private fun ShowState.toJson(): JsonVal = jObj(
    "aliases" to JsonVal.Arr(aliases.map { JsonVal.Str(it) }),
    "episodes" to JsonVal.Arr(episodes.map { it.toJson() }),
    "showId" to JsonVal.Str(showId),
)

private fun EpisodeState.toJson(): JsonVal = jObj(
    "detections" to JsonVal.Arr(detections.map { it.toJson() }),
    "effective" to JsonVal.Arr(effective.map { it.toJson() }),
    "episode" to jsonNum(episode),
    "episodeKey" to JsonVal.Str(episodeKey),
    "marks" to JsonVal.Arr(marks.map { it.toJson() }),
    "season" to jsonNum(season),
    "userNoneTypes" to JsonVal.Arr(userNoneTypes.map { JsonVal.Str(it) }),
)

private fun MarkView.toJson(): JsonVal = jObj(
    "applied" to JsonVal.Bool(applied),
    "durationMs" to JsonVal.Num(durationMs),
    "endMs" to jsonNum(endMs),
    "hlc" to hlcJson(hlc),
    "id" to JsonVal.Str(id),
    "presence" to JsonVal.Str(presence),
    "segmentIndex" to jsonNum(segmentIndex),
    "segmentType" to JsonVal.Str(segmentType),
    "startMs" to jsonNum(startMs),
    "suppressedBy" to jsonString(suppressedBy),
)

private fun DetectionView.toJson(): JsonVal = jObj(
    "algorithm" to JsonVal.Str(algorithm),
    "algorithmVersion" to JsonVal.Num(algorithmVersion.toLong()),
    "applied" to JsonVal.Bool(applied),
    "durationMs" to JsonVal.Num(durationMs),
    "endMs" to jsonNum(endMs),
    "hlc" to hlcJson(hlc),
    "id" to JsonVal.Str(id),
    "outcome" to JsonVal.Str(outcome),
    "releaseId" to jsonString(releaseId),
    "segmentIndex" to JsonVal.Num(segmentIndex.toLong()),
    "segmentType" to JsonVal.Str(segmentType),
    "startMs" to jsonNum(startMs),
    "suppressedBy" to jsonString(suppressedBy),
)

private fun EffectiveSegment.toJson(): JsonVal = jObj(
    "algorithm" to jsonString(algorithm),
    "durationMs" to JsonVal.Num(durationMs),
    "endMs" to jsonNum(endMs),
    "id" to JsonVal.Str(id),
    "presence" to JsonVal.Str(presence),
    "segmentIndex" to jsonNum(segmentIndex),
    "segmentType" to JsonVal.Str(segmentType),
    "source" to JsonVal.Str(source),
    "startMs" to jsonNum(startMs),
)

private fun SubmissionState.toJson(): JsonVal = jObj(
    "accountScope" to JsonVal.Str(accountScope),
    "durationMs" to JsonVal.Num(durationMs),
    "endMs" to JsonVal.Num(endMs),
    "episode" to jsonNum(episode),
    "hlc" to hlcJson(hlc),
    "id" to JsonVal.Str(id),
    "mediaType" to JsonVal.Str(mediaType),
    "remoteId" to JsonVal.Str(remoteId),
    "season" to jsonNum(season),
    "segmentType" to JsonVal.Str(segmentType),
    "showId" to JsonVal.Str(showId),
    "startMs" to JsonVal.Num(startMs),
    "status" to JsonVal.Str(status),
)

private fun TombstoneState.toJson(): JsonVal = jObj(
    "hlc" to hlcJson(hlc),
    "id" to JsonVal.Str(id),
    "kind" to JsonVal.Str(kind),
    "purgeAfterMs" to JsonVal.Num(purgeAfterMs),
)

private fun jsonNum(value: Long?): JsonVal = if (value == null) JsonVal.Null else JsonVal.Num(value)
private fun jsonString(value: String?): JsonVal = if (value == null) JsonVal.Null else JsonVal.Str(value)
