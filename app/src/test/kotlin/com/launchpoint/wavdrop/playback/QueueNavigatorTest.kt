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

    // ── buildPlaybackOrder edge cases ────────────────────────────────────────

    @Test
    fun `buildPlaybackOrder empty queue returns empty`() {
        val order = QueueNavigator.buildPlaybackOrder(queueSize = 0, currentIndex = 0, shuffleEnabled = true)
        assertTrue(order.isEmpty())
    }

    @Test
    fun `buildPlaybackOrder single item shuffle returns single-element list`() {
        val order = QueueNavigator.buildPlaybackOrder(queueSize = 1, currentIndex = 0, shuffleEnabled = true)
        assertEquals(listOf(0), order)
    }

    @Test
    fun `buildPlaybackOrder shuffle off returns identity order`() {
        val order = QueueNavigator.buildPlaybackOrder(queueSize = 4, currentIndex = 2, shuffleEnabled = false)
        assertEquals(listOf(0, 1, 2, 3), order)
    }

    @Test
    fun `buildPlaybackOrder shuffle on contains every index exactly once`() {
        val order = QueueNavigator.buildPlaybackOrder(
            queueSize = 6, currentIndex = 3, shuffleEnabled = true, random = Random(42),
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), order.sorted())
    }

    @Test
    fun `shuffle off from source song 100 continues to source song 101`() {
        val sourceSongIds = (1L..102L).toList()
        val currentSourceIndex = 99
        val shuffledOrder = listOf(currentSourceIndex) +
            sourceSongIds.indices.filterNot { it == currentSourceIndex }

        val restoredOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = sourceSongIds.size,
            currentIndex = shuffledOrder[0],
            shuffleEnabled = false,
        )
        val restoredPlaybackIndex = restoredOrder.indexOf(currentSourceIndex)
        val nextPlaybackIndex = QueueNavigator.nextIndex(
            queueSize = restoredOrder.size,
            currentIndex = restoredPlaybackIndex,
            repeatMode = RepeatMode.OFF,
        )

        assertEquals(99, restoredPlaybackIndex)
        assertEquals(101L, sourceSongIds[restoredOrder[nextPlaybackIndex!!]])
    }

    @Test
    fun `current song remains active when shuffle turns off`() {
        val sourceSongIds = (1L..102L).toList()
        val currentSourceIndex = 99
        val shuffledOrder = listOf(currentSourceIndex, 4, 2, 10, 100) +
            sourceSongIds.indices.filterNot { it in setOf(currentSourceIndex, 4, 2, 10, 100) }

        val restoredOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = sourceSongIds.size,
            currentIndex = shuffledOrder[0],
            shuffleEnabled = false,
        )
        val restoredPlaybackIndex = restoredOrder.indexOf(currentSourceIndex)

        assertEquals(100L, sourceSongIds[shuffledOrder[0]])
        assertEquals(100L, sourceSongIds[restoredOrder[restoredPlaybackIndex]])
    }

    @Test
    fun `source queue identity remains unchanged after shuffle on and off`() {
        val sourceSongIds = (1L..102L).toList()
        val originalSourceSongIds = sourceSongIds.toList()
        val shuffledOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = sourceSongIds.size,
            currentIndex = 99,
            shuffleEnabled = true,
            random = Random(100),
        )
        val restoredOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = sourceSongIds.size,
            currentIndex = shuffledOrder.first(),
            shuffleEnabled = false,
        )

        assertEquals(originalSourceSongIds, sourceSongIds)
        assertEquals(originalSourceSongIds, restoredOrder.map { sourceSongIds[it] })
    }

    @Test
    fun `playback order has no duplicate or lost songs after shuffle on and off`() {
        val queueSize = 102
        val shuffledOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = queueSize,
            currentIndex = 99,
            shuffleEnabled = true,
            random = Random(101),
        )
        val restoredOrder = QueueNavigator.buildPlaybackOrder(
            queueSize = queueSize,
            currentIndex = shuffledOrder.first(),
            shuffleEnabled = false,
        )

        assertEquals((0 until queueSize).toList(), shuffledOrder.sorted())
        assertEquals((0 until queueSize).toList(), restoredOrder.sorted())
    }

    @Test
    fun `buildPlaybackOrder shuffle on with out-of-range currentIndex falls back to identity`() {
        val order = QueueNavigator.buildPlaybackOrder(
            queueSize = 3, currentIndex = 10, shuffleEnabled = true,
        )
        assertEquals(listOf(0, 1, 2), order)
    }

    // ── automaticNextIndex — shuffle + repeat interactions ───────────────────

    @Test
    fun `automaticNextIndex repeat off mid-queue advances forward`() {
        assertEquals(
            2,
            QueueNavigator.automaticNextIndex(queueSize = 5, currentIndex = 1, RepeatMode.OFF),
        )
    }

    @Test
    fun `automaticNextIndex repeat off at last item returns null (stop)`() {
        assertNull(
            QueueNavigator.automaticNextIndex(queueSize = 5, currentIndex = 4, RepeatMode.OFF),
        )
    }

    @Test
    fun `automaticNextIndex repeat all at last item wraps to index 0`() {
        // With ExoPlayer holding playbackQueue, index 0 is the first item in shuffle order.
        assertEquals(
            0,
            QueueNavigator.automaticNextIndex(queueSize = 5, currentIndex = 4, RepeatMode.ALL),
        )
    }

    @Test
    fun `automaticNextIndex repeat all single item returns null (ExoPlayer loops natively)`() {
        // QueueNavigator guards queueSize > 1 for ALL wraps. A single-item queue under
        // REPEAT_ALL loops through ExoPlayer's internal REPEAT_MODE_ALL; no manual seek needed.
        assertNull(
            QueueNavigator.automaticNextIndex(queueSize = 1, currentIndex = 0, RepeatMode.ALL),
        )
    }

    @Test
    fun `automaticNextIndex repeat one returns current index (same song loops)`() {
        assertEquals(
            3,
            QueueNavigator.automaticNextIndex(queueSize = 5, currentIndex = 3, RepeatMode.ONE),
        )
    }

    @Test
    fun `automaticNextIndex repeat one at last index returns last index`() {
        assertEquals(
            4,
            QueueNavigator.automaticNextIndex(queueSize = 5, currentIndex = 4, RepeatMode.ONE),
        )
    }

    // ── nextIndex vs automaticNextIndex distinction ──────────────────────────

    @Test
    fun `nextIndex repeat one still advances (manual next skips repeat-one)`() {
        // Manual next should advance past repeat-one; automaticNextIndex stays.
        assertEquals(
            2,
            QueueNavigator.nextIndex(queueSize = 5, currentIndex = 1, RepeatMode.ONE),
        )
        assertEquals(
            1,
            QueueNavigator.automaticNextIndex(queueSize = 5, currentIndex = 1, RepeatMode.ONE),
        )
    }

    @Test
    fun `nextIndex repeat off at last position returns null`() {
        assertNull(QueueNavigator.nextIndex(queueSize = 3, currentIndex = 2, RepeatMode.OFF))
    }

    @Test
    fun `nextIndex repeat all at last position wraps to 0`() {
        assertEquals(0, QueueNavigator.nextIndex(queueSize = 3, currentIndex = 2, RepeatMode.ALL))
    }

    // ── Full shuffled-queue traversal ─────────────────────────────────────────

    @Test
    fun `shuffled queue traversal with repeat off visits every song exactly once then stops`() {
        val order = QueueNavigator.buildPlaybackOrder(
            queueSize = 5, currentIndex = 2, shuffleEnabled = true, random = Random(99),
        )
        // Traverse the entire queue using nextIndex on playbackQueue size (new model).
        val visited = mutableListOf(order[0])
        var currentPlaybackIdx = 0
        repeat(4) {
            val next = QueueNavigator.nextIndex(
                queueSize = order.size,
                currentIndex = currentPlaybackIdx,
                repeatMode = RepeatMode.OFF,
            )!!
            visited += order[next]
            currentPlaybackIdx = next
        }
        // After visiting all 5 items, next should be null.
        assertNull(
            QueueNavigator.nextIndex(
                queueSize = order.size,
                currentIndex = currentPlaybackIdx,
                repeatMode = RepeatMode.OFF,
            ),
        )
        // Every library index visited exactly once.
        assertEquals(listOf(0, 1, 2, 3, 4), visited.sorted())
    }

    @Test
    fun `shuffled queue traversal with repeat all wraps back to start after last item`() {
        val order = QueueNavigator.buildPlaybackOrder(
            queueSize = 3, currentIndex = 0, shuffleEnabled = true, random = Random(7),
        )
        val last = order.size - 1
        val wrapped = QueueNavigator.nextIndex(
            queueSize = order.size,
            currentIndex = last,
            repeatMode = RepeatMode.ALL,
        )
        assertEquals(0, wrapped)
    }

    // ── previousAction at boundaries ─────────────────────────────────────────

    @Test
    fun `previousAction at index 0 repeat off restarts current`() {
        val action = QueueNavigator.previousAction(
            queueSize = 4, currentIndex = 0, currentPositionMs = 0L, repeatMode = RepeatMode.OFF,
        )
        assertEquals(PreviousQueueAction.RestartCurrent, action)
    }

    @Test
    fun `previousAction at index 0 repeat all wraps to last`() {
        val action = QueueNavigator.previousAction(
            queueSize = 4, currentIndex = 0, currentPositionMs = 0L, repeatMode = RepeatMode.ALL,
        )
        assertEquals(PreviousQueueAction.MoveTo(3), action)
    }

    @Test
    fun `previousAction beyond threshold always restarts regardless of repeat mode`() {
        listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE).forEach { mode ->
            val action = QueueNavigator.previousAction(
                queueSize = 4, currentIndex = 2, currentPositionMs = 5_000L, repeatMode = mode,
            )
            assertEquals(PreviousQueueAction.RestartCurrent, action)
        }
    }
}
