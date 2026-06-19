package com.launchpoint.wavdrop.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.playlists.PlaylistSongRemapPlanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistRetentionDaoTest {

    private lateinit var db: WavdropDatabase
    private lateinit var playlistDao: PlaylistDao
    private lateinit var songDao: SongDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WavdropDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        playlistDao = db.playlistDao()
        songDao = db.songDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun playlistCountExcludesOrphanEntries() = runBlocking {
        val playlistId = insertPlaylist()
        songDao.upsertAll(listOf(song(id = 1L)))
        playlistDao.insertSongs(
            listOf(
                PlaylistSongEntity(playlistId = playlistId, songId = 1L, position = 0),
                PlaylistSongEntity(playlistId = playlistId, songId = 2L, position = 1),
            ),
        )

        val playlists = playlistDao.getAllPlaylistsWithCount().first()

        assertEquals(1, playlists.single().songCount)
        assertEquals(2, playlistDao.getSongsForPlaylistSnapshot(playlistId).size)
    }

    @Test
    fun emptyPlaylistStillAppearsWithZeroCount() = runBlocking {
        insertPlaylist()

        val playlists = playlistDao.getAllPlaylistsWithCount().first()

        assertEquals(1, playlists.size)
        assertEquals(0, playlists.single().songCount)
    }

    @Test
    fun explicitDeletionCleanupStillRemovesPlaylistMembership() = runBlocking {
        val playlistId = insertPlaylist()
        playlistDao.insertSong(
            PlaylistSongEntity(playlistId = playlistId, songId = 7L, position = 0),
        )

        playlistDao.removeAllEntriesForSong(7L)

        assertEquals(emptyList<PlaylistSongEntity>(), playlistDao.getSongsForPlaylistSnapshot(playlistId))
    }

    @Test
    fun remapUpdatesAllPlaylistsAndPreservesPositions() = runBlocking {
        val firstPlaylist = insertPlaylist("First")
        val secondPlaylist = insertPlaylist("Second")
        playlistDao.insertSongs(
            listOf(
                PlaylistSongEntity(firstPlaylist, songId = 1L, position = 3),
                PlaylistSongEntity(secondPlaylist, songId = 1L, position = 7),
            ),
        )

        playlistDao.removeRedundantEntriesForRemap(oldSongId = 1L, newSongId = 11L)
        playlistDao.remapSongId(oldSongId = 1L, newSongId = 11L)

        assertEquals(
            listOf(PlaylistSongEntity(firstPlaylist, songId = 11L, position = 3)),
            playlistDao.getSongsForPlaylistSnapshot(firstPlaylist),
        )
        assertEquals(
            listOf(PlaylistSongEntity(secondPlaylist, songId = 11L, position = 7)),
            playlistDao.getSongsForPlaylistSnapshot(secondPlaylist),
        )
    }

    @Test
    fun existingTargetMembershipRemovesOnlyRedundantOldRow() = runBlocking {
        val playlistId = insertPlaylist()
        playlistDao.insertSongs(
            listOf(
                PlaylistSongEntity(playlistId, songId = 1L, position = 0),
                PlaylistSongEntity(playlistId, songId = 99L, position = 1),
                PlaylistSongEntity(playlistId, songId = 11L, position = 2),
            ),
        )

        playlistDao.removeRedundantEntriesForRemap(oldSongId = 1L, newSongId = 11L)
        playlistDao.remapSongId(oldSongId = 1L, newSongId = 11L)

        assertEquals(
            listOf(
                PlaylistSongEntity(playlistId, songId = 99L, position = 1),
                PlaylistSongEntity(playlistId, songId = 11L, position = 2),
            ),
            playlistDao.getSongsForPlaylistSnapshot(playlistId),
        )
    }

    @Test
    fun unmatchedOrphanRemainsUntouched() = runBlocking {
        val playlistId = insertPlaylist()
        val orphan = PlaylistSongEntity(playlistId, songId = 5L, position = 4)
        playlistDao.insertSong(orphan)

        playlistDao.removeRedundantEntriesForRemap(oldSongId = 1L, newSongId = 11L)
        playlistDao.remapSongId(oldSongId = 1L, newSongId = 11L)

        assertEquals(listOf(orphan), playlistDao.getSongsForPlaylistSnapshot(playlistId))
    }

    @Test
    fun visibleCountUpdatesAfterRemapAndStaleSongDeletion() = runBlocking {
        val playlistId = insertPlaylist()
        songDao.upsertAll(listOf(song(1L)))
        playlistDao.insertSong(
            PlaylistSongEntity(playlistId, songId = 1L, position = 0),
        )

        db.withTransaction {
            songDao.upsertAll(listOf(song(11L)))
            playlistDao.removeRedundantEntriesForRemap(oldSongId = 1L, newSongId = 11L)
            playlistDao.remapSongId(oldSongId = 1L, newSongId = 11L)
            songDao.deleteByIds(listOf(1L))
        }

        assertEquals(1, playlistDao.getAllPlaylistsWithCount().first().single().songCount)
        assertEquals(listOf(11L), songDao.getAllSongsSnapshot().map { it.id })
        assertEquals(
            listOf(PlaylistSongEntity(playlistId, songId = 11L, position = 0)),
            playlistDao.getSongsForPlaylistSnapshot(playlistId),
        )
    }

    @Test
    fun ambiguousPlanLeavesOldMembershipOrphanedWhileStaleSongIsDeleted() = runBlocking {
        val playlistId = insertPlaylist()
        val oldSong = song(1L).toDomain()
        val newA = song(11L).toDomain()
        val newB = song(12L).toDomain()
        songDao.upsertAll(listOf(song(1L)))
        playlistDao.insertSong(PlaylistSongEntity(playlistId, songId = 1L, position = 0))

        val plan = PlaylistSongRemapPlanner.plan(
            staleSongs = listOf(oldSong),
            newSongs = listOf(newA, newB),
        )
        db.withTransaction {
            songDao.upsertAll(listOf(song(11L), song(12L)))
            plan.mappings.forEach { mapping ->
                playlistDao.removeRedundantEntriesForRemap(mapping.oldSongId, mapping.newSongId)
                playlistDao.remapSongId(mapping.oldSongId, mapping.newSongId)
            }
            songDao.deleteByIds(listOf(1L))
        }

        assertEquals(emptyList<Long>(), songDao.getAllSongsSnapshot().map { it.id }.filter { it == 1L })
        assertEquals(
            listOf(PlaylistSongEntity(playlistId, songId = 1L, position = 0)),
            playlistDao.getSongsForPlaylistSnapshot(playlistId),
        )
        assertEquals(0, playlistDao.getAllPlaylistsWithCount().first().single().songCount)
    }

    private suspend fun insertPlaylist(): Long {
        return insertPlaylist("Retention")
    }

    private suspend fun insertPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        return playlistDao.insertPlaylist(
            PlaylistEntity(name = name, createdAt = now, updatedAt = now),
        )
    }

    private fun song(id: Long) = SongEntity(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 1,
        year = 2026,
    )

    private fun SongEntity.toDomain() = com.launchpoint.wavdrop.data.model.Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = duration,
        uri = uri,
        dateAdded = dateAdded,
        trackNumber = trackNumber,
        year = year,
        folderPath = folderPath,
        folderName = folderName,
    )
}
