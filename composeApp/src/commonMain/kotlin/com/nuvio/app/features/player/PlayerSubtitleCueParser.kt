package com.nuvio.app.features.player

object PlayerSubtitleCueParser {
    private val timestampRegex = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([.,](\d+))?""")

    fun parse(text: String, sourceUrl: String? = null): List<SubtitleSyncCue> {
        val cleanedText = cleanText(text)
        return when (detectSubtitleFormat(sourceUrl, cleanedText)) {
            SubtitleFormatHint.WebVtt -> parseVtt(cleanedText)
            SubtitleFormatHint.Ass -> parseAss(cleanedText)
            SubtitleFormatHint.Ttml -> parseTtml(cleanedText)
            SubtitleFormatHint.Srt -> parseSrt(cleanedText)
        }
    }

    fun parseFromText(rawText: String, sourceUrl: String): List<SubtitleSyncCue> {
        val cleanedText = cleanText(rawText)
        return when (detectSubtitleFormat(sourceUrl, cleanedText)) {
            SubtitleFormatHint.WebVtt -> parseVtt(cleanedText)
            SubtitleFormatHint.Srt -> parseSrt(cleanedText)
            SubtitleFormatHint.Ass, SubtitleFormatHint.Ttml -> emptyList()
        }
    }

    internal fun fileExtension(text: String, sourceUrl: String): String =
        when (detectSubtitleFormat(sourceUrl, cleanText(text))) {
            SubtitleFormatHint.Srt -> "srt"
            SubtitleFormatHint.WebVtt -> "vtt"
            SubtitleFormatHint.Ass -> "ass"
            SubtitleFormatHint.Ttml -> "ttml"
        }

    private fun cleanText(rawText: String): String =
        rawText
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    private enum class SubtitleFormatHint {
        Srt,
        WebVtt,
        Ass,
        Ttml,
    }

    private fun detectSubtitleFormat(sourceUrl: String?, text: String): SubtitleFormatHint {
        val sourcePath = sourceUrl
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.lowercase()
            .orEmpty()
        val sample = text.take(4_096).lowercase()

        return when {
            sourcePath.endsWith(".vtt") || sourcePath.endsWith(".webvtt") || text.trimStart().startsWith("WEBVTT") ->
                SubtitleFormatHint.WebVtt
            sourcePath.endsWith(".ass") || sourcePath.endsWith(".ssa") ||
                (sample.contains("[events]") && sample.contains("dialogue:")) ->
                SubtitleFormatHint.Ass
            sourcePath.endsWith(".ttml") || sourcePath.endsWith(".dfxp") || sourcePath.endsWith(".xml") ||
                Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(text.take(512)) ->
                SubtitleFormatHint.Ttml
            else -> SubtitleFormatHint.Srt
        }
    }

    private fun parseSrt(text: String): List<SubtitleSyncCue> {
        val blocks = text.split(Regex("""\n\s*\n"""))
        val cues = mutableListOf<SubtitleSyncCue>()
        for (block in blocks) {
            val lines = block
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var index = 0
            if (lines[index].all { it.isDigit() } && index + 1 < lines.size) {
                index++
            }
            val timing = lines.getOrNull(index) ?: continue
            if (!timing.contains("-->")) continue
            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timing) ?: continue
            if (endTimeMs - startTimeMs <= 0) continue
            val textLines = lines.drop(index + 1)
            val cueText = normalizeCueText(textLines.joinToString("\n"))
            if (cueText.isBlank()) continue
            cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
        }
        return cues
    }

    private fun parseVtt(text: String): List<SubtitleSyncCue> {
        val lines = text
            .lines()
            .map { it.trimEnd() }

        val cues = mutableListOf<SubtitleSyncCue>()
        var cursor = 0

        while (cursor < lines.size) {
            val line = lines[cursor].trim()
            if (line.isBlank()) {
                cursor++
                continue
            }
            if (line.startsWith("WEBVTT")) {
                cursor++
                continue
            }
            if (isWebVttMetadataBlockHeader(line)) {
                val nextLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                if (nextLine.isEmpty() || !nextLine.contains("-->")) {
                    cursor = skipWebVttBlock(lines, cursor + 1)
                    continue
                }
            }

            var timingLine = line
            var textStart = cursor + 1
            if (!timingLine.contains("-->")) {
                timingLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                textStart = cursor + 2
            }
            if (!timingLine.contains("-->")) {
                cursor++
                continue
            }

            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timingLine) ?: run {
                cursor++
                continue
            }
            if (endTimeMs - startTimeMs <= 0) {
                cursor++
                continue
            }

            val textParts = mutableListOf<String>()
            var i = textStart
            while (i < lines.size && lines[i].isNotBlank()) {
                textParts += lines[i].trim()
                i++
            }
            val cueText = normalizeCueText(textParts.joinToString("\n"))
            if (cueText.isNotBlank()) {
                cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
            }
            cursor = i + 1
        }

        return cues
    }

    private val defaultAssFormatFields = listOf(
        "Layer",
        "Start",
        "End",
        "Style",
        "Name",
        "MarginL",
        "MarginR",
        "MarginV",
        "Effect",
        "Text",
    )

    private fun parseAss(text: String): List<SubtitleSyncCue> {
        var inEventsSection = false
        var formatFields: List<String>? = null

        return text.lines()
            .mapNotNull { rawLine ->
                val line = rawLine.trim()
                when {
                    line.equals("[Events]", ignoreCase = true) -> {
                        inEventsSection = true
                        null
                    }
                    line.startsWith("[") && line.endsWith("]") -> {
                        inEventsSection = false
                        null
                    }
                    inEventsSection && line.startsWith("Format:", ignoreCase = true) -> {
                        formatFields = line.substringAfter(':')
                            .split(',')
                            .map { it.trim() }
                        null
                    }
                    inEventsSection && line.startsWith("Dialogue:", ignoreCase = true) ->
                        parseAssDialogue(line.substringAfter(':'), formatFields)
                    else -> null
                }
            }
            .sortedBy { it.startTimeMs }
    }

    private fun parseAssDialogue(payload: String, formatFields: List<String>?): SubtitleSyncCue? {
        val fields = formatFields.orEmpty()
        val parts = payload
            .split(',', limit = fields.ifEmpty { defaultAssFormatFields }.size)
            .map { it.trim() }
        val startIndex = fields.indexOfField("Start").takeIf { it >= 0 } ?: 1
        val endIndex = fields.indexOfField("End").takeIf { it >= 0 } ?: 2
        val textIndex = fields.indexOfField("Text").takeIf { it >= 0 } ?: 9

        if (parts.size <= startIndex || parts.size <= textIndex) return null
        val start = parseTimestampMs(parts[startIndex]) ?: return null
        val end = parts.getOrNull(endIndex)?.let { parseTimestampMs(it) } ?: (start + 3000L)
        if (end - start <= 0) return null
        val body = normalizeCueText(
            parts[textIndex]
                .replace(Regex("""\{[^}]*}"""), "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
        )
        return if (body.isBlank()) null else SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = body)
    }

    private fun parseTtml(text: String): List<SubtitleSyncCue> =
        Regex("""(?is)<p\b([^>]*)>(.*?)</p>""")
            .findAll(text)
            .mapNotNull { match ->
                val attrs = match.groupValues[1]
                val startRaw = attrs.attributeValue("begin")
                    ?: attrs.attributeValue("start")
                    ?: return@mapNotNull null
                val endRaw = attrs.attributeValue("end")
                val start = parseTtmlTimestamp(startRaw) ?: return@mapNotNull null
                val end = endRaw?.let { parseTtmlTimestamp(it) } ?: (start + 3000L)
                if (end - start <= 0) return@mapNotNull null
                val body = normalizeCueText(
                    match.groupValues[2]
                        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                )
                if (body.isBlank()) null else SubtitleSyncCue(startTimeMs = start, endTimeMs = end, text = body)
            }
            .sortedBy { it.startTimeMs }
            .toList()

    private fun parseTtmlTimestamp(raw: String): Long? {
        val cleaned = raw.trim().substringBefore(' ')
        if (cleaned.isBlank()) return null

        parseClockTimeWithFrames(cleaned)?.let { return it }
        parseTimestampMs(cleaned)?.let { return it }

        val match = Regex("""^([0-9]+(?:\.[0-9]+)?)(ms|h|m|s)$""", RegexOption.IGNORE_CASE)
            .matchEntire(cleaned)
            ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "h" -> 3_600_000.0
            "m" -> 60_000.0
            "s" -> 1_000.0
            "ms" -> 1.0
            else -> return null
        }
        return finiteMillis(value * multiplier)
    }

    private fun parseClockTimeWithFrames(raw: String): Long? {
        val parts = raw.split(':')
        if (parts.size != 4) return null

        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toLongOrNull() ?: return null
        val frames = parts[3].substringBefore('.').toLongOrNull() ?: return null
        if (frames < 0L) return null
        val frameMillis = frames * 1_000L / 30L
        return composeTimestampMs(hours, minutes, seconds, 0L)?.let { base ->
            if (frameMillis > Long.MAX_VALUE - base) null else base + frameMillis
        }
    }

    private fun isWebVttMetadataBlockHeader(line: String): Boolean {
        return line == "STYLE" ||
            line == "REGION" ||
            line == "NOTE" ||
            line.startsWith("NOTE ") ||
            line.startsWith("NOTE\t")
    }

    private fun skipWebVttBlock(lines: List<String>, start: Int): Int {
        var cursor = start
        while (cursor < lines.size && lines[cursor].isNotBlank()) {
            cursor++
        }
        return if (cursor < lines.size) cursor + 1 else cursor
    }

    private fun parseStartEndTimeMs(timingLine: String): Pair<Long, Long>? {
        val parts = timingLine.split("-->")
        if (parts.size != 2) return null
        val startTimeMs = parseTimestampMs(parts[0].trim().substringBefore(' ')) ?: return null
        val endTimeMs = parseTimestampMs(parts[1].trim().substringBefore(' ')) ?: return null
        return startTimeMs to endTimeMs
    }

    private fun parseTimestampMs(rawTimestamp: String): Long? {
        val match = timestampRegex.matchEntire(rawTimestamp.trim()) ?: return null
        val hoursRaw = match.groupValues[1]
        val hours = if (hoursRaw.isEmpty()) 0L else hoursRaw.toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millisRaw = match.groupValues[5]
        val millis = when (millisRaw.length) {
            0 -> 0L
            1 -> "${millisRaw}00".toLong()
            2 -> "${millisRaw}0".toLong()
            else -> millisRaw.take(3).toLongOrNull() ?: return null
        }
        return composeTimestampMs(hours, minutes, seconds, millis)
    }

    /** Milliseconds since the start, or null when the parts do not fit in a signed 64-bit value. */
    private fun composeTimestampMs(hours: Long, minutes: Long, seconds: Long, millis: Long): Long? {
        if (hours < 0L || minutes < 0L || seconds < 0L || millis < 0L) return null
        if (hours > Long.MAX_VALUE / 3_600_000L) return null
        val fromHours = hours * 3_600_000L
        if (minutes > (Long.MAX_VALUE - fromHours) / 60_000L) return null
        val afterMinutes = fromHours + minutes * 60_000L
        if (seconds > (Long.MAX_VALUE - afterMinutes) / 1_000L) return null
        val afterSeconds = afterMinutes + seconds * 1_000L
        if (millis > Long.MAX_VALUE - afterSeconds) return null
        return afterSeconds + millis
    }

    private fun finiteMillis(value: Double): Long? {
        if (!value.isFinite() || value < 0.0 || value > Long.MAX_VALUE.toDouble()) return null
        return value.toLong()
    }

    private fun List<String>.indexOfField(name: String): Int =
        indexOfFirst { it.equals(name, ignoreCase = true) }

    private fun String.attributeValue(name: String): String? =
        Regex("""\b${Regex.escape(name)}\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun normalizeCueText(text: String): String {
        return text
            .replace(Regex("""<(?:\d+:)?\d{1,2}:\d{2}(?:[.,]\d+)?>"""), "")
            .replace(Regex("""</?[a-zA-Z0-9._-]+(?: [^>]*)?>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .lines()
            .map { it.replace(Regex("""[ \t]+"""), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
