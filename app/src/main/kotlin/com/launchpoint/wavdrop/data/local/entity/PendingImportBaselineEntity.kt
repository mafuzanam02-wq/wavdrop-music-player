package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Quarantined import baseline for a [PendingTrackEntity]. Many per track. */
@Entity(
    tableName   = "pending_import_baselines",
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
data class PendingImportBaselineEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pendingId: Long,
    val sourceType: String,
    val sourceKey: String,
    val playCount: Int,
    val originFingerprint: String,
)
