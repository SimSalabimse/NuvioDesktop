package com.nuvio.app.core.build

import kotlin.test.Test
import kotlin.test.assertEquals

class AppVersionLabelTest {
    @Test
    fun releaseLabelIsNameAndCode() {
        assertEquals("0.1.26-alpha (26)", appVersionDetail("0.1.26-alpha", 26))
    }

    @Test
    fun currentDetailUsesTheDesktopVersion() {
        val detail = currentAppVersionDetail()
        assertEquals(
            "${AppVersionConfig.DESKTOP_VERSION_NAME} (${AppVersionConfig.DESKTOP_VERSION_CODE})",
            detail.substringBefore(" · "),
        )
        assertEquals(
            false,
            detail.startsWith("${AppVersionConfig.VERSION_NAME} (${AppVersionConfig.VERSION_CODE})"),
        )
    }

    @Test
    fun gitRevisionIsAppended() {
        assertEquals(
            "0.1.26-alpha (26) · abc1234",
            appVersionDetail("0.1.26-alpha", 26, gitRevision = "ABC1234"),
        )
    }

    @Test
    fun dirtyTreeMarksTheRevision() {
        assertEquals(
            "0.1.26-alpha (26) · abc1234*",
            appVersionDetail("0.1.26-alpha", 26, gitRevision = " abc1234 ", gitDirty = true),
        )
    }

    @Test
    fun invalidRevisionIsOmitted() {
        assertEquals(
            "0.1.26-alpha (26)",
            appVersionDetail("0.1.26-alpha", 26, gitRevision = "not-a-commit", gitDirty = true),
        )
        assertEquals("", appVersionRevisionSuffix("   ", gitDirty = true))
        assertEquals("", appVersionRevisionSuffix("abc", gitDirty = true))
        assertEquals(" · abcd", appVersionRevisionSuffix("ABCD", gitDirty = false))
    }
}
