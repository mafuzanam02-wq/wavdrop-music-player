package com.launchpoint.wavdrop.playback

import kotlin.random.Random

sealed interface PreviousQueueAction {
    data object RestartCurrent : PreviousQueueAction
    data class MoveTo(val index: Int) : PreviousQueueAction
}

object QueueNavigator {
    const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L

    fun nextIndex(
        queueSize: Int,
        currentIndex: Int,
        repeatMode: RepeatMode,
    ): Int? {
        if (!isValid(queueSize, currentIndex)) return null
        if (currentIndex < queueSize - 1) return currentIndex + 1
        return if (repeatMode == RepeatMode.ALL && queueSize > 1) 0 else null
    }

    fun automaticNextIndex(
        queueSize: Int,
        currentIndex: Int,
        repeatMode: RepeatMode,
    ): Int? {
        if (!isValid(queueSize, currentIndex)) return null
        if (repeatMode == RepeatMode.ONE) return currentIndex
        return nextIndex(queueSize, currentIndex, repeatMode)
    }

    fun previousAction(
        queueSize: Int,
        currentIndex: Int,
        currentPositionMs: Long,
        repeatMode: RepeatMode,
        restartThresholdMs: Long = PREVIOUS_RESTART_THRESHOLD_MS,
    ): PreviousQueueAction? {
        if (!isValid(queueSize, currentIndex)) return null
        if (currentPositionMs > restartThresholdMs) return PreviousQueueAction.RestartCurrent
        if (currentIndex > 0) return PreviousQueueAction.MoveTo(currentIndex - 1)
        return if (repeatMode == RepeatMode.ALL && queueSize > 1) {
            PreviousQueueAction.MoveTo(queueSize - 1)
        } else {
            PreviousQueueAction.RestartCurrent
        }
    }

    fun nextQueueIndex(
        playbackOrder: List<Int>,
        currentQueueIndex: Int,
        repeatMode: RepeatMode,
    ): Int? {
        val currentOrderIndex = playbackOrder.indexOf(currentQueueIndex)
        val nextOrderIndex = nextIndex(
            queueSize = playbackOrder.size,
            currentIndex = currentOrderIndex,
            repeatMode = repeatMode,
        ) ?: return null
        return playbackOrder.getOrNull(nextOrderIndex)
    }

    fun previousQueueAction(
        playbackOrder: List<Int>,
        currentQueueIndex: Int,
        currentPositionMs: Long,
        repeatMode: RepeatMode,
        restartThresholdMs: Long = PREVIOUS_RESTART_THRESHOLD_MS,
    ): PreviousQueueAction? {
        val currentOrderIndex = playbackOrder.indexOf(currentQueueIndex)
        return when (val action = previousAction(
            queueSize = playbackOrder.size,
            currentIndex = currentOrderIndex,
            currentPositionMs = currentPositionMs,
            repeatMode = repeatMode,
            restartThresholdMs = restartThresholdMs,
        )) {
            is PreviousQueueAction.MoveTo ->
                playbackOrder.getOrNull(action.index)?.let(PreviousQueueAction::MoveTo)
            PreviousQueueAction.RestartCurrent -> PreviousQueueAction.RestartCurrent
            null -> null
        }
    }

    fun buildPlaybackOrder(
        queueSize: Int,
        currentIndex: Int,
        shuffleEnabled: Boolean,
        random: Random = Random.Default,
    ): List<Int> {
        if (queueSize <= 0) return emptyList()
        val naturalOrder = (0 until queueSize).toList()
        if (!shuffleEnabled || currentIndex !in naturalOrder) return naturalOrder

        val remaining = naturalOrder.filterNot { it == currentIndex }.shuffled(random)
        return listOf(currentIndex) + remaining
    }

    private fun isValid(queueSize: Int, currentIndex: Int): Boolean =
        queueSize > 0 && currentIndex in 0 until queueSize
}
