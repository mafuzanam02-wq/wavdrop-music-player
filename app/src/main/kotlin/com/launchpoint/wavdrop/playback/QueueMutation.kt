package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song

/** Pure queue-mutation helpers used by [PlayerController]. Extracted for unit-testability. */
internal object QueueMutation {

    data class RemoveResult(val queue: List<Song>, val currentIndex: Int)
    data class ShuffleToggleResult(
        val playbackOrder: List<Int>,
        val playbackQueue: List<Song>,
        val currentPlaybackIndex: Int,
        val currentSong: Song,
        val requiresCurrentItemReplacement: Boolean,
    )
    data class NativeMoveResult(
        val playbackOrder: List<Int>,
        val fromLibraryIndex: Int,
        val toLibraryIndex: Int,
    )

    fun shuffleToggleModel(
        libraryQueue: List<Song>,
        currentSongId: Long,
        shuffleEnabled: Boolean,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): ShuffleToggleResult? {
        val currentSourceIndex = libraryQueue.indexOfFirst { it.id == currentSongId }
            .takeIf { it >= 0 }
            ?: return null
        val playbackOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = libraryQueue.size,
            currentIndex = currentSourceIndex,
            shuffleEnabled = shuffleEnabled,
            random = random,
        )
        val playbackQueue = playbackOrder.mapNotNull { libraryQueue.getOrNull(it) }
        val currentPlaybackIndex = playbackOrder.indexOf(currentSourceIndex)
            .takeIf { it >= 0 }
            ?: return null

        return ShuffleToggleResult(
            playbackOrder = playbackOrder,
            playbackQueue = playbackQueue,
            currentPlaybackIndex = currentPlaybackIndex,
            currentSong = libraryQueue[currentSourceIndex],
            requiresCurrentItemReplacement = false,
        )
    }

    /**
     * Removes the item at [playbackIndex] from [playbackQueue].
     * Returns [RemoveResult] with the new queue and adjusted current index, or null when
     * [playbackIndex] equals [currentPlaybackIndex] or is out of bounds.
     */
    fun remove(
        playbackQueue: List<Song>,
        playbackIndex: Int,
        currentPlaybackIndex: Int,
    ): RemoveResult? {
        if (playbackIndex == currentPlaybackIndex) return null
        if (playbackIndex !in playbackQueue.indices) return null
        val newQueue = playbackQueue.toMutableList().also { it.removeAt(playbackIndex) }
        val newCurrentIndex = if (playbackIndex < currentPlaybackIndex) {
            currentPlaybackIndex - 1
        } else {
            currentPlaybackIndex
        }
        return RemoveResult(newQueue, newCurrentIndex)
    }

    /**
     * Moves the item at [playbackIndex] to immediately after [currentPlaybackIndex].
     * Returns the reordered queue, or null if the operation is a no-op or invalid.
     */
    fun moveToPlayNext(
        playbackQueue: List<Song>,
        playbackIndex: Int,
        currentPlaybackIndex: Int,
    ): List<Song>? {
        val immediateNextIndex = currentPlaybackIndex + 1
        if (playbackIndex <= currentPlaybackIndex) return null
        if (playbackIndex == immediateNextIndex) return null
        if (playbackIndex !in playbackQueue.indices) return null
        val newQueue = playbackQueue.toMutableList()
        val song = newQueue.removeAt(playbackIndex)
        newQueue.add(immediateNextIndex, song)
        return newQueue
    }

    fun insertAfterCurrent(
        playbackQueue: List<Song>,
        song: Song,
        currentPlaybackIndex: Int,
    ): List<Song>? {
        if (playbackQueue.isEmpty()) return listOf(song)
        if (currentPlaybackIndex !in playbackQueue.indices) return null
        val insertIndex = (currentPlaybackIndex + 1).coerceAtMost(playbackQueue.size)
        return playbackQueue.toMutableList().also { it.add(insertIndex, song) }
    }

    fun append(
        playbackQueue: List<Song>,
        song: Song,
    ): List<Song> =
        playbackQueue.toMutableList().also { it.add(song) }

    fun searchPreserveQueue(
        playbackQueue: List<Song>,
        currentPlaybackIndex: Int?,
        song: Song,
    ): List<Song> {
        val currentIndex = currentPlaybackIndex?.takeIf { it in playbackQueue.indices }
            ?: return listOf(song)
        val history = playbackQueue.take(currentIndex + 1)
        val continuation = playbackQueue.drop(currentIndex + 1)
            .filterNot { it.id == song.id }
        return history + song + continuation
    }

    /**
     * Returns an updated [playbackOrder] after a new library item has been inserted at
     * [insertLibraryIndex].  All existing entries >= [insertLibraryIndex] are incremented
     * (they shifted up in the library list), and the new index is spliced into the playback
     * sequence immediately after [currentPlaybackIndex].
     */
    fun shiftPlaybackOrderForInsert(
        playbackOrder: List<Int>,
        insertLibraryIndex: Int,
        currentPlaybackIndex: Int,
    ): List<Int> {
        val shifted = playbackOrder.map { if (it >= insertLibraryIndex) it + 1 else it }.toMutableList()
        val insertPlaybackIndex = (currentPlaybackIndex + 1).coerceAtMost(shifted.size)
        shifted.add(insertPlaybackIndex, insertLibraryIndex)
        return shifted
    }

    /**
     * Returns the playback-order mapping needed after moving a Media3/library playlist item.
     * The native playlist move changes library indices first; playback positions are then swapped
     * so the visible queue order reflects Move Up / Move Down without rebuilding playback.
     */
    fun playbackOrderAfterNativeMove(
        playbackOrder: List<Int>,
        playbackIndex: Int,
        otherIndex: Int,
        currentPlaybackIndex: Int,
    ): NativeMoveResult? {
        if (playbackIndex <= currentPlaybackIndex || otherIndex <= currentPlaybackIndex) return null
        if (playbackIndex !in playbackOrder.indices || otherIndex !in playbackOrder.indices) return null
        if (playbackIndex == otherIndex) return null

        val fromLibraryIndex = playbackOrder[playbackIndex]
        val toLibraryIndex = playbackOrder[otherIndex]
        val remappedOrder = playbackOrder
            .map { remapLibraryIndexAfterMove(it, fromLibraryIndex, toLibraryIndex) }
            .toMutableList()
        val moved = remappedOrder[playbackIndex]
        remappedOrder[playbackIndex] = remappedOrder[otherIndex]
        remappedOrder[otherIndex] = moved
        return NativeMoveResult(
            playbackOrder = remappedOrder,
            fromLibraryIndex = fromLibraryIndex,
            toLibraryIndex = toLibraryIndex,
        )
    }

    /**
     * Swaps [playbackIndex] with [otherIndex] in [playbackQueue].
     * Both indices must be strictly after [currentPlaybackIndex] and within bounds.
     * Returns the reordered queue or null if invalid.
     */
    fun swapAdjacent(
        playbackQueue: List<Song>,
        playbackIndex: Int,
        otherIndex: Int,
        currentPlaybackIndex: Int,
    ): List<Song>? {
        if (playbackIndex <= currentPlaybackIndex || otherIndex <= currentPlaybackIndex) return null
        if (playbackIndex !in playbackQueue.indices || otherIndex !in playbackQueue.indices) return null
        val newQueue = playbackQueue.toMutableList()
        val tmp = newQueue[playbackIndex]
        newQueue[playbackIndex] = newQueue[otherIndex]
        newQueue[otherIndex] = tmp
        return newQueue
    }

    private fun remapLibraryIndexAfterMove(index: Int, fromIndex: Int, toIndex: Int): Int =
        when {
            index == fromIndex -> toIndex
            fromIndex < toIndex && index in (fromIndex + 1)..toIndex -> index - 1
            toIndex < fromIndex && index in toIndex until fromIndex -> index + 1
            else -> index
        }
}
