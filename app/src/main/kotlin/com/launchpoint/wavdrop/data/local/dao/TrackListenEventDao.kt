package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackListenEventDao {

    @Insert
    suspend fun insert(event: TrackListenEventEntity)

    /** All events, most recent first. Use for full-history analytics (Monthly Reports, Wrapped). */
    @Query("SELECT * FROM track_listen_events ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TrackListenEventEntity>>

    /** Events in an inclusive time range, most recent first. */
    @Query("""
        SELECT * FROM track_listen_events
        WHERE occurredAt >= :fromMs AND occurredAt <= :toMs
        ORDER BY occurredAt DESC
    """)
    fun observeInRange(fromMs: Long, toMs: Long): Flow<List<TrackListenEventEntity>>

    /** All events for a specific song, most recent first. */
    @Query("SELECT * FROM track_listen_events WHERE songId = :songId ORDER BY occurredAt DESC")
    fun observeForSong(songId: Long): Flow<List<TrackListenEventEntity>>
}
