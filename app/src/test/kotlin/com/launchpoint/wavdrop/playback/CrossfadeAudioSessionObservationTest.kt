package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CF-2I1: runtime-level, exact-key observation of the secondary audio session and the once-per-preparation diagnostic
 * observer. Observation never mutates state, cancels, or touches any EQ.
 */
class CrossfadeAudioSessionObservationTest {

    private class FakeScheduler : CrossfadeTimingScheduler {
        class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }
        val all = mutableListOf<Pending>()
        val activeNow get() = all.filter { it.active }
        override fun postDelayed(delayMs: Long, block: () -> Unit) { all += Pending(delayMs, block) }
        override fun cancelAll() { all.forEach { it.active = false } }
        fun runNext() { val p = activeNow.single(); p.active = false; p.block() }
    }

    private class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var sessionId = 77
        var sessionReads = 0
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean = true
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = SecondaryHandoffSnapshot(6_125L, 180_000L)
        override fun audioSessionSnapshot(): SecondaryAudioSessionSnapshot? {
            sessionReads++
            return SecondaryAudioSessionSnapshot(sessionId)
        }
        override fun reset() { resets++ }
        override fun release() {}
        fun ready() = callbacks!!.onReady(prepared.last(), 180_000L)
    }

    private data class Observation(val key: CrossfadeTransitionKey, val primary: Int, val secondary: Int?)

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1), song(2), song(3), song(4))
    private val scheduler = FakeScheduler()
    private val backend = FakeBackend()
    private val observations = mutableListOf<Observation>()
    private var primarySession = 5
    private var observerThrows = false
    private var position = 0L
    private val now = 10_000L

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = 5L, playbackQueue = queue, currentPlaybackIndex = 1,
                repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
                playerQueueNeedsSync = false, controllerConnected = true,
            )
        },
        backendFactory = { backend },
        mediaItemFactory = { MediaItem.Builder().setMediaId(it.id.toString()).build() },
        primaryGainBackend = PrimaryGainBackend { true },
        reconcilePrimary = { _, _ -> CrossfadePrimaryReconciliationResult.Succeeded },
        scheduler = scheduler,
        clock = { now },
        configuredDurationMsProvider = { 6_000L },
        primaryDurationMs = { 200_000L },
        primaryPositionMs = { position },
        primaryAudioSessionId = { primarySession },
        audioSessionObserver = CrossfadeAudioSessionObserver { key, primary, secondary ->
            observations += Observation(key, primary, secondary)
            if (observerThrows) error("observer boom")
        },
    )
    private val runtime get() = graph.runtime
    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val wrongKey = CrossfadeTransitionKey(5L, 2, 3)
    private val startA = 194_000L

    private fun toArmed() {
        graph.timingDriver.start()
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Armed)
    }

    private fun toReady() {
        toArmed()
        backend.ready()
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    private fun toFading() {
        toReady()
        position = startA + 1_000L
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    @Test fun idleHasNoSession() {
        assertNull(runtime.secondaryAudioSessionSnapshot(keyA))
        assertEquals(0, backend.sessionReads)
    }

    @Test fun armedAndReadyExposeSessionForExactKeyOnly() {
        toArmed()
        assertEquals(SecondaryAudioSessionSnapshot(77), runtime.secondaryAudioSessionSnapshot(keyA))
        assertNull(runtime.secondaryAudioSessionSnapshot(wrongKey))
        backend.ready()
        assertEquals(SecondaryAudioSessionSnapshot(77), runtime.secondaryAudioSessionSnapshot(keyA))
        assertNull(runtime.secondaryAudioSessionSnapshot(wrongKey))
    }

    @Test fun fadingPreservesExactKeyOwnership() {
        toFading()
        assertEquals(SecondaryAudioSessionSnapshot(77), runtime.secondaryAudioSessionSnapshot(keyA))
        assertNull(runtime.secondaryAudioSessionSnapshot(wrongKey))
    }

    @Test fun unavailableSessionIsObservationOnlyAndNeverCancels() {
        backend.sessionId = 0
        toReady()
        assertNull(runtime.secondaryAudioSessionSnapshot(keyA))
        assertTrue(runtime.state is CrossfadeState.Ready)
        assertEquals(0, backend.resets)
        assertEquals(listOf(Observation(keyA, 5, null)), observations)
    }

    @Test fun readingNeverChangesState() {
        toReady()
        val before = runtime.state
        repeat(4) { runtime.secondaryAudioSessionSnapshot(keyA) }
        assertEquals(before, runtime.state)
        assertEquals(0, backend.resets)
    }

    @Test fun cancelRemovesSession() {
        toReady()
        runtime.cancel(CrossfadeCancelReason.Pause)
        assertNull(runtime.secondaryAudioSessionSnapshot(keyA))
    }

    @Test fun handoffSuccessLeavesNoOldSessionOwnership() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        assertTrue(runtime.executeHandoff(keyA) is CrossfadeHandoffExecutionResult.Succeeded)
        assertNull(runtime.secondaryAudioSessionSnapshot(keyA))
    }

    @Test fun closeRemovesSession() {
        toFading()
        runtime.close()
        assertNull(runtime.secondaryAudioSessionSnapshot(keyA))
    }

    @Test fun observerFiresOnceAtReadyWithBothIds() {
        toReady()
        assertEquals(listOf(Observation(keyA, 5, 77)), observations)
        assertEquals(CrossfadeAudioSessionRelationship.Distinct, compareCrossfadeAudioSessions(5, 77))
    }

    @Test fun observerCarriesSharedIdsUnchanged() {
        primarySession = 77
        toReady()
        assertEquals(listOf(Observation(keyA, 77, 77)), observations)
        assertEquals(
            CrossfadeAudioSessionRelationship.Shared,
            compareCrossfadeAudioSessions(observations.single().primary, observations.single().secondary),
        )
    }

    @Test fun timingTicksAndFadeDoNotRepeatObservation() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        assertEquals(1, observations.size)
    }

    @Test fun staleReadyAfterCancelNeverObserves() {
        toArmed()
        runtime.cancel(CrossfadeCancelReason.Pause)
        backend.ready() // late callback of the cancelled preparation
        assertTrue(observations.isEmpty())
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun throwingObserverCannotAffectOwnership() {
        observerThrows = true
        toReady()
        assertEquals(1, observations.size)
        assertTrue(runtime.state is CrossfadeState.Ready)
        assertEquals(0, backend.resets)
    }
}
