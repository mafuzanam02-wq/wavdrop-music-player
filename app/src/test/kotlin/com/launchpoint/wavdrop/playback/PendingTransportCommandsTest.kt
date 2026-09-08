package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `deferred seek applies when the target track is still current`() {
        val seek = PendingSeek(positionMs = 42_000L, targetSongId = 7L)
        assertTrue(PendingTransport.shouldApplySeek(seek, currentSongId = 7L))
    }

    @Test
    fun `deferred seek is dropped when the current track changed`() {
        val seek = PendingSeek(positionMs = 42_000L, targetSongId = 7L)
        // Queue advanced to a different song before reconnect — must NOT seek the wrong track.
        assertFalse(PendingTransport.shouldApplySeek(seek, currentSongId = 8L))
    }

    @Test
    fun `deferred seek is dropped when there is no current track`() {
        val seek = PendingSeek(positionMs = 42_000L, targetSongId = 7L)
        assertFalse(PendingTransport.shouldApplySeek(seek, currentSongId = null))
    }

    // ── Bounded model shape (single latest-wins slots, not command lists) ───────

    @Test
    fun `navigation intent has exactly the two directions`() {
        assertTrue(NavigationIntent.entries.toSet() == setOf(NavigationIntent.NEXT, NavigationIntent.PREVIOUS))
    }

    // ── Warm-reconnect shuffle-queue synchronization policy ─────────────────────
    //
    // Regression for the Phase 7 correction: a shuffle change made while the MediaController is
    // unavailable updates the app-owned playbackQueue/playbackOrder, but the PRE-FIX dirty flag was
    // `controller != null && !requiresCurrentItemReplacement` — so with no controller it stayed
    // false and a WARM reconnect (session alive, player never reloaded as it would be on a COLD
    // start) left the live player's traversal order stale with nothing pending to sync it.

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 180_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    @Test
    fun `A shuffle OFF to ON while controller absent marks the queue as needing sync`() {
        // requiresCurrentItemReplacement is false for the reorder model → dirty flag must be set,
        // independently of controller availability (the pre-fix `controller != null` guard was the
        // bug). A warm reconnect then sees a dirty queue and synchronizes.
        assertTrue(shuffleToggleRequiresQueueSync(requiresCurrentItemReplacement = false))
        assertTrue(reconnectRequiresQueueSync(playerQueueNeedsSync = true, supersededByQueueRequest = false))
    }

    @Test
    fun `B shuffle ON to OFF while controller absent also marks the queue as needing sync`() {
        // The pure decision does not depend on shuffle direction: turning shuffle OFF while
        // disconnected diverges the app-owned (unshuffled) order from the stale player just the same.
        assertTrue(shuffleToggleRequiresQueueSync(requiresCurrentItemReplacement = false))
        assertTrue(reconnectRequiresQueueSync(playerQueueNeedsSync = true, supersededByQueueRequest = false))
    }

    @Test
    fun `C shuffle change is marked dirty identically whether or not a controller is present`() {
        // The fix removed controller availability from the decision. The controller-present path
        // (existing immediate behavior) still marks the queue dirty for the deferred-sync machinery,
        // exactly as before — so nothing about the connected case regresses.
        val withoutController = shuffleToggleRequiresQueueSync(requiresCurrentItemReplacement = false)
        val withController = shuffleToggleRequiresQueueSync(requiresCurrentItemReplacement = false)
        assertEquals(withController, withoutController)
        assertTrue(withController)
    }

    @Test
    fun `D final app-owned order wins after two shuffle changes while disconnected`() {
        val queue = listOf(song(1), song(2), song(3), song(4), song(5))
        // OFF -> ON
        val on = QueueMutation.shuffleToggleModel(
            libraryQueue = queue,
            currentSongId = 3L,
            shuffleEnabled = true,
            random = Random(7),
        )!!
        // ON -> OFF (second toggle, threading the current song through)
        val off = QueueMutation.shuffleToggleModel(
            libraryQueue = queue,
            currentSongId = on.currentSong.id,
            shuffleEnabled = false,
            random = Random(7),
        )!!
        // The last toggle wins: shuffle OFF restores the identity (source) order, and THAT is the
        // app-owned order a warm reconnect must push — not the intermediate shuffled order.
        assertEquals(queue.indices.toList(), off.playbackOrder)
        assertEquals(queue, off.playbackQueue)
        assertTrue(shuffleToggleRequiresQueueSync(off.requiresCurrentItemReplacement))
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
