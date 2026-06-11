package com.launchpoint.wavdrop.data.backup

import com.launchpoint.wavdrop.data.model.Song

/**
 * Pure planner for restoring one playlist's entries from a backup.
 *
 * Entries are processed in backup position order and appended after the
 * playlist's current entries. Songs already in the playlist are skipped
 * (repeat restores are idempotent); songs that cannot be confidently resolved
 * are skipped and counted rather than attached to a wrong duplicate.
 */
object PlaylistEntryRestorePlanner {

    data class Entry(val songId: Long, val position: Int)

    data class Plan(
        val toAdd: List<Entry>,
        val entriesInBackup: Int,
        val restored: Int,
        val skippedExisting: Int,
        val skippedUnmatched: Int,
    )

    fun plan(
        entries: List<BackupPlaylistSong>,
        resolve: (BackupPlaylistSong) -> Song?,
        existingSongIds: Set<Long>,
        nextPosition: Int,
    ): Plan {
        val seen = existingSongIds.toMutableSet()
        val toAdd = mutableListOf<Entry>()
        var skippedExisting = 0
        var skippedUnmatched = 0
        var position = nextPosition

        for (entry in entries.sortedBy { it.position }) {
            val song = resolve(entry)
            if (song == null) {
                skippedUnmatched++
                continue
            }
            if (song.id in seen) {
                skippedExisting++
                continue
            }
            seen += song.id
            toAdd += Entry(songId = song.id, position = position)
            position++
        }

        return Plan(
            toAdd            = toAdd,
            entriesInBackup  = entries.size,
            restored         = toAdd.size,
            skippedExisting  = skippedExisting,
            skippedUnmatched = skippedUnmatched,
        )
    }
}
