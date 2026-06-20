package com.launchpoint.wavdrop.ui.screen.home

import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.LibraryScanSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderModeNeedsSelectionTest {

    @Test
    fun `selected folders mode with no uris returns true`() {
        val settings = LibraryScanSettings(
            scanMode = LibraryScanMode.SELECTED_FOLDERS,
            selectedFolderUris = emptyList(),
        )
        assertTrue(isFolderModeNeedsSelection(settings))
    }

    @Test
    fun `selected folders mode with one uri returns false`() {
        val settings = LibraryScanSettings(
            scanMode = LibraryScanMode.SELECTED_FOLDERS,
            selectedFolderUris = listOf("content://com.android.externalstorage/Music"),
        )
        assertFalse(isFolderModeNeedsSelection(settings))
    }

    @Test
    fun `whole device mode with no uris returns false`() {
        val settings = LibraryScanSettings(
            scanMode = LibraryScanMode.WHOLE_DEVICE,
            selectedFolderUris = emptyList(),
        )
        assertFalse(isFolderModeNeedsSelection(settings))
    }
}
