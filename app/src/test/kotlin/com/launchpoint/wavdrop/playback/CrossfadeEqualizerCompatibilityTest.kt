package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CF-2I2: the primary alone carries the Equalizer, so crossfade is unavailable while EQ is enabled, and an OFF -> ON change
 * terminates any owned transition (PlanInvalidated) without stopping the driver or touching any setting.
 */
class CrossfadeEqualizerCompatibilityTest {

    private val events = mutableListOf<String>()

    private class FakeScheduler : CrossfadeTimingScheduler {
        class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }
        val all = mutableListOf<Pending>()
        val activeNow get() = all.filter { it.active }
        override fun postDelayed(delayMs: Long, block: () -> Unit) { all += Pending(delayMs, block) }
        override fun cancelAll() { all.forEach { it.active = false } }
        fun runNext() { val p = activeNow.single(); p.active = false; p.block() }
    }

    private inner class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean = true
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = SecondaryHandoffSnapshot(6_125L, 180_000L)
        override fun reset() { resets++; events += "s:reset" }
        override fun release() {}
        fun ready() = callbacks!!.onReady(prepared.last(), 180_000L)
    }

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1), song(2), song(3), song(4))
    private var eqEnabled = false
    private val scheduler = FakeScheduler()
    private val backend = FakeBackend()
    private val primaryWrites = mutableListOf<Float>()
    private var reconcileCalls = 0
    private var position = 0L
    private val now = 10_000L

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = 5L, playbackQueue = queue, currentPlaybackIndex = 1,
                repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
                playerQueueNeedsSync = false, controllerConnected = true, equalizerEnabled = eqEnabled,
            )
        },
        backendFactory = { backend },
        mediaItemFactory = { MediaItem.Builder().setMediaId(it.id.toString()).build() },
        primaryGainBackend = PrimaryGainBackend { primaryWrites += it; events += "p:$it"; true },
        reconcilePrimary = { _, _ -> reconcileCalls++; CrossfadePrimaryReconciliationResult.Succeeded },
        scheduler = scheduler,
        clock = { now },
        configuredDurationMsProvider = { 6_000L },
        primaryDurationMs = { 200_000L },
        primaryPositionMs = { position },
    )
    private val runtime get() = graph.runtime
    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val startA = 194_000L

    private fun toArmed() {
        graph.timingDriver.start()
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Armed)
    }

    private fun toReady() { toArmed(); backend.ready(); assertTrue(runtime.state is CrossfadeState.Ready) }

    private fun toFading() {
        toReady()
        position = startA + 1_000L
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Fading)
        assertTrue(primaryWrites.last() < 1f)
    }

    /** What the service collector does on an OFF -> ON emission. */
    private fun enableEqualizer() {
        val previous = eqEnabled
        eqEnabled = true
        if (shouldCancelCrossfadeForEqualizerChange(previous, true)) recoverCrossfadeFromEqualizerEnabled(runtime)
    }

    @Test fun settingTransitionTable() {
        assertFalse(shouldCancelCrossfadeForEqualizerChange(false, false))
        assertTrue(shouldCancelCrossfadeForEqualizerChange(false, true))
        assertFalse(shouldCancelCrossfadeForEqualizerChange(true, false))
        assertFalse(shouldCancelCrossfadeForEqualizerChange(true, true))
    }

    @Test fun nullRuntimeIsHarmless() {
        recoverCrossfadeFromEqualizerEnabled(null)
    }

    @Test fun eqEnabledMeansNoPreparationAndNoPlaybackEffects() {
        eqEnabled = true
        graph.timingDriver.start()
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.prepared.isEmpty())
        assertTrue(primaryWrites.isEmpty())
        assertTrue(scheduler.activeNow.isNotEmpty()) // driver keeps pulsing
    }

    @Test fun armedEvaluationWithEqEnabledCancelsViaPlanInvalidated() {
        toArmed()
        eqEnabled = true
        runtime.evaluatePreparation(6_000L, 200_000L)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
    }

    @Test fun armedOffToOnAbandonsSecondary() {
        toArmed()
        enableEqualizer()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
    }

    @Test fun readyOffToOnAbandonsSecondaryWithoutPrimaryWrite() {
        toReady()
        enableEqualizer()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
    }

    @Test fun fadingOffToOnRestoresPrimaryThenResetsSecondary() {
        toFading()
        events.clear()
        enableEqualizer()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(0, reconcileCalls)
    }

    @Test fun handoffPendingOffToOnCleansUpAndStaleHandoffIsInactive() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        events.clear()
        enableEqualizer()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        val resets = backend.resets
        val writes = primaryWrites.size
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA))
        assertEquals(0, reconcileCalls)
        assertEquals(resets, backend.resets)
        assertEquals(writes, primaryWrites.size)
    }

    @Test fun staleFadeTickAfterEqEnableIsInactive() {
        toFading()
        enableEqualizer()
        val writes = primaryWrites.size
        runtime.executeFadeTick(keyA, now + 1_000L)
        assertEquals(writes, primaryWrites.size)
        assertTrue(runtime.state !is CrossfadeState.Fading && runtime.state !is CrossfadeState.HandoffPending)
    }

    @Test fun audibleSnapshotFallbackCancelsOnNextEvaluation() {
        toFading()
        eqEnabled = true // service seam not invoked; the defensive snapshot check must still end the overlap
        runtime.evaluatePreparation(6_000L, 200_000L)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
    }

    @Test fun repeatedEnabledEmissionDoesNothingMore() {
        toFading()
        enableEqualizer()
        val resets = backend.resets
        val writes = primaryWrites.size
        enableEqualizer() // true -> true: not a transition
        assertEquals(resets, backend.resets)
        assertEquals(writes, primaryWrites.size)
    }

    @Test fun disablingDoesNotResurrectButAFreshPulseMayPrepare() {
        toFading()
        enableEqualizer()
        val prepared = backend.prepared.size
        val previous = eqEnabled
        eqEnabled = false
        assertFalse(shouldCancelCrossfadeForEqualizerChange(previous, false))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(prepared, backend.prepared.size) // nothing restored immediately
        position = 0L
        scheduler.runNext() // later driver pulse plans a fresh transition
        assertTrue(runtime.state is CrossfadeState.Armed)
        assertTrue(backend.prepared.size > prepared)
    }

    @Test fun driverKeepsRunningAfterEqEnable() {
        toFading()
        val pending = scheduler.activeNow.single()
        enableEqualizer()
        assertTrue(pending.active)
    }

    @Test fun gateStaysFalse() {
        assertFalse(PlaybackService.CROSSFADE_SECONDARY_RUNTIME_ENABLED)
    }
}
