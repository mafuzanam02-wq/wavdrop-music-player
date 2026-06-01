package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanner
import com.launchpoint.wavdrop.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SongRepository @Inject constructor(
    private val dao: SongDao,
    private val scanner: MediaStoreScanner,
) {
    val songs: Flow<List<Song>> = dao.getAllSongs().map { it.map(SongEntity::toDomain) }

    suspend fun sync() = withContext(Dispatchers.IO) {
        val found = scanner.scanSongs()
        dao.upsertAll(found.map(Song::toEntity))
        if (found.isNotEmpty()) {
            dao.pruneDeleted(found.map(Song::id))
        }
    }
}

private fun SongEntity.toDomain() = Song(
    id = id, title = title, artist = artist, album = album,
    albumId = albumId, duration = duration, uri = uri,
    dateAdded = dateAdded, trackNumber = trackNumber, year = year,
)

private fun Song.toEntity() = SongEntity(
    id = id, title = title, artist = artist, album = album,
    albumId = albumId, duration = duration, uri = uri,
    dateAdded = dateAdded, trackNumber = trackNumber, year = year,
)
