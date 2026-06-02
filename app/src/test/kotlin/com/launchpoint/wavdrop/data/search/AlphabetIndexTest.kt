package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlphabetIndexTest {

    @Test
    fun `songs match A to Z by title`() {
        val songs = ('A'..'Z').mapIndexed { index, letter -> song(index.toLong(), "$letter song") }

        assertEquals(0, AlphabetIndex.firstIndexForSongLetter(songs, 'A'))
        assertEquals(25, AlphabetIndex.firstIndexForSongLetter(songs, 'Z'))
    }

    @Test
    fun `songs match lowercase letter case insensitively`() {
        val songs = listOf(song(1, "alpha"), song(2, "Beta"))

        assertEquals(0, AlphabetIndex.firstIndexForSongLetter(songs, 'a'))
        assertEquals(1, AlphabetIndex.firstIndexForSongLetter(songs, 'b'))
    }

    @Test
    fun `songs trim whitespace before matching`() {
        val songs = listOf(song(1, "   Delta"), song(2, "Echo"))

        assertEquals(0, AlphabetIndex.firstIndexForSongLetter(songs, 'D'))
    }

    @Test
    fun `songs use hash for numbers symbols and blanks`() {
        val songs = listOf(song(1, "  "), song(2, "9 Lives"), song(3, "!Bang"), song(4, "Alpha"))

        assertEquals(0, AlphabetIndex.firstIndexForSongLetter(songs, '#'))
    }

    @Test
    fun `songs return null when no matching title exists`() {
        val songs = listOf(song(1, "Alpha"), song(2, "Beta"))

        assertNull(AlphabetIndex.firstIndexForSongLetter(songs, 'Z'))
    }

    @Test
    fun `albums match by album key`() {
        val albums = listOf(album("1989"), album("  abbey road"), album("Zooropa"))

        assertEquals(0, AlphabetIndex.firstIndexForAlbumLetter(albums, '#'))
        assertEquals(1, AlphabetIndex.firstIndexForAlbumLetter(albums, 'A'))
        assertEquals(2, AlphabetIndex.firstIndexForAlbumLetter(albums, 'z'))
    }

    @Test
    fun `albums return null when no matching album exists`() {
        val albums = listOf(album("Kind of Blue"), album("Mellon Collie"))

        assertNull(AlphabetIndex.firstIndexForAlbumLetter(albums, 'A'))
    }

    @Test
    fun `artists match by artist key`() {
        val artists = listOf(artist("_Unknown"), artist("  beatles"), artist("Queen"))

        assertEquals(0, AlphabetIndex.firstIndexForArtistLetter(artists, '#'))
        assertEquals(1, AlphabetIndex.firstIndexForArtistLetter(artists, 'B'))
        assertEquals(2, AlphabetIndex.firstIndexForArtistLetter(artists, 'q'))
    }

    @Test
    fun `artists return null when no matching artist exists`() {
        val artists = listOf(artist("Nina Simone"), artist("Prince"))

        assertNull(AlphabetIndex.firstIndexForArtistLetter(artists, 'X'))
    }

    @Test
    fun `folders match by display name`() {
        val folders = listOf(folder("music/1989", "1989"), folder("music/Audio", "  audio"), folder("z", "Zebra"))

        assertEquals(0, AlphabetIndex.firstIndexForFolderLetter(folders, '#'))
        assertEquals(1, AlphabetIndex.firstIndexForFolderLetter(folders, 'A'))
        assertEquals(2, AlphabetIndex.firstIndexForFolderLetter(folders, 'z'))
    }

    @Test
    fun `folders return null when no matching display name exists`() {
        val folders = listOf(folder("Music", "Music"), folder("Download", "Download"))

        assertNull(AlphabetIndex.firstIndexForFolderLetter(folders, 'X'))
    }

    private fun song(id: Long, title: String) = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 200_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private fun album(key: String) = AlbumSummary(
        albumId = 0L,
        albumKey = key,
        artist = "Artist",
        songCount = 1,
        totalDurationMs = 200_000L,
    )

    private fun artist(key: String) = ArtistSummary(
        artistKey = key,
        songCount = 1,
        albumCount = 1,
        totalDurationMs = 200_000L,
    )

    private fun folder(key: String, displayName: String) = FolderSummary(
        folderKey = key,
        displayName = displayName,
        songCount = 1,
        totalDurationMs = 200_000L,
    )
}
