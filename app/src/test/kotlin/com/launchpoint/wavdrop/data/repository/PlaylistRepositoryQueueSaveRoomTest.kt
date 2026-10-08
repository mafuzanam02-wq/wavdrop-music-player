package com.launchpoint.wavdrop.data.repository

import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.model.ExternalAudioIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * QSP-1: [PlaylistRepository.createPlaylistFromQueue] over real Room. The exact queue occurrence sequence is persisted
 * (repeated songs kept) in one transaction; the ordinary Add-to-playlist duplicate policy is untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistRepositoryQueueSaveRoomTest {

    private lateinit var db: WavdropDatabase
    private lateinit var dao: PlaylistDao
    private lateinit var repo: PlaylistRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java).allowMainThreadQueries().build()
        dao = db.playlistDao()
        repo = PlaylistRepository(db, dao)
    }

    @After
    fun tearDown() = db.close()

    private fun entries(playlistId: Long) = runBlocking { dao.getSongsForPlaylistSnapshot(playlistId) }
    private fun playlists() = runBlocking { dao.getAllPlaylistsSnapshot() }
    private fun allEntries() = runBlocking { dao.getAllPlaylistSongs().first() }

    @Test fun `A - a basic save creates positions 0 to N-1 in queue order`() = runBlocking {
        val result = repo.createPlaylistFromQueue("Road trip", listOf(1L, 2L, 3L))

        val success = result as QueueSaveResult.Success
        assertEquals(3, success.songCount)
        assertEquals(listOf(0 to 1L, 1 to 2L, 2 to 3L), entries(success.playlistId).map { it.position to it.songId })
        assertEquals("Road trip", playlists().single().name)
    }

    @Test fun `B - repeated occurrences are preserved exactly in place`() = runBlocking {
        val success = repo.createPlaylistFromQueue("Loops", listOf(1L, 2L, 1L, 3L, 1L)) as QueueSaveResult.Success

        assertEquals(5, success.songCount)
        assertEquals(listOf(1L, 2L, 1L, 3L, 1L), entries(success.playlistId).map { it.songId })
        assertEquals(listOf(0, 1, 2, 3, 4), entries(success.playlistId).map { it.position })
    }

    @Test fun `the name is trimmed and the playlist is created once with one timestamp`() = runBlocking {
        val success = repo.createPlaylistFromQueue("   Evening   ", listOf(7L)) as QueueSaveResult.Success
        val playlist = playlists().single()
        assertEquals("Evening", playlist.name)
        assertEquals("created once, never touched again", playlist.createdAt, playlist.updatedAt)
        assertEquals(success.playlistId, playlist.playlistId)
    }

    @Test fun `C - ordinary Add to playlist still skips songs already in the playlist`() = runBlocking {
        val id = (repo.createPlaylist("Normal") as PlaylistOperationResult.Success).playlistId
        assertEquals(AddToPlaylistResult(added = 3, skipped = 0), repo.addSongsToPlaylist(id, listOf(1L, 2L, 3L)))

        val second = repo.addSongsToPlaylist(id, listOf(1L, 2L, 4L))

        assertEquals("1 and 2 are already there: duplicate prevention is unchanged", AddToPlaylistResult(added = 1, skipped = 2), second)
        assertEquals(listOf(1L, 2L, 3L, 4L), entries(id).map { it.songId })
        assertEquals(AddToPlaylistResult(added = 0, skipped = 1), repo.addSongToPlaylist(3L, id))
        assertEquals(4, entries(id).size)
    }

    @Test fun `D - a blank name creates nothing`() = runBlocking {
        for (blank in listOf("", "   ", "\t\n")) {
            assertEquals(QueueSaveResult.BlankName, repo.createPlaylistFromQueue(blank, listOf(1L, 2L)))
        }
        assertEquals(emptyList<Any>(), playlists())
        assertEquals(emptyList<Any>(), allEntries())
    }

    @Test fun `E - a duplicate playlist name creates nothing and leaves the existing playlist alone`() = runBlocking {
        val first = repo.createPlaylistFromQueue("Mix", listOf(1L, 2L)) as QueueSaveResult.Success

        assertEquals(QueueSaveResult.DuplicateName, repo.createPlaylistFromQueue("Mix", listOf(9L, 9L, 9L)))

        assertEquals(1, playlists().size)
        assertEquals("no overwrite, no append", listOf(1L, 2L), entries(first.playlistId).map { it.songId })
        assertEquals(2, allEntries().size)
    }

    @Test fun `F - the duplicate check is case-insensitive and also catches playlists made elsewhere`() = runBlocking {
        repo.createPlaylist("Workout")
        assertEquals(QueueSaveResult.DuplicateName, repo.createPlaylistFromQueue("WORKOUT", listOf(1L)))
        assertEquals(QueueSaveResult.DuplicateName, repo.createPlaylistFromQueue("  workout  ", listOf(1L)))
        assertEquals(1, playlists().size)
        assertEquals(emptyList<Any>(), allEntries())
    }

    @Test fun `G - an empty queue never creates a playlist`() = runBlocking {
        assertEquals(QueueSaveResult.EmptyQueue, repo.createPlaylistFromQueue("Empty", emptyList()))
        assertEquals(emptyList<Any>(), playlists())
        assertEquals("a blank name is reported before the empty queue", QueueSaveResult.BlankName, repo.createPlaylistFromQueue("  ", emptyList()))
    }

    @Test fun `H - the operation is atomic - a failing entry insert rolls the playlist row back`() = runBlocking {
        val failing = object : PlaylistDao by dao {
            override suspend fun insertSongs(entities: List<PlaylistSongEntity>) {
                throw IllegalStateException("simulated insertion failure")
            }
        }
        val atomicRepo = PlaylistRepository(db, failing)

        try {
            atomicRepo.createPlaylistFromQueue("Doomed", listOf(1L, 2L, 3L))
            fail("the failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated insertion failure", expected.message)
        }

        assertEquals("the playlist row was rolled back with the entries", emptyList<Any>(), playlists())
        assertEquals(emptyList<Any>(), allEntries())
        // the same name is still available afterwards
        assertTrue(repo.createPlaylistFromQueue("Doomed", listOf(1L)) is QueueSaveResult.Success)
    }

    @Test fun `E and F - the external audio id is never persisted and no playlist row is created`() = runBlocking {
        val external = ExternalAudioIdentity.SONG_ID

        assertEquals(QueueSaveResult.UnsavableQueue, repo.createPlaylistFromQueue("Opened file", listOf(external)))

        assertEquals("no playlist row", emptyList<Any>(), playlists())
        assertEquals("no entries, so the synthetic id is not persisted anywhere", emptyList<Any>(), allEntries())
        assertTrue("the name is still free afterwards", repo.createPlaylistFromQueue("Opened file", listOf(1L)) is QueueSaveResult.Success)
    }

    @Test fun `G - a mixed queue is refused whole - nothing is saved and the sequence is never filtered`() = runBlocking {
        val mixed = listOf(1L, ExternalAudioIdentity.SONG_ID, 2L, 1L)

        assertEquals(QueueSaveResult.UnsavableQueue, repo.createPlaylistFromQueue("Mixed", mixed))

        assertEquals(emptyList<Any>(), playlists())
        assertEquals(emptyList<Any>(), allEntries())
    }

    @Test fun `blank name and empty queue are still reported before the external check`() = runBlocking {
        assertEquals(QueueSaveResult.BlankName, repo.createPlaylistFromQueue("  ", listOf(ExternalAudioIdentity.SONG_ID)))
        assertEquals(QueueSaveResult.EmptyQueue, repo.createPlaylistFromQueue("Name", emptyList()))
    }

    @Test fun `H and I - ordinary duplicates and large ordinary queues still save exactly after the external guard`() = runBlocking {
        val dup = repo.createPlaylistFromQueue("Dups", listOf(5L, 6L, 5L, 5L)) as QueueSaveResult.Success
        assertEquals(listOf(5L, 6L, 5L, 5L), entries(dup.playlistId).map { it.songId })
        val big = List(5_000) { (it % 400).toLong() + 1L }
        val success = repo.createPlaylistFromQueue("Big", big) as QueueSaveResult.Success
        assertEquals(big, entries(success.playlistId).map { it.songId })
    }

    @Test fun `the caller's list can change after the call without affecting what was written`() = runBlocking {
        val live = mutableListOf(1L, 2L, 3L)
        val success = repo.createPlaylistFromQueue("Snapshot", live) as QueueSaveResult.Success
        live.clear()
        live += listOf(9L, 9L)
        assertEquals(listOf(1L, 2L, 3L), entries(success.playlistId).map { it.songId })
    }

    @Test fun `a large queue is saved in full and in order`() = runBlocking {
        val ids = List(3_000) { index -> (index % 250).toLong() + 1L } // heavy repetition
        val success = repo.createPlaylistFromQueue("Marathon", ids) as QueueSaveResult.Success

        val saved = entries(success.playlistId)
        assertEquals(3_000, saved.size)
        assertEquals(ids, saved.map { it.songId })
        assertEquals((0 until 3_000).toList(), saved.map { it.position })
    }

    @Test fun `other playlists are untouched by a queue save`() = runBlocking {
        val other = (repo.createPlaylist("Other") as PlaylistOperationResult.Success).playlistId
        repo.addSongsToPlaylist(other, listOf(5L, 6L))
        val before = entries(other)

        repo.createPlaylistFromQueue("New", listOf(5L, 6L, 5L))

        assertEquals(before, entries(other))
        assertEquals(2, playlists().size)
    }
}
