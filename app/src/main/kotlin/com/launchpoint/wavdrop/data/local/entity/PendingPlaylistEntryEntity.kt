package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Quarantined playlist membership for a [PendingTrackEntity]. Many per track.
 *
 * [sourcePlaylistId] — the backup-internal playlist ID (opaque string), used as the identity key
 * in [originFingerprint] so same-name playlists with different IDs remain distinct.
 * Null for rows migrated from schema v9, where this information was not stored
 * (treat as legacy-ambiguous provenance in P2-B rematching logic).
 *
 * [sourcePlaylistCreatedAt] / [sourcePlaylistUpdatedAt] — timestamps from the source
 * [BackupPlaylist], preserved for future audit surfaces. Null for pre-v10 rows.
 */
@Entity(
    tableName   = "pending_playlist_entries",
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
data class PendingPlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pendingId: Long,
    val playlistName: String,
    val position: Int,
    val originFingerprint: String,
    /** Opaque backup playlist ID; null for rows created before schema v10. */
    val sourcePlaylistId: String? = null,
    /** Epoch-ms timestamp from the source backup playlist; null for pre-v10 rows. */
    val sourcePlaylistCreatedAt: Long? = null,
    /** Epoch-ms timestamp from the source backup playlist; null for pre-v10 rows. */
    val sourcePlaylistUpdatedAt: Long? = null,
)
