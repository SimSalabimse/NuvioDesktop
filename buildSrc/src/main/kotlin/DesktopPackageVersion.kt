object DesktopPackageVersion {
    const val MSI_MAX_MAJOR = 255
    const val MSI_MAX_MINOR = 255
    const val MSI_MAX_BUILD = 65_535
    const val REVISION_STRIDE = 100

    fun map(version: String): String {
        val versionCore = version.substringBefore('-').substringBefore('+').trim()
        val parts = versionCore.split('.').filter { it.isNotBlank() }
        require(parts.size in 1..4) {
            "Desktop package version must use one to four numeric components: $version"
        }
        val numbers = parts.map { part ->
            part.toIntOrNull() ?: error("Desktop package version component is not numeric: $version")
        }
        require(numbers.all { it >= 0 }) {
            "Desktop package version components must not be negative: $version"
        }
        val major = numbers.getOrElse(0) { 0 }.coerceAtLeast(1)
        val minor = numbers.getOrElse(1) { 0 }
        val patch = numbers.getOrElse(2) { 0 }
        val revision = numbers.getOrElse(3) { 0 }
        require(revision < REVISION_STRIDE) {
            "Desktop package revision must be smaller than $REVISION_STRIDE: $version"
        }
        val build = patch * REVISION_STRIDE + revision
        require(major <= MSI_MAX_MAJOR && minor <= MSI_MAX_MINOR && build <= MSI_MAX_BUILD) {
            "Desktop package version is outside MSI ProductVersion limits: $version"
        }
        return "$major.$minor.$build"
    }
}
