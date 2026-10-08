package com.launchpoint.wavdrop.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.local.WavdropDatabase
import com.launchpoint.wavdrop.data.library.TrackIdentityScanPlanner
import com.launchpoint.wavdrop.data.local.dao.PlaylistDao
import com.launchpoint.wavdrop.data.local.dao.SongDao
import com.launchpoint.wavdrop.data.local.dao.TrackIdentityDao
import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.local.entity.TrackIdentityEntity
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanException
import com.launchpoint.wavdrop.data.mediastore.MediaStoreScanner
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playlists.PlaylistSongRemapPlanner
import java.util.UUID
import com.launchpoint.wavdrop.data.search.SongSort
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SongRepository @Inject constructor(
    private val db: WavdropDatabase,
    private val dao: SongDao,
    private val playlistDao: PlaylistDao,
    private val scanner: MediaStoreScanner,
    private val scanSettingsRepository: LibraryScanSettingsRepository,
    private val trackIdentityDao: TrackIdentityDao,
) {
    val songs: Flow<List<Song>> = dao.getAllSongs().map { entities ->
        entities.map(SongEntity::toDomain).sortedWith(SongSort.byTitle)
    }

    fun observeSongById(songId: Long): Flow<Song?> =
        dao.observeSongById(songId).map { it?.toDomain() }

    suspend fun pruneSong(songId: Long) {
        dao.deleteSong(songId)
    }

    suspend fun sync(): LibrarySyncResult = withContext(Dispatchers.IO) {
        val scanSettings = scanSettingsRepository.settings.first()

        // A failed scan (permission revoked, MediaStore failure, cursor error) must NOT be
        // treated as an empty library. Contain it here, before any DB transaction runs, so no
        // songs are deleted and no identity reconciliation runs against an invalid result (WB-02).
        val scan = try {
            scanner.scanSongs(scanSettings)
        } catch (e: MediaStoreScanException) {
            Log.e(TAG, "Library scan failed — existing library preserved, no changes made", e)
            return@withContext LibrarySyncResult.Failed(
                "Library scan could not complete. Your existing songs were kept. " +
                    "Check your media permission or storage and try again."
            )
        }

        val found = scan.songs

        try {
            db.withTransaction {
                val existingEntities = dao.getAllSongsSnapshot()
                val existingIds = existingEntities.mapTo(mutableSetOf()) { it.id }

                if (found.isEmpty()) {
                    val disposition = SongSyncPolicy.emptyScanDisposition(
                        settings = scanSettings,
                        existingSongCount = existingIds.size,
                        eligibleBeforeExplicitExclusionsCount = scan.eligibleBeforeExplicitExclusionsCount,
                    )
                    if (disposition == EmptyScanDisposition.PRESERVE_AMBIGUOUS) {
                        Log.w(TAG,
                            "Scan returned 0 songs — preserving ${existingIds.size} existing songs. " +
                            "Mode: ${scanSettings.scanMode}"
                        )
                        // Songs are unchanged; reconcile against the preserved live set so any
                        // preserved song still gains an identity and none are spuriously cleared.
                        reconcileIdentities(existingIds)
                        return@withTransaction LibrarySyncResult.EmptyPreserved(
                            SongSyncPolicy.emptyPreservedReason(scanSettings)
                        )
                    }
                    // Definitive empty: the table is already empty, or the explicit exclusions (preset and/or custom folder) removed every
                    // otherwise-eligible song. Stats, listen events and identity rows are not deleted here.
                    if (existingIds.isNotEmpty()) dao.deleteAll()
                    // All songs were removed — every identity's referenced song is now absent.
                    reconcileIdentities(emptySet())
                    return@withTransaction LibrarySyncResult.Success(0)
                }

                // WC-08: write only what the scan actually changed. MediaStore is still fully scanned; Room is no longer
                // fully rewritten. Playlist remapping keeps its boundary: only genuinely NEW ids (never same-id updates)
                // are candidate destinations for stale ones.
                val plan = SongSyncPlanner.plan(existingEntities, found)
                val remapPlan = PlaylistSongRemapPlanner.plan(
                    staleSongs = plan.staleSongs,
                    newSongs = plan.newSongs,
                )

                applySongSyncPlan(plan, remapPlan, dao, playlistDao)

                // Scan owns TrackIdentity: the songs table now equals the scanned live set, so reconcile identities against it
                // inside the same transaction. This runs even when no song row changed (a live song may still lack an identity).
                reconcileIdentities(plan.liveSongIds)

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "sync scan=${found.size} existing=${existingIds.size} new=${plan.newSongs.size} " +
                        "changed=${plan.changedEntities.size} unchanged=${plan.unchangedCount} stale=${plan.staleSongs.size} " +
                        "remaps=${remapPlan.mappings.size}")
                }

                LibrarySyncResult.Success(found.size)
            }
        } catch (e: DuplicateScannedSongIdException) {
            // Invariant guard: MediaStore _ID is unique. Fail closed (the transaction rolled back, nothing was written).
            Log.e(TAG, "Library scan returned duplicate song ids — existing library preserved, no changes made", e)
            LibrarySyncResult.Failed(
                "Library scan returned inconsistent data. Your existing songs were kept. Try again."
            )
        }
    }

    private suspend fun reconcileIdentities(liveSongIds: Set<Long>) =
        reconcileTrackIdentities(liveSongIds, trackIdentityDao)

    private companion object {
        const val TAG = "Wavdrop-Sync"
    }
}

internal const val SONG_WRITE_CHUNK_SIZE = 500
internal const val SONG_PRUNE_CHUNK_SIZE = 500
internal const val IDENTITY_CHUNK_SIZE = 500

/**
 * Applies a [SongSyncPlan] inside the caller's transaction (WC-08), in the safe order: upsert new + changed rows, THEN playlist
 * remaps (destinations now exist), THEN stale deletion. Writes nothing for an unchanged library: no empty upsert, no empty delete.
 * Rows are written in chunks of [SONG_WRITE_CHUNK_SIZE] and deleted in chunks of [SONG_PRUNE_CHUNK_SIZE] to bound each DAO call.
 */
internal suspend fun applySongSyncPlan(
    plan: SongSyncPlan,
    remapPlan: PlaylistSongRemapPlanner.Plan,
    songDao: SongDao,
    playlistDao: PlaylistDao,
) {
    plan.entitiesToUpsert.chunked(SONG_WRITE_CHUNK_SIZE).forEach { songDao.upsertAll(it) }

    remapPlan.mappings.forEach { mapping ->
        playlistDao.removeRedundantEntriesForRemap(
            oldSongId = mapping.oldSongId,
            newSongId = mapping.newSongId,
        )
        playlistDao.remapSongId(
            oldSongId = mapping.oldSongId,
            newSongId = mapping.newSongId,
        )
    }

    // Unmatched or ambiguous playlist memberships are intentionally retained as hidden orphan entries. Confirmed user
    // deletion still removes memberships through the explicit delete flow.
    plan.staleIds.chunked(SONG_PRUNE_CHUNK_SIZE).forEach { songDao.deleteByIds(it) }
}

/**
 * Scan-owned TrackIdentity reconciliation (P2-B1). Runs inside [sync]'s transaction.
 *
 * Mints one identity per live song that has none, and clears currentSongId for identities
 * whose referenced song is no longer live. Identity rows are never deleted. No metadata,
 * URI, path, PortableTrackKey, or pending-history inference is performed — a live song with
 * no identity simply gets a fresh UUID.
 */
internal suspend fun reconcileTrackIdentities(liveSongIds: Set<Long>, trackIdentityDao: TrackIdentityDao) {
    val existing = trackIdentityDao.getAllSnapshot().map {
        TrackIdentityScanPlanner.ExistingIdentity(
            identityUuid  = it.identityUuid,
            currentSongId = it.currentSongId,
        )
    }
    val plan = TrackIdentityScanPlanner.plan(liveSongIds, existing)
    if (!plan.hasWork) return

    val nowMs = System.currentTimeMillis()

    plan.songIdsNeedingIdentity
        .map { songId ->
            TrackIdentityEntity(
                identityUuid   = UUID.randomUUID().toString(),
                currentSongId  = songId,
                createdAt      = nowMs,
                lastResolvedAt = nowMs,
            )
        }
        .chunked(IDENTITY_CHUNK_SIZE)
        .forEach { trackIdentityDao.insertAll(it) }

    plan.identityUuidsToClear
        .chunked(IDENTITY_CHUNK_SIZE)
        .forEach { trackIdentityDao.clearCurrentSongIds(it, nowMs) }
}


internal fun SongEntity.toDomain() = Song(
    id = id, title = title, artist = artist, album = album,
    albumId = albumId, duration = duration, uri = uri,
    dateAdded = dateAdded, trackNumber = trackNumber, year = year,
    folderPath = folderPath, folderName = folderName,
)

internal fun Song.toEntity() = SongEntity(
    id = id, title = title, artist = artist, album = album,
    albumId = albumId, duration = duration, uri = uri,
    dateAdded = dateAdded, trackNumber = trackNumber, year = year,
    folderPath = folderPath, folderName = folderName,
)
