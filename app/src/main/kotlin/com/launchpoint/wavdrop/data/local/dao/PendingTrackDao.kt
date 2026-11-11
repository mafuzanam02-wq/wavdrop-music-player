package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.launchpoint.wavdrop.data.local.entity.PendingImportBaselineEntity
import com.launchpoint.wavdrop.data.local.entity.PendingListenEventEntity
import com.launchpoint.wavdrop.data.local.entity.PendingLyricsOverrideEntity
import com.launchpoint.wavdrop.data.local.entity.PendingPlaylistEntryEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackEntity
import com.launchpoint.wavdrop.data.local.entity.PendingTrackStatsEntity

@Dao
interface PendingTrackDao {

    // ── pending_tracks ────────────────────────────────────────────────────────

    /** Inserts a pending track. Returns the new rowId, or -1 if the originKey already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(entity: PendingTrackEntity): Long

    @Query("SELECT pendingId FROM pending_tracks WHERE originKey = :originKey")
    suspend fun getPendingId(originKey: String): Long?

    @Query("SELECT originKey FROM pending_tracks")
    suspend fun getAllOriginKeys(): List<String>

    // ── pending_track_stats ───────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStats(entity: PendingTrackStatsEntity)

    // ── pending_listen_events ─────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(entity: PendingListenEventEntity)

    @Query("SELECT originFingerprint FROM pending_listen_events")
    suspend fun getAllEventFingerprints(): List<String>

    // ── pending_lyrics_overrides ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLyrics(entity: PendingLyricsOverrideEntity)

    @Query("SELECT originFingerprint FROM pending_lyrics_overrides")
    suspend fun getAllLyricsFingerprints(): List<String>

    // ── pending_import_baselines ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBaseline(entity: PendingImportBaselineEntity)

    @Query("SELECT originFingerprint FROM pending_import_baselines")
    suspend fun getAllBaselineFingerprints(): List<String>

    // ── pending_playlist_entries ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistEntry(entity: PendingPlaylistEntryEntity)

    @Query("SELECT originFingerprint FROM pending_playlist_entries")
    suspend fun getAllPlaylistEntryFingerprints(): List<String>

    // ── P5-STATS-17B: portable pending-history EXPORT (read-only, deterministic order) ────────────────

    @Query("SELECT * FROM pending_tracks ORDER BY originKey")
    suspend fun getAllTracksForExport(): List<PendingTrackEntity>

    @Query("SELECT * FROM pending_track_stats")
    suspend fun getAllStatsForExport(): List<PendingTrackStatsEntity>

    @Query("SELECT * FROM pending_lyrics_overrides")
    suspend fun getAllLyricsForExport(): List<PendingLyricsOverrideEntity>

    @Query("SELECT * FROM pending_import_baselines")
    suspend fun getAllBaselinesForExport(): List<PendingImportBaselineEntity>

    @Query("SELECT * FROM pending_playlist_entries")
    suspend fun getAllPlaylistEntriesForExport(): List<PendingPlaylistEntryEntity>

    /** Snapshot boundary for the event stream: rows inserted after this id never enter the backup being written. */
    @Query("SELECT MAX(id) FROM pending_listen_events")
    suspend fun getMaxEventId(): Long?

    /**
     * One keyset page of pending events, ordered by their unique origin fingerprint (an indexed column, so the order is deterministic
     * on every device and paging never re-reads or skips a row). The parent's origin key is joined in: the local `pendingId` is never exported.
     */
    @Query(
        """
        SELECT t.originKey AS originKey, e.eventType AS eventType, e.occurredAt AS occurredAt, e.listenedMs AS listenedMs,
               e.durationMs AS durationMs, e.originFingerprint AS originFingerprint
        FROM pending_listen_events e INNER JOIN pending_tracks t ON t.pendingId = e.pendingId
        WHERE e.id <= :upToId AND e.originFingerprint > :afterFingerprint
        ORDER BY e.originFingerprint
        LIMIT :limit
        """,
    )
    suspend fun getEventExportPage(upToId: Long, afterFingerprint: String, limit: Int): List<PendingEventExportRow>

    // ── P5-STATS-17B: portable pending-history RESTORE (insert-or-detect-conflict) ────────────────────

    @Query("SELECT * FROM pending_tracks WHERE originKey = :originKey")
    suspend fun getTrackByOriginKey(originKey: String): PendingTrackEntity?

    /** Like the IGNORE inserts above but report the outcome: the new rowId, or -1 when a unique key already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStatsOrIgnore(entity: PendingTrackStatsEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventOrIgnore(entity: PendingListenEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLyricsOrIgnore(entity: PendingLyricsOverrideEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBaselineOrIgnore(entity: PendingImportBaselineEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistEntryOrIgnore(entity: PendingPlaylistEntryEntity): Long

    @Query("SELECT * FROM pending_track_stats WHERE pendingId = :pendingId")
    suspend fun getStatsFor(pendingId: Long): PendingTrackStatsEntity?

    @Query("SELECT * FROM pending_lyrics_overrides WHERE pendingId = :pendingId")
    suspend fun getLyricsFor(pendingId: Long): PendingLyricsOverrideEntity?

    @Query("SELECT * FROM pending_lyrics_overrides WHERE originFingerprint = :fingerprint")
    suspend fun getLyricsByFingerprint(fingerprint: String): PendingLyricsOverrideEntity?

    @Query("SELECT * FROM pending_listen_events WHERE originFingerprint = :fingerprint")
    suspend fun getEventByFingerprint(fingerprint: String): PendingListenEventEntity?

    @Query("SELECT * FROM pending_import_baselines WHERE originFingerprint = :fingerprint")
    suspend fun getBaselineByFingerprint(fingerprint: String): PendingImportBaselineEntity?

    @Query("SELECT * FROM pending_playlist_entries WHERE originFingerprint = :fingerprint")
    suspend fun getPlaylistEntryByFingerprint(fingerprint: String): PendingPlaylistEntryEntity?
}

/** Query-result row (not a table): one pending event with its parent's portable origin key. */
data class PendingEventExportRow(
    val originKey: String,
    val eventType: String,
    val occurredAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val originFingerprint: String,
)
