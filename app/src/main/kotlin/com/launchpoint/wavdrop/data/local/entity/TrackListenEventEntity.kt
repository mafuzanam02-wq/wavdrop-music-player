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
    val source: String,         // SOURCE_WAVDROP_PLAYBACK for native playback events
) {
    companion object {
        const val TYPE_PLAY = "PLAY"
        const val TYPE_SKIP = "SKIP"

        /** Written by StatsTracker via ExoPlayer / Media3 native playback. */
        const val SOURCE_WAVDROP_PLAYBACK = "wavdrop_playback"

        /** Reserved — not written today. BlackPlayer imports remain aggregate-only. */
        const val SOURCE_BLACKPLAYER_IMPORT = "blackplayer_import"

        /** Reserved — for future Wavdrop JSON restore support. */
        const val SOURCE_MANUAL_RESTORE = "manual_restore"
    }
}
