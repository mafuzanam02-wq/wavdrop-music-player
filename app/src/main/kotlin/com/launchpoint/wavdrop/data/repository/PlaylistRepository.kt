package com.launchpoint.wavdrop.data.repository

import androidx.room.withTransaction
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import com.launchpoint.wavdrop.data.model.ExternalAudioIdentity
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

/** Result of [PlaylistRepository.createPlaylistFromQueue]. Separate from [PlaylistOperationResult] (QSP-1 has its own contract). */
sealed interface QueueSaveResult {
    data class Success(val playlistId: Long, val songCount: Int) : QueueSaveResult
    data object BlankName     : QueueSaveResult
    data object DuplicateName : QueueSaveResult
    /** Nothing to save: no playlist is created. */
    data object EmptyQueue    : QueueSaveResult
    /** The queue holds a song that is not a library song (external ACTION_VIEW audio): nothing is saved, nothing is filtered. */
    data object UnsavableQueue : QueueSaveResult
}

data class AddToPlaylistResult(val added: Int, val skipped: Int) {

    fun singleSongMessage(): String = when {
        added == 1  -> "Added to playlist"
        skipped > 0 -> "Already in playlist"
        else        -> "Could not add to playlist"
    }

    fun multiAddMessage(): String = when {
        added > 0 && skipped == 0 ->
            if (added == 1) "Added 1 song" else "Added $added songs"
        added > 0 && skipped > 0  ->
            "${if (added == 1) "Added 1 song" else "Added $added songs"} • $skipped already in playlist"
        skipped > 0               ->
            if (skipped == 1) "Already in playlist" else "All selected songs are already in this playlist"
        else                      -> "No songs were added"
    }
}

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

    /**
     * QSP-1: persists a queue snapshot as a NEW ordinary playlist, EXACTLY as given: [songIds] is inserted at positions
     * 0..N-1 in the supplied order and repeated song ids are KEPT (the row identity is playlistId + position, so the same song
     * may appear at several positions). This deliberately does NOT go through [addSongsToPlaylist], whose duplicate
     * prevention is the contract of the ordinary "Add to playlist" action and is unchanged.
     *
     * The name goes through [PlaylistNameRules] (trimmed, blank rejected, case-insensitive duplicate rejected, nothing is ever
     * overwritten or auto-numbered). An empty list never creates a playlist. The duplicate check, the playlist row and every
     * entry (one batched insert) happen in ONE Room transaction, so a failure leaves neither a playlist nor partial entries.
     * The playlist is created once (createdAt == updatedAt) and not touched again. Playback is never involved.
     */
    suspend fun createPlaylistFromQueue(name: String, songIds: List<Long>): QueueSaveResult {
        val trimmed = PlaylistNameRules.normalize(name)
        if (trimmed.isBlank()) return QueueSaveResult.BlankName
        if (songIds.isEmpty()) return QueueSaveResult.EmptyQueue
        // All-or-nothing: a queue that contains external (non-library) audio is refused whole. The visible sequence is never
        // silently changed by dropping entries, and the synthetic external id is never persisted.
        if (songIds.any(ExternalAudioIdentity::isExternalAudioId)) return QueueSaveResult.UnsavableQueue
        val snapshot = songIds.toList() // immutable copy: later changes to the caller's list cannot reach the write
        return db.withTransaction {
            if (dao.countByName(trimmed) > 0) return@withTransaction QueueSaveResult.DuplicateName
            val now = System.currentTimeMillis()
            val playlistId = dao.insertPlaylist(PlaylistEntity(name = trimmed, createdAt = now, updatedAt = now))
            dao.insertSongs(
                snapshot.mapIndexed { index, songId ->
                    PlaylistSongEntity(playlistId = playlistId, songId = songId, position = index)
                },
            )
            QueueSaveResult.Success(playlistId = playlistId, songCount = snapshot.size)
        }
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

    suspend fun removeSongFromAllPlaylists(songId: Long) {
        dao.removeAllEntriesForSong(songId)
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
