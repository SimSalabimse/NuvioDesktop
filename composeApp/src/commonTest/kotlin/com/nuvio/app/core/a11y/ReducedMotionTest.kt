package com.nuvio.app.core.a11y

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReducedMotionTest {
    @AfterTest
    fun reset() {
        ReducedMotion.resetForTest(false)
    }

    @Test
    fun linuxDisablesAnimationsWhenTheSettingIsFalse() {
        assertTrue(prefersReducedMotion("Linux", "false"))
        assertFalse(prefersReducedMotion("Linux", "true"))
        assertFalse(prefersReducedMotion("Linux", null))
    }

    @Test
    fun macAndWindowsReadTheirReducedMotionValues() {
        assertTrue(prefersReducedMotion("Mac OS X", "1"))
        assertFalse(prefersReducedMotion("Mac OS X", "0"))
        assertTrue(prefersReducedMotion("Windows 11", "    MinAnimate    REG_SZ    0"))
        assertFalse(prefersReducedMotion("Windows 11", "    MinAnimate    REG_SZ    1"))
    }

    @Test
    fun durationIsInstantOnlyWhileReducedMotionIsOn() {
        ReducedMotion.resetForTest(false)
        assertEquals(300, ReducedMotion.duration(300))
        ReducedMotion.resetForTest(true)
        assertEquals(0, ReducedMotion.duration(300))
        assertEquals(0, ReducedMotion.duration(260))
    }
}
