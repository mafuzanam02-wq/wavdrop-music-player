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
