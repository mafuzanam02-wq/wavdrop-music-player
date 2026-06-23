package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanInstallRecoveryOrchestratorTest {

    @Test
    fun `whole-library recovery scans before applying exactly once`() = runBlocking {
        val calls = mutableListOf<String>()
        var applyCount = 0

        val result = CleanInstallRecoveryOrchestrator.scanThenApply(
            scanLibrary = { calls += "scan" },
            applyImport = {
                calls += "apply"
                applyCount++
                "done"
            },
        )

        assertEquals(listOf("scan", "apply"), calls)
        assertEquals(1, applyCount)
        assertEquals("done", result)
    }

    @Test
    fun `scan failure never applies history`() {
        var applied = false

        runCatching {
            runBlocking {
                CleanInstallRecoveryOrchestrator.scanThenApply(
                    scanLibrary = { error("scan failed") },
                    applyImport = {
                        applied = true
                        Unit
                    },
                )
            }
        }

        assertFalse(applied)
    }

    @Test
    fun `missing music permission pauses before scan`() {
        assertEquals(
            CleanInstallRecoveryOrchestrator.NextStep.REQUEST_MUSIC_PERMISSION,
            CleanInstallRecoveryOrchestrator.nextStep(
                hasMusicPermission = false,
                needsFolderReselection = false,
            ),
        )
    }

    @Test
    fun `selected folders pause for reselection after permission`() {
        assertEquals(
            CleanInstallRecoveryOrchestrator.NextStep.REQUEST_FOLDER_SELECTION,
            CleanInstallRecoveryOrchestrator.nextStep(
                hasMusicPermission = true,
                needsFolderReselection = true,
            ),
        )
    }

    @Test
    fun `whole library proceeds to scan when permission is ready`() {
        assertEquals(
            CleanInstallRecoveryOrchestrator.NextStep.SCAN_LIBRARY,
            CleanInstallRecoveryOrchestrator.nextStep(
                hasMusicPermission = true,
                needsFolderReselection = false,
            ),
        )
    }

    @Test
    fun `selected folder backup never treats old URI as reusable`() {
        val preferences = preferences(
            scanMode = LibraryScanMode.SELECTED_FOLDERS.name,
            selectedFolderUris = listOf("content://old-device/tree/Music"),
        )

        assertTrue(CleanInstallRecoveryPolicy.requiresFolderReselection(preferences))
    }

    @Test
    fun `playback mode and device settings are visibly classified as not restored`() {
        val skipped = CleanInstallRecoveryOrchestrator.notRestoredOnThisDevice

        assertTrue("Shuffle and repeat" in skipped)
        assertTrue("Queue and playback position" in skipped)
        assertTrue("Equalizer" in skipped)
        assertTrue("Previous music-folder permissions" in skipped)
        assertTrue("Automatic-backup destination" in skipped)
    }

    @Test
    fun `backup preferences do not export raw EQ or auto-backup destination fields`() {
        val names = BackupPreferences::class.java.declaredFields.map { it.name }.toSet()

        assertFalse("eqPlatformPresetIndex" in names)
        assertFalse("eqPresetType" in names)
        assertFalse("autoBackupFolderUri" in names)
        assertFalse("queueSongIds" in names)
        assertFalse("currentPlaybackPosition" in names)
    }

    private fun preferences(
        scanMode: String?,
        selectedFolderUris: List<String>?,
    ) = BackupPreferences(
        startupDestination = null,
        mostPlayedPeriod = null,
        mostPlayedLimit = null,
        homeVisibleSections = null,
        scanMode = scanMode,
        selectedFolderUris = selectedFolderUris,
        minimumTrackDurationSeconds = null,
    )
}
