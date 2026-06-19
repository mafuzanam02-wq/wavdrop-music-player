package com.launchpoint.wavdrop.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanner
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playlists.PlaylistSongRemapPlanner
import com.launchpoint.wavdrop.data.search.SongSort
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SongRepository @Inject constructor(
    private val db: WavdropDatabase,
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

        db.withTransaction {
            val existingEntities = dao.getAllSongsSnapshot()
            val existingIds = existingEntities.mapTo(mutableSetOf()) { it.id }

            if (found.isEmpty()) {
                if (SongSyncPolicy.shouldPreserveOnEmptyScan(scanSettings, existingIds.size)) {
                    Log.w(TAG,
                        "Scan returned 0 songs — preserving ${existingIds.size} existing songs. " +
                        "Mode: ${scanSettings.scanMode}"
                    )
                    return@withTransaction LibrarySyncResult.EmptyPreserved(
                        SongSyncPolicy.emptyPreservedReason(scanSettings)
                    )
                }
                dao.deleteAll()
                return@withTransaction LibrarySyncResult.Success(0)
            }

            val scannedIds = found.mapTo(mutableSetOf()) { it.id }
            val staleSongs = existingEntities
                .filter { it.id !in scannedIds }
                .map(SongEntity::toDomain)
            val newSongs = found.filter { it.id !in existingIds }
            val remapPlan = PlaylistSongRemapPlanner.plan(
                staleSongs = staleSongs,
                newSongs = newSongs,
            )

            dao.upsertAll(found.map(Song::toEntity))

            remapPlan.mappings.forEach { mapping ->
                playlistDao.removeRedundantEntriesForRemap(
                    oldSongId = mapping.oldSongId,
                    newSongId = mapping.newSongId,
                )
                playlistDao.remapSongId(
                    oldSongId = mapping.oldSongId,
                    newSongId = mapping.newSongId,
                )
            }

            val staleIds = SongSyncPolicy.computeStaleIds(existingIds, scannedIds)
            staleIds.chunked(PRUNE_CHUNK_SIZE).forEach { chunk ->
                dao.deleteByIds(chunk)
                // Unmatched or ambiguous playlist memberships are intentionally retained as
                // hidden orphan entries. Confirmed user deletion still removes memberships
                // through the explicit delete flow.
            }

            LibrarySyncResult.Success(found.size)
        }
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
