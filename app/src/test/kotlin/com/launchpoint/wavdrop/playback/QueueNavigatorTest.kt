package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueNavigatorTest {

    @Test
    fun `next normal moves forward`() {
        assertEquals(1, QueueNavigator.nextIndex(queueSize = 3, currentIndex = 0, RepeatMode.OFF))
    }

    @Test
    fun `next at end repeat off returns no next index`() {
        assertNull(QueueNavigator.nextIndex(queueSize = 3, currentIndex = 2, RepeatMode.OFF))
    }

    @Test
    fun `next at end repeat all wraps to first`() {
        assertEquals(0, QueueNavigator.nextIndex(queueSize = 3, currentIndex = 2, RepeatMode.ALL))
    }

    @Test
    fun `previous normal moves backward near start of song`() {
        val action = QueueNavigator.previousAction(
            queueSize = 3,
            currentIndex = 2,
            currentPositionMs = 1_000L,
            repeatMode = RepeatMode.OFF,
        )

        assertEquals(PreviousQueueAction.MoveTo(1), action)
    }

    @Test
    fun `previous restarts current song after threshold`() {
        val action = QueueNavigator.previousAction(
            queueSize = 3,
            currentIndex = 2,
            currentPositionMs = 3_001L,
            repeatMode = RepeatMode.OFF,
        )

        assertEquals(PreviousQueueAction.RestartCurrent, action)
    }

    @Test
    fun `previous at start repeat off restarts current song`() {
        val action = QueueNavigator.previousAction(
            queueSize = 3,
            currentIndex = 0,
            currentPositionMs = 0L,
            repeatMode = RepeatMode.OFF,
        )

        assertEquals(PreviousQueueAction.RestartCurrent, action)
    }

    @Test
    fun `previous at start repeat all wraps to last`() {
        val action = QueueNavigator.previousAction(
            queueSize = 3,
            currentIndex = 0,
            currentPositionMs = 0L,
            repeatMode = RepeatMode.ALL,
        )

        assertEquals(PreviousQueueAction.MoveTo(2), action)
    }

    @Test
    fun `repeat one automatic next stays on current song`() {
        assertEquals(
            1,
            QueueNavigator.automaticNextIndex(queueSize = 3, currentIndex = 1, RepeatMode.ONE),
        )
    }

    @Test
    fun `repeat one manual next still moves when possible`() {
        assertEquals(2, QueueNavigator.nextIndex(queueSize = 3, currentIndex = 1, RepeatMode.ONE))
    }

    @Test
    fun `shuffle order preserves current song first`() {
        val order = QueueNavigator.buildPlaybackOrder(
            queueSize = 5,
            currentIndex = 2,
            shuffleEnabled = true,
            random = Random(7),
        )

        assertEquals(2, order.first())
        assertEquals(listOf(0, 1, 2, 3, 4), order.sorted())
        assertTrue(order.drop(1).none { it == 2 })
    }

    @Test
    fun `next after shuffle uses shuffled order`() {
        val order = listOf(2, 4, 1, 0, 3)

        assertEquals(
            4,
            QueueNavigator.nextQueueIndex(
                playbackOrder = order,
                currentQueueIndex = 2,
                repeatMode = RepeatMode.OFF,
            ),
        )
        assertEquals(
            1,
            QueueNavigator.nextQueueIndex(
                playbackOrder = order,
                currentQueueIndex = 4,
                repeatMode = RepeatMode.OFF,
            ),
        )
    }

    @Test
    fun `next after shuffle at end repeat all wraps through shuffled order`() {
        val order = listOf(2, 4, 1, 0, 3)

        assertEquals(
            2,
            QueueNavigator.nextQueueIndex(
                playbackOrder = order,
                currentQueueIndex = 3,
                repeatMode = RepeatMode.ALL,
            ),
        )
    }

    @Test
    fun `previous after shuffle uses shuffled order`() {
        val order = listOf(2, 4, 1, 0, 3)

        val action = QueueNavigator.previousQueueAction(
            playbackOrder = order,
            currentQueueIndex = 1,
            currentPositionMs = 0L,
            repeatMode = RepeatMode.OFF,
        )

        assertEquals(PreviousQueueAction.MoveTo(4), action)
    }
}
