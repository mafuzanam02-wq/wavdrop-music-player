package com.launchpoint.wavdrop.data.repository

import androidx.room.Room
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
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
 * P5-AUDIT-06. Concurrent create / rename / queue-save must not produce two playlists whose names are equal ignoring case
 * (WavDrop's existing policy). Real repository, real in-memory Room; the race is genuine concurrency on Dispatchers.Default, not a bypass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistNameConcurrencyRoomTest {
    private lateinit var db: WavdropDatabase
    private lateinit var repo: PlaylistRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java).allowMainThreadQueries().build()
        repo = PlaylistRepository(db, db.playlistDao())
    }
    @After fun tearDown() = db.close()

    private fun all() = runBlocking { db.playlistDao().getAllPlaylistsSnapshot() }
    private fun named(name: String) = all().filter { it.name.trim().equals(name, ignoreCase = true) }
    private fun entries(id: Long) = runBlocking { db.playlistDao().getSongsForPlaylistSnapshot(id) }.map { it.songId }
    private fun create(name: String) = (runBlocking { repo.createPlaylist(name) } as PlaylistOperationResult.Success).playlistId
    private fun <T> race(n: Int, block: suspend (Int) -> T): List<T> =
        runBlocking { (0 until n).map { i -> async(Dispatchers.Default) { block(i) } }.awaitAll() }

    @Test fun `A concurrent creation of the same name yields one playlist`() {
        val results = race(40) { repo.createPlaylist("Roadtrip") }
        assertEquals("one winner", 1, results.count { it is PlaylistOperationResult.Success })
        assertEquals("every loser reports DuplicateName", 39, results.count { it == PlaylistOperationResult.DuplicateName })
        assertEquals(1, all().size)
    }

    @Test fun `B concurrent creation with case and whitespace variations yields one playlist`() {
        val variants = listOf("Roadtrip", "ROADTRIP", " roadtrip ")
        val results = race(42) { repo.createPlaylist(variants[it % 3]) }
        assertEquals(1, results.count { it is PlaylistOperationResult.Success })
        assertEquals(1, named("roadtrip").size)
        assertEquals(1, all().size)
    }

    @Test fun `C two playlists concurrently renamed to the same name leave exactly one holder`() {
        val ids = (0 until 20).map { create("Original $it") }
        val results = race(ids.size) { repo.renamePlaylist(ids[it], "Target") }
        assertEquals(1, results.count { it is PlaylistOperationResult.Success })
        assertEquals(19, results.count { it == PlaylistOperationResult.DuplicateName })
        assertEquals(1, named("Target").size)
        assertEquals("losers keep their original names", 19, all().count { it.name.startsWith("Original ") })
    }

    @Test fun `D concurrent rename and create targeting the same name yield one holder`() {
        val ids = (0 until 10).map { create("Existing $it") }
        val results = race(20) { i -> if (i < 10) repo.renamePlaylist(ids[i], "Same") else repo.createPlaylist("same") }
        assertEquals(1, results.count { it is PlaylistOperationResult.Success })
        assertEquals(1, named("Same").size)
        assertEquals("no playlist was added or lost besides the one winner", 10 + results.count { it is PlaylistOperationResult.Success } - results.take(10).count { it is PlaylistOperationResult.Success }, all().size)
    }

    @Test fun `E a normal create racing with queue-save yields one holder and keeps the winner's content`() {
        val results = race(30) { i -> if (i % 2 == 0) repo.createPlaylist("Mix") else repo.createPlaylistFromQueue("MIX", listOf(1L, 2L, 3L)) }
        val winners = results.count { it is PlaylistOperationResult.Success || it is QueueSaveResult.Success }
        assertEquals(1, winners)
        val holder = named("mix").single()
        val queueWon = results.any { it is QueueSaveResult.Success }
        assertEquals(if (queueWon) listOf(1L, 2L, 3L) else emptyList<Long>(), entries(holder.playlistId))
    }

    @Test fun `different names concurrently all succeed`() {
        val results = race(30) { repo.createPlaylist("Name $it") }
        assertEquals(30, results.count { it is PlaylistOperationResult.Success })
        assertEquals(30, all().size)
    }

    @Test fun `rename rules - own name and case-only variation allowed, other names and blanks rejected`() {
        val a = create("Alpha"); val b = create("Beta")
        assertTrue(runBlocking { repo.renamePlaylist(a, "Alpha") } is PlaylistOperationResult.Success)
        assertTrue(runBlocking { repo.renamePlaylist(a, "ALPHA") } is PlaylistOperationResult.Success)
        assertEquals("ALPHA", all().first { it.playlistId == a }.name)
        assertEquals(PlaylistOperationResult.DuplicateName, runBlocking { repo.renamePlaylist(b, " alpha ") })
        assertEquals(PlaylistOperationResult.BlankName, runBlocking { repo.renamePlaylist(b, "   ") })
        assertEquals(PlaylistOperationResult.BlankName, runBlocking { repo.createPlaylist("  ") })
        assertEquals("Beta", all().first { it.playlistId == b }.name)
    }

    @Test fun `rejected operations preserve ids, names, timestamps and entries`() {
        val a = (runBlocking { repo.createPlaylistFromQueue("Keep", listOf(5L, 6L)) } as QueueSaveResult.Success).playlistId
        val b = create("Other")
        val before = all(); val entriesBefore = entries(a)
        Thread.sleep(15)
        assertEquals(PlaylistOperationResult.DuplicateName, runBlocking { repo.createPlaylist("keep") })
        assertEquals(QueueSaveResult.DuplicateName, runBlocking { repo.createPlaylistFromQueue("KEEP", listOf(9L)) })
        assertEquals(PlaylistOperationResult.DuplicateName, runBlocking { repo.renamePlaylist(b, "Keep") })
        assertEquals(before, all())
        assertEquals(entriesBefore, entries(a))
    }

    @Test fun `legacy duplicate names already in the database are never deleted or renamed`() {
        runBlocking {
            db.playlistDao().insertPlaylist(PlaylistEntity(name = "Dup", createdAt = 1, updatedAt = 1))
            db.playlistDao().insertPlaylist(PlaylistEntity(name = "dup", createdAt = 2, updatedAt = 2))
        }
        val before = all()
        assertEquals(PlaylistOperationResult.DuplicateName, runBlocking { repo.createPlaylist("DUP") })
        val other = create("Other")
        assertEquals(PlaylistOperationResult.DuplicateName, runBlocking { repo.renamePlaylist(other, "dup") })
        assertEquals(before.map { it.name to it.createdAt }, all().filter { it.name.equals("dup", true) }.map { it.name to it.createdAt })
        assertEquals(3, all().size)
    }
}
