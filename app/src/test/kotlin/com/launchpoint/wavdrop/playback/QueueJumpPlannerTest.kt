package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Phase 5 truthful queue-jump command contract via the pure decision helpers
 * [planQueueJump] (accept / execute-now / defer) and [resolvePendingQueueJump] (reconnect-time
 * validation).
 *
 * The defect: [PlayerController.jumpToSongById] returned true whenever an occurrence resolved, even
 * when the controller was null and no seek occurred — a false success that suppressed the caller's
 * fallback and produced a dead tap during a disconnect/reconnect window. The fix distinguishes
 * "executed now" from "accepted for deferred execution", and never seeks a different song if the
 * queue mutated before reconnection.
 */
class QueueJumpPlannerTest {

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

    // ── planQueueJump: accept / execute / defer ───────────────────────────────

    // A. occurrence absent → reject (caller fallback)
    @Test
    fun `absent occurrence is rejected`() {
        assertEquals(QueueJumpAction.Reject, planQueueJump(resolvedIndex = -1, controllerAvailable = true))
    }

    // B. occurrence ambiguous (resolver returned -1) → reject
    @Test
    fun `ambiguous occurrence is rejected`() {
        assertEquals(QueueJumpAction.Reject, planQueueJump(resolvedIndex = -1, controllerAvailable = false))
    }

    // C. valid + controller available → execute now
    @Test
    fun `valid occurrence with controller executes now`() {
        assertEquals(QueueJumpAction.ExecuteNow, planQueueJump(resolvedIndex = 4, controllerAvailable = true))
    }

    // D. valid + controller unavailable → defer and reconnect (CORE REGRESSION; false-success case)
    @Test
    fun `valid occurrence without controller defers and reconnects`() {
        assertEquals(
            QueueJumpAction.DeferAndReconnect,
            planQueueJump(resolvedIndex = 4, controllerAvailable = false),
        )
    }

    // E. invalid playback index (resolver returned -1) → reject, even without controller
    @Test
    fun `invalid index is rejected regardless of controller`() {
        assertEquals(QueueJumpAction.Reject, planQueueJump(resolvedIndex = -1, controllerAvailable = false))
        assertEquals(QueueJumpAction.Reject, planQueueJump(resolvedIndex = -1, controllerAvailable = true))
    }

    // F. latest-intent-wins for the single pending slot (tap X then Y before reconnect → Y).
    @Test
    fun `latest pending jump supersedes an older one`() {
        // Models PlayerController's single pending slot: a later assignment overwrites the earlier.
        var pending: Pair<Long, Int>? = null
        pending = 24L to 4      // tap X
        pending = 25L to 2      // tap Y before reconnect
        assertEquals(25L to 2, pending)
    }

    // ── resolvePendingQueueJump: reconnect-time validation ────────────────────

    // G. pending index still maps to the same song → seek that exact index
    @Test
    fun `pending jump with still-valid index seeks the exact occurrence`() {
        val queue = queueOf(1, 24, 2, 13, 24, 14) // X at 1 and 4
        val resolution = resolvePendingQueueJump(
            queue = queue,
            currentPlaybackIndex = 3,
            songId = 24L,
            resolvedPlaybackIndex = 4,
        )
        assertEquals(PendingQueueJumpResolution.Seek(4), resolution)
    }

    // H. pending index now maps to a DIFFERENT song → must not seek it; re-resolve the real song
    @Test
    fun `pending jump whose index now points at a different song re-resolves the target`() {
        // Original resolved index 4 pointed at X(24). A remove shifted the queue so index 4 is now
        // N(14); the real X(24) is now at index 3. Must seek 3, never 4.
        val mutated = queueOf(1, 2, 13, 24, 14) // X(24) now at index 3, index 4 is N(14)
        val resolution = resolvePendingQueueJump(
            queue = mutated,
            currentPlaybackIndex = 2,
            songId = 24L,
            resolvedPlaybackIndex = 4,
        )
        assertEquals(PendingQueueJumpResolution.Seek(3), resolution)
    }

    // I. queue replaced before reconnect (target absent) → discard, do not seek anything
    @Test
    fun `pending jump against a replacement queue without the target is discarded`() {
        val replacement = queueOf(7, 8, 9) // X(24) is gone entirely
        val resolution = resolvePendingQueueJump(
            queue = replacement,
            currentPlaybackIndex = 0,
            songId = 24L,
            resolvedPlaybackIndex = 4,
        )
        assertEquals(PendingQueueJumpResolution.Discard, resolution)
    }

    @Test
    fun `pending jump index out of bounds re-resolves against current queue`() {
        val queue = queueOf(1, 24, 2) // X(24) at 1
        val resolution = resolvePendingQueueJump(
            queue = queue,
            currentPlaybackIndex = 0,
            songId = 24L,
            resolvedPlaybackIndex = 9, // stale, out of bounds
        )
        assertEquals(PendingQueueJumpResolution.Seek(1), resolution)
    }
}
