package com.launchpoint.wavdrop.data.library

/**
 * Pure decision logic for scan-owned TrackIdentity reconciliation (contract §5; P2-B1).
 *
 * The media scan is the single writer of identities and of currentSongId. This planner takes
 * the set of currently live song ids plus the existing identity references and decides only:
 *   - which live songs need a fresh identity, and
 *   - which identities must have their currentSongId cleared (their song disappeared).
 *
 * It NEVER deletes identities and NEVER infers identity from metadata, URI, folder path,
 * PortableTrackKey, or pending-history rows. A live song without an identity simply gets a new
 * one; no attempt is made to reconnect it to an existing identity (that is rematching, out of
 * P2-B1 scope). Identities are durable: a vanished song yields a cleared reference, never a delete.
 */
object TrackIdentityScanPlanner {

    /** A minimal view of an existing identity row — only what reconciliation needs. */
    data class ExistingIdentity(
        val identityUuid: String,
        val currentSongId: Long?,
    )

    data class Plan(
        /** Live song ids that currently have no identity pointing at them — mint one each. */
        val songIdsNeedingIdentity: List<Long>,
        /** Identities whose currentSongId is non-null but no longer live — clear the reference. */
        val identityUuidsToClear: List<String>,
    ) {
        val hasWork: Boolean get() = songIdsNeedingIdentity.isNotEmpty() || identityUuidsToClear.isNotEmpty()
    }

    fun plan(
        liveSongIds: Set<Long>,
        existingIdentities: List<ExistingIdentity>,
    ): Plan {
        // Song ids already claimed by some identity (non-null references only).
        val claimedSongIds = existingIdentities
            .mapNotNullTo(HashSet()) { it.currentSongId }

        // Rule 1 & 4: a live song with no identity pointing at it needs one; one that is
        // already claimed is left alone.
        val songIdsNeedingIdentity = liveSongIds
            .filter { it !in claimedSongIds }

        // Rule 2 & 3: clear identities whose reference points at a song that is no longer live;
        // identities with a null reference are untouched.
        val identityUuidsToClear = existingIdentities
            .filter { it.currentSongId != null && it.currentSongId !in liveSongIds }
            .map { it.identityUuid }

        return Plan(
            songIdsNeedingIdentity = songIdsNeedingIdentity,
            identityUuidsToClear   = identityUuidsToClear,
        )
    }
}
