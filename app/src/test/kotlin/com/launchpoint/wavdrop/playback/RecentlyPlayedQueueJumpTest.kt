package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure occurrence-selection logic used by [PlayerController.jumpToSongById].
 *
 * PlayerController itself requires a live MediaController (Android component) and cannot be
 * instantiated in a JVM unit test. [resolveQueueOccurrenceIndex] is extracted as a package-internal
 * pure function so the "which occurrence of this song?" logic can be exercised without any Android
 * dependencies.
 *
 * Contract: selection is positional relative to the current playback position —
 * current → nearest upcoming → nearest historical → not found. A song id alone does NOT identify an
 * occurrence once duplicates are legal, so the old "first occurrence wins" (indexOfFirst) behaviour
 * is deliberately removed: it jumped backwards to the oldest history even when the song was current
 * or upcoming.
 */
class RecentlyPlayedQueueJumpTest {

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

    // A. empty queue → not found
    @Test
    fun `empty queue returns not found`() {
        assertEquals(-1, resolveQueueOccurrenceIndex(emptyList(), currentPlaybackIndex = null, songId = 1L))
        assertEquals(-1, resolveQueueOccurrenceIndex(emptyList(), currentPlaybackIndex = 0, songId = 1L))
    }

    // B. unique target → its unique index
    @Test
    fun `unique target resolves to its index`() {
        val queue = queueOf(10, 20, 30)
        assertEquals(1, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 0, songId = 20L))
    }

    // C. target is the current occurrence
    @Test
    fun `target that is the current occurrence resolves to current`() {
        val queue = queueOf(1, 24, 2) // A, X, B
        assertEquals(1, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 1, songId = 24L))
    }

    // D. duplicate in history + upcoming → nearest upcoming (MUST fail under old indexOfFirst)
    @Test
    fun `duplicate in history and upcoming resolves to nearest upcoming`() {
        val queue = queueOf(1, 24, 2, 13, 24, 14) // A, X, B, M, X, N ; current = M(3)
        assertEquals(4, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 3, songId = 24L))
    }

    // E. multiple historical duplicates, no future → nearest historical (MUST fail under old)
    @Test
    fun `historical duplicates with no future resolve to nearest historical`() {
        val queue = queueOf(1, 24, 2, 24, 13, 14) // A, X, B, X, M, N ; current = M(4)
        assertEquals(3, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 4, songId = 24L))
    }

    // F. multiple future duplicates → nearest upcoming
    @Test
    fun `multiple future duplicates resolve to nearest upcoming`() {
        val queue = queueOf(1, 13, 24, 2, 24, 14) // A, M, X, B, X, N ; current = M(1)
        assertEquals(2, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 1, songId = 24L))
    }

    // G. target exists only before current → nearest historical
    @Test
    fun `target only before current resolves to historical`() {
        val queue = queueOf(1, 24, 2, 13) // A, X, B, M ; current = M(3)
        assertEquals(1, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 3, songId = 24L))
    }

    // H. target absent → not found
    @Test
    fun `absent target returns not found`() {
        val queue = queueOf(1, 2, 3)
        assertEquals(-1, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 1, songId = 99L))
    }

    // I. duplicate target includes current and future → current wins
    @Test
    fun `duplicate at current and future resolves to current`() {
        val queue = queueOf(1, 24, 24, 2) // A, X, X, B ; current = X(1)
        assertEquals(1, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 1, songId = 24L))
    }

    // J. unresolved current index policy
    @Test
    fun `unresolved current index returns single occurrence`() {
        val queue = queueOf(1, 24, 2)
        assertEquals(1, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = null, songId = 24L))
    }

    @Test
    fun `unresolved current index with multiple occurrences is ambiguous and returns not found`() {
        val queue = queueOf(1, 24, 2, 24, 3) // two X occurrences, no known current position
        assertEquals(-1, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = null, songId = 24L))
    }

    @Test
    fun `out-of-bounds current index is treated as unresolved`() {
        val singleOccurrence = queueOf(1, 24, 2)
        assertEquals(1, resolveQueueOccurrenceIndex(singleOccurrence, currentPlaybackIndex = 99, songId = 24L))
        val multiple = queueOf(1, 24, 2, 24, 3)
        assertEquals(-1, resolveQueueOccurrenceIndex(multiple, currentPlaybackIndex = 99, songId = 24L))
    }

    // ── Recently Played tap decision (caller contract) ────────────────────────
    // Callers do: if (jumpToSongById(id)) return  else  playFromQueue(...)
    // i.e. a non-negative resolver result means "jump"; -1 means "fall back".

    @Test
    fun `song present resolves to a non-negative index so caller jumps`() {
        val queue = queueOf(1, 2, 3)
        val index = resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 0, songId = 2L)
        assert(index >= 0) { "Expected jump path, got index=$index" }
    }

    @Test
    fun `song absent resolves to -1 so caller falls back`() {
        val queue = queueOf(1, 2, 3)
        assertEquals(-1, resolveQueueOccurrenceIndex(queue, currentPlaybackIndex = 0, songId = 99L))
    }
}
