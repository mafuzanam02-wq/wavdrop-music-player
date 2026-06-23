package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records a single listening event — one PLAY or one SKIP — at a specific calendar time.
 *
 * This table was introduced in DB version 6 (2026-06). There is no historical backfill:
 *  - TrackStatsEntity aggregate counts from before this version have no corresponding events.
 *  - BlackPlayer import stats are aggregate-only and are never written as events.
 *
 * Event history is the source of truth for per-month analytics (Monthly Reports, Wrapped).
 * TrackStatsEntity remains the fast aggregate source for counts displayed in the UI.
 *
 * Indices:
 *   occurredAt             – time-range queries (monthly reports, year-in-review)
 *   (songId, occurredAt)   – per-song timeline queries
 */
@Entity(
    tableName = "track_listen_events",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["songId", "occurredAt"]),
    ],
)
data class TrackListenEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val songId: Long,
    val eventType: String,      // TYPE_PLAY or TYPE_SKIP
    val occurredAt: Long,       // epoch ms — wall-clock time when the event was finalised
    val listenedMs: Long = 0L,  // accumulated continuous listening time (0 for SKIP events)
    val durationMs: Long = 0L,  // track duration at event time (0 if unknown)
    val source: String,         // playback/restored source identifier
    // Stable per-event identifier (contract §9.2). Generated at event creation — NOT here in
    // P2-B1 Phase 1, which only adds the column. null = legacy event (pre-eventId or never
    // assigned); legacy rows are never backfilled. Dedup may key on this only when both sides
    // are non-null; otherwise the (songId, occurredAt, eventType, listenedMs) fingerprint applies.
    val eventId: String? = null,
) {
    companion object {
        const val TYPE_PLAY = "PLAY"
        const val TYPE_SKIP = "SKIP"

        /** Written by StatsTracker via ExoPlayer / Media3 native playback. */
        const val SOURCE_WAVDROP_PLAYBACK = "wavdrop_playback"

        /** Reserved — not written today. BlackPlayer imports remain aggregate-only. */
        const val SOURCE_BLACKPLAYER_IMPORT = "blackplayer_import"

        /** Written by WavdropBackupImportRepository when restoring listen events from a backup. */
        const val SOURCE_MANUAL_RESTORE = "manual_restore"

        /** Preserved from verified Wavdrop Desktop playback event imports. */
        const val SOURCE_DESKTOP_PLAYBACK = "wavdrop_desktop_playback"
    }
}
