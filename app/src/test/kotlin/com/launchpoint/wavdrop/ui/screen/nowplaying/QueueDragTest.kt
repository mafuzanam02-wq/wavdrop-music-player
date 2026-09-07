package com.launchpoint.wavdrop.ui.screen.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // ── Phase A.1: parent-level handle hit-testing (virtualization-safe gesture ownership) ────────

    private fun handle(playbackIndex: Int, songId: Long, top: Float, bottom: Float) =
        QueueDragHandleTarget(
            playbackIndex = playbackIndex,
            songId = songId,
            // A 20dp-ish handle column at the far left: x in [0,20], y in [top,bottom].
            bounds = Rect(left = 0f, top = top, right = 20f, bottom = bottom),
        )

    // A. pointer-down inside a handle region → eligible; the exact handle is returned.
    @Test
    fun `down inside a handle region is eligible`() {
        val targets = listOf(
            handle(playbackIndex = 3, songId = 100, top = 0f, bottom = 56f),
            handle(playbackIndex = 4, songId = 101, top = 56f, bottom = 112f),
        )
        val hit = findDragHandleAt(targets, Offset(x = 10f, y = 80f))
        assertNotNull(hit)
        assertEquals(4, hit!!.playbackIndex)
        assertEquals(101L, hit.songId)
    }

    // B. pointer-down outside every handle (e.g. on artwork/title, x past the handle column) → null.
    @Test
    fun `down outside all handles is not eligible`() {
        val targets = listOf(handle(playbackIndex = 3, songId = 100, top = 0f, bottom = 56f))
        // Correct Y band but past the handle's right edge (on the row body, not the handle).
        assertNull(findDragHandleAt(targets, Offset(x = 120f, y = 20f)))
        // Correct X but below every registered handle.
        assertNull(findDragHandleAt(targets, Offset(x = 10f, y = 500f)))
    }

    // C. duplicate song ids in separate handles → selection is by exact hit region, NOT song id.
    @Test
    fun `duplicate song ids resolve by hit region not by id`() {
        val targets = listOf(
            handle(playbackIndex = 2, songId = 5, top = 0f, bottom = 56f),   // first "5"
            handle(playbackIndex = 7, songId = 5, top = 56f, bottom = 112f), // second "5"
        )
        val firstHit = findDragHandleAt(targets, Offset(x = 10f, y = 10f))
        val secondHit = findDragHandleAt(targets, Offset(x = 10f, y = 100f))
        assertEquals(2, firstHit!!.playbackIndex)
        assertEquals(7, secondHit!!.playbackIndex)
    }

    // D. empty / stale registry → cannot start a drag.
    @Test
    fun `no registered handles cannot start a drag`() {
        assertNull(findDragHandleAt(emptyList(), Offset(x = 10f, y = 10f)))
    }

    // E. CORE A.1 INVARIANT: unregistering the source handle AFTER the session is created (the row
    // being virtualised off-screen) must NOT invalidate the drag. Session identity is independent of
    // the ephemeral handle registry; it resolves purely against the live queue.
    @Test
    fun `unregistering the source handle after session start does not invalidate the drag`() {
        val queue = queueOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val startIndex = 3
        // Registry as it is at drag start: the source handle is present and hit-tests.
        val registry = linkedMapOf(
            "up-next-2-2" to handle(playbackIndex = 2, songId = 2, top = 0f, bottom = 56f),
            "up-next-3-3" to handle(playbackIndex = 3, songId = 3, top = 56f, bottom = 112f),
        )
        val hit = findDragHandleAt(registry.values, Offset(x = 10f, y = 80f))
        assertEquals(startIndex, hit!!.playbackIndex)

        // Session is created from the hit; this is the parent-owned drag identity.
        val session = sessionFor(queue, startIndex)

        // The source row scrolls far off-screen and is disposed → its handle is unregistered.
        registry.remove("up-next-3-3")
        assertNull(findDragHandleAt(registry.values, Offset(x = 10f, y = 80f)))

        // The drag survives: the occurrence still resolves and a distant end still commits once.
        assertNotNull(resolveDraggedOccurrenceIndex(queue, session))
        assertTrue(isDraggedOccurrenceStillValid(queue, currentIndex = 0, session))
        val decision = planQueueDragEnd(queue, currentIndex = 0, session, targetPlaybackIndex = 10, cancelled = false)
        assertEquals(QueueDragEndDecision.Commit(3, 10), decision)
    }

    // F/G/H: structural invalidation still cancels even though virtualization does not — these reuse
    // the Phase A helpers to prove A.1 did not weaken any Phase A invariant.
    @Test
    fun `structural removal still invalidates after the handle is gone`() {
        val start = queueOf(9, 1, 2, 3, 4) // current=9 at 0; drag "3" at index 3
        val session = sessionFor(start, startIndex = 3)
        val mutated = queueOf(9, 1, 2, 4) // "3" removed from the queue itself (not just the handle)
        assertFalse(isDraggedOccurrenceStillValid(mutated, currentIndex = 0, session))
        assertEquals(
            QueueDragEndDecision.NoOp,
            planQueueDragEnd(mutated, currentIndex = 0, session, targetPlaybackIndex = 3, cancelled = false),
        )
    }

    // The registry entry and the session are genuinely decoupled objects.
    @Test
    fun `handle target and session are independent objects`() {
        val queue = queueOf(0, 1, 2, 3)
        val target = handle(playbackIndex = 2, songId = 2, top = 0f, bottom = 56f)
        val session = QueueDragSession(
            sourceSongId = target.songId,
            sourceOccurrenceOrdinal = queueOccurrenceOrdinal(queue, target.playbackIndex),
            startSourcePlaybackIndex = target.playbackIndex,
        )
        // Forgetting the target collection cannot touch the session's ability to resolve.
        val registry = mutableListOf(target)
        registry.clear()
        assertTrue(registry.isEmpty())
        assertEquals(2, resolveDraggedOccurrenceIndex(queue, session))
    }
}
