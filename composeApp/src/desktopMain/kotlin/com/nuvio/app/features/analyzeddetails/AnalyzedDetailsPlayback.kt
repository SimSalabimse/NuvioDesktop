package com.nuvio.app.features.analyzeddetails

import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipMarks
import kotlin.math.abs

/**
 * Marks already in [intervals] came from [SkipMarks]. This adds applied remote
 * marks and found detections that are not already shown. A not_found detection
 * adds nothing and never becomes a "none here" mark.
 */
internal fun overlaySyncedSkips(
    intervals: List<SkipInterval>,
    merged: MergedState,
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    fileDurationMs: Long,
    isMovie: Boolean,
): List<SkipInterval> {
    val showId = showIdForParent(parentMetaId) ?: return intervals
    val norm = normShowId(showId)
    val show = merged.shows.find { candidate ->
        normShowId(candidate.showId) == norm || candidate.aliases.any { normShowId(it) == norm }
    } ?: return intervals
    val episodeState = show.episodes.find { item ->
        if (season == null || episode == null) item.season == null && item.episode == null
        else item.season == season.toLong() && item.episode == episode.toLong()
    } ?: return intervals
    val blocked = episodeState.userNoneTypes.toSet()
    val kept = intervals.filter { interval ->
        val local = SkipMarks.canonicalType(interval.type) ?: return@filter true
        val wire = wireSegment(local) ?: return@filter true
        wire !in blocked
    }
    val added = mutableListOf<SkipInterval>()
    for (mark in episodeState.marks) {
        if (!mark.applied || mark.presence != "range") continue
        if (!durationCovers(mark.durationMs, fileDurationMs)) continue
        val start = mark.startMs ?: continue
        val end = mark.endMs ?: continue
        val local = localSegment(mark.segmentType) ?: continue
        if (shown(kept + added, local, start, end)) continue
        added += skipInterval(local, start, end, mark.hlc.deviceId, isMovie)
    }
    for (detection in episodeState.detections) {
        if (!detection.applied || detection.outcome != "found") continue
        if (!cutsMatch(detection.durationMs, fileDurationMs)) continue
        val start = detection.startMs ?: continue
        val end = detection.endMs ?: continue
        val local = localSegment(detection.segmentType) ?: continue
        if (wireSegment(local) in blocked) continue
        if (shown(kept + added, local, start, end)) continue
        added += skipInterval(local, start, end, detection.hlc.deviceId, isMovie)
    }
    return (kept + added).sortedBy { it.startTime }
}

internal fun mergedFromSession(session: SyncSession, nowMs: Long): MergedState {
    val known = session.knownCanonical.mapNotNull { parseJsonOrNull(it) }
    val local = session.slots.mapNotNull { slotWire(it) } + session.submissions.mapNotNull { parseJsonOrNull(it) }
    return mergeRecords(known + local, nowMs)
}

private fun skipInterval(local: String, startMs: Long, endMs: Long, deviceId: String, isMovie: Boolean): SkipInterval {
    val type = if (local == "outro" && isMovie) "movie-credits" else local
    val provider = if (deviceId.isBlank()) SkipMarks.PROVIDER else "sync:$deviceId"
    return SkipInterval(startMs / 1000.0, endMs / 1000.0, type, provider)
}

private fun shown(intervals: List<SkipInterval>, local: String, startMs: Long, endMs: Long): Boolean =
    intervals.any { interval ->
        SkipMarks.canonicalType(interval.type) == local &&
            abs(interval.startTime * 1000.0 - startMs) < 1.0 &&
            abs(interval.endTime * 1000.0 - endMs) < 1.0
    }

private fun slotWire(slot: SyncSlot): JsonVal? {
    val stamp = ClockStamp(slot.hlcWallMs, slot.hlcCounter, slot.hlcDeviceId)
    val record = if (slot.deleted) {
        tombstoneRecord("mark", slot.recordId, stamp)
    } else {
        val wire = wireSegment(slot.segmentLocal) ?: return null
        if (slot.presence == "none") {
            noneMarkRecord(stamp, slot.showId, slot.season?.toLong(), slot.episode?.toLong(), wire, slot.durationMs)
        } else {
            rangeMarkRecord(
                slot.recordId, stamp, slot.showId, slot.season?.toLong(), slot.episode?.toLong(), wire,
                slot.segmentIndex, slot.startMs, slot.endMs, slot.durationMs,
            )
        }
    }
    return record?.wire
}
