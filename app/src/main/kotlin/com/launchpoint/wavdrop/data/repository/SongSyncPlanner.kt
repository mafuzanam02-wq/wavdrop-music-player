package com.launchpoint.wavdrop.data.repository

import com.launchpoint.wavdrop.data.local.entity.SongEntity
import com.launchpoint.wavdrop.data.model.Song

/** A scan contained the same MediaStore id twice, so no unambiguous write plan exists (the sync fails closed). */
class DuplicateScannedSongIdException(val duplicateIds: Set<Long>) :
    IllegalStateException("Scan returned duplicate song ids: ${duplicateIds.size}")

/**
 * The difference between the persisted songs table and one authoritative scan (WC-08).
 *
 * @property newSongs scanned songs whose id did not previously exist (the ONLY songs the playlist remap planner may match to).
 * @property staleSongs persisted songs absent from the scan.
 * @property changedEntities existing ids whose persisted fields differ; they are updated in place and are NEVER "new" songs.
 * @property unchangedCount existing ids whose persisted row is identical to the scan.
 */
class SongSyncPlan(
    val newSongs: List<Song>,
    val changedEntities: List<SongEntity>,
    val unchangedCount: Int,
    val staleSongs: List<Song>,
    val liveSongIds: Set<Long>,
    val existingSongIds: Set<Long>,
) {
    val newEntities: List<SongEntity> get() = newSongs.map { it.toEntity() }

    /** Exactly the rows the song table needs written: new + changed. Empty for an unchanged library. */
    val entitiesToUpsert: List<SongEntity> get() = (newEntities + changedEntities).sortedBy { it.id }

    val staleIds: Set<Long> get() = staleSongs.mapTo(sortedSetOf()) { it.id }

    val hasSongTableWork: Boolean get() = newSongs.isNotEmpty() || changedEntities.isNotEmpty() || staleSongs.isNotEmpty()
}

/**
 * Pure (no Room/Android) song-table diff. A scanned song is unchanged only when its [SongEntity] equals the persisted row in EVERY
 * field (data-class equality over id, title, artist, album, albumId, duration, uri, dateAdded, trackNumber, year, folderPath,
 * folderName). The result never depends on input ordering: all output lists are sorted by id.
 */
object SongSyncPlanner {

    /** @throws DuplicateScannedSongIdException if [scanned] contains an id twice (MediaStore `_ID` is unique; this is an invariant guard). */
    fun plan(existing: List<SongEntity>, scanned: List<Song>): SongSyncPlan {
        val duplicates = scanned.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) throw DuplicateScannedSongIdException(duplicates)

        val existingById = existing.associateBy { it.id }
        val scannedIds = scanned.mapTo(HashSet()) { it.id }

        val newSongs = ArrayList<Song>()
        val changed = ArrayList<SongEntity>()
        var unchanged = 0
        for (song in scanned) {
            val persisted = existingById[song.id]
            when {
                persisted == null -> newSongs += song
                persisted == song.toEntity() -> unchanged++
                else -> changed += song.toEntity()
            }
        }
        val stale = existing.filter { it.id !in scannedIds }.map { it.toDomain() }

        return SongSyncPlan(
            newSongs = newSongs.sortedBy { it.id },
            changedEntities = changed.sortedBy { it.id },
            unchangedCount = unchanged,
            staleSongs = stale.sortedBy { it.id },
            liveSongIds = scannedIds,
            existingSongIds = existingById.keys,
        )
    }
}
