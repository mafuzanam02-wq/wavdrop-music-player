package com.launchpoint.wavdrop.data.repository

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.model.PlaylistSong
import com.launchpoint.wavdrop.data.model.PlaylistSummary
import com.launchpoint.wavdrop.data.playlists.PlaylistNameRules
import com.launchpoint.wavdrop.data.playlists.PlaylistPositionEntry
import com.launchpoint.wavdrop.data.playlists.PlaylistPositionRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PlaylistOperationResult {
    data class Success(val playlistId: Long) : PlaylistOperationResult
    data object BlankName                    : PlaylistOperationResult
    data object DuplicateName               : PlaylistOperationResult
}

data class AddToPlaylistResult(val added: Int, val skipped: Int)

@Singleton
class PlaylistRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val dao: PlaylistDao,
) {
    fun observePlaylists(): Flow<List<PlaylistSummary>> =
        dao.getAllPlaylistsWithCount().map { list ->
            list.map { row ->
                PlaylistSummary(
                    id        = row.playlistId,
                    name      = row.name,
                    songCount = row.songCount,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                )
            }
        }

    fun observePlaylistSongs(playlistId: Long): Flow<List<PlaylistSong>> =
        dao.getSongsForPlaylist(playlistId).map { it.map(PlaylistSongEntity::toDomain) }

    fun observeAllPlaylistSongs(): Flow<List<PlaylistSong>> =
        dao.getAllPlaylistSongs().map { it.map(PlaylistSongEntity::toDomain) }

    suspend fun createPlaylist(name: String): PlaylistOperationResult {
        val trimmed = PlaylistNameRules.normalize(name)
        if (trimmed.isBlank()) return PlaylistOperationResult.BlankName
        if (dao.countByName(trimmed) > 0) return PlaylistOperationResult.DuplicateName
        val now = System.currentTimeMillis()
        val id = dao.insertPlaylist(
            PlaylistEntity(name = trimmed, createdAt = now, updatedAt = now)
        )
        return PlaylistOperationResult.Success(id)
    }

    suspend fun renamePlaylist(id: Long, name: String): PlaylistOperationResult {
        val trimmed = PlaylistNameRules.normalize(name)
        if (trimmed.isBlank()) return PlaylistOperationResult.BlankName
        if (dao.countByNameExcluding(trimmed, excludeId = id) > 0) {
            return PlaylistOperationResult.DuplicateName
        }
        dao.renamePlaylist(id = id, name = trimmed, updatedAt = System.currentTimeMillis())
        return PlaylistOperationResult.Success(id)
    }

    suspend fun deletePlaylist(id: Long) {
        dao.deletePlaylist(id)
    }

    suspend fun addSongToPlaylist(songId: Long, playlistId: Long): AddToPlaylistResult =
        addSongsToPlaylist(playlistId = playlistId, songIds = listOf(songId))

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>): AddToPlaylistResult {
        if (songIds.isEmpty()) return AddToPlaylistResult(added = 0, skipped = 0)
        return db.withTransaction {
            val existingIds = dao.getSongsForPlaylistSnapshot(playlistId)
                .mapTo(mutableSetOf()) { it.songId }
            val newIds = songIds.filterNot { it in existingIds }
            val skipped = songIds.size - newIds.size
            if (newIds.isNotEmpty()) {
                val nextPos = dao.getMaxPosition(playlistId) + 1
                dao.insertSongs(
                    newIds.mapIndexed { index, id ->
                        PlaylistSongEntity(
                            playlistId = playlistId,
                            songId     = id,
                            position   = nextPos + index,
                        )
                    },
                )
                dao.touchPlaylist(playlistId, System.currentTimeMillis())
            }
            AddToPlaylistResult(added = newIds.size, skipped = skipped)
        }
    }

    suspend fun removeSongFromPlaylist(songId: Long, playlistId: Long) {
        db.withTransaction {
            val position = dao.getSongsForPlaylistSnapshot(playlistId)
                .firstOrNull { it.songId == songId }
                ?.position
                ?: return@withTransaction
            replacePlaylistSongs(
                playlistId = playlistId,
                entries    = PlaylistPositionRules.removeAtPosition(
                    current  = playlistEntries(playlistId),
                    position = position,
                ),
            )
        }
    }

    suspend fun removePlaylistEntry(playlistId: Long, position: Int) {
        db.withTransaction {
            replacePlaylistSongs(
                playlistId = playlistId,
                entries    = PlaylistPositionRules.removeAtPosition(
                    current  = playlistEntries(playlistId),
                    position = position,
                ),
            )
        }
    }

    suspend fun movePlaylistSong(playlistId: Long, fromPosition: Int, toPosition: Int) {
        db.withTransaction {
            replacePlaylistSongs(
                playlistId = playlistId,
                entries    = PlaylistPositionRules.move(
                    current      = playlistEntries(playlistId),
                    fromPosition = fromPosition,
                    toPosition   = toPosition,
                ),
            )
        }
    }

    suspend fun clearPlaylist(playlistId: Long) {
        db.withTransaction {
            dao.clearPlaylist(playlistId)
            dao.touchPlaylist(playlistId, System.currentTimeMillis())
        }
    }

    private suspend fun playlistEntries(playlistId: Long): List<PlaylistPositionEntry> =
        dao.getSongsForPlaylistSnapshot(playlistId).map { entity ->
            PlaylistPositionEntry(songId = entity.songId, position = entity.position)
        }

    private suspend fun replacePlaylistSongs(
        playlistId: Long,
        entries: List<PlaylistPositionEntry>,
    ) {
        dao.clearPlaylist(playlistId)
        if (entries.isNotEmpty()) {
            dao.insertSongs(
                entries.map { entry ->
                    PlaylistSongEntity(
                        playlistId = playlistId,
                        songId     = entry.songId,
                        position   = entry.position,
                    )
                },
            )
        }
        dao.touchPlaylist(playlistId, System.currentTimeMillis())
    }
}
