package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScanSettingsRulesTest {

    @Test
    fun `default scan settings are whole device with thirty second minimum`() {
        val settings = LibraryScanSettingsRules.normalize(LibraryScanSettings())

        assertEquals(LibraryScanMode.WHOLE_DEVICE, settings.scanMode)
        assertEquals(emptyList<String>(), settings.selectedFolderUris)
        assertEquals(30, settings.minimumTrackDurationSeconds)
    }

    @Test
    fun `duration is clamped from one to sixty seconds`() {
        assertEquals(1, LibraryScanSettingsRules.clampMinimumTrackDurationSeconds(0))
        assertEquals(1, LibraryScanSettingsRules.clampMinimumTrackDurationSeconds(-4))
        assertEquals(60, LibraryScanSettingsRules.clampMinimumTrackDurationSeconds(61))
        assertEquals(45, LibraryScanSettingsRules.clampMinimumTrackDurationSeconds(45))
    }

    @Test
    fun `adding selected folder prevents duplicate uris`() {
        val settings = LibraryScanSettings(
            selectedFolderUris = listOf("content://music"),
        )

        val updated = LibraryScanSettingsRules
            .withAddedFolderUri(settings, " content://music ")

        assertEquals(listOf("content://music"), updated.selectedFolderUris)
    }

    @Test
    fun `remove selected folder deletes matching uri`() {
        val settings = LibraryScanSettings(
            selectedFolderUris = listOf("content://music", "content://downloads"),
        )

        val updated = LibraryScanSettingsRules
            .withRemovedFolderUri(settings, "content://music")

        assertEquals(listOf("content://downloads"), updated.selectedFolderUris)
    }

    @Test
    fun `scan mode switching keeps selected folders stored`() {
        val settings = LibraryScanSettings(
            scanMode = LibraryScanMode.WHOLE_DEVICE,
            selectedFolderUris = listOf("content://music"),
        )

        val updated = LibraryScanSettingsRules
            .withScanMode(settings, LibraryScanMode.SELECTED_FOLDERS)

        assertEquals(LibraryScanMode.SELECTED_FOLDERS, updated.scanMode)
        assertEquals(listOf("content://music"), updated.selectedFolderUris)
    }

    @Test
    fun `duration filtering excludes shorter audio`() {
        val settings = LibraryScanSettings(minimumTrackDurationSeconds = 30)
        val songs = listOf(
            song(id = 1, duration = 29_999L),
            song(id = 2, duration = 30_000L),
            song(id = 3, duration = 60_000L),
        )

        val filtered = LibraryScanSettingsRules.filterSongsForScanSettings(songs, settings)

        assertEquals(listOf(2L, 3L), filtered.map { it.id })
    }

    @Test
    fun `selected folder filtering keeps only matched folder songs`() {
        val settings = LibraryScanSettings(
            scanMode = LibraryScanMode.SELECTED_FOLDERS,
            selectedFolderUris = listOf(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRock",
            ),
            minimumTrackDurationSeconds = 1,
        )
        val songs = listOf(
            song(id = 1, duration = 120_000L, folderPath = "Music/Rock", folderName = "Rock"),
            song(id = 2, duration = 120_000L, folderPath = "Music/Jazz", folderName = "Jazz"),
        )

        val filtered = LibraryScanSettingsRules.filterSongsForScanSettings(songs, settings)

        assertEquals(listOf(1L), filtered.map { it.id })
    }

    private fun song(
        id: Long,
        duration: Long,
        folderPath: String? = null,
        folderName: String? = null,
    ) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = duration,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
        folderPath = folderPath,
        folderName = folderName,
    )
}
