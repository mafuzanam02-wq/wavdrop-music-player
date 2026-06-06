package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.launchpoint.wavdrop.data.local.entity.PlaylistEntity
import com.launchpoint.wavdrop.data.local.entity.PlaylistSongEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithCount(
    val playlistId: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val songCount: Int,
)

@Dao
interface PlaylistDao {

    // ── Playlists ─────────────────────────────────────────────────────────────

    @Query("""
        SELECT p.playlistId, p.name, p.createdAt, p.updatedAt,
               COUNT(ps.songId) AS songCount
        FROM playlists p
        LEFT JOIN playlist_songs ps ON p.playlistId = ps.playlistId
        GROUP BY p.playlistId
        ORDER BY p.name COLLATE NOCASE ASC
    """)
    fun getAllPlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllPlaylistsSnapshot(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE playlistId = :id")
    fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(entity: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE playlistId = :id")
    suspend fun renamePlaylist(id: Long, name: String, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE playlistId = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT COUNT(*) FROM playlists WHERE LOWER(name) = LOWER(:name)")
    suspend fun countByName(name: String): Int

    @Query("SELECT COUNT(*) FROM playlists WHERE LOWER(name) = LOWER(:name) AND playlistId != :excludeId")
    suspend fun countByNameExcluding(name: String, excludeId: Long): Int

    // ── Playlist songs ────────────────────────────────────────────────────────

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getSongsForPlaylist(playlistId: Long): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId ASC, position ASC")
    fun getAllPlaylistSongs(): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getSongsForPlaylistSnapshot(playlistId: Long): List<PlaylistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(entity: PlaylistSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(entities: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    suspend fun removeAllEntriesForSong(songId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE playlistId = :playlistId")
    suspend fun touchPlaylist(playlistId: Long, updatedAt: Long)
}
