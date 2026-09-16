package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class QueueMutationTest {

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val a = song(1)
    private val b = song(2)
    private val c = song(3)
    private val d = song(4)
    private val e = song(5)
    private val queue = listOf(a, b, c, d, e)

    @Test
    fun `shuffle toggle model preserves current song identity`() {
        val result = QueueMutation.shuffleToggleModel(
            libraryQueue = queue,
            currentSongId = c.id,
            shuffleEnabled = true,
            random = Random(7),
        )!!

        assertEquals(c, result.currentSong)
        assertEquals(c.id, result.playbackQueue[result.currentPlaybackIndex].id)
    }

    @Test
    fun `shuffle toggle model does not require replacing current item`() {
        val result = QueueMutation.shuffleToggleModel(
            libraryQueue = queue,
            currentSongId = c.id,
            shuffleEnabled = true,
            random = Random(7),
        )!!

        assertFalse(result.requiresCurrentItemReplacement)
    }

    @Test
    fun `next after shuffle follows shuffled order`() {
        val result = QueueMutation.shuffleToggleModel(
            libraryQueue = queue,
            currentSongId = c.id,
            shuffleEnabled = true,
            random = Random(7),
        )!!
        val nextPlaybackIndex = QueueNavigator.nextIndex(
            queueSize = result.playbackQueue.size,
            currentIndex = result.currentPlaybackIndex,
            repeatMode = RepeatMode.OFF,
        )!!

        assertEquals(result.playbackOrder[1], result.playbackOrder[nextPlaybackIndex])
        assertEquals(result.playbackQueue[1], result.playbackQueue[nextPlaybackIndex])
    }

    @Test
    fun `next after shuffle off follows source order`() {
        val result = QueueMutation.shuffleToggleModel(
            libraryQueue = queue,
            currentSongId = c.id,
            shuffleEnabled = false,
            random = Random(7),
        )!!
        val nextPlaybackIndex = QueueNavigator.nextIndex(
            queueSize = result.playbackQueue.size,
            currentIndex = result.currentPlaybackIndex,
            repeatMode = RepeatMode.OFF,
        )!!

        assertEquals(listOf(a, b, c, d, e), result.playbackQueue)
        assertEquals(d, result.playbackQueue[nextPlaybackIndex])
    }

    // ── remove ──────────────────────────────────────────────────────────────────

    @Test
    fun `remove returns null when removing current song`() {
        assertNull(QueueMutation.remove(queue, playbackIndex = 2, currentPlaybackIndex = 2))
    }

    @Test
    fun `remove returns null for out-of-bounds index`() {
        assertNull(QueueMutation.remove(queue, playbackIndex = 10, currentPlaybackIndex = 1))
    }

    @Test
    fun `remove song after current does not shift currentIndex`() {
        val result = QueueMutation.remove(queue, playbackIndex = 3, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, c, e), result.queue)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun `remove song before current decrements currentIndex`() {
        val result = QueueMutation.remove(queue, playbackIndex = 0, currentPlaybackIndex = 2)!!
        assertEquals(listOf(b, c, d, e), result.queue)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun `remove last song in queue`() {
        val result = QueueMutation.remove(queue, playbackIndex = 4, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, c, d), result.queue)
        assertEquals(1, result.currentIndex)
    }

    // ── moveToPlayNext ───────────────────────────────────────────────────────────

    @Test
    fun `moveToPlayNext returns null when index is current song`() {
        assertNull(QueueMutation.moveToPlayNext(queue, playbackIndex = 1, currentPlaybackIndex = 1))
    }

    @Test
    fun `moveToPlayNext returns null when index is before current`() {
        assertNull(QueueMutation.moveToPlayNext(queue, playbackIndex = 0, currentPlaybackIndex = 2))
    }

    @Test
    fun `moveToPlayNext returns null when already immediately next`() {
        assertNull(QueueMutation.moveToPlayNext(queue, playbackIndex = 2, currentPlaybackIndex = 1))
    }

    @Test
    fun `moveToPlayNext moves song two positions ahead to immediately next`() {
        val result = QueueMutation.moveToPlayNext(queue, playbackIndex = 3, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, d, c, e), result)
    }

    @Test
    fun `moveToPlayNext moves last song to immediately next`() {
        val result = QueueMutation.moveToPlayNext(queue, playbackIndex = 4, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, e, c, d), result)
    }

    // ── swapAdjacent ────────────────────────────────────────────────────────────

    @Test
    fun `swapAdjacent returns null when first index is current song`() {
        assertNull(QueueMutation.swapAdjacent(queue, 1, 2, currentPlaybackIndex = 1))
    }

    @Test
    fun `swapAdjacent returns null when second index is current song`() {
        assertNull(QueueMutation.swapAdjacent(queue, 2, 1, currentPlaybackIndex = 1))
    }

    @Test
    fun `swapAdjacent returns null when an index is before current`() {
        assertNull(QueueMutation.swapAdjacent(queue, 0, 3, currentPlaybackIndex = 1))
    }

    @Test
    fun `swapAdjacent swaps two adjacent up-next songs - move up`() {
        val result = QueueMutation.swapAdjacent(queue, playbackIndex = 3, otherIndex = 2, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, d, c, e), result)
    }

    @Test
    fun `swapAdjacent swaps two adjacent up-next songs - move down`() {
        val result = QueueMutation.swapAdjacent(queue, playbackIndex = 2, otherIndex = 3, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, d, c, e), result)
    }

    @Test
    fun `swapAdjacent is symmetric - same result regardless of index order`() {
        val r1 = QueueMutation.swapAdjacent(queue, 2, 3, currentPlaybackIndex = 1)
        val r2 = QueueMutation.swapAdjacent(queue, 3, 2, currentPlaybackIndex = 1)
        assertEquals(r1, r2)
    }

    @Test
    fun `insertAfterCurrent inserts selected song directly after current`() {
        val result = QueueMutation.insertAfterCurrent(queue, song = e, currentPlaybackIndex = 1)!!
        assertEquals(listOf(a, b, e, c, d, e), result)
    }

    @Test
    fun `insertAfterCurrent returns null for stale current index`() {
        assertNull(QueueMutation.insertAfterCurrent(queue, song = e, currentPlaybackIndex = 99))
    }

    @Test
    fun `append adds selected song to queue end`() {
        val result = QueueMutation.append(queue, song = c)
        assertEquals(listOf(a, b, c, d, e, c), result)
    }

    @Test
    fun `batch play next does not duplicate current requested occurrence`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c),
            playbackOrder = listOf(0, 1, 2),
            currentPlaybackIndex = 1,
            songs = listOf(b, d),
        )!!

        assertEquals(listOf(1L, 2L, 4L, 3L), result.playbackQueue.map { it.id })
    }

    @Test
    fun `batch play next reuses existing future song`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c, d, e),
            playbackOrder = listOf(0, 1, 2, 3, 4),
            currentPlaybackIndex = 1,
            songs = listOf(d),
        )!!

        assertEquals(listOf(1L, 2L, 4L, 3L, 5L), result.playbackQueue.map { it.id })
        assertEquals(5, result.libraryQueue.size)
    }

    @Test
    fun `batch play next reuses overlap and adds only missing songs`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, song(99), d, e),
            playbackOrder = listOf(0, 1, 2, 3, 4),
            currentPlaybackIndex = 0,
            songs = listOf(b, c, d),
        )!!

        assertEquals(listOf(1L, 2L, 3L, 4L, 99L, 5L), result.playbackQueue.map { it.id })
        assertEquals(listOf(1L, 2L, 99L, 4L, 5L, 3L), result.libraryQueue.map { it.id })
    }

    @Test
    fun `repeated identical batch play next is idempotent`() {
        val first = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c, d),
            playbackOrder = listOf(0, 1, 2, 3),
            currentPlaybackIndex = 0,
            songs = listOf(b, c, d),
        )!!
        val second = QueueMutation.insertAllAfterCurrent(
            libraryQueue = first.libraryQueue,
            playbackOrder = first.playbackOrder,
            currentPlaybackIndex = 0,
            songs = listOf(b, c, d),
        )!!

        assertEquals(listOf(1L, 2L, 3L, 4L), second.playbackQueue.map { it.id })
        assertEquals(4, second.libraryQueue.size)
    }

    @Test
    fun `batch play next keeps unrelated future items in relative order`() {
        val x = song(99)
        val y = song(100)
        val z = song(101)
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, x, b, y, c, z),
            playbackOrder = listOf(0, 1, 2, 3, 4, 5),
            currentPlaybackIndex = 0,
            songs = listOf(b, c),
        )!!

        assertEquals(listOf(1L, 2L, 3L, 99L, 100L, 101L), result.playbackQueue.map { it.id })
    }

    @Test
    fun `batch play next does not reuse historical occurrence`() {
        val x = song(99)
        val y = song(100)
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, d, b, x, d, y),
            playbackOrder = listOf(0, 1, 2, 3, 4, 5),
            currentPlaybackIndex = 2,
            songs = listOf(d),
        )!!

        assertEquals(listOf(1L, 4L, 2L, 4L, 99L, 100L), result.playbackQueue.map { it.id })
        assertEquals(6, result.libraryQueue.size)
    }

    @Test
    fun `batch play next preserves duplicate requested multiplicity`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b),
            playbackOrder = listOf(0, 1),
            currentPlaybackIndex = 0,
            songs = listOf(d, d, e),
        )!!

        assertEquals(listOf(1L, 4L, 4L, 5L, 2L), result.playbackQueue.map { it.id })
        assertEquals(listOf(1L, 2L, 4L, 4L, 5L), result.libraryQueue.map { it.id })
    }

    @Test
    fun `two existing future duplicates satisfy two requested duplicates`() {
        val d2 = song(4)
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, d, b, d2, c),
            playbackOrder = listOf(0, 1, 2, 3, 4),
            currentPlaybackIndex = 0,
            songs = listOf(d, d),
        )!!

        assertEquals(listOf(1L, 4L, 4L, 2L, 3L), result.playbackQueue.map { it.id })
        assertEquals(5, result.libraryQueue.size)
    }

    @Test
    fun `one existing duplicate and two requested duplicates creates one new occurrence`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, d, b),
            playbackOrder = listOf(0, 1, 2),
            currentPlaybackIndex = 0,
            songs = listOf(d, d),
        )!!

        assertEquals(listOf(1L, 4L, 4L, 2L), result.playbackQueue.map { it.id })
        assertEquals(listOf(1L, 4L, 2L, 4L), result.libraryQueue.map { it.id })
    }

    @Test
    fun `current song requested once consumes current occurrence`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, a, c),
            playbackOrder = listOf(0, 1, 2, 3),
            currentPlaybackIndex = 0,
            songs = listOf(a),
        )!!

        assertEquals(listOf(1L, 2L, 1L, 3L), result.playbackQueue.map { it.id })
        assertEquals(4, result.libraryQueue.size)
    }

    @Test
    fun `current song requested twice reuses one additional future occurrence`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, a, c),
            playbackOrder = listOf(0, 1, 2, 3),
            currentPlaybackIndex = 0,
            songs = listOf(a, a),
        )!!

        assertEquals(listOf(1L, 1L, 2L, 3L), result.playbackQueue.map { it.id })
        assertEquals(4, result.libraryQueue.size)
    }

    @Test
    fun `batch play next empty batch is no-op`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c),
            playbackOrder = listOf(0, 1, 2),
            currentPlaybackIndex = 1,
            songs = emptyList(),
        )!!

        assertEquals(listOf(a, b, c), result.libraryQueue)
        assertEquals(listOf(0, 1, 2), result.playbackOrder)
        assertEquals(listOf(a, b, c), result.playbackQueue)
    }

    @Test
    fun `single-song batch reuses matching future occurrence`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c),
            playbackOrder = listOf(0, 1, 2),
            currentPlaybackIndex = 0,
            songs = listOf(c),
        )!!

        assertEquals(listOf(1L, 3L, 2L), result.playbackQueue.map { it.id })
    }

    @Test
    fun `batch play next works against shuffled playback sequence`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c, d, e),
            playbackOrder = listOf(2, 4, 1, 0, 3),
            currentPlaybackIndex = 1,
            songs = listOf(d, b),
        )!!

        assertEquals(listOf(3L, 5L, 4L, 2L, 1L), result.playbackQueue.map { it.id })
        assertEquals(listOf(2, 4, 3, 1, 0), result.playbackOrder)
    }

    @Test
    fun `batch play next keeps current playback index unchanged`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c, d),
            playbackOrder = listOf(0, 1, 2, 3),
            currentPlaybackIndex = 1,
            songs = listOf(d),
        )!!

        assertEquals(b, result.playbackQueue[1])
    }

    @Test
    fun `batch play next result playback order indexes remain valid`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c),
            playbackOrder = listOf(0, 1, 2),
            currentPlaybackIndex = 0,
            songs = listOf(c, d, e),
        )!!

        assert(result.playbackOrder.all { it in result.libraryQueue.indices })
    }

    @Test
    fun `batch play next playback queue is derived from playback order`() {
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(a, b, c),
            playbackOrder = listOf(0, 1, 2),
            currentPlaybackIndex = 0,
            songs = listOf(c, d),
        )!!

        assertEquals(result.playbackOrder.map { result.libraryQueue[it] }, result.playbackQueue)
    }

    @Test
    fun `batch play next does not mutate inputs`() {
        val library = mutableListOf(a, b, c)
        val order = mutableListOf(0, 1, 2)
        val requested = mutableListOf(c, d)

        QueueMutation.insertAllAfterCurrent(
            libraryQueue = library,
            playbackOrder = order,
            currentPlaybackIndex = 0,
            songs = requested,
        )!!

        assertEquals(listOf(a, b, c), library)
        assertEquals(listOf(0, 1, 2), order)
        assertEquals(listOf(c, d), requested)
    }

    @Test
    fun `artist catalogue regression reuses upcoming and does not duplicate current`() {
        val cigarette = song(10)
        val twentyThree = song(23)
        val other = song(99)
        val hereComesTheFall = song(30)
        val longRoad = song(40)
        val result = QueueMutation.insertAllAfterCurrent(
            libraryQueue = listOf(cigarette, twentyThree, other, cigarette, hereComesTheFall),
            playbackOrder = listOf(0, 1, 2, 3, 4),
            currentPlaybackIndex = 0,
            songs = listOf(twentyThree, cigarette, hereComesTheFall, longRoad),
        )!!

        assertEquals(listOf(10L, 23L, 30L, 40L, 99L, 10L), result.playbackQueue.map { it.id })
        assertEquals(6, result.libraryQueue.size)
    }

    @Test
    fun `searchPreserveQueue inserts searched song after current while preserving next`() {
        val x = song(99)

        val result = QueueMutation.searchPreserveQueue(
            playbackQueue = listOf(a, b, c),
            currentPlaybackIndex = 0,
            song = x,
        )

        assertEquals(listOf(a, x, b, c), result)
        assertSearchPlaybackPosition(result, x, previous = a, next = b)
    }

    @Test
    fun `searchPreserveQueue inserts searched song after middle current`() {
        val x = song(99)

        val result = QueueMutation.searchPreserveQueue(
            playbackQueue = listOf(a, b, c, d),
            currentPlaybackIndex = 1,
            song = x,
        )

        assertEquals(listOf(a, b, x, c, d), result)
        assertSearchPlaybackPosition(result, x, previous = b, next = c)
    }

    @Test
    fun `searchPreserveQueue returns searched song when no queue exists`() {
        val x = song(99)

        val result = QueueMutation.searchPreserveQueue(
            playbackQueue = emptyList(),
            currentPlaybackIndex = null,
            song = x,
        )

        assertEquals(listOf(x), result)
    }

    @Test
    fun `searchPreserveQueue removes duplicate searched song from later upcoming queue`() {
        val x = song(99)

        val result = QueueMutation.searchPreserveQueue(
            playbackQueue = listOf(a, b, x, c),
            currentPlaybackIndex = 0,
            song = x,
        )

        assertEquals(listOf(a, x, b, c), result)
        assertSearchPlaybackPosition(result, x, previous = a, next = b)
    }

    @Test
    fun `replace queue behavior remains selected search context`() {
        val x = song(99)
        val searchResults = listOf(c, x, d)
        val startIndex = searchResults.indexOfFirst { it.id == x.id }

        assertEquals(1, startIndex)
        assertEquals(listOf(c, x, d), searchResults)
    }

    private fun assertSearchPlaybackPosition(
        queue: List<Song>,
        current: Song,
        previous: Song,
        next: Song,
    ) {
        val currentIndex = queue.indexOfFirst { it.id == current.id }
        val previousAction = QueueNavigator.previousAction(
            queueSize = queue.size,
            currentIndex = currentIndex,
            currentPositionMs = 0L,
            repeatMode = RepeatMode.OFF,
        )
        val nextIndex = QueueNavigator.nextIndex(
            queueSize = queue.size,
            currentIndex = currentIndex,
            repeatMode = RepeatMode.OFF,
        )

        assertEquals(current, queue[currentIndex])
        assertEquals(PreviousQueueAction.MoveTo(currentIndex - 1), previousAction)
        assertEquals(previous, queue[currentIndex - 1])
        assertEquals(next, queue[nextIndex!!])
    }

    // ── shiftPlaybackOrderForInsert ──────────────────────────────────────────────

    @Test
    fun `shiftPlaybackOrderForInsert identity order splices new index after current`() {
        // [A B C D E], playing B (playbackIndex=1, libraryIndex=1), insert X at libraryIndex=2
        val result = QueueMutation.shiftPlaybackOrderForInsert(
            playbackOrder = listOf(0, 1, 2, 3, 4),
            insertLibraryIndex = 2,
            currentPlaybackIndex = 1,
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result)
    }

    @Test
    fun `shiftPlaybackOrderForInsert shuffle order inserts after current in playback sequence`() {
        // libraryQueue=[A,B,C,D,E], shuffled playbackOrder=[1,3,0,4,2] (B,D,A,E,C)
        // playing B (playbackIndex=0, libraryIndex=1), insert X at libraryIndex=2
        val result = QueueMutation.shiftPlaybackOrderForInsert(
            playbackOrder = listOf(1, 3, 0, 4, 2),
            insertLibraryIndex = 2,
            currentPlaybackIndex = 0,
        )
        // shift: [1,4,0,5,3], then insert 2 at position 1 → [1,2,4,0,5,3]
        // playback: B(1), X(2), D(4), A(0), E(5), C(3) — X lands right after B
        assertEquals(listOf(1, 2, 4, 0, 5, 3), result)
    }

    @Test
    fun `shiftPlaybackOrderForInsert current is last in playback sequence appends new index`() {
        // playing E (playbackIndex=4, last), insert X after last library item
        val result = QueueMutation.shiftPlaybackOrderForInsert(
            playbackOrder = listOf(0, 1, 2, 3, 4),
            insertLibraryIndex = 5,
            currentPlaybackIndex = 4,
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result)
    }

    @Test
    fun `shiftPlaybackOrderForInsert shuffle current is last library item inserts after current`() {
        // libraryQueue=[A,B,C,D,E], shuffled playbackOrder=[4,2,0,1,3] (E,C,A,B,D)
        // playing E (playbackIndex=0, libraryIndex=4), insert X at libraryIndex=5
        val result = QueueMutation.shiftPlaybackOrderForInsert(
            playbackOrder = listOf(4, 2, 0, 1, 3),
            insertLibraryIndex = 5,
            currentPlaybackIndex = 0,
        )
        // all entries < 5, no shift; insert 5 at position 1 → [4,5,2,0,1,3]
        assertEquals(listOf(4, 5, 2, 0, 1, 3), result)
    }

    @Test
    fun `shiftPlaybackOrderForInsert insert at library beginning shifts all entries`() {
        // Insert X at libraryIndex=0, playing B (playbackIndex=1, libraryIndex=1 → now 2)
        val result = QueueMutation.shiftPlaybackOrderForInsert(
            playbackOrder = listOf(0, 1, 2, 3, 4),
            insertLibraryIndex = 0,
            currentPlaybackIndex = 1,
        )
        // shift all >= 0 (all): [1,2,3,4,5], insert 0 at position 2 → [1,2,0,3,4,5]
        assertEquals(listOf(1, 2, 0, 3, 4, 5), result)
    }

    // ── playbackOrderAfterNativeMove ────────────────────────────────────────────

    @Test
    fun `playbackOrderAfterNativeMove returns native move indexes for identity move up`() {
        val result = QueueMutation.playbackOrderAfterNativeMove(
            playbackOrder = listOf(0, 1, 2, 3, 4),
            playbackIndex = 3,
            otherIndex = 2,
            currentPlaybackIndex = 1,
        )!!

        assertEquals(3, result.fromLibraryIndex)
        assertEquals(2, result.toLibraryIndex)
        assertEquals(listOf(0, 1, 2, 3, 4), result.playbackOrder)
    }

    @Test
    fun `playbackOrderAfterNativeMove returns native move indexes for identity move down`() {
        val result = QueueMutation.playbackOrderAfterNativeMove(
            playbackOrder = listOf(0, 1, 2, 3, 4),
            playbackIndex = 2,
            otherIndex = 3,
            currentPlaybackIndex = 1,
        )!!

        assertEquals(2, result.fromLibraryIndex)
        assertEquals(3, result.toLibraryIndex)
        assertEquals(listOf(0, 1, 2, 3, 4), result.playbackOrder)
    }

    @Test
    fun `playbackOrderAfterNativeMove preserves shuffled current and swaps visible up-next items`() {
        val result = QueueMutation.playbackOrderAfterNativeMove(
            playbackOrder = listOf(0, 3, 1, 4, 2),
            playbackIndex = 3,
            otherIndex = 2,
            currentPlaybackIndex = 1,
        )!!

        assertEquals(4, result.fromLibraryIndex)
        assertEquals(1, result.toLibraryIndex)
        assertEquals(listOf(0, 4, 1, 2, 3), result.playbackOrder)
    }

    @Test
    fun `playbackOrderAfterNativeMove rejects current and previous items`() {
        assertNull(
            QueueMutation.playbackOrderAfterNativeMove(
                playbackOrder = listOf(0, 1, 2, 3, 4),
                playbackIndex = 1,
                otherIndex = 2,
                currentPlaybackIndex = 1,
            ),
        )
        assertNull(
            QueueMutation.playbackOrderAfterNativeMove(
                playbackOrder = listOf(0, 1, 2, 3, 4),
                playbackIndex = 0,
                otherIndex = 3,
                currentPlaybackIndex = 1,
            ),
        )
    }
}
