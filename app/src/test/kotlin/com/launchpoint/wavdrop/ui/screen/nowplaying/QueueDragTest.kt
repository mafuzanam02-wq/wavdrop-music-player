package com.launchpoint.wavdrop.ui.screen.nowplaying

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase A queue long-reorder correctness, via the pure drag-decision helpers. Compose UI is not
 * unit-tested here; the safety invariants are proven at the decision level.
 *
 * Cases C (cancel), F (duplicate occurrence), H (playback overtake) and J (auto-scroll suppression)
 * demonstrate a behavioural failure against the pre-fix model before the fix is applied.
 */
class QueueDragTest {

    private fun song(id: Long) = Song(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private fun queueOf(vararg ids: Long) = ids.map { song(it) }

    /** Builds a session as the UI would at drag start: ordinal computed from the start queue. */
    private fun sessionFor(queue: List<Song>, startIndex: Int): QueueDragSession = QueueDragSession(
        sourceSongId = queue[startIndex].id,
        sourceOccurrenceOrdinal = queueOccurrenceOrdinal(queue, startIndex),
        startSourcePlaybackIndex = startIndex,
    )

    // A. valid end, source 4 -> target 20 → Commit(4,20)
    @Test
    fun `valid end commits from source to target`() {
        val queue = queueOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        val session = sessionFor(queue, startIndex = 4)
        val decision = planQueueDragEnd(queue, currentIndex = 0, session, targetPlaybackIndex = 20, cancelled = false)
        assertEquals(QueueDragEndDecision.Commit(4, 20), decision)
    }

    // B. same source/target → NoOp
    @Test
    fun `same source and target is a no-op`() {
        val queue = queueOf(0, 1, 2, 3, 4, 5)
        val session = sessionFor(queue, startIndex = 4)
        val decision = planQueueDragEnd(queue, currentIndex = 0, session, targetPlaybackIndex = 4, cancelled = false)
        assertEquals(QueueDragEndDecision.NoOp, decision)
    }

    // C. cancel with source 4 target 20 → NoOp (CORE: old model committed here)
    @Test
    fun `cancel never commits`() {
        val queue = queueOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        val session = sessionFor(queue, startIndex = 4)
        val decision = planQueueDragEnd(queue, currentIndex = 0, session, targetPlaybackIndex = 20, cancelled = true)
        assertEquals(QueueDragEndDecision.NoOp, decision)
    }

    // D. source occurrence removed during drag → NoOp
    @Test
    fun `removed source occurrence does not commit`() {
        val start = queueOf(9, 1, 2, 3, 4) // current=9 at 0; drag "3" at index 3
        val session = sessionFor(start, startIndex = 3)
        val mutated = queueOf(9, 1, 2, 4) // "3" removed
        val decision = planQueueDragEnd(mutated, currentIndex = 0, session, targetPlaybackIndex = 3, cancelled = false)
        assertEquals(QueueDragEndDecision.NoOp, decision)
    }

    // E. start index now points to another song → must never move that song
    @Test
    fun `stale start index never moves a different song`() {
        val start = queueOf(9, 1, 7, 3) // current=9; drag "7" at index 2
        val session = sessionFor(start, startIndex = 2)
        // "1" removed: index 2 now holds "3" (a different song); "7" is gone.
        val mutated = queueOf(9, 7, 3) // actually 7 still present at 1
        // Rebuild a case where the original song is truly gone and index 2 holds a different song:
        val goneQueue = queueOf(9, 3, 5, 8) // "7" absent; index 2 is "5"
        val decision = planQueueDragEnd(goneQueue, currentIndex = 0, session, targetPlaybackIndex = 3, cancelled = false)
        assertEquals(QueueDragEndDecision.NoOp, decision)
        // And when present, it resolves the real occurrence, not whatever sits at the start index.
        val resolved = resolveDraggedOccurrenceIndex(mutated, session)
        assertEquals(1, resolved) // "7" is at index 1 now, not the stale start index 2
    }

    // F. duplicate ids: dragged occurrence tracked; the wrong duplicate is never selected.
    @Test
    fun `duplicate occurrence is tracked and the wrong duplicate is never moved`() {
        val start = queueOf(9, 8, 5, 5) // current=9; two "5"s at 2 and 3; drag the FIRST "5" (index 2)
        val session = sessionFor(start, startIndex = 2)
        assertEquals(0, session.sourceOccurrenceOrdinal) // first occurrence
        val mutated = queueOf(9, 5, 5) // "8" removed → first "5" now at index 1, second at index 2
        val decision = planQueueDragEnd(mutated, currentIndex = 0, session, targetPlaybackIndex = 2, cancelled = false)
        // Must move the FIRST "5" (now index 1), NOT the item sitting at the stale start index 2.
        assertEquals(QueueDragEndDecision.Commit(1, 2), decision)
    }

    // G. harmless shift, unique occurrence remains resolvable → resolve new index
    @Test
    fun `harmless shift resolves the moved occurrence`() {
        val start = queueOf(9, 1, 2, 7, 3) // current=9; drag unique "7" at index 3
        val session = sessionFor(start, startIndex = 3)
        val mutated = queueOf(9, 2, 7, 3) // "1" removed → "7" now at index 2
        val decision = planQueueDragEnd(mutated, currentIndex = 0, session, targetPlaybackIndex = 3, cancelled = false)
        assertEquals(QueueDragEndDecision.Commit(2, 3), decision)
    }

    // H. currentIndex advances onto the dragged source → cancel/no commit
    @Test
    fun `playback overtaking the source does not commit`() {
        val queue = queueOf(9, 7, 8) // drag "7" at index 1
        val session = sessionFor(queue, startIndex = 1)
        // Playback advanced: current is now index 1 (the dragged item itself).
        val decision = planQueueDragEnd(queue, currentIndex = 1, session, targetPlaybackIndex = 2, cancelled = false)
        assertEquals(QueueDragEndDecision.NoOp, decision)
        assertFalse(isDraggedOccurrenceStillValid(queue, currentIndex = 1, session))
    }

    // I. target becomes <= currentIndex → no commit
    @Test
    fun `target inside history or current does not commit`() {
        val queue = queueOf(9, 8, 7, 6) // current will be 2; drag "6" at index 3
        val session = sessionFor(queue, startIndex = 3)
        val decision = planQueueDragEnd(queue, currentIndex = 2, session, targetPlaybackIndex = 2, cancelled = false)
        assertEquals(QueueDragEndDecision.NoOp, decision)
    }

    // J. currentIndex change during active drag → suppress Playing Now auto-scroll
    @Test
    fun `auto-scroll suppressed while dragging`() {
        assertFalse(shouldAutoScrollToPlayingNow(currentIndex = 5, isDragActive = true))
    }

    // K. currentIndex change outside drag → normal auto-scroll
    @Test
    fun `auto-scroll allowed when not dragging`() {
        assertTrue(shouldAutoScrollToPlayingNow(currentIndex = 5, isDragActive = false))
        assertFalse(shouldAutoScrollToPlayingNow(currentIndex = -1, isDragActive = false))
    }

    // L. drag disposed/cancelled → no commit
    @Test
    fun `disposed or cancelled drag does not commit`() {
        val queue = queueOf(9, 1, 2, 3)
        val session = sessionFor(queue, startIndex = 2)
        assertEquals(
            QueueDragEndDecision.NoOp,
            planQueueDragEnd(queue, currentIndex = 0, session, targetPlaybackIndex = 3, cancelled = true),
        )
    }

    @Test
    fun `occurrence ordinal counts same-id rows before the index`() {
        val queue = queueOf(5, 5, 7, 5) // three "5"s at 0,1,3
        assertEquals(0, queueOccurrenceOrdinal(queue, 0))
        assertEquals(1, queueOccurrenceOrdinal(queue, 1))
        assertEquals(2, queueOccurrenceOrdinal(queue, 3))
        assertEquals(0, queueOccurrenceOrdinal(queue, 2)) // "7" is unique
    }

    @Test
    fun `resolve returns null when the ordinal occurrence no longer exists`() {
        val start = queueOf(9, 5, 8, 5) // drag second "5" at index 3 (ordinal 1)
        val session = sessionFor(start, startIndex = 3)
        assertEquals(1, session.sourceOccurrenceOrdinal)
        val mutated = queueOf(9, 8, 5) // only one "5" left → ordinal 1 no longer exists
        assertNull(resolveDraggedOccurrenceIndex(mutated, session))
    }
}
