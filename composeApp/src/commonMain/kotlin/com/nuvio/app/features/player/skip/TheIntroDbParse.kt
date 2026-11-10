package com.nuvio.app.features.player.skip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads a TheIntroDB `/media` body. A bad field is skipped. The whole body is rejected
 * only when it is not a JSON object, so one odd segment does not drop the others.
 */
internal fun parseTheIntroDbMedia(text: String): TheIntroDbMediaResponse? {
    val root = text.toJsonObjectOrNull() ?: return null
    return TheIntroDbMediaResponse(
        tmdbId = root.flexibleLong("tmdb_id")?.toIntInRange(),
        type = root.flexibleString("type"),
        season = root.flexibleLong("season")?.toIntInRange(),
        episode = root.flexibleLong("episode")?.toIntInRange(),
        intro = root.segments("intro"),
        recap = root.segments("recap"),
        credits = root.segments("credits"),
        preview = root.segments("preview"),
    )
}

/** Reads a TheIntroDB submissions page. A bad row is skipped. */
internal fun parseTheIntroDbSubmissions(text: String): TheIntroDbSubmissionsPage? {
    val root = text.toJsonObjectOrNull() ?: return null
    val submissions = when (val raw = root["submissions"]) {
        null, is JsonNull -> emptyList()
        is JsonArray -> raw.mapNotNull { it.toSubmissionOrNull() }
        else -> emptyList()
    }
    return TheIntroDbSubmissionsPage(
        submissions = submissions,
        total = root.flexibleLong("total")?.toIntInRange() ?: 0,
        limit = root.flexibleLong("limit")?.toIntInRange() ?: 0,
        offset = root.flexibleLong("offset")?.toIntInRange() ?: 0,
    )
}

private fun String.toJsonObjectOrNull(): JsonObject? =
    try {
        Json.parseToJsonElement(this) as? JsonObject
    } catch (_: Exception) {
        null
    }

private fun JsonObject.segments(name: String): List<TheIntroDbSegment>? {
    val raw = this[name] ?: return null
    val elements = when (raw) {
        is JsonNull -> return null
        is JsonArray -> raw
        is JsonObject -> JsonArray(listOf(raw))
        else -> return emptyList()
    }
    return elements.mapNotNull { it.toSegmentOrNull() }
}

private fun JsonElement.toSegmentOrNull(): TheIntroDbSegment? {
    val obj = this as? JsonObject ?: return null
    val start = obj.millisField("start_ms", "start_sec")
    val end = obj.millisField("end_ms", "end_sec")
    if (start is FieldRead.Invalid || end is FieldRead.Invalid) return null
    return TheIntroDbSegment(
        startMs = (start as? FieldRead.Value)?.millis,
        endMs = (end as? FieldRead.Value)?.millis,
        confidence = obj.flexibleDouble("confidence"),
        submissionCount = obj.flexibleLong("submission_count")?.toIntInRange(),
    )
}

private sealed class FieldRead {
    data class Value(val millis: Long) : FieldRead()
    data object AbsentOrNull : FieldRead()
    data object Invalid : FieldRead()
}

private fun JsonObject.millisField(msName: String, secName: String): FieldRead {
    val milliseconds = this[msName]
    if (milliseconds != null && milliseconds !is JsonNull) {
        return flexibleLong(msName)?.let { FieldRead.Value(it) } ?: FieldRead.Invalid
    }
    val seconds = this[secName]
    if (seconds != null && seconds !is JsonNull) {
        return secondsAsMillis(secName)?.let { FieldRead.Value(it) } ?: FieldRead.Invalid
    }
    return FieldRead.AbsentOrNull
}

private fun JsonElement.toSubmissionOrNull(): TheIntroDbUserSubmission? {
    val obj = this as? JsonObject ?: return null
    return TheIntroDbUserSubmission(
        id = obj.flexibleString("id"),
        tmdbId = (obj.flexibleLong("tmdb_id") ?: obj.flexibleLong("tmdbId"))?.toIntInRange(),
        type = obj.flexibleString("type"),
        season = obj.flexibleLong("season")?.toIntInRange(),
        episode = obj.flexibleLong("episode")?.toIntInRange(),
        segment = obj.flexibleString("segment"),
        status = obj.flexibleString("status"),
        startMs = obj.flexibleLong("start_ms") ?: obj.flexibleLong("startMs"),
        endMs = obj.flexibleLong("end_ms") ?: obj.flexibleLong("endMs"),
        videoDurationMs = obj.flexibleLong("video_duration_ms") ?: obj.flexibleLong("videoDurationMs"),
    )
}

private fun JsonObject.secondsAsMillis(name: String): Long? {
    val seconds = flexibleDouble(name) ?: return null
    val millis = seconds * 1_000.0
    if (!millis.isFinite() || millis < Long.MIN_VALUE.toDouble() || millis > Long.MAX_VALUE.toDouble()) return null
    return millis.toLong()
}

private fun JsonObject.flexibleString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.flexibleLong(name: String): Long? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    primitive.longOrNull?.let { return it }
    primitive.content.toLongOrNull()?.let { return it }
    val asDouble = primitive.doubleOrNull ?: primitive.content.toDoubleOrNull() ?: return null
    if (!asDouble.isFinite() || asDouble < Long.MIN_VALUE.toDouble() || asDouble > Long.MAX_VALUE.toDouble()) {
        return null
    }
    return asDouble.toLong()
}

private fun JsonObject.flexibleDouble(name: String): Double? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val value = primitive.doubleOrNull ?: primitive.content.toDoubleOrNull() ?: return null
    return value.takeIf { it.isFinite() }
}

private fun Long.toIntInRange(): Int? =
    if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null
