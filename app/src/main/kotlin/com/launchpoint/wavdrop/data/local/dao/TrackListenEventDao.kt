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

    /**
     * Recovery Restore only. Deletes exactly the event rows a Wavdrop backup exports (the [sources] accepted by
     * BackupEventExportRules), so the verified safety snapshot covers everything removed. Rows from any other source are never
     * exported and are therefore never deleted.
     */
    @Query("DELETE FROM track_listen_events WHERE source IN (:sources)")
    suspend fun deleteExportedEventsForRecovery(sources: List<String>): Int

    @Query("SELECT * FROM track_listen_events ORDER BY occurredAt DESC")
    suspend fun getAllSnapshot(): List<TrackListenEventEntity>

    /**
     * Highest event row id, or null for an empty table. WDBK export captures it once as the export snapshot's upper bound so
     * paging terminates even while playback keeps inserting rows.
     */
    @Query("SELECT MAX(id) FROM track_listen_events")
    suspend fun getMaxId(): Long?

    /**
     * Keyset page for WDBK export, in the canonical fingerprint order reduced to the event table:
     * occurredAt, songId, eventType, listenedMs, durationMs, source, COALESCE(eventId, ''), then id DESCENDING.
     *
     * The fingerprint orders by occurredAt, songId, contentUri, eventType, listenedMs, durationMs, source, title, artist,
     * album, eventId. contentUri/title/artist/album are properties of the song, so they are equal for equal songId and are
     * never reached for different songIds; they drop out.
     *
     * Final tie-break: id DESC. The legacy export read the history with getAllSnapshot() (ORDER BY occurredAt DESC) and
     * stable-sorted it by the fingerprint comparator; SQLite serves that from the occurredAt index scanned backwards, which
     * visits equal occurredAt entries in DESCENDING id order (observed through real Room/SQLite in a regression test). Rows
     * that tie on every comparator field therefore reached the fingerprint in descending id order, which matters because a
     * null eventId and an empty one compare equal yet emit different canonical records. id DESC reproduces exactly that
     * order. id is only the physical paging tie-breaker; it is not part of the event, the fingerprint or the payload.
     *
     * Keyset: rows strictly after the cursor and at or below the export snapshot bound upToId. The last key runs the
     * opposite way, so it is compared as -id: ONE eight-column row-value comparison
     * (..., COALESCE(eventId, ''), -id) > (..., :afterEventKey, -:afterId) is exactly "greater on the ascending keys, or equal
     * on them and a smaller id". (An explicit OR formulation is equivalent but makes SQLite switch to a MULTI-INDEX OR plus a
     * full sort of every remaining row per page; the single comparison keeps the occurredAt seek and a per-page partial
     * sort. Pinned by a query-plan test.) Domain: ids are SQLite rowids; positive auto-generated ids are the production case
     * and every id in [-(2^63-1), 2^63-1] is exact (negating Long.MIN_VALUE would overflow, so that single value is not
     * supported; it cannot be auto-generated). No OFFSET. The redundant occurredAt >= :afterAt term lets SQLite seek the
     * occurredAt index, and the unary plus in +id stops the planner from choosing a rowid range scan plus a whole-table sort
     * instead. Strings compare BINARY, i.e. by code point.
     * Start with (Long.MIN_VALUE, Long.MIN_VALUE, "", Long.MIN_VALUE, Long.MIN_VALUE, "", "", Long.MAX_VALUE).
     */
    @Query("""
        SELECT * FROM track_listen_events
        WHERE +id <= :upToId
          AND occurredAt >= :afterAt
          AND (occurredAt, songId, eventType, listenedMs, durationMs, source, COALESCE(eventId, ''), -id)
              > (:afterAt, :afterSongId, :afterEventType, :afterListenedMs, :afterDurationMs, :afterSource, :afterEventKey, -:afterId)
        ORDER BY occurredAt ASC, songId ASC, eventType ASC, listenedMs ASC, durationMs ASC, source ASC,
                 COALESCE(eventId, '') ASC, id DESC
        LIMIT :limit
    """)
    suspend fun getExportPage(
        afterAt: Long, afterSongId: Long, afterEventType: String, afterListenedMs: Long, afterDurationMs: Long,
        afterSource: String, afterEventKey: String, afterId: Long, upToId: Long, limit: Int,
    ): List<TrackListenEventEntity>

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
     * use [observeCount], [observeAnalyticsEventTimestamps], [playTimestampCursor] or [observeInRange] instead.
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
     * occurredAt of every PLAY event as a CURSOR, in no particular order (WC-09). It is meant to be reduced row-by-row (see
     * `InsightsPlayActivityReader`) and closed by the caller, NEVER materialized into a list: the most-active weekday/hour and streak
     * need only small buckets, grouped in Kotlin through the user's ZoneId (never SQLite date functions). Blocking: call it off the
     * main thread. SKIP and unsupported types are excluded. Re-run it when [observeCount] re-emits (any change to the table).
     */
    @Query("SELECT occurredAt FROM track_listen_events WHERE eventType = 'PLAY'")
    fun playTimestampCursor(): android.database.Cursor

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
