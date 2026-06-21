package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackListenEventDao {

    @Insert
    suspend fun insert(event: TrackListenEventEntity)

    @Query("SELECT * FROM track_listen_events ORDER BY occurredAt DESC")
    suspend fun getAllSnapshot(): List<TrackListenEventEntity>

    @Query("""
        SELECT * FROM track_listen_events
        WHERE occurredAt >= :fromMs AND occurredAt <= :toMs
    """)
    suspend fun getInRangeSnapshot(fromMs: Long, toMs: Long): List<TrackListenEventEntity>

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

    /**
     * Per-song engagement summary from native Wavdrop playback only.
     *
     * Aggregates both PLAY events (threshold crossed) and SKIP events (abandoned before
     * threshold) so that usually-abandoned songs are visible even when they only produce
     * SKIP events. Songs with source != 'wavdrop_playback' (imports, restores) are excluded.
     *
     * [avgCompletion] averages only PLAY events with known durationMs (> 0), capped at 1.0
     * per event via CASE WHEN. Yields 0.0 via COALESCE when no valid plays exist.
     * Re-emits whenever the track_listen_events table changes.
     */
    @Query("""
        SELECT
            songId,
            SUM(CASE WHEN eventType = 'PLAY' THEN 1 ELSE 0 END) AS nativePlays,
            SUM(CASE WHEN eventType = 'SKIP' THEN 1 ELSE 0 END) AS nativeSkips,
            SUM(CASE WHEN eventType = 'PLAY' AND durationMs > 0 THEN 1 ELSE 0 END) AS validCompletionPlays,
            COALESCE(AVG(CASE WHEN eventType = 'PLAY' AND durationMs > 0 THEN
                CASE WHEN CAST(listenedMs AS REAL) / CAST(durationMs AS REAL) > 1.0
                     THEN 1.0
                     ELSE CAST(listenedMs AS REAL) / CAST(durationMs AS REAL)
                END
            ELSE NULL END), 0.0) AS avgCompletion
        FROM track_listen_events
        WHERE source = 'wavdrop_playback'
        GROUP BY songId
    """)
    fun observeCompletionSummaries(): Flow<List<SongCompletionSummary>>
}
