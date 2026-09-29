package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavdropMediaLibraryTest {
    private val songs = (1L..5L).map { song(it) }

    @Test
    fun `root children are recent and songs both browsable and not playable`() {
        val children = WavdropMediaLibrary.rootChildren()
        assertEquals(listOf(WavdropLibraryIds.RECENT, WavdropLibraryIds.SONGS), children.map { it.mediaId })
        children.forEach {
            assertTrue(it.mediaMetadata.isBrowsable == true)
            assertFalse(it.mediaMetadata.isPlayable == true)
        }
        val root = WavdropMediaLibrary.rootItem()
        assertTrue(root.mediaMetadata.isBrowsable == true)
        assertFalse(root.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `browse node ids are stable and not numeric`() {
        listOf(WavdropLibraryIds.ROOT, WavdropLibraryIds.RECENT, WavdropLibraryIds.SONGS).forEach {
            assertTrue(WavdropMediaLibrary.isBrowseNode(it))
            assertNull(it.toLongOrNull())
        }
        assertFalse(WavdropMediaLibrary.isBrowseNode("42"))
    }

    @Test
    fun `song items are playable with stable id and preserved metadata`() {
        val item = WavdropMediaLibrary.songItem(songs[2])
        assertEquals("3", item.mediaId)
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertEquals(songs[2].displayTitle, item.mediaMetadata.title.toString())
        assertEquals(songs[2].displayArtist, item.mediaMetadata.artist.toString())
        assertEquals(songs[2].album, item.mediaMetadata.albumTitle.toString())
    }

    @Test
    fun `song pagination slices correctly and handles edges`() {
        assertEquals(listOf("1", "2"), WavdropMediaLibrary.songChildren(songs, 0, 2).map { it.mediaId })
        assertEquals(listOf("3", "4"), WavdropMediaLibrary.songChildren(songs, 1, 2).map { it.mediaId })
        assertEquals(listOf("5"), WavdropMediaLibrary.songChildren(songs, 2, 2).map { it.mediaId })
        assertTrue(WavdropMediaLibrary.songChildren(songs, 3, 2).isEmpty())
        assertTrue(WavdropMediaLibrary.songChildren(songs, -1, 2).isEmpty())
        assertTrue(WavdropMediaLibrary.songChildren(songs, 0, 0).isEmpty())
        assertEquals(5, WavdropMediaLibrary.songChildren(songs, 0, Int.MAX_VALUE).size)
    }

    @Test
    fun `empty library yields empty song children`() {
        assertTrue(WavdropMediaLibrary.songChildren(emptyList(), 0, 50).isEmpty())
    }

    @Test
    fun `recent yields one playable item for a resumable session`() {
        val recent = WavdropMediaLibrary.recentChildren(
            snapshot(currentSongId = 2, currentIndex = 1),
            ResumeBehaviorSettings(),
            songs,
        )
        assertEquals(listOf("2"), recent.map { it.mediaId })
        assertTrue(recent.single().mediaMetadata.isPlayable == true)
        assertTrue(recent.single().mediaMetadata.title.toString().isNotBlank())
    }

    @Test
    fun `recent is empty without a saved session`() {
        assertTrue(WavdropMediaLibrary.recentChildren(null, ResumeBehaviorSettings(), songs).isEmpty())
    }

    @Test
    fun `recent is empty when the saved song is unavailable`() {
        val recent = WavdropMediaLibrary.recentChildren(
            snapshot(queueIds = listOf(99), currentSongId = 99, currentIndex = 0),
            ResumeBehaviorSettings(),
            songs,
        )
        assertTrue(recent.isEmpty())
    }

    @Test
    fun `recent does not guess a duplicate occurrence when the saved index is unusable`() {
        // Song 1 appears twice and the saved index no longer matches: ambiguous, so empty.
        val recent = WavdropMediaLibrary.recentChildren(
            snapshot(queueIds = listOf(1, 2, 1, 3), currentSongId = 1, currentIndex = 7),
            ResumeBehaviorSettings(),
            songs,
        )
        assertTrue(recent.isEmpty())
    }

    @Test
    fun `recent resolves the duplicate song when the saved index is valid`() {
        val recent = WavdropMediaLibrary.recentChildren(
            snapshot(queueIds = listOf(1, 2, 1, 3), currentSongId = 1, currentIndex = 2),
            ResumeBehaviorSettings(),
            songs,
        )
        assertEquals(listOf("1"), recent.map { it.mediaId })
    }

    @Test
    fun `item lookup resolves nodes and songs and rejects unknown ids`() {
        assertEquals(WavdropLibraryIds.ROOT, WavdropMediaLibrary.item(WavdropLibraryIds.ROOT, songs)?.mediaId)
        assertEquals("4", WavdropMediaLibrary.item("4", songs)?.mediaId)
        assertNull(WavdropMediaLibrary.item("999", songs))
        assertNull(WavdropMediaLibrary.item("not-an-id", songs))
    }

    @Test
    fun `normal root request returns the normal root`() {
        assertEquals(WavdropLibraryIds.ROOT, WavdropMediaLibrary.rootFor(isRecent = false).mediaId)
    }

    @Test
    fun `recent query root is a stable non-numeric browse root distinct from other nodes`() {
        val root = WavdropMediaLibrary.rootFor(isRecent = true)
        assertEquals(WavdropLibraryIds.RECENT_ROOT, root.mediaId)
        assertNull(root.mediaId.toLongOrNull())
        assertTrue(root.mediaMetadata.isBrowsable == true)
        assertFalse(root.mediaMetadata.isPlayable == true)
        listOf(WavdropLibraryIds.ROOT, WavdropLibraryIds.RECENT, WavdropLibraryIds.SONGS).forEach {
            assertFalse(it == root.mediaId)
        }
        assertTrue(WavdropMediaLibrary.isBrowseNode(WavdropLibraryIds.RECENT_ROOT))
        assertEquals(WavdropLibraryIds.RECENT_ROOT, WavdropMediaLibrary.item(WavdropLibraryIds.RECENT_ROOT, songs)?.mediaId)
    }

    @Test
    fun `recent query root children are the playable song directly with no intermediate node`() {
        val children = WavdropMediaLibrary.recentChildren(
            snapshot(currentSongId = 2, currentIndex = 1),
            ResumeBehaviorSettings(),
            songs,
        )
        assertEquals(listOf("2"), children.map { it.mediaId })
        assertFalse(children.any { it.mediaId == WavdropLibraryIds.RECENT })
        val item = children.single()
        assertTrue(item.mediaMetadata.isPlayable == true)
        assertFalse(item.mediaMetadata.isBrowsable == true)
        assertTrue(item.mediaMetadata.title.toString().isNotBlank())
        assertEquals(songs[1].displayArtist, item.mediaMetadata.artist.toString())
    }

    @Test
    fun `normal root still lists recent and songs`() {
        assertEquals(
            listOf(WavdropLibraryIds.RECENT, WavdropLibraryIds.SONGS),
            WavdropMediaLibrary.rootChildren().map { it.mediaId },
        )
    }

    private fun snapshot(
        queueIds: List<Long> = listOf(1, 2, 3),
        currentSongId: Long = 1,
        currentIndex: Int = 0,
    ) = PlaybackSessionSnapshot(
        queueSongIds = queueIds,
        currentSongId = currentSongId,
        currentIndex = currentIndex,
        positionMs = 1_000L,
        repeatMode = RepeatMode.OFF,
        shuffleEnabled = false,
        updatedAtMs = 0L,
    )

    private fun song(id: Long) = Song(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        album = "Album $id",
        albumId = id,
        duration = 200_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 1,
        year = 2020,
    )
}
