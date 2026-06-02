package com.launchpoint.wavdrop.data.library

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderGrouperTest {

    @Test
    fun `group creates one summary per folder key`() {
        val songs = listOf(
            song(1, "Track A", folderPath = "Music/Rock", folderName = "Rock"),
            song(2, "Track B", folderPath = "Music/Rock", folderName = "Rock"),
            song(3, "Track C", folderPath = "Download", folderName = "Download"),
        )

        val folders = FolderGrouper.groupSongsByFolder(songs)

        assertEquals(2, folders.size)
        assertEquals(2, folders.first { it.folderKey == "Music/Rock" }.songCount)
    }

    @Test
    fun `unknown folder fallback is used when no folder path exists`() {
        val folders = FolderGrouper.groupSongsByFolder(listOf(song(1, "Track A")))

        assertEquals(FolderGrouper.UNKNOWN_FOLDER, folders.single().folderKey)
        assertEquals(FolderGrouper.UNKNOWN_FOLDER, folders.single().displayName)
    }

    @Test
    fun `folder display name uses folderName when available`() {
        val folders = FolderGrouper.groupSongsByFolder(
            listOf(song(1, "Track A", folderPath = "Music/WhatsApp Audio", folderName = "WhatsApp Audio"))
        )

        assertEquals("WhatsApp Audio", folders.single().displayName)
    }

    @Test
    fun `folder display name derives from folder key when folderName is blank`() {
        val folders = FolderGrouper.groupSongsByFolder(
            listOf(song(1, "Track A", folderPath = "Music/Artist Folder", folderName = " "))
        )

        assertEquals("Artist Folder", folders.single().displayName)
    }

    @Test
    fun `folder path is trimmed before grouping`() {
        val songs = listOf(
            song(1, "Track A", folderPath = " /storage/emulated/0/Music/ "),
            song(2, "Track B", folderPath = "storage/emulated/0/Music"),
        )

        assertEquals(1, FolderGrouper.groupSongsByFolder(songs).size)
    }

    @Test
    fun `song count and total duration are summed`() {
        val folders = FolderGrouper.groupSongsByFolder(
            listOf(
                song(1, "Track A", duration = 100_000L, folderPath = "Music", folderName = "Music"),
                song(2, "Track B", duration = 250_000L, folderPath = "Music", folderName = "Music"),
            )
        )

        assertEquals(2, folders.single().songCount)
        assertEquals(350_000L, folders.single().totalDurationMs)
    }

    @Test
    fun `group results sort by display name`() {
        val folders = FolderGrouper.groupSongsByFolder(
            listOf(
                song(1, "Track A", folderPath = "zeta", folderName = "zeta"),
                song(2, "Track B", folderPath = "Alpha", folderName = "Alpha"),
                song(3, "Track C", folderPath = "middle", folderName = "middle"),
            )
        )

        assertEquals(listOf("Alpha", "middle", "zeta"), folders.map { it.displayName })
    }

    @Test
    fun `songsForFolder returns only songs in selected folder`() {
        val songs = listOf(
            song(1, "Beta", folderPath = "Music", folderName = "Music"),
            song(2, "Alpha", folderPath = "Music", folderName = "Music"),
            song(3, "Other", folderPath = "Download", folderName = "Download"),
        )

        val folderSongs = FolderGrouper.songsForFolder(songs, "Music")

        assertEquals(listOf("Alpha", "Beta"), folderSongs.map { it.title })
    }

    @Test
    fun `songsForFolder supports unknown folder`() {
        val songs = listOf(
            song(1, "Known", folderPath = "Music", folderName = "Music"),
            song(2, "Unknown"),
        )

        assertEquals(listOf("Unknown"), FolderGrouper.songsForFolder(songs, FolderGrouper.UNKNOWN_FOLDER).map { it.title })
    }

    private fun song(
        id: Long,
        title: String,
        duration: Long = 200_000L,
        folderPath: String? = null,
        folderName: String? = null,
    ) = Song(
        id = id,
        title = title,
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
