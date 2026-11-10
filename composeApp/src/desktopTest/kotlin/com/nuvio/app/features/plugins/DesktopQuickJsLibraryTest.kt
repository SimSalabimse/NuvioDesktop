package com.nuvio.app.features.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopQuickJsLibraryTest {
    @Test
    fun libraryPathStaysInstalledAfterASecondCall() {
        configureDesktopQuickJsLibrary()
        val path = System.getProperty("com.dokar.quickjs.library.path")
        assertTrue(!path.isNullOrBlank())
        assertTrue(File(path, "libquickjs.so").isFile)
        configureDesktopQuickJsLibrary()
        assertEquals(path, System.getProperty("com.dokar.quickjs.library.path"))
    }
}
