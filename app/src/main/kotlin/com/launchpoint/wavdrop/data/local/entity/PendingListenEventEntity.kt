package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Quarantined listening event for a [PendingTrackEntity]. Many per track. */
@Entity(
    tableName   = "pending_listen_events",
    indices     = [
        Index("pendingId"),
        Index(value = ["originFingerprint"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity        = PendingTrackEntity::class,
            parentColumns = ["pendingId"],
            childColumns  = ["pendingId"],
            onDelete      = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingListenEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pendingId: Long,
    val eventType: String,
    val occurredAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    /** Unique per-event fingerprint; INSERT OR IGNORE prevents re-import duplicates. */
    val originFingerprint: String,
)
