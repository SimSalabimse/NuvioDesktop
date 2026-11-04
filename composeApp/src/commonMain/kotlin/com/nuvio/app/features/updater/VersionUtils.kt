package com.nuvio.app.features.updater

internal data class SemanticVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>,
    val extra: List<Long> = emptyList(),
) : Comparable<SemanticVersion> {
    val numbers: List<Long> = listOf(major, minor, patch) + extra

    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        val extraSize = maxOf(extra.size, other.extra.size)
        for (index in 0 until extraSize) {
            compareValues(
                extra.getOrElse(index) { 0L },
                other.extra.getOrElse(index) { 0L },
            ).takeIf { it != 0 }?.let { return it }
        }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val sharedSize = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until sharedSize) {
            comparePrereleaseIdentifier(
                prerelease[index],
                other.prerelease[index]
            ).takeIf { it != 0 }?.let { return it }
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    private fun comparePrereleaseIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)

        if (leftNumeric && rightNumeric) {
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            compareValues(normalizedLeft.length, normalizedRight.length)
                .takeIf { it != 0 }
                ?.let { return it }
            return normalizedLeft.compareTo(normalizedRight)
        }
        if (leftNumeric) return -1
        if (rightNumeric) return 1
        return left.compareTo(right)
    }
}

internal object VersionUtils {
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parse(raw: String?): SemanticVersion? {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return null
        val withoutBuild = normalized.substringBefore('+')
        if (withoutBuild.isEmpty()) return null

        val hyphen = withoutBuild.indexOf('-')
        val core: String
        val prereleaseText: String
        if (hyphen < 0) {
            core = withoutBuild
            prereleaseText = ""
        } else {
            core = withoutBuild.substring(0, hyphen)
            prereleaseText = withoutBuild.substring(hyphen + 1)
            if (prereleaseText.isEmpty()) return null
        }

        val numberTokens = core.split('.')
        if (numberTokens.size < 3) return null
        val numbers = numberTokens.map { token ->
            if (token.isEmpty() || token.any { !it.isDigit() }) return null
            token.toLongOrNull() ?: return null
        }
        val prerelease = if (prereleaseText.isEmpty()) {
            emptyList()
        } else {
            prereleaseText.split('.').also { identifiers ->
                if (identifiers.any { identifier ->
                        identifier.isEmpty() || identifier.any { character ->
                            !character.isLetterOrDigit() && character != '-'
                        }
                    }
                ) {
                    return null
                }
            }
        }
        return SemanticVersion(
            major = numbers[0],
            minor = numbers[1],
            patch = numbers[2],
            extra = numbers.drop(3),
            prerelease = prerelease,
        )
    }

    fun isPrerelease(raw: String?): Boolean = parse(raw)?.prerelease?.isNotEmpty() == true

    /**
     * A remote build is newer only when it is strictly greater and it is on the
     * same numbering line. Fork builds add a fourth numeric part (0.1.30.3-alpha).
     * A shorter tag such as upstream 0.1.34 is a different line and is not an update,
     * even though 34 is greater than 30.
     */
    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteVersion = parse(remote) ?: return false
        val localVersion = parse(local) ?: return false
        if (remoteVersion.numbers.size < localVersion.numbers.size) return false
        return remoteVersion > localVersion
    }

    // Desktop historically compares numeric version components and falls back to
    // treating a changed, non-empty release label as a new release.
    fun isRemoteNewerLegacy(remote: String?, local: String?): Boolean {
        val remoteParts = parseLegacyVersionParts(remote)
        val localParts = parseLegacyVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val remoteValue = normalize(remote)
            val localValue = normalize(local)
            return remoteValue.isNotBlank() && localValue.isNotBlank() && remoteValue != localValue
        }

        val maxSize = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until maxSize) {
            val remoteValue = remoteParts.getOrElse(index) { 0 }
            val localValue = localParts.getOrElse(index) { 0 }
            if (remoteValue != localValue) return remoteValue > localValue
        }
        return false
    }

    private fun parseLegacyVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile(Char::isDigit).toIntOrNull() }
        return parts.takeIf { it.isNotEmpty() }
    }
}
