package com.launchpoint.wavdrop.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.launchpoint.wavdrop.data.local.entity.TrackIdentityEntity

/**
 * Access to the device-local [track_identities] store (contract §5).
 *
 * Every write here is owned by the media scan (P2-B1: scan is the single writer of
 * identities and of [TrackIdentityEntity.currentSongId]). This phase only exposes the
 * operations; no caller wires them yet.
 *
 * Deliberately ABSENT (forbidden in P2-B1):
 *  - any delete (identity rows are durable; a vanished song nulls currentSongId, never deletes)
 *  - metadata / PortableTrackKey / title-artist-album-duration lookups (no rematching, no
 *    metadata-as-identity)
 *  - pending-table joins and any backup/export queries (identity is device-local, never exported)
 */
@Dao
interface TrackIdentityDao {

    /** Insert one identity. ABORT on PK conflict — identities are created once, never overwritten. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(identity: TrackIdentityEntity)

    /** Insert several identities in one call (scan creates new live-song identities in batch). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(identities: List<TrackIdentityEntity>)

    /** All identities — the scan's working snapshot for reconciliation. */
    @Query("SELECT * FROM track_identities")
    suspend fun getAllSnapshot(): List<TrackIdentityEntity>

    /** Identities currently resolving to a given live song id. */
    @Query("SELECT * FROM track_identities WHERE currentSongId = :songId")
    suspend fun getByCurrentSongId(songId: Long): List<TrackIdentityEntity>

    /** All live-song references currently held by identities (non-null only). */
    @Query("SELECT currentSongId FROM track_identities WHERE currentSongId IS NOT NULL")
    suspend fun getAllCurrentSongIds(): List<Long>

    /** Link an identity to a live song and record when it resolved. */
    @Query(
        "UPDATE track_identities SET currentSongId = :songId, lastResolvedAt = :resolvedAt " +
            "WHERE identityUuid = :identityUuid"
    )
    suspend fun updateCurrentSongId(identityUuid: String, songId: Long, resolvedAt: Long)

    /**
     * Clear the live-song link for a set of identities (their song disappeared on rescan).
     * Nulls currentSongId and stamps lastResolvedAt; the identity row itself is retained.
     */
    @Query(
        "UPDATE track_identities SET currentSongId = NULL, lastResolvedAt = :clearedAt " +
            "WHERE identityUuid IN (:identityUuids)"
    )
    suspend fun clearCurrentSongIds(identityUuids: List<String>, clearedAt: Long)
}
