package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.launchpoint.wavdrop.data.local.entity.TrackStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackStatsDao {

    // Insert a blank row the first time a song is encountered.
    // IGNORE means subsequent calls for the same songId are no-ops.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: TrackStatsEntity)

    @Query("SELECT * FROM track_stats")
    fun getAllStats(): Flow<List<TrackStatsEntity>>

    @Query("SELECT * FROM track_stats ORDER BY songId ASC")
    suspend fun getAllStatsSnapshot(): List<TrackStatsEntity>

    @Query("SELECT * FROM track_stats WHERE songId IN (:songIds)")
    fun getStatsForSongs(songIds: List<Long>): Flow<List<TrackStatsEntity>>

    @Query("SELECT * FROM track_stats WHERE songId = :songId")
    fun observeStatsBySongId(songId: Long): Flow<TrackStatsEntity?>

    @Query("SELECT * FROM track_stats ORDER BY playCount DESC")
    fun getMostPlayed(): Flow<List<TrackStatsEntity>>

    @Query("SELECT * FROM track_stats WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC")
    fun getRecentlyPlayed(): Flow<List<TrackStatsEntity>>

    @Query("SELECT * FROM track_stats ORDER BY skipCount DESC")
    fun getMostSkipped(): Flow<List<TrackStatsEntity>>

    @Query("""
        UPDATE track_stats
        SET playCount           = playCount + 1,
            lastPlayedAt        = :nowMs,
            totalListeningTimeMs = totalListeningTimeMs + :listenedMs
        WHERE songId = :songId
    """)
    suspend fun incrementPlayCount(songId: Long, nowMs: Long, listenedMs: Long)

    @Query("UPDATE track_stats SET skipCount = skipCount + 1 WHERE songId = :songId")
    suspend fun incrementSkipCount(songId: Long)

    @Query("UPDATE track_stats SET isFavorite = :isFavorite WHERE songId = :songId")
    suspend fun setFavorite(songId: Long, isFavorite: Boolean)

    @Query("UPDATE track_stats SET isFavorite = CASE WHEN isFavorite = 0 THEN 1 ELSE 0 END WHERE songId = :songId")
    suspend fun toggleFavorite(songId: Long)

    @Query("SELECT songId FROM track_stats WHERE isFavorite = 1")
    fun favoriteSongIds(): Flow<List<Long>>

    /**
     * Merge-adds imported BlackPlayer stats into an existing row.
     * - playCount and skipCount are incremented (never replaced).
     * - lastPlayedAt is set to the newer of the two timestamps.
     * Row must already exist (call [insertIfAbsent] first).
     */
    @Query("""
        UPDATE track_stats
        SET playCount    = playCount + :addPlays,
            skipCount    = skipCount + :addSkips,
            lastPlayedAt = CASE WHEN lastPlayedAt > :importedLastPlayedAt
                                THEN lastPlayedAt
                                ELSE :importedLastPlayedAt
                           END
        WHERE songId = :songId
    """)
    suspend fun mergeImportedStats(
        songId: Long,
        addPlays: Int,
        addSkips: Int,
        importedLastPlayedAt: Long,
    )
}
