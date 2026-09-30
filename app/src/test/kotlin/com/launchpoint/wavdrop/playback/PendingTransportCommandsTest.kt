package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PendingTransportCommandsTest {

    // ── Play/Pause: desired-state intent, never a raw toggle ────────────────────

    @Test
    fun `desired play when ready is the inverse of current playing state`() {
        assertTrue(PendingTransport.desiredPlayWhenReady(currentlyPlaying = false))
        assertFalse(PendingTransport.desiredPlayWhenReady(currentlyPlaying = true))
    }

    // ── Seek: bound to the track it was issued against ──────────────────────────

    @Test
    fun `A same occurrence and generation applies`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        val seek = capturePendingSeek(10L, queue, 2, 42_000L)!!
        assertEquals(
            PendingSeekResolution.Apply(42_000L),
            PendingTransport.resolveSeek(seek, 10L, queue, 2),
        )
    }

    @Test
    fun `B duplicate song at another occurrence is discarded`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        val seek = capturePendingSeek(10L, queue, 2, 42_000L)!!
        assertEquals(
            PendingSeekResolution.Discard,
            PendingTransport.resolveSeek(seek, 10L, queue, 0),
        )
    }

    @Test
    fun `C generation change with same song is discarded`() {
        val oldQueue = listOf(song(1), song(2), song(1))
        val replacement = listOf(song(4), song(1), song(5))
        val seek = capturePendingSeek(10L, oldQueue, 2, 42_000L)!!
        assertEquals(
            PendingSeekResolution.Discard,
            PendingTransport.resolveSeek(seek, 11L, replacement, 1),
        )
    }

    @Test
    fun `D generation change with same index and song is discarded`() {
        val queue = listOf(song(1), song(2), song(1))
        val seek = capturePendingSeek(10L, queue, 2, 42_000L)!!
        assertEquals(
            PendingSeekResolution.Discard,
            PendingTransport.resolveSeek(seek, 11L, queue, 2),
        )
    }

    @Test
    fun `E captured index outside current queue is discarded`() {
        val oldQueue = listOf(song(1), song(2), song(3))
        val seek = capturePendingSeek(10L, oldQueue, 2, 42_000L)!!
        assertEquals(
            PendingSeekResolution.Discard,
            PendingTransport.resolveSeek(seek, 10L, oldQueue.take(2), 1),
        )
    }

    @Test
    fun `F captured index now holding another song is discarded`() {
        val oldQueue = listOf(song(1), song(2), song(3))
        val changedQueue = listOf(song(1), song(2), song(4))
        val seek = capturePendingSeek(10L, oldQueue, 2, 42_000L)!!
        assertEquals(
            PendingSeekResolution.Discard,
            PendingTransport.resolveSeek(seek, 10L, changedQueue, 2),
        )
    }

    @Test
    fun `G normal non duplicate reconnect applies`() {
        val queue = listOf(song(1), song(2), song(3))
        val seek = capturePendingSeek(10L, queue, 1, 30_000L)!!
        assertEquals(
            PendingSeekResolution.Apply(30_000L),
            PendingTransport.resolveSeek(seek, 10L, queue, 1),
        )
    }

    @Test
    fun `H navigation to another occurrence discards stale seek`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        val seek = capturePendingSeek(10L, queue, 2, 42_000L)!!
        assertEquals(
            PendingSeekResolution.Discard,
            PendingTransport.resolveSeek(seek, 10L, queue, 3),
        )
    }

    @Test
    fun `I pending queue jump to another occurrence discards stale seek`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        val seek = capturePendingSeek(10L, queue, 2, 42_000L)!!
        assertEquals(
            PendingSeekResolution.Discard,
            PendingTransport.resolveSeek(seek, 10L, queue, 0),
        )
    }

    @Test
    fun `J queue replacement before reconnect discards seek`() {
        val queue = listOf(song(1), song(2), song(1))
        val seek = capturePendingSeek(10L, queue, 2, 45_000L)!!
        val replacement = listOf(song(4), song(5), song(1))
        assertEquals(
            PendingSeekResolution.Discard,
            PendingTransport.resolveSeek(seek, 11L, replacement, 2),
        )
    }

    @Test
    fun `K latest seek on same occurrence replaces older position`() {
        val queue = listOf(song(1), song(2), song(3))
        var pending = capturePendingSeek(10L, queue, 1, 10_000L)
        pending = capturePendingSeek(10L, queue, 1, 30_000L)
        assertEquals(
            PendingSeekResolution.Apply(30_000L),
            PendingTransport.resolveSeek(pending!!, 10L, queue, 1),
        )
    }

    @Test
    fun `L latest seek on new occurrence replaces older occurrence intent`() {
        val queue = listOf(song(1), song(2), song(1), song(3))
        var pending = capturePendingSeek(10L, queue, 2, 10_000L)
        pending = capturePendingSeek(10L, queue, 0, 25_000L)
        assertEquals(
            PendingSeekResolution.Apply(25_000L),
            PendingTransport.resolveSeek(pending!!, 10L, queue, 0),
        )
    }

    @Test
    fun `unresolved occurrence is not captured`() {
        val queue = listOf(song(1), song(2), song(3))
        assertNull(capturePendingSeek(10L, queue, null, 30_000L))
        assertNull(capturePendingSeek(10L, queue, 4, 30_000L))
    }

    // ── Bounded model shape (single latest-wins slots, not command lists) ───────

    @Test
    fun `navigation intent has exactly the two directions`() {
        assertTrue(NavigationIntent.entries.toSet() == setOf(NavigationIntent.NEXT, NavigationIntent.PREVIOUS))
    }

    // ── Warm-reconnect queue synchronization policy ─────────────────────────────
    //
    // Whether a shuffle toggle reaches the player live or is deferred is decided by
    // planShufflePhysicalSync (see ShuffleQueueSyncPlannerTest). What remains here is the
    // reconnect-side policy for a queue left dirty, and the shuffle model round trip.

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    @Test
    fun `D final app-owned order wins after two shuffle changes while disconnected`() {
        val queue = listOf(song(1), song(2), song(3), song(4), song(5))
        // OFF -> ON
        val on = QueueMutation.shuffleToggleModel(
            libraryQueue = queue,
            currentPlaybackOrder = queue.indices.toList(),
            currentPlaybackIndex = 2,
            shuffleEnabled = true,
            random = Random(7),
        )!!
        // ON -> OFF (second toggle, threading the current song through)
        val off = QueueMutation.shuffleToggleModel(
            libraryQueue = queue,
            currentPlaybackOrder = on.playbackOrder,
            currentPlaybackIndex = on.currentPlaybackIndex,
            shuffleEnabled = false,
            random = Random(7),
        )!!
        // The last toggle wins: shuffle OFF restores the identity (source) order, and THAT is the
        // app-owned order a warm reconnect must push — not the intermediate shuffled order.
        assertEquals(queue.indices.toList(), off.playbackOrder)
        assertEquals(queue, off.playbackQueue)
    }

    @Test
    fun `E a stale connection attempt cannot consume the dirty queue state`() {
        // Only the authoritative generation drains pending state / synchronizes the queue. A late
        // completion from a superseded attempt is discarded and never reaches the sync decision.
        assertEquals(
            ControllerAttemptOutcome.DiscardStale,
            ControllerAttemptOwnership.onCompletion(attemptGeneration = 1L, currentGeneration = 2L),
        )
        assertEquals(
            ControllerAttemptOutcome.Apply,
            ControllerAttemptOwnership.onCompletion(attemptGeneration = 2L, currentGeneration = 2L),
        )
    }

    @Test
    fun `F authoritative reconnect synchronizes then clears the dirty state exactly once`() {
        // First authoritative reconnect: dirty and not superseded → sync required.
        assertTrue(reconnectRequiresQueueSync(playerQueueNeedsSync = true, supersededByQueueRequest = false))
        // syncPlayerQueueAt clears the flag; a subsequent evaluation must NOT sync again (idempotent).
        assertFalse(reconnectRequiresQueueSync(playerQueueNeedsSync = false, supersededByQueueRequest = false))
    }

    @Test
    fun `a queue-replacing request supersedes the dirty queue and skips the reconnect sync`() {
        // A play/restore/jump request captured while disconnected reloads the player itself, so the
        // active reconnect sync must stand down even though the queue was marked dirty.
        assertFalse(reconnectRequiresQueueSync(playerQueueNeedsSync = true, supersededByQueueRequest = true))
    }

    @Test
    fun `a clean queue never triggers a reconnect sync`() {
        assertFalse(reconnectRequiresQueueSync(playerQueueNeedsSync = false, supersededByQueueRequest = false))
    }
}
