package com.launchpoint.wavdrop.data.repository

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
    private val scanner: MediaStoreScanner,
    private val scanSettingsRepository: LibraryScanSettingsRepository,
) {
    val songs: Flow<List<Song>> = dao.getAllSongs().map { entities ->
        entities.map(SongEntity::toDomain).sortedWith(SongSort.byTitle)
    }

    fun observeSongById(songId: Long): Flow<Song?> =
        dao.observeSongById(songId).map { it?.toDomain() }

    suspend fun sync() = withContext(Dispatchers.IO) {
        val scanSettings = scanSettingsRepository.settings.first()
        val found = scanner.scanSongs(scanSettings)
        if (found.isEmpty()) {
            dao.deleteAll()
            return@withContext
        }
        dao.upsertAll(found.map(Song::toEntity))
        dao.pruneDeleted(found.map(Song::id))
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
