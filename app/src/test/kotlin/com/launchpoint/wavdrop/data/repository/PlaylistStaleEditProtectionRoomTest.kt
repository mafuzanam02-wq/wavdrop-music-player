package com.launchpoint.wavdrop.data.repository

import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.playlists.PlaylistPositionEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P5-AUDIT-05. Playlist remove/move commands carry the ordered row snapshot the UI acted on; the repository compares it with the
 * current rows inside the same Room transaction and rejects a stale command without writing anything. Real repository, real Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistStaleEditProtectionRoomTest {
    private lateinit var db: WavdropDatabase
    private lateinit var repo: PlaylistRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java).allowMainThreadQueries().build()
        repo = PlaylistRepository(db, db.playlistDao())
    }
    @After fun tearDown() = db.close()

    private fun make(name: String, vararg songs: Long): Long =
        (runBlocking { repo.createPlaylistFromQueue(name, songs.toList()) } as QueueSaveResult.Success).playlistId
    private fun rows(id: Long) = runBlocking { db.playlistDao().getSongsForPlaylistSnapshot(id) }
    private fun ids(id: Long) = rows(id).map { it.songId }
    /** What the UI would hold after its latest emission. */
    private fun snap(id: Long): List<PlaylistPositionEntry> = rows(id).map { PlaylistPositionEntry(it.songId, it.position) }
    private fun updatedAt(id: Long) = runBlocking { db.playlistDao().getAllPlaylistsSnapshot() }.first { it.playlistId == id }.updatedAt
    private fun remove(id: Long, pos: Int, s: List<PlaylistPositionEntry>) = runBlocking { repo.removePlaylistEntry(id, pos, s) }
    private fun move(id: Long, from: Int, to: Int, s: List<PlaylistPositionEntry>) = runBlocking { repo.movePlaylistSong(id, from, to, s) }

    @Test fun `repeated removal of position 0 from one snapshot removes only one entry`() {
        val id = make("P", 1, 2, 3, 4)
        val old = snap(id)
        assertEquals(PlaylistEditResult.Applied, remove(id, 0, old))
        assertEquals(PlaylistEditResult.Stale, remove(id, 0, old))
        assertEquals(listOf(2L, 3L, 4L), ids(id))
        assertEquals("positions stay contiguous", listOf(0, 1, 2), rows(id).map { it.position })
    }

    @Test fun `repeated removal with duplicate song ids removes only one occurrence`() {
        val id = make("P", 1, 1, 2, 3)
        val old = snap(id)
        assertEquals(PlaylistEditResult.Applied, remove(id, 0, old))
        // the new first entry has the SAME song id as the removed one: an expected-song-id check alone would accept this
        assertEquals(PlaylistEditResult.Stale, remove(id, 0, old))
        assertEquals(listOf(1L, 2L, 3L), ids(id))
    }

    @Test fun `a duplicate occurrence can still be removed from the updated snapshot`() {
        val id = make("P", 1, 1, 2, 3)
        assertEquals(PlaylistEditResult.Applied, remove(id, 0, snap(id)))
        assertEquals(PlaylistEditResult.Applied, remove(id, 0, snap(id)))
        assertEquals(listOf(2L, 3L), ids(id))
    }

    @Test fun `removal after an unrelated edit of the same playlist is rejected`() {
        val id = make("P", 1, 2, 3)
        val old = snap(id)
        runBlocking { repo.addSongsToPlaylist(id, listOf(9)) }
        assertEquals(PlaylistEditResult.Stale, remove(id, 1, old))
        assertEquals(listOf(1L, 2L, 3L, 9L), ids(id))
    }

    @Test fun `repeated move-down from one snapshot moves once`() {
        val id = make("P", 1, 2, 3, 4)
        val old = snap(id)
        assertEquals(PlaylistEditResult.Applied, move(id, 0, 1, old))
        assertEquals(PlaylistEditResult.Stale, move(id, 0, 1, old))
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(id))
    }

    @Test fun `repeated move-up from one snapshot moves once`() {
        val id = make("P", 1, 2, 3, 4)
        val old = snap(id)
        assertEquals(PlaylistEditResult.Applied, move(id, 2, 1, old))
        assertEquals(PlaylistEditResult.Stale, move(id, 2, 1, old))
        assertEquals(listOf(1L, 3L, 2L, 4L), ids(id))
    }

    @Test fun `move after another edit changed the positions is rejected`() {
        val id = make("P", 1, 2, 3, 4)
        val old = snap(id)
        assertEquals(PlaylistEditResult.Applied, remove(id, 0, old))
        assertEquals(PlaylistEditResult.Stale, move(id, 2, 1, old))
        assertEquals(listOf(2L, 3L, 4L), ids(id))
    }

    @Test fun `valid sequential edits with refreshed snapshots all apply`() {
        val id = make("P", 1, 2, 3, 4, 5)
        assertEquals(PlaylistEditResult.Applied, remove(id, 1, snap(id)))          // [1,3,4,5]
        assertEquals(PlaylistEditResult.Applied, move(id, 0, 2, snap(id)))         // [3,4,1,5]
        assertEquals(PlaylistEditResult.Applied, remove(id, 3, snap(id)))          // [3,4,1]
        assertEquals(PlaylistEditResult.Applied, move(id, 2, 0, snap(id)))         // [1,3,4]
        assertEquals(listOf(1L, 3L, 4L), ids(id))
        assertEquals(listOf(0, 1, 2), rows(id).map { it.position })
    }

    @Test fun `an empty playlist rejects a removal without writing`() {
        val id = (runBlocking { repo.createPlaylist("Empty") } as PlaylistOperationResult.Success).playlistId
        assertEquals(PlaylistEditResult.InvalidTarget, remove(id, 0, snap(id)))
        assertEquals(emptyList<Long>(), ids(id))
    }

    @Test fun `a single-entry playlist removes once and then rejects the repeat`() {
        val id = make("One", 7)
        val old = snap(id)
        assertEquals(PlaylistEditResult.Applied, remove(id, 0, old))
        assertEquals(PlaylistEditResult.Stale, remove(id, 0, old))
        assertEquals(emptyList<Long>(), ids(id))
    }

    @Test fun `concurrent commands from the same snapshot apply exactly once`() = runBlocking {
        val id = make("P", 1, 2, 3, 4)
        val old = snap(id)
        val results = (1..12).map { async(Dispatchers.Default) { repo.removePlaylistEntry(id, 0, old) } }.awaitAll()
        assertEquals(1, results.count { it == PlaylistEditResult.Applied })
        assertEquals(11, results.count { it == PlaylistEditResult.Stale })
        assertEquals(listOf(2L, 3L, 4L), ids(id))
    }

    @Test fun `an edit never touches another playlist even with identical content`() {
        val a = make("A", 1, 2, 3)
        val b = make("B", 1, 2, 3)
        assertEquals(PlaylistEditResult.Applied, remove(a, 0, snap(a)))
        assertEquals(listOf(1L, 2L, 3L), ids(b))
        assertEquals(listOf(2L, 3L), ids(a))
    }

    @Test fun `rejected commands neither rewrite rows nor update the timestamp`() {
        val id = make("P", 1, 2, 3)
        val old = snap(id)
        assertEquals(PlaylistEditResult.Applied, move(id, 0, 1, old))
        val rowsAfter = rows(id)
        val stamp = updatedAt(id)
        Thread.sleep(15)
        assertEquals(PlaylistEditResult.Stale, remove(id, 0, old))
        assertEquals(PlaylistEditResult.Stale, move(id, 0, 1, old))
        assertEquals(PlaylistEditResult.InvalidTarget, remove(id, 9, snap(id)))
        assertEquals(PlaylistEditResult.InvalidTarget, move(id, 1, 1, snap(id)))
        assertEquals(PlaylistEditResult.InvalidTarget, move(id, 0, 9, snap(id)))
        assertEquals(rowsAfter, rows(id))
        assertEquals("timestamp untouched by rejected commands", stamp, updatedAt(id))
        assertTrue(true)
    }

    @Test fun `an applied command updates the timestamp`() {
        val id = make("P", 1, 2, 3)
        val stamp = updatedAt(id)
        Thread.sleep(15)
        assertEquals(PlaylistEditResult.Applied, remove(id, 0, snap(id)))
        assertTrue(updatedAt(id) > stamp)
    }

    @Test fun `orphan rows are part of the snapshot so a hidden entry change is detected`() {
        val id = make("P", 1, 2, 3) // pretend song 2 is a hidden orphan: the UI list would be [1,3] but the guard uses ALL rows
        val uiAllRows = snap(id)
        runBlocking { db.playlistDao().insertSong(com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity(id, 8, 3)) } // hidden row appears
        assertEquals(PlaylistEditResult.Stale, remove(id, 0, uiAllRows))
        assertEquals(listOf(1L, 2L, 3L, 8L), ids(id))
    }

    @Test fun `the original unguarded overloads keep their behaviour`() {
        val id = make("P", 1, 2, 3)
        runBlocking { repo.removePlaylistEntry(id, 0) }
        assertEquals(listOf(2L, 3L), ids(id))
    }
}
