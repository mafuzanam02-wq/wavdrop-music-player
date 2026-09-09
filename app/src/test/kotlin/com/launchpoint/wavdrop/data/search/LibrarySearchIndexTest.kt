package com.launchpoint.wavdrop.data.search

import com.launchpoint.wavdrop.data.grouping.AlbumGrouper
import com.launchpoint.wavdrop.data.grouping.ArtistGrouper
import com.launchpoint.wavdrop.data.library.FolderGrouper
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.model.SmartCollection
import com.launchpoint.wavdrop.data.model.SmartCollectionType
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9: proves [LibrarySearchIndex] produces results IDENTICAL to [LibrarySearch] (the pinned
 * pre-Phase-9 semantics) across every matched field and ordering, that one index answers many
 * queries (reuse), and that a new library snapshot yields an updated projection (rebuild).
 *
 * The index only changes WHEN normalization/grouping happens (once per snapshot instead of once per
 * keystroke); it must not change WHAT is matched or the order results come back in.
 */
class LibrarySearchIndexTest {

    private fun song(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        folderPath: String? = null,
    ) = Song(
        id = id, title = title, artist = artist, album = album,
        albumId = 0L, duration = 200_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
        folderPath = folderPath, folderName = folderPath?.substringAfterLast('/'),
    )

    private fun playlist(id: Long, name: String) =
        PlaylistSummary(id = id, name = name, songCount = 1, createdAt = 0L, updatedAt = 0L)

    private fun smartCollection(type: SmartCollectionType, title: String, description: String) =
        SmartCollection(id = type.name, title = title, description = description, type = type, songCount = 1)

    private val library = listOf(
        song(1, "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera", folderPath = "Music/Rock"),
        song(2, "Hotel California", artist = "Eagles", album = "Hotel California", folderPath = "Music/Rock"),
        song(3, "Jolé", artist = "Jolé", album = "Café Sessions", folderPath = "Music/World"),
        song(4, "Picture_Perfect", artist = "Queen", album = "Hits", folderPath = "Download"),
        song(5, "Still(256k)", artist = "Various", album = "Mix", folderPath = "Download"),
        song(6, "軽注", artist = "Various", album = "Mix", folderPath = null),
        // Duplicate song id/title to exercise grouping and ordering under repeats.
        song(7, "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera", folderPath = "Music/Rock"),
    )

    private val playlists = listOf(playlist(1, "Road Trip"), playlist(2, "Café Mix"))
    private val collections = listOf(
        smartCollection(SmartCollectionType.FAVORITES, "Favorites", "Songs you've marked as favorites"),
        smartCollection(SmartCollectionType.MOST_PLAYED, "Most Played", "Your most frequently played tracks"),
    )

    private val queries = listOf(
        "", "   ", "queen", "QUEEN", "rhapsody", "hotel", "opera", "jole", "cafe",
        "picture perfect", "still", "軽注", "e", "zzz", "'", "music", "download", "road", "favorites", "played",
    )

    private fun index() = LibrarySearchIndex.from(library, playlists, collections)

    @Test
    fun `filterSongs matches LibrarySearch for every query`() {
        val index = index()
        for (q in queries) {
            assertEquals("songs mismatch for query='$q'", LibrarySearch.filterSongs(library, q), index.filterSongs(q))
        }
    }

    @Test
    fun `filterArtists matches LibrarySearch for every query`() {
        val index = index()
        val artists = ArtistGrouper.group(library)
        for (q in queries) {
            assertEquals("artists mismatch for query='$q'", LibrarySearch.filterArtists(artists, library, q), index.filterArtists(q))
        }
    }

    @Test
    fun `filterAlbums matches LibrarySearch for every query`() {
        val index = index()
        val albums = AlbumGrouper.group(library)
        for (q in queries) {
            assertEquals("albums mismatch for query='$q'", LibrarySearch.filterAlbums(albums, q), index.filterAlbums(q))
        }
    }

    @Test
    fun `filterFolders matches LibrarySearch for every query`() {
        val index = index()
        val folders = FolderGrouper.groupSongsByFolder(library)
        for (q in queries) {
            assertEquals("folders mismatch for query='$q'", LibrarySearch.filterFolders(folders, q), index.filterFolders(q))
        }
    }

    @Test
    fun `filterPlaylists matches LibrarySearch for every query`() {
        val index = index()
        for (q in queries) {
            assertEquals("playlists mismatch for query='$q'", LibrarySearch.filterPlaylists(playlists, q), index.filterPlaylists(q))
        }
    }

    @Test
    fun `filterSmartCollections matches LibrarySearch for every query`() {
        val index = index()
        for (q in queries) {
            assertEquals(
                "smart collections mismatch for query='$q'",
                LibrarySearch.filterSmartCollections(collections, q),
                index.filterSmartCollections(q),
            )
        }
    }

    @Test
    fun `blank query returns full library in source order`() {
        val index = index()
        assertEquals(library, index.filterSongs(""))
        assertEquals(library, index.filterSongs("   "))
        // Ordering preserved (including the duplicate at the tail).
        assertEquals(library.map { it.id }, index.filterSongs("").map { it.id })
    }

    @Test
    fun `tolerant matching preserves display values`() {
        val index = index()
        assertEquals("Jolé", index.filterSongs("Jole").single { it.id == 3L }.title)
        assertEquals("Picture_Perfect", index.filterSongs("Picture Perfect").single().title)
        assertEquals("Café Sessions", index.filterAlbums("cafe").single().albumKey)
    }

    @Test
    fun `one index instance answers many queries without rebuild`() {
        val index = index()
        // Reuse the SAME instance across queries; each must be correct.
        assertEquals(LibrarySearch.filterSongs(library, "queen"), index.filterSongs("queen"))
        assertEquals(LibrarySearch.filterSongs(library, "hotel"), index.filterSongs("hotel"))
        assertEquals(LibrarySearch.filterSongs(library, "cafe"), index.filterSongs("cafe"))
        // songs snapshot is the exact instance passed in (no defensive copy / duplication).
        assertSame(library, index.songs)
    }

    @Test
    fun `rebuild reflects a changed library`() {
        val first = LibrarySearchIndex.from(library)
        assertTrue(first.filterSongs("newtrack").isEmpty())

        val grown = library + song(99, "NewTrack", artist = "Fresh")
        val second = LibrarySearchIndex.from(grown)
        assertNotSame(first.songs, second.songs)
        assertEquals(1, second.filterSongs("newtrack").size)
        assertEquals(99L, second.filterSongs("newtrack").single().id)
        // Old index is unaffected (immutable projection).
        assertTrue(first.filterSongs("newtrack").isEmpty())
    }

    @Test
    fun `query that normalizes to empty returns full lists like LibrarySearch`() {
        // A lone apostrophe trims to non-empty but normalizes to "", so every filter returns all —
        // this mirrors LibrarySearch's `if (q.isEmpty()) return input` branch exactly.
        val index = index()
        assertEquals(library, index.filterSongs("'"))
        assertEquals(ArtistGrouper.group(library), index.filterArtists("'"))
        assertEquals(AlbumGrouper.group(library), index.filterAlbums("'"))
    }
}
