package com.launchpoint.wavdrop.data.repository

import android.util.Log
import androidx.room.withTransaction
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
        val found = try {
            scanner.scanSongs(scanSettings)
        } catch (e: MediaStoreScanException) {
            Log.e(TAG, "Library scan failed — existing library preserved, no changes made", e)
            return@withContext LibrarySyncResult.Failed(
                "Library scan could not complete. Your existing songs were kept. " +
                    "Check your media permission or storage and try again."
            )
        }

        db.withTransaction {
            val existingEntities = dao.getAllSongsSnapshot()
            val existingIds = existingEntities.mapTo(mutableSetOf()) { it.id }

            if (found.isEmpty()) {
                if (SongSyncPolicy.shouldPreserveOnEmptyScan(scanSettings, existingIds.size)) {
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
                dao.deleteAll()
                // All songs were removed — every identity's referenced song is now absent.
                reconcileIdentities(emptySet())
                return@withTransaction LibrarySyncResult.Success(0)
            }

            val scannedIds = found.mapTo(mutableSetOf()) { it.id }
            val staleSongs = existingEntities
                .filter { it.id !in scannedIds }
                .map(SongEntity::toDomain)
            val newSongs = found.filter { it.id !in existingIds }
            val remapPlan = PlaylistSongRemapPlanner.plan(
                staleSongs = staleSongs,
                newSongs = newSongs,
            )

            dao.upsertAll(found.map(Song::toEntity))

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

            val staleIds = SongSyncPolicy.computeStaleIds(existingIds, scannedIds)
            staleIds.chunked(PRUNE_CHUNK_SIZE).forEach { chunk ->
                dao.deleteByIds(chunk)
                // Unmatched or ambiguous playlist memberships are intentionally retained as
                // hidden orphan entries. Confirmed user deletion still removes memberships
                // through the explicit delete flow.
            }

            // Scan owns TrackIdentity: the songs table now equals the scanned live set, so
            // reconcile identities against it inside the same transaction.
            reconcileIdentities(scannedIds)

            LibrarySyncResult.Success(found.size)
        }
    }

    /**
     * Scan-owned TrackIdentity reconciliation (P2-B1). Runs inside [sync]'s transaction.
     *
     * Mints one identity per live song that has none, and clears currentSongId for identities
     * whose referenced song is no longer live. Identity rows are never deleted. No metadata,
     * URI, path, PortableTrackKey, or pending-history inference is performed — a live song with
     * no identity simply gets a fresh UUID.
     */
    private suspend fun reconcileIdentities(liveSongIds: Set<Long>) {
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

    private companion object {
        const val TAG = "Wavdrop-Sync"
        const val PRUNE_CHUNK_SIZE = 500
        const val IDENTITY_CHUNK_SIZE = 500
    }
}

private fun SongEntity.toDomain() = Song(
    id = id, title = title, artist = artist, album = album,
    albumId = albumId, duration = duration, uri = uri,
    dateAdded = dateAdded, trackNumber = trackNumber, year = year,
    folderPath = folderPath, folderName = folderName,
)

private fun Song.toEntity() = SongEntity(
    id = id, title = title, artist = artist, album = album,
    albumId = albumId, duration = duration, uri = uri,
    dateAdded = dateAdded, trackNumber = trackNumber, year = year,
    folderPath = folderPath, folderName = folderName,
)
