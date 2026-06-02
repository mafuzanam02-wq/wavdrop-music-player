package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.model.AlbumSummary
import com.launchpoint.wavdrop.data.model.ArtistSummary
import com.launchpoint.wavdrop.data.model.FolderSummary
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {

    private fun song(id: Long, title: String, artist: String = "Artist", album: String = "Album") = Song(
        id = id, title = title, artist = artist, album = album,
        albumId = 0L, duration = 200_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun album(key: String, artist: String = "Artist") = AlbumSummary(
        albumId = 0L, albumKey = key, artist = artist, songCount = 1, totalDurationMs = 200_000L,
    )

    private fun artist(key: String) = ArtistSummary(
        artistKey = key, songCount = 1, albumCount = 1, totalDurationMs = 200_000L,
    )

    private fun folder(key: String, displayName: String = key) = FolderSummary(
        folderKey = key, displayName = displayName, songCount = 1, totalDurationMs = 200_000L,
    )

    // ── Song filter ───────────────────────────────────────────────────────────

    @Test
    fun `blank query returns all songs`() {
        val songs = listOf(song(1, "Alpha"), song(2, "Beta"))
        assertEquals(2, LibrarySearch.filterSongs(songs, "").size)
        assertEquals(2, LibrarySearch.filterSongs(songs, "   ").size)
    }

    @Test
    fun `song filter matches by title case-insensitively`() {
        val songs = listOf(song(1, "Bohemian Rhapsody"), song(2, "Hotel California"))
        val result = LibrarySearch.filterSongs(songs, "rhapsody")
        assertEquals(1, result.size)
        assertEquals("Bohemian Rhapsody", result.first().title)
    }

    @Test
    fun `song filter matches by artist`() {
        val songs = listOf(
            song(1, "Track A", artist = "Queen"),
            song(2, "Track B", artist = "Eagles"),
        )
        val result = LibrarySearch.filterSongs(songs, "queen")
        assertEquals(1, result.size)
        assertEquals("Queen", result.first().artist)
    }

    @Test
    fun `song filter matches by album`() {
        val songs = listOf(
            song(1, "Track A", album = "A Night at the Opera"),
            song(2, "Track B", album = "Hotel California"),
        )
        val result = LibrarySearch.filterSongs(songs, "opera")
        assertEquals(1, result.size)
    }

    @Test
    fun `song filter returns empty when nothing matches`() {
        val songs = listOf(song(1, "Alpha"), song(2, "Beta"))
        assertTrue(LibrarySearch.filterSongs(songs, "zzz").isEmpty())
    }

    @Test
    fun `song filter matches partial title`() {
        val songs = listOf(song(1, "Yesterday"), song(2, "Let It Be"))
        assertEquals(2, LibrarySearch.filterSongs(songs, "e").size)
    }

    // ── Album filter ──────────────────────────────────────────────────────────

    @Test
    fun `blank query returns all albums`() {
        val albums = listOf(album("Dark Side"), album("Thriller"))
        assertEquals(2, LibrarySearch.filterAlbums(albums, "").size)
    }

    @Test
    fun `album filter matches by album name`() {
        val albums = listOf(album("Dark Side of the Moon"), album("Thriller"))
        val result = LibrarySearch.filterAlbums(albums, "moon")
        assertEquals(1, result.size)
        assertEquals("Dark Side of the Moon", result.first().albumKey)
    }

    @Test
    fun `album filter matches by artist`() {
        val albums = listOf(
            album("Dark Side", artist = "Pink Floyd"),
            album("Thriller", artist = "Michael Jackson"),
        )
        val result = LibrarySearch.filterAlbums(albums, "pink")
        assertEquals(1, result.size)
        assertEquals("Pink Floyd", result.first().artist)
    }

    @Test
    fun `blank query returns all folders`() {
        val folders = listOf(folder("Music"), folder("Download"))

        assertEquals(2, LibrarySearch.filterFolders(folders, "").size)
        assertEquals(2, LibrarySearch.filterFolders(folders, "   ").size)
    }

    @Test
    fun `folder filter matches by display name`() {
        val folders = listOf(
            folder("Music/WhatsApp Audio", displayName = "WhatsApp Audio"),
            folder("Download"),
        )

        val result = LibrarySearch.filterFolders(folders, "whatsapp")

        assertEquals(1, result.size)
        assertEquals("WhatsApp Audio", result.first().displayName)
    }

    @Test
    fun `folder filter matches by folder key`() {
        val folders = listOf(
            folder("storage/emulated/0/Music/Artist Folder", displayName = "Artist Folder"),
            folder("Download"),
        )

        val result = LibrarySearch.filterFolders(folders, "emulated")

        assertEquals(1, result.size)
        assertEquals("Artist Folder", result.first().displayName)
    }

    @Test
    fun `folder filter returns empty when nothing matches`() {
        val folders = listOf(folder("Music"), folder("Download"))

        assertTrue(LibrarySearch.filterFolders(folders, "zzz").isEmpty())
    }

    // ── Artist filter ─────────────────────────────────────────────────────────

    @Test
    fun `blank query returns all artists`() {
        val artists = listOf(artist("Queen"), artist("Eagles"))
        val songs = listOf(song(1, "Bohemian Rhapsody", artist = "Queen"))
        assertEquals(2, LibrarySearch.filterArtists(artists, songs, "").size)
        assertEquals(2, LibrarySearch.filterArtists(artists, songs, "   ").size)
    }

    @Test
    fun `artist filter matches by artist name`() {
        val artists = listOf(artist("Queen"), artist("Eagles"))
        val songs = listOf(song(1, "Track", artist = "Queen"))
        val result = LibrarySearch.filterArtists(artists, songs, "queen")
        assertEquals(1, result.size)
        assertEquals("Queen", result.first().artistKey)
    }

    @Test
    fun `artist filter matches by song title from that artist`() {
        val artists = listOf(artist("Queen"), artist("Eagles"))
        val songs = listOf(
            song(1, "Bohemian Rhapsody", artist = "Queen"),
            song(2, "Hotel California", artist = "Eagles"),
        )

        val result = LibrarySearch.filterArtists(artists, songs, "rhapsody")

        assertEquals(1, result.size)
        assertEquals("Queen", result.first().artistKey)
    }

    @Test
    fun `artist filter matches by album name from that artist`() {
        val artists = listOf(artist("Queen"), artist("Eagles"))
        val songs = listOf(
            song(1, "Love of My Life", artist = "Queen", album = "A Night at the Opera"),
            song(2, "Desperado", artist = "Eagles", album = "Desperado"),
        )

        val result = LibrarySearch.filterArtists(artists, songs, "opera")

        assertEquals(1, result.size)
        assertEquals("Queen", result.first().artistKey)
    }

    @Test
    fun `artist filter does not return unrelated artist`() {
        val artists = listOf(artist("Queen"), artist("Eagles"))
        val songs = listOf(
            song(1, "Bohemian Rhapsody", artist = "Queen"),
            song(2, "Hotel California", artist = "Eagles"),
        )

        val result = LibrarySearch.filterArtists(artists, songs, "hotel")

        assertEquals(1, result.size)
        assertEquals("Eagles", result.first().artistKey)
    }

    @Test
    fun `artist filter returns empty when nothing matches`() {
        val artists = listOf(artist("Queen"), artist("Eagles"))
        val songs = listOf(song(1, "Bohemian Rhapsody", artist = "Queen"))
        assertTrue(LibrarySearch.filterArtists(artists, songs, "zzz").isEmpty())
    }
}
