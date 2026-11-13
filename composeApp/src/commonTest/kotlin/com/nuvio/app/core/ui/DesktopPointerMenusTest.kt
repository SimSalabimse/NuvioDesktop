package com.nuvio.app.core.ui

import com.nuvio.app.features.downloads.DownloadPointerAction
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.downloadPointerActions
import com.nuvio.app.features.downloads.downloadsListWidthDp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPointerMenusTest {
    @Test
    fun pointerMenuOpensAtThePointerAndFlipsNearTheEdge() {
        assertEquals(240, positionPointerMenuAxis(pointer = 240, popupExtent = 200, windowExtent = 1000, edgeMargin = 8))
        assertEquals(760, positionPointerMenuAxis(pointer = 960, popupExtent = 200, windowExtent = 1000, edgeMargin = 8))
        assertEquals(8, positionPointerMenuAxis(pointer = 2, popupExtent = 200, windowExtent = 1000, edgeMargin = 8))
        assertEquals(8, positionPointerMenuAxis(pointer = 50, popupExtent = 1200, windowExtent = 1000, edgeMargin = 8))
    }

    @Test
    fun continueWatchingMenuKeepsTheLiftActions() {
        assertEquals(
            listOf(
                ContinueWatchingPointerAction.Details,
                ContinueWatchingPointerAction.PlayManually,
                ContinueWatchingPointerAction.StartFromBeginning,
                ContinueWatchingPointerAction.Remove,
            ),
            continueWatchingPointerActions(
                showDetails = true,
                showManualPlay = true,
                showStartFromBeginning = true,
            ),
        )
        assertEquals(
            listOf(ContinueWatchingPointerAction.Remove),
            continueWatchingPointerActions(
                showDetails = false,
                showManualPlay = false,
                showStartFromBeginning = false,
            ),
        )
        assertEquals(
            listOf(
                ContinueWatchingPointerAction.Details,
                ContinueWatchingPointerAction.Remove,
            ),
            continueWatchingPointerActions(
                showDetails = true,
                showManualPlay = false,
                showStartFromBeginning = false,
            ),
        )
    }

    @Test
    fun desktopSheetsAreCenteredDialogsWithoutADragHandle() {
        assertEquals(NuvioSheetPresentation.CenteredDialog, nuvioSheetPresentation(desktop = true))
        assertEquals(NuvioSheetPresentation.BottomSheet, nuvioSheetPresentation(desktop = false))
        assertFalse(nuvioSheetShowsDragHandle(desktop = true, requested = true))
        assertTrue(nuvioSheetShowsDragHandle(desktop = false, requested = true))
        assertFalse(nuvioSheetShowsDragHandle(desktop = false, requested = false))
        assertEquals(520, centeredSheetWidthPx(windowWidthPx = 1600, sheetMaxWidthPx = 520, horizontalMarginPx = 24))
        assertEquals(352, centeredSheetWidthPx(windowWidthPx = 400, sheetMaxWidthPx = 520, horizontalMarginPx = 24))
        assertEquals(40, centeredSheetWidthPx(windowWidthPx = 40, sheetMaxWidthPx = 520, horizontalMarginPx = 24))
        assertEquals(920, centeredSheetMaxHeightPx(windowHeightPx = 1000, fullHeight = true))
        assertEquals(900, centeredSheetMaxHeightPx(windowHeightPx = 1000, fullHeight = false))
    }

    @Test
    fun downloadsColumnCapsWideWindowsAndRowsExposeTheirOwnActions() {
        assertEquals(720, downloadsListWidthDp(1600))
        assertEquals(480, downloadsListWidthDp(480))
        assertEquals(0, downloadsListWidthDp(-20))
        assertEquals(
            listOf(DownloadPointerAction.Pause, DownloadPointerAction.Delete),
            downloadPointerActions(DownloadStatus.Downloading),
        )
        assertEquals(
            listOf(DownloadPointerAction.Resume, DownloadPointerAction.Delete),
            downloadPointerActions(DownloadStatus.Paused),
        )
        assertEquals(
            listOf(DownloadPointerAction.Retry, DownloadPointerAction.Delete),
            downloadPointerActions(DownloadStatus.Failed),
        )
        assertEquals(
            listOf(DownloadPointerAction.Play, DownloadPointerAction.Delete),
            downloadPointerActions(DownloadStatus.Completed),
        )
    }
}
