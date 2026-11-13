package com.nuvio.app.features.analyzeddetails

/**
 * Canonical JSON for sync plaintext. Object keys sort by UTF-16 code unit,
 * the same order as JavaScript `JSON.stringify` key ordering and Kotlin
 * `String.compareTo`. Integers only, no spaces.
 */
internal sealed interface JsonVal {
    data object Null : JsonVal
    data class Bool(val value: Boolean) : JsonVal
    data class Num(val value: Long) : JsonVal
    data class Str(val value: String) : JsonVal
    data class Arr(val value: List<JsonVal>) : JsonVal
    data class Obj(val value: Map<String, JsonVal>) : JsonVal
}

internal const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L

internal fun jObj(vararg entries: Pair<String, JsonVal>): JsonVal.Obj =
    JsonVal.Obj(linkedMapOf(*entries))

internal fun canonicalJson(value: JsonVal): String = buildString { appendCanonical(value) }

internal fun parseJson(text: String): JsonVal {
    val parser = JsonParser(text)
    val value = parser.parseValue()
    parser.skipWs()
    if (!parser.eof()) parser.fail()
    return value
}

internal fun parseJsonOrNull(text: String): JsonVal? =
    try {
        parseJson(text)
    } catch (_: JsonParseException) {
        null
    }

internal class JsonParseException : Exception()

private fun StringBuilder.appendCanonical(value: JsonVal) {
    when (value) {
        JsonVal.Null -> append("null")
        is JsonVal.Bool -> append(if (value.value) "true" else "false")
        is JsonVal.Num -> append(value.value.toString())
        is JsonVal.Str -> append(jsonString(value.value))
        is JsonVal.Arr -> {
            append('[')
            value.value.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendCanonical(item)
            }
            append(']')
        }
        is JsonVal.Obj -> {
            append('{')
            val keys = value.value.keys.sorted()
            keys.forEachIndexed { index, key ->
                if (index > 0) append(',')
                append(jsonString(key))
                append(':')
                appendCanonical(value.value.getValue(key))
            }
            append('}')
        }
    }
}

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun eof(): Boolean = index >= text.length

    fun fail(): Nothing = throw JsonParseException()

    fun skipWs() {
        while (!eof() && text[index].isWhitespace()) index++
    }

    fun parseValue(): JsonVal {
        skipWs()
        if (eof()) fail()
        return when (val ch = text[index]) {
            'n' -> literal("null", JsonVal.Null)
            't' -> literal("true", JsonVal.Bool(true))
            'f' -> literal("false", JsonVal.Bool(false))
            '"' -> JsonVal.Str(parseString())
            '[' -> parseArray()
            '{' -> parseObject()
            else -> if (ch == '-' || ch.isDigit()) parseNumber() else fail()
        }
    }

    private fun literal(token: String, value: JsonVal): JsonVal {
        if (!text.startsWith(token, index)) fail()
        index += token.length
        return value
    }

    private fun parseArray(): JsonVal.Arr {
        index++
        val items = mutableListOf<JsonVal>()
        skipWs()
        if (!eof() && text[index] == ']') {
            index++
            return JsonVal.Arr(items)
        }
        while (true) {
            items += parseValue()
            skipWs()
            if (eof()) fail()
            when (text[index]) {
                ']' -> {
                    index++
                    return JsonVal.Arr(items)
                }
                ',' -> index++
                else -> fail()
            }
        }
    }

    private fun parseObject(): JsonVal.Obj {
        index++
        val fields = linkedMapOf<String, JsonVal>()
        skipWs()
        if (!eof() && text[index] == '}') {
            index++
            return JsonVal.Obj(fields)
        }
        while (true) {
            skipWs()
            if (eof() || text[index] != '"') fail()
            val key = parseString()
            skipWs()
            if (eof() || text[index] != ':') fail()
            index++
            fields[key] = parseValue()
            skipWs()
            if (eof()) fail()
            when (text[index]) {
                '}' -> {
                    index++
                    return JsonVal.Obj(fields)
                }
                ',' -> index++
                else -> fail()
            }
        }
    }

    private fun parseString(): String {
        index++
        val out = StringBuilder()
        while (!eof()) {
            val ch = text[index++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (eof()) fail()
                    when (val esc = text[index++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail()
                            val hex = text.substring(index, index + 4)
                            index += 4
                            val code = hex.toIntOrNull(16) ?: fail()
                            out.append(code.toChar())
                        }
                        else -> fail()
                    }
                }
                else -> if (ch.code < 0x20) fail() else out.append(ch)
            }
        }
        fail()
    }

    private fun parseNumber(): JsonVal.Num {
        val start = index
        if (text[index] == '-') index++
        if (eof() || !text[index].isDigit()) fail()
        if (text[index] == '0') {
            index++
            if (!eof() && text[index].isDigit()) fail()
        } else {
            while (!eof() && text[index].isDigit()) index++
        }
        if (!eof() && text[index] in ".eE+") fail()
        val token = text.substring(start, index)
        val value = token.toLongOrNull() ?: fail()
        if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) fail()
        return JsonVal.Num(value)
    }
}
