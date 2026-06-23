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
}
