package com.launchpoint.wavdrop.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Device-local, durable Wavdrop identity for a piece of music (contract §5).
 *
 * This is the durable identity that survives MediaStore rescans and id changes — it is
 * NOT the volatile [SongEntity] (a CurrentLibraryTrack, contract §3.1) and NOT a
 * [PendingTrackEntity] (quarantine evidence for unresolved identity).
 *
 * P2-B1 scope (foundation only):
 *  - Rows are created exclusively by the media scan; no other component writes them.
 *  - [currentSongId] is a SOFT, nullable reference to the live [SongEntity.id] this
 *    identity currently resolves to. There is deliberately NO foreign key / cascade:
 *    songs are rebuilt on rescan, so a cascade would destroy durable identities. When the
 *    referenced song disappears the scan nulls [currentSongId] and retains the row.
 *
 * Explicitly NOT in P2-B1: backup export of identity, rematching, GHOST/archive lifecycle,
 * metadata/PortableTrackKey-derived identity. PortableTrackKey is intentionally absent —
 * it is evidence, never identity, and has no proven use in this phase.
 */
@Entity(
    tableName = "track_identities",
    indices = [Index(value = ["currentSongId"])],
)
data class TrackIdentityEntity(
    /** Wavdrop-generated UUID — the durable identity (contract §5.1). */
    @PrimaryKey val identityUuid: String,
    /** Soft reference to the current live song id; null when no live song resolves. */
    val currentSongId: Long? = null,
    /** Epoch ms when this identity was first created. */
    val createdAt: Long,
    /** Epoch ms when this identity was last linked to a live song. */
    val lastResolvedAt: Long,
)
