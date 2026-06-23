package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Quarantined aggregate statistics for a [PendingTrackEntity]. 1:1 relationship. */
@Entity(
    tableName    = "pending_track_stats",
    foreignKeys  = [
        ForeignKey(
            entity         = PendingTrackEntity::class,
            parentColumns  = ["pendingId"],
            childColumns   = ["pendingId"],
            onDelete       = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingTrackStatsEntity(
    @PrimaryKey
    val pendingId: Long,
    val playCount: Int,
    val skipCount: Int,
    val lastPlayedAt: Long,
    val totalListeningTimeMs: Long,
    val isFavorite: Boolean,
    /**
     * Epoch ms of last qualifying 5-second listen, preserved from the source backup.
     *
     * null  = quarantined from a v1 backup (field absent; never a guessed value).
     * ≥ 0   = quarantined from a v2 backup; 0 means no qualifying listen occurred.
     *
     * This distinction allows future rematching to correctly identify "v1 absent"
     * vs "v2 zero" without inspecting the backup source format separately.
     */
    val lastListenedAt: Long? = null,
)
