package com.launchpoint.wavdrop.data.repository

import android.util.Log
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanner
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.search.SongSort
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SongRepository @Inject constructor(
    private val dao: SongDao,
    private val playlistDao: PlaylistDao,
    private val scanner: MediaStoreScanner,
    private val scanSettingsRepository: LibraryScanSettingsRepository,
) {
    val songs: Flow<List<Song>> = dao.getAllSongs().map { entities ->
        entities.map(SongEntity::toDomain).sortedWith(SongSort.byTitle)
    }

    fun observeSongById(songId: Long): Flow<Song?> =
        dao.observeSongById(songId).map { it?.toDomain() }

    suspend fun pruneSong(songId: Long) {
        dao.deleteSong(songId)
    }

    suspend fun sync(): LibrarySyncResult = withContext(Dispatchers.IO) {
        val scanSettings = scanSettingsRepository.settings.first()
        val found = scanner.scanSongs(scanSettings)

        val currentIds = dao.getAllSongIds().toSet()

        if (found.isEmpty()) {
            if (SongSyncPolicy.shouldPreserveOnEmptyScan(scanSettings, currentIds.size)) {
                Log.w(TAG,
                    "Scan returned 0 songs — preserving ${currentIds.size} existing songs. " +
                    "Mode: ${scanSettings.scanMode}"
                )
                return@withContext LibrarySyncResult.EmptyPreserved(
                    SongSyncPolicy.emptyPreservedReason(scanSettings)
                )
            }
            dao.deleteAll()
            return@withContext LibrarySyncResult.Success(0)
        }

        dao.upsertAll(found.map(Song::toEntity))

        val activeIds = found.map { it.id }.toSet()
        val staleIds = SongSyncPolicy.computeStaleIds(currentIds, activeIds)
        staleIds.chunked(PRUNE_CHUNK_SIZE).forEach { chunk ->
            dao.deleteByIds(chunk)
            // Remove playlist memberships for songs that are no longer in the library.
            // track_stats, track_listen_events, lyrics_overrides, and import_baselines are
            // intentionally kept: they reconnect automatically if the file reappears with the
            // same MediaStore ID, and they preserve listening history across temporary scan gaps.
            playlistDao.removeEntriesForSongs(chunk)
        }

        LibrarySyncResult.Success(found.size)
    }

    private companion object {
        const val TAG = "Wavdrop-Sync"
        const val PRUNE_CHUNK_SIZE = 500
    }
}

private fun SongEntity.toDomain() = Song(
    id = id, title = title, artist = artist, album = album,
    albumId = albumId, duration = duration, uri = uri,
    dateAdded = dateAdded, trackNumber = trackNumber, year = year,
    folderPath = folderPath, folderName = folderName,
)

private fun Song.toEntity() = SongEntity(
    id = id, title = title, artist = artist, album = album,
    albumId = albumId, duration = duration, uri = uri,
    dateAdded = dateAdded, trackNumber = trackNumber, year = year,
    folderPath = folderPath, folderName = folderName,
)
