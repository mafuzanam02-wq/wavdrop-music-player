package com.launchpoint.wavdrop.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSaveValidatorTest {

    @Test
    fun `manual export read-back validation succeeds for parser-valid backup`() {
        val json = WavdropBackupExporter.toJson(minimalBackup())

        assertTrue(BackupSaveValidator.isSavedBackupValid(json))
    }

    @Test
    fun `manual export read-back validation fails for damaged backup`() {
        val json = WavdropBackupExporter.toJson(minimalBackup())
        val damaged = JSONObject(json).apply {
            getJSONObject("manifest").put("songCount", 99)
        }.toString(2)

        assertFalse(BackupSaveValidator.isSavedBackupValid(damaged))
        assertEquals(
            "Backup could not be verified after saving. Try a different location.",
            BackupSaveValidator.VALIDATION_FAILED_MESSAGE,
        )
    }

    @Test
    fun `manual export read-back validation fails for blank content`() {
        assertFalse(BackupSaveValidator.isSavedBackupValid(""))
        assertFalse(BackupSaveValidator.isSavedBackupValid(null))
    }

    private fun minimalBackup() = WavdropBackup(
        exportedAt = "2026-06-11T10:00:00Z",
        songs = listOf(
            BackupSong(
                id = 1L,
                uri = "content://media/1",
                title = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                duration = 180_000L,
                dateAdded = 0L,
                trackNumber = 1,
                year = 2026,
            ),
        ),
        trackStats = emptyList(),
        importBaselines = emptyList(),
    )
}
