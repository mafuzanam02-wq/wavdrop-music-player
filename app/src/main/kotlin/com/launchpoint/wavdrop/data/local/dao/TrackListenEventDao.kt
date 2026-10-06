package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.launchpoint.wavdrop.data.local.entity.TrackListenEventEntity
import com.launchpoint.wavdrop.data.model.HomeWrappedSongActivity
import com.launchpoint.wavdrop.data.model.SongCompletionSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackListenEventDao {

    @Insert
    suspend fun insert(event: TrackListenEventEntity)

    @Insert
    suspend fun insertAll(events: List<TrackListenEventEntity>)

    @Query("SELECT * FROM track_listen_events ORDER BY occurredAt DESC")
    suspend fun getAllSnapshot(): List<TrackListenEventEntity>

    @Query("""
        SELECT * FROM track_listen_events
        WHERE occurredAt >= :fromMs AND occurredAt <= :toMs
    """)
    suspend fun getInRangeSnapshot(fromMs: Long, toMs: Long): List<TrackListenEventEntity>

    /**
     * Non-null eventIds in an inclusive occurredAt range. Used by restore dedup to skip an
     * incoming event whose stable eventId already exists locally (P2-B1). Range-scoped to mirror
     * [getInRangeSnapshot]; legacy/null-eventId rows are excluded by the IS NOT NULL filter.
     */
    @Query("""
        SELECT eventId FROM track_listen_events
        WHERE eventId IS NOT NULL AND occurredAt >= :fromMs AND occurredAt <= :toMs
    """)
    suspend fun getEventIdsInRangeSnapshot(fromMs: Long, toMs: Long): List<String>

    /**
     * All events, most recent first. A genuine full-history ENTITY read: ordinary analytics screens must not use it (WC-02); they
     * use [observeCount], [observeAnalyticsEventTimestamps], [observePlayEventTimestamps] or [observeInRange] instead.
     */
    @Query("SELECT * FROM track_listen_events ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TrackListenEventEntity>>

    /**
     * Total number of listen-event rows (every type, exactly what `observeAll().size` was). A single COUNT so a screen that only
     * needs the number (Diagnostics) never materializes the rows. Re-emits when the table changes.
     */
    @Query("SELECT COUNT(*) FROM track_listen_events")
    fun observeCount(): Flow<Int>

    /**
     * occurredAt of every PLAY and SKIP event (the same two types the analytics builders accept; unsupported future types are
     * excluded). Timestamps only, so available months/years can be derived (in Kotlin, through the user's ZoneId) without
     * loading TrackListenEventEntity rows. Order is irrelevant to the callers.
     */
    @Query("SELECT occurredAt FROM track_listen_events WHERE eventType IN ('PLAY', 'SKIP')")
    fun observeAnalyticsEventTimestamps(): Flow<List<Long>>

    /**
     * occurredAt of every PLAY event, most recent first (the order the full-entity query used, which the most-active weekday/hour
     * tie-break depends on). Timestamps only, for streaks and most-active day/hour. SKIP is excluded.
     */
    @Query("SELECT occurredAt FROM track_listen_events WHERE eventType = 'PLAY' ORDER BY occurredAt DESC")
    fun observePlayEventTimestamps(): Flow<List<Long>>

    /** Latest PLAY/SKIP timestamp for analytics previews; ignores unsupported future event types. */
    @Query("""
        SELECT MAX(occurredAt) FROM track_listen_events
        WHERE eventType IN ('PLAY', 'SKIP')
    """)
    fun observeLatestAnalyticsEventAt(): Flow<Long?>

    /**
     * Home Wrapped preview aggregate (WC-05): one row per LIVE song (INNER JOIN songs) with PLAY and/or SKIP events in the inclusive
     * range, carrying only the per-song PLAY/SKIP counts plus the range's PLAY total over ALL events (orphan-song PLAYs included,
     * as the full Wrapped total counts them). Unsupported event types are excluded from the rows. The total rides on every row so one
     * result is internally coherent; no live activity means no rows. Room observes track_listen_events and songs.
     */
    @Query("""
        SELECT
            e.songId AS songId,
            SUM(CASE WHEN e.eventType = 'PLAY' THEN 1 ELSE 0 END) AS playCount,
            SUM(CASE WHEN e.eventType = 'SKIP' THEN 1 ELSE 0 END) AS skipCount,
            MAX(CASE WHEN e.eventType = 'PLAY' THEN e.occurredAt ELSE NULL END) AS latestPlayAt,
            (SELECT COUNT(*) FROM track_listen_events AS t
                WHERE t.eventType = 'PLAY' AND t.occurredAt >= :fromMs AND t.occurredAt <= :toMs) AS totalPlayCount
        FROM track_listen_events AS e
        INNER JOIN songs AS s ON s.id = e.songId
        WHERE e.occurredAt >= :fromMs AND e.occurredAt <= :toMs AND e.eventType IN ('PLAY', 'SKIP')
        GROUP BY e.songId
        ORDER BY e.songId ASC
    """)
    fun observeHomeWrappedActivity(fromMs: Long, toMs: Long): Flow<List<HomeWrappedSongActivity>>

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
     * Per-song engagement summary from trusted Wavdrop playback sources.
     *
     * Trusted sources:
     *   - 'wavdrop_playback'       — native on-device playback
     *   - 'manual_restore'         — events restored from a Wavdrop backup
     *   - 'wavdrop_desktop_playback' — verified events imported from Wavdrop Desktop
     *
     * BlackPlayer import events are excluded — they are aggregate-only and carry no
     * reliable per-event listenedMs/durationMs completion evidence.
     *
     * Aggregates both PLAY events (threshold crossed) and SKIP events (abandoned before
     * threshold) so that usually-abandoned songs are visible even when they only produce
     * SKIP events.
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
        WHERE source IN (
            'wavdrop_playback',
            'manual_restore',
            'wavdrop_desktop_playback'
        )
        GROUP BY songId
    """)
    fun observeCompletionSummaries(): Flow<List<SongCompletionSummary>>
}
