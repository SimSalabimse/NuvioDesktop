import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopPackageVersionTest {
    @Test
    fun fourthComponentMapsToDistinctOrderedInstallerVersions() {
        val plain = DesktopPackageVersion.map("0.1.34")
        val first = DesktopPackageVersion.map("0.1.34.1")
        val second = DesktopPackageVersion.map("0.1.34.2")

        assertEquals("1.1.3400", plain)
        assertEquals("1.1.3401", first)
        assertEquals("1.1.3402", second)
        assertNotEquals(plain, first)
        assertNotEquals(first, second)
        assertTrue(compareVersions(plain, first) < 0)
        assertTrue(compareVersions(first, second) < 0)
        assertTrue(compareVersions(second, DesktopPackageVersion.map("0.1.35")) < 0)
        assertWithinMsiLimits(plain)
        assertWithinMsiLimits(first)
        assertWithinMsiLimits(second)
    }

    @Test
    fun prereleaseSuffixDoesNotChangeTheInstallerVersion() {
        assertEquals(
            DesktopPackageVersion.map("0.1.30.3"),
            DesktopPackageVersion.map("0.1.30.3-alpha"),
        )
    }

    @Test
    fun revisionThatWouldCollideWithTheNextPatchIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DesktopPackageVersion.map("0.1.34.100")
        }
    }

    private fun assertWithinMsiLimits(version: String) {
        val parts = version.split('.').map(String::toInt)
        assertEquals(3, parts.size)
        assertTrue(parts[0] in 0..DesktopPackageVersion.MSI_MAX_MAJOR)
        assertTrue(parts[1] in 0..DesktopPackageVersion.MSI_MAX_MINOR)
        assertTrue(parts[2] in 0..DesktopPackageVersion.MSI_MAX_BUILD)
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map(String::toInt)
        val rightParts = right.split('.').map(String::toInt)
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val compared = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
            if (compared != 0) return compared
        }
        return 0
    }
}
