package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Quarantined lyrics override for a [PendingTrackEntity]. 1:1 relationship. */
@Entity(
    tableName   = "pending_lyrics_overrides",
    indices     = [Index(value = ["originFingerprint"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity        = PendingTrackEntity::class,
            parentColumns = ["pendingId"],
            childColumns  = ["pendingId"],
            onDelete      = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingLyricsOverrideEntity(
    @PrimaryKey
    val pendingId: Long,
    val lyrics: String,
    val updatedAt: Long,
    val originFingerprint: String,
)
