package com.launchpoint.wavdrop.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreWarningsTest {

    @Test
    fun `selected folder restore reports device permission warning`() {
        val prefs = basePreferences().copy(
            selectedFolderUris = listOf("content://tree/music"),
        )

        assertEquals(
            "Some library folders may need to be selected again on this device.",
            BackupRestoreWarnings.selectedFolderPermissionWarning(prefs),
        )
    }

    @Test
    fun `blank selected folder entries do not report warning`() {
        val prefs = basePreferences().copy(selectedFolderUris = listOf(" ", ""))

        assertNull(BackupRestoreWarnings.selectedFolderPermissionWarning(prefs))
    }

    @Test
    fun `legacy backup without selected folders does not report warning`() {
        assertNull(BackupRestoreWarnings.selectedFolderPermissionWarning(basePreferences()))
        assertNull(BackupRestoreWarnings.selectedFolderPermissionWarning(null))
    }

    @Test
    fun `restore result can report partial settings warning without losing data result`() {
        val result = WavdropBackupImportApplyResult(
            matchedTracks = 1,
            unmatchedTracks = 0,
            statsUpdated = 1,
            dataRestored = true,
            preferencesRestored = false,
            warnings = listOf(BackupRestoreWarnings.SETTINGS_PARTIAL),
        )

        assertTrue(result.dataRestored)
        assertEquals(
            listOf("Some settings could not be restored. Your music data was restored."),
            result.warnings,
        )
    }

    @Test
    fun `launcher icon restore result is represented separately from other settings`() {
        val result = WavdropBackupImportApplyResult(
            matchedTracks = 0,
            unmatchedTracks = 0,
            statsUpdated = 0,
            preferencesRestored = true,
            launcherIconRestored = true,
        )

        assertTrue(result.preferencesRestored)
        assertTrue(result.launcherIconRestored)
    }

    private fun basePreferences() = BackupPreferences(
        startupDestination = null,
        mostPlayedPeriod = null,
        mostPlayedLimit = null,
        homeVisibleSections = null,
        scanMode = null,
        selectedFolderUris = null,
        minimumTrackDurationSeconds = null,
    )
}
