package com.launchpoint.wavdrop.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playlists.PlaylistSongRemapPlanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * WC-08 on real Room/SQLite (Robolectric): the transaction application seam used by SongRepository.sync() — plan, playlist remap,
 * song write/delete, identity reconciliation — with operation counts from a counting SongDao and SQLite's own `total_changes()`.
 * SongRepository itself needs the Android MediaStore scanner, so the seam (applySongSyncPlan / reconcileTrackIdentities) is driven
 * directly in the same order sync() uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongSyncApplyRoomTest {

    private lateinit var db: WavdropDatabase
    private lateinit var realSongDao: SongDao
    private val counting = Counting()

    private class Counting {
        var upsertCalls = 0; var upsertRows = 0; var deleteCalls = 0; var deletedIds = 0; var deleteAllCalls = 0
        fun reset() { upsertCalls = 0; upsertRows = 0; deleteCalls = 0; deletedIds = 0; deleteAllCalls = 0 }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WavdropDatabase::class.java).allowMainThreadQueries().build()
        realSongDao = db.songDao()
    }

    @After
    fun tearDown() = db.close()

    private val countingSongDao: SongDao by lazy {
        object : SongDao by realSongDao {
            override suspend fun upsertAll(songs: List<SongEntity>) { counting.upsertCalls++; counting.upsertRows += songs.size; realSongDao.upsertAll(songs) }
            override suspend fun deleteByIds(ids: List<Long>) { counting.deleteCalls++; counting.deletedIds += ids.size; realSongDao.deleteByIds(ids) }
            override suspend fun deleteAll() { counting.deleteAllCalls++; realSongDao.deleteAll() }
        }
    }

    private fun song(id: Long, title: String = "Song $id", artist: String = "Artist $id", duration: Long = 180_000L + id * 1_000L) = Song(
        id = id, title = title, artist = artist, album = "Album ${id % 7}", albumId = id % 7 + 1, duration = duration,
        uri = "content://media/external/audio/media/$id", dateAdded = 1_700_000_000L + id, trackNumber = (id % 10).toInt(),
        year = 2020, folderPath = "/Music/F${id % 3}", folderName = "F${id % 3}",
    )

    /** The same orchestration as SongRepository.sync() for a non-empty scan. Returns the plan for assertions. */
    private suspend fun sync(found: List<Song>): SongSyncPlan = db.withTransaction {
        val plan = SongSyncPlanner.plan(realSongDao.getAllSongsSnapshot(), found)
        val remap = PlaylistSongRemapPlanner.plan(staleSongs = plan.staleSongs, newSongs = plan.newSongs)
        applySongSyncPlan(plan, remap, countingSongDao, db.playlistDao())
        reconcileTrackIdentities(plan.liveSongIds, db.trackIdentityDao())
        plan
    }

    private fun totalChanges(): Long =
        db.query(SimpleSQLiteQuery("SELECT total_changes()"), null).use { it.moveToFirst(); it.getLong(0) }

    // ── A. unchanged scan ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anUnchangedScanWritesNoSongRowAndDeletesNothing() = runBlocking {
        val songs = (1L..400L).map { song(it) }
        sync(songs) // first scan seeds songs + identities
        counting.reset()
        val before = totalChanges()

        sync(songs.reversed())

        assertEquals(0, counting.upsertCalls); assertEquals(0, counting.upsertRows)
        assertEquals(0, counting.deleteCalls); assertEquals(0, counting.deleteAllCalls)
        assertEquals("SQLite saw no row change at all (no REPLACE, no DELETE, no identity write)", 0L, totalChanges() - before)
        assertEquals(400, realSongDao.getAllSongsSnapshot().size)
    }

    // ── B. changed row ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun oneMetadataChangeWritesExactlyThatRow() = runBlocking {
        val songs = (1L..100L).map { song(it) }
        sync(songs)
        counting.reset()

        val plan = sync(songs.map { if (it.id == 5L) it.copy(title = "Renamed", year = 1999) else it })

        assertEquals(1, counting.upsertRows)
        assertEquals(0, counting.deleteCalls)
        assertTrue(plan.newSongs.isEmpty()) // a same-id update is never a "new" song
        val stored = realSongDao.getAllSongsSnapshot().first { it.id == 5L }
        assertEquals("Renamed", stored.title); assertEquals(1999, stored.year)
    }

    // ── C. new + stale ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun newRowsAreInsertedStaleRowsDeletedAndIdentitiesFollow() = runBlocking {
        val songs = (1L..20L).map { song(it) }
        sync(songs)
        val identityOfStale = db.trackIdentityDao().getByCurrentSongId(3L).single().identityUuid
        counting.reset()

        sync(songs.filter { it.id != 3L } + song(999L))

        assertEquals(1, counting.upsertRows); assertEquals(1, counting.deletedIds)
        val ids = realSongDao.getAllSongsSnapshot().map { it.id }.toSet()
        assertTrue(999L in ids && 3L !in ids)
        val identities = db.trackIdentityDao().getAllSnapshot()
        assertNull("identity of the vanished song is cleared, not deleted", identities.first { it.identityUuid == identityOfStale }.currentSongId)
        assertNotNull(db.trackIdentityDao().getByCurrentSongId(999L).singleOrNull())
        assertEquals(21, identities.size) // 20 original (one now unbound) + 1 minted; none deleted
    }

    // ── F. identity minted even when no song row changed ────────────────────────────────────────────────────────────────

    @Test fun aLiveSongWithoutAnIdentityStillGetsOneOnAnOtherwiseUnchangedScan() = runBlocking {
        val songs = (1L..10L).map { song(it) }
        realSongDao.upsertAll(songs.map { it.toEntity() }) // historical rows with no identity
        counting.reset()

        sync(songs)

        assertEquals("no song row written", 0, counting.upsertRows)
        assertEquals(10, db.trackIdentityDao().getAllSnapshot().size)
        assertEquals((1L..10L).toSet(), db.trackIdentityDao().getAllCurrentSongIds().toSet())
    }

    // ── D. playlist remap ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aVanishedSongWithASafeMatchRemapsItsPlaylistMembershipAndAChangedSameIdRowDoesNot() = runBlocking {
        val keep = song(30L)
        val old = song(10L, title = "Remap Me", artist = "The Band", duration = 200_000L)
        sync(listOf(keep, old))
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = "P", createdAt = 1L, updatedAt = 1L))
        db.playlistDao().insertSongs(listOf(
            PlaylistSongEntity(playlistId, songId = 10L, position = 0),
            PlaylistSongEntity(playlistId, songId = 30L, position = 1),
        ))
        val reborn = old.copy(id = 200L, uri = "content://media/external/audio/media/200") // same tags, new MediaStore id
        counting.reset()

        // 30 also changes metadata under the SAME id: it must be updated, never treated as a remap destination
        sync(listOf(keep.copy(title = "Changed Title"), reborn))

        val members = db.playlistDao().getSongsForPlaylistSnapshot(playlistId).map { it.songId }.sorted()
        assertEquals(listOf(30L, 200L), members)
        assertEquals(2, counting.upsertRows) // reborn (new) + keep (changed)
        assertEquals(1, counting.deletedIds)
    }

    @Test fun anAmbiguousVanishedSongStaysAnOrphanMembership() = runBlocking {
        val old = song(10L, title = "Twin", artist = "A", duration = 200_000L)
        sync(listOf(old, song(31L)))
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = "P", createdAt = 1L, updatedAt = 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(playlistId, songId = 10L, position = 0))
        val twinA = old.copy(id = 300L, uri = "content://media/300")
        val twinB = old.copy(id = 301L, uri = "content://media/301")

        sync(listOf(song(31L), twinA, twinB))

        assertEquals("ambiguous: membership intentionally left on the old id", listOf(10L), db.playlistDao().getSongsForPlaylistSnapshot(playlistId).map { it.songId })
    }

    // ── E. history preservation ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun deletingAStaleSongKeepsItsStatsAndListenHistory() = runBlocking {
        val songs = (1L..5L).map { song(it) }
        sync(songs)
        db.trackStatsDao().insertIfAbsent(TrackStatsEntity(songId = 2L, contentUri = "u2", playCount = 9))
        db.trackListenEventDao().insert(
            TrackListenEventEntity(songId = 2L, eventType = "PLAY", occurredAt = 5L, listenedMs = 1L, durationMs = 2L, source = TrackListenEventEntity.SOURCE_WAVDROP_PLAYBACK),
        )

        sync(songs.filter { it.id != 2L })

        assertTrue(realSongDao.getAllSongsSnapshot().none { it.id == 2L })
        assertEquals(9, db.trackStatsDao().getAllStatsSnapshot().first { it.songId == 2L }.playCount)
        assertEquals(1, db.trackListenEventDao().getAllSnapshot().size)
    }

    // ── sparse change at scale on real SQLite ───────────────────────────────────────────────────────────────────────────

    @Test fun aSparseChangeOnALargeLibraryOnlyTouchesTheChangedRowsInSqlite() = runBlocking {
        val songs = (1L..3_000L).map { song(it) }
        sync(songs)
        counting.reset()
        val before = totalChanges()

        val scan = songs.filter { it.id !in setOf(2_999L, 3_000L) }.map { if (it.id in 10L..16L) it.copy(album = "Edited") else it } + (9_001L..9_003L).map { song(it) }
        sync(scan)

        assertEquals(10, counting.upsertRows) // 7 changed + 3 new, not 3,001
        assertEquals(2, counting.deletedIds)
        assertTrue("writes bounded by the change set (rows + identity bookkeeping), not by library size", totalChanges() - before < 40)
    }
}
