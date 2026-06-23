package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A quarantined backup song that could not be matched to any current local song.
 *
 * Identified by [originKey] = "$backupFingerprint|$backupSongId", which is stable
 * across re-imports of the same backup file. The UNIQUE constraint on [originKey]
 * prevents duplicate rows when the same backup is imported more than once.
 *
 * [backupSongId] is stored as an opaque string — it is a backup-internal identifier,
 * not a device MediaStore ID. It must not be used as a durable identity on this device.
 *
 * Source references ([sourceUri], [sourceFolderPath]) are low-trust hints that may
 * help P2-B rematching but must not be the sole basis for a history attachment.
 */
@Entity(
    tableName = "pending_tracks",
    indices   = [Index(value = ["originKey"], unique = true)],
)
data class PendingTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val pendingId: Long = 0,
    val originKey: String,
    val backupFingerprint: String,
    val backupSongId: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val trackNumber: Int,
    val year: Int,
    val sourceUri: String?,
    val sourceFolderPath: String?,
    val restoredAt: Long,
    val status: String = STATUS_PENDING,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
    }
}
