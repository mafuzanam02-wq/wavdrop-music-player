package com.launchpoint.wavdrop.data.playback

import com.launchpoint.wavdrop.data.model.Song

/**
 * P5-F16B: the single pure rule for turning a saved queue into a playable one when some entries are unavailable.
 * Both restore routes (PlayerController hydration and the Media3 resumption mapper) use it, so they cannot interpret a partially
 * available queue differently.
 *
 * Identity is by OCCURRENCE (position in the saved queue), never by song id: a queue may contain a song twice and each copy is
 * kept or dropped independently. Nothing here reads or writes persistence.
 */
internal data class SavedQueueRestore(
    /** The surviving songs, in saved library order. */
    val songs: List<Song>,
    /** For each surviving song, its index in the ORIGINAL saved queue. */
    val originalIndices: List<Int>,
    /** Old saved index -> new index, or -1 when that occurrence is unavailable. Size = original queue size. */
    val oldToNew: List<Int>,
    val missingCount: Int,
    /** Restored playback order over [songs] (library indices), a valid permutation containing [currentLibraryIndex]. */
    val playbackOrder: List<Int>,
    val currentLibraryIndex: Int,
    val positionMs: Long,
    /** True when the saved current occurrence was unavailable and the next surviving one was chosen (paused at 0). */
    val currentOccurrenceWasMissing: Boolean,
) {
    val isPartial: Boolean get() = missingCount > 0
}

internal sealed interface SavedQueueRestoreResult {
    data class Restored(val restore: SavedQueueRestore) : SavedQueueRestoreResult

    /** Nothing playable could be rebuilt. The caller must preserve the persisted snapshot. */
    data class Unresolvable(val reason: Reason) : SavedQueueRestoreResult

    enum class Reason {
        /** The saved queue is empty. */
        EMPTY_SAVED_QUEUE,

        /** No saved occurrence is available in the library. */
        NO_SURVIVORS,

        /** The saved current occurrence cannot be identified (never guessed). */
        CURRENT_OCCURRENCE_UNIDENTIFIABLE,
    }
}

internal object SavedQueueRestorePlanner {

    fun plan(snapshot: PlaybackSessionSnapshot, availableSongs: List<Song>): SavedQueueRestoreResult {
        val savedIds = snapshot.queueSongIds
        if (savedIds.isEmpty()) {
            return SavedQueueRestoreResult.Unresolvable(SavedQueueRestoreResult.Reason.EMPTY_SAVED_QUEUE)
        }
        val songsById = availableSongs.associateBy { it.id }
        val oldToNew = IntArray(savedIds.size) { -1 }
        val songs = ArrayList<Song>(savedIds.size)
        val originalIndices = ArrayList<Int>(savedIds.size)
        savedIds.forEachIndexed { oldIndex, id ->
            val song = songsById[id] ?: return@forEachIndexed
            oldToNew[oldIndex] = songs.size
            songs += song
            originalIndices += oldIndex
        }
        if (songs.isEmpty()) {
            return SavedQueueRestoreResult.Unresolvable(SavedQueueRestoreResult.Reason.NO_SURVIVORS)
        }

        // The saved current occurrence is identified on the ORIGINAL queue with the existing rule (index, else a unique id match).
        val oldCurrent = PlaybackSessionRules.resolveSavedCurrentIndex(
            sessionSongId = snapshot.currentSongId,
            sessionIndex = snapshot.currentIndex,
            queueSongIds = savedIds,
        ) ?: return SavedQueueRestoreResult.Unresolvable(SavedQueueRestoreResult.Reason.CURRENT_OCCURRENCE_UNIDENTIFIABLE)

        // A malformed saved order is never invented into occurrence identity: it is dropped and rebuilt by the existing rule.
        val savedOrder = PlaybackSessionRules.validatePlaybackOrder(snapshot.playbackOrder, savedIds.size)

        val currentMissing = oldToNew[oldCurrent] < 0
        val newCurrent: Int
        if (!currentMissing) {
            newCurrent = oldToNew[oldCurrent]
        } else {
            // Next surviving occurrence in saved playback order (identity order when none), wrapping around.
            val order = savedOrder ?: savedIds.indices.toList()
            val at = order.indexOf(oldCurrent)
            var chosen = -1
            for (step in 1..order.size) {
                val candidate = order[(at + step) % order.size]
                if (oldToNew[candidate] >= 0) { chosen = candidate; break }
            }
            newCurrent = oldToNew[chosen] // a survivor exists, so chosen >= 0
        }

        val remappedOrder = savedOrder?.mapNotNull { old -> oldToNew[old].takeIf { it >= 0 } }
        val playbackOrder = PlaybackSessionRules.restorePlaybackOrder(
            savedPlaybackOrder = remappedOrder,
            queueSize = songs.size,
            currentQueueIndex = newCurrent,
            shuffleEnabled = snapshot.shuffleEnabled,
        )
        return SavedQueueRestoreResult.Restored(
            SavedQueueRestore(
                songs = songs,
                originalIndices = originalIndices,
                oldToNew = oldToNew.toList(),
                missingCount = savedIds.size - songs.size,
                playbackOrder = playbackOrder,
                currentLibraryIndex = newCurrent,
                positionMs = if (currentMissing) 0L else snapshot.positionMs,
                currentOccurrenceWasMissing = currentMissing,
            ),
        )
    }
}
