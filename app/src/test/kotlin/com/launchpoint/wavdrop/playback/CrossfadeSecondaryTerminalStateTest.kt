package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CF-2F3: an unexpected secondary STATE_IDLE / STATE_ENDED is routed through the real mapping
 * ([dispatchSecondaryPlaybackState]) into the existing exact-attempt failure path (onError -> failTerminally ->
 * onSecondaryFailed). BUFFERING and READY keep their meanings.
 */
class CrossfadeSecondaryTerminalStateTest {

    private class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var releases = 0
        val starts = mutableListOf<Float>()

        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }

        override fun start(initialGain: Float): Boolean { starts += initialGain; return true }
        override fun setGain(gain: Float): Boolean = true
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = null
        override fun reset() { resets++ }
        override fun release() { releases++ }
    }

    private class RecordingListener : CrossfadeSecondaryListener {
        val readies = mutableListOf<CrossfadeTransitionKey>()
        val failures = mutableListOf<CrossfadeTransitionKey>()
        override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) { readies += key }
        override fun onSecondaryFailed(key: CrossfadeTransitionKey) { failures += key }
    }

    private val backend = FakeBackend()
    private val listener = RecordingListener()
    private val owner = CrossfadeSecondaryPlayer(backendFactory = { backend }, listener = listener)
    private val keyA = CrossfadeTransitionKey(1L, 0, 1)
    private val keyB = CrossfadeTransitionKey(1L, 1, 2)

    private fun song(id: Long) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun attemptOf(index: Int) = backend.prepared[index]

    /** What the real attempt listener does for one state change of [attempt]. */
    private fun state(attempt: Long, playbackState: Int) =
        dispatchSecondaryPlaybackState(attempt, playbackState, { 180_000L }, backend.callbacks!!)

    private fun errorCallback(attempt: Long) = backend.callbacks!!.onError(attempt)

    @Test fun terminalStateTruthTable() {
        assertTrue(isSecondaryTerminalPlaybackState(Player.STATE_IDLE))
        assertTrue(isSecondaryTerminalPlaybackState(Player.STATE_ENDED))
        assertFalse(isSecondaryTerminalPlaybackState(Player.STATE_BUFFERING))
        assertFalse(isSecondaryTerminalPlaybackState(Player.STATE_READY))
    }

    @Test fun readyIsReadinessAndNeverAFailure() {
        owner.prepare(keyA, song(1))
        state(attemptOf(0), Player.STATE_READY)
        assertEquals(listOf(keyA), listener.readies)
        assertTrue(listener.failures.isEmpty())
    }

    @Test fun bufferingIsAllowedInEveryLivePhase() {
        owner.prepare(keyA, song(1))
        state(attemptOf(0), Player.STATE_BUFFERING) // Preparing
        state(attemptOf(0), Player.STATE_READY)
        state(attemptOf(0), Player.STATE_BUFFERING) // Prepared
        assertTrue(owner.start(keyA, 0f))
        state(attemptOf(0), Player.STATE_BUFFERING) // Started
        assertTrue(listener.failures.isEmpty())
        assertEquals(keyA, owner.currentKey)
        assertEquals(0, backend.resets)
    }

    @Test fun terminalWhilePreparingFailsTheExactAttempt() {
        owner.prepare(keyA, song(1))
        state(attemptOf(0), Player.STATE_IDLE)
        assertEquals(listOf(keyA), listener.failures)
        assertEquals(1, backend.resets)
        assertNull(owner.currentKey)
        assertTrue(listener.readies.isEmpty())
    }

    @Test fun terminalWhilePreparedFailsTheAttemptInsteadOfKeepingAGoneSecondary() {
        owner.prepare(keyA, song(1))
        state(attemptOf(0), Player.STATE_READY)
        state(attemptOf(0), Player.STATE_ENDED)
        assertEquals(listOf(keyA), listener.failures)
        assertEquals(1, backend.resets)
        assertNull(owner.currentKey)
        assertFalse(owner.start(keyA, 0f))
    }

    @Test fun terminalWhileStartedFailsTheAttempt() {
        owner.prepare(keyA, song(1))
        state(attemptOf(0), Player.STATE_READY)
        assertTrue(owner.start(keyA, 0f))
        state(attemptOf(0), Player.STATE_ENDED)
        assertEquals(listOf(keyA), listener.failures)
        assertEquals(1, backend.resets)
        assertNull(owner.currentKey)
    }

    @Test fun endedThenIdleFailsOnce() {
        owner.prepare(keyA, song(1))
        state(attemptOf(0), Player.STATE_READY)
        owner.start(keyA, 0f)
        state(attemptOf(0), Player.STATE_ENDED)
        state(attemptOf(0), Player.STATE_IDLE)
        assertEquals(1, listener.failures.size)
        assertEquals(1, backend.resets)
    }

    @Test fun errorThenTerminalIsStale() {
        owner.prepare(keyA, song(1))
        errorCallback(attemptOf(0))
        state(attemptOf(0), Player.STATE_IDLE)
        assertEquals(listOf(keyA), listener.failures)
        assertEquals(1, backend.resets)
    }

    @Test fun terminalThenErrorIsStale() {
        owner.prepare(keyA, song(1))
        state(attemptOf(0), Player.STATE_ENDED)
        errorCallback(attemptOf(0))
        assertEquals(listOf(keyA), listener.failures)
        assertEquals(1, backend.resets)
    }

    @Test fun supersededAttemptTerminalLeavesTheNewAttemptUntouched() {
        owner.prepare(keyA, song(1))
        owner.prepare(keyB, song(2))
        state(attemptOf(0), Player.STATE_IDLE) // late terminal of A
        assertTrue(listener.failures.isEmpty())
        assertEquals(keyB, owner.currentKey)
        assertEquals(0, backend.resets)
        state(attemptOf(1), Player.STATE_READY)
        assertEquals(listOf(keyB), listener.readies)
    }

    @Test fun terminalAfterAbandonIsIgnored() {
        owner.prepare(keyA, song(1))
        assertTrue(owner.abandon(keyA))
        val resets = backend.resets
        state(attemptOf(0), Player.STATE_IDLE)
        assertTrue(listener.failures.isEmpty())
        assertEquals(resets, backend.resets)
    }

    @Test fun terminalAfterReleaseIsIgnored() {
        owner.prepare(keyA, song(1))
        owner.release()
        val resets = backend.resets
        state(attemptOf(0), Player.STATE_ENDED)
        assertTrue(listener.failures.isEmpty())
        assertEquals(resets, backend.resets)
    }

    @Test fun newPreparationAfterTerminalFailureWorks() {
        owner.prepare(keyA, song(1))
        state(attemptOf(0), Player.STATE_IDLE)
        assertTrue(owner.prepare(keyB, song(2)))
        state(attemptOf(1), Player.STATE_READY)
        assertEquals(listOf(keyB), listener.readies)
        assertEquals(keyB, owner.currentKey)
    }

    @Test fun terminalFailureListenerStartingANewPreparationIsNotWiped() {
        val b = FakeBackend()
        lateinit var o: CrossfadeSecondaryPlayer
        val failures = mutableListOf<CrossfadeTransitionKey>()
        val l = object : CrossfadeSecondaryListener {
            override fun onSecondaryReady(key: CrossfadeTransitionKey, preparedDurationMs: Long) = Unit
            override fun onSecondaryFailed(key: CrossfadeTransitionKey) {
                failures += key
                o.prepare(keyB, song(2)) // re-entrant: a new attempt starts synchronously
            }
        }
        o = CrossfadeSecondaryPlayer(backendFactory = { b }, listener = l)
        o.prepare(keyA, song(1))
        dispatchSecondaryPlaybackState(b.prepared[0], Player.STATE_ENDED, { 0L }, b.callbacks!!)
        assertEquals(listOf(keyA), failures)
        assertEquals(keyB, o.currentKey) // B survived A's failure cleanup
        assertEquals(2, b.prepared.size)
    }
}
