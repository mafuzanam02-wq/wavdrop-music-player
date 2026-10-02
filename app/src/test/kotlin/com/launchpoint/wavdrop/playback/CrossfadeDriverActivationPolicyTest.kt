package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2E2: persisted-duration driver activation policy over the real production graph composition. */
class CrossfadeDriverActivationPolicyTest {

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
        override fun reset() { resets++ }
        override fun release() {}
        fun ready() = callbacks!!.onReady(prepared.last(), 180_000L)
    }

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1), song(2), song(3), song(4))
    private val scheduler = FakeScheduler()
    private val backend = FakeBackend()
    private val primaryWrites = mutableListOf<Float>()
    private var reconcileCalls = 0
    private var reconcileResult: CrossfadePrimaryReconciliationResult = CrossfadePrimaryReconciliationResult.Succeeded
    private var configured = 0L
    private var configReads = 0
    private var position = 0L
    private var now = 10_000L

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = 5L, playbackQueue = queue, currentPlaybackIndex = 1,
                repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
                playerQueueNeedsSync = false, controllerConnected = true,
            )
        },
        backendFactory = { backend },
        primaryGainBackend = PrimaryGainBackend { primaryWrites += it; true },
        reconcilePrimary = { _, _ -> reconcileCalls++; reconcileResult },
        scheduler = scheduler,
        clock = { now },
        configuredDurationMsProvider = { configReads++; configured },
        primaryDurationMs = { 200_000L },
        primaryPositionMs = { position },
    )
    private val runtime get() = graph.runtime
    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val startA = 194_000L

    private fun apply(previous: Long?, new: Long) =
        applyCrossfadeConfiguredDurationChange(previous, new, graph.runtime, graph.timingDriver)

    private fun toArmed() {
        configured = 6_000L
        apply(null, 6_000L)
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Armed)
    }

    private fun toReady() { toArmed(); backend.ready(); assertTrue(runtime.state is CrossfadeState.Ready) }

    private fun toFading() {
        toReady()
        position = startA
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    private fun assertOffCleanedUp() {
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(scheduler.activeNow.isEmpty())
        assertEquals(1, backend.resets)
    }

    // -- Pure decision -------------------------------------------------------------------

    @Test fun decisionTable() {
        assertEquals(CrossfadeActivationDecision.NoOp, decideCrossfadeDriverActivation(null, 0L))
        assertEquals(CrossfadeActivationDecision.Start, decideCrossfadeDriverActivation(null, 6_000L))
        assertEquals(CrossfadeActivationDecision.Start, decideCrossfadeDriverActivation(0L, 6_000L))
        assertEquals(CrossfadeActivationDecision.UpdateOnly, decideCrossfadeDriverActivation(6_000L, 6_000L))
        assertEquals(CrossfadeActivationDecision.UpdateOnly, decideCrossfadeDriverActivation(6_000L, 3_000L))
        assertEquals(CrossfadeActivationDecision.Disable, decideCrossfadeDriverActivation(6_000L, 0L))
        assertEquals(CrossfadeActivationDecision.NoOp, decideCrossfadeDriverActivation(0L, 0L))
    }

    // -- Start / update ------------------------------------------------------------------------

    @Test fun initialOffDoesNothing() {
        apply(null, 0L)
        assertTrue(scheduler.all.isEmpty())
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun initialEnabledStartsOnceWithOneDelayZeroCallback() {
        apply(null, 6_000L)
        assertEquals(0L, scheduler.activeNow.single().delayMs)
    }

    @Test fun duplicateEnabledEmissionDoesNotRestart() {
        apply(null, 6_000L)
        apply(6_000L, 6_000L)
        assertEquals(1, scheduler.all.size)
        assertEquals(1, scheduler.activeNow.size)
    }

    @Test fun enabledDurationChangeOnlyUpdatesWithoutStopRestartOrCancel() {
        toFading()
        val state = runtime.state
        configured = 3_000L
        assertEquals(CrossfadeActivationDecision.UpdateOnly, apply(6_000L, 3_000L))
        assertEquals(state, runtime.state) // the live fade keeps its original 6000 ms plan
        assertEquals(6_000L, (runtime.state as CrossfadeState.Fading).effectiveDurationMs)
        assertEquals(1, scheduler.activeNow.size)
        assertEquals(0, backend.resets)
    }

    // -- OFF ---------------------------------------------------------------------------------------

    @Test fun offFromIdleIsHarmlessAndStopsTheDriver() {
        apply(null, 6_000L)
        apply(6_000L, 0L)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(scheduler.activeNow.isEmpty())
    }

    @Test fun offFromArmedAbandonsAndStops() {
        toArmed()
        apply(6_000L, 0L)
        assertOffCleanedUp()
    }

    @Test fun offFromReadyAbandonsAndStops() {
        toReady()
        apply(6_000L, 0L)
        assertOffCleanedUp()
    }

    @Test fun offFromFadingRestoresPrimaryAbandonsSecondaryAndStops() {
        toFading()
        apply(6_000L, 0L)
        assertOffCleanedUp()
        assertEquals(1f, primaryWrites.last(), 0f)
    }

    @Test fun offFromHandoffPendingCleansUpWithoutHandoff() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        apply(6_000L, 0L)
        assertOffCleanedUp()
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(0, reconcileCalls)
    }

    @Test fun staleCallbackAfterOffDoesNothing() {
        apply(null, 6_000L)
        val stale = scheduler.activeNow.single()
        apply(6_000L, 0L)
        val reads = configReads
        stale.block()
        assertEquals(reads, configReads)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(scheduler.activeNow.isEmpty())
    }

    @Test fun enabledOffEnabledRestartsWithAFreshGeneration() {
        apply(null, 6_000L)
        val stale = scheduler.activeNow.single()
        apply(6_000L, 0L)
        apply(0L, 6_000L)
        assertEquals(0L, scheduler.activeNow.single().delayMs)
        val reads = configReads
        stale.block()
        assertEquals(reads, configReads)
        assertEquals(1, scheduler.activeNow.size)
    }

    // -- Halted driver / partial graph -----------------------------------------------------------------

    @Test fun repeatedEnabledValueDoesNotRestartAHaltedDriver() {
        toFading()
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty)
        now += 6_000L
        scheduler.runNext() // terminal tick -> handoff fails -> driver halts itself
        assertTrue(scheduler.activeNow.isEmpty())
        assertEquals(CrossfadeActivationDecision.UpdateOnly, apply(6_000L, 6_000L))
        assertTrue(scheduler.activeNow.isEmpty())
    }

    @Test fun missingOrPartialGraphNeverStartsAndNeverThrows() {
        assertEquals(CrossfadeActivationDecision.Start, applyCrossfadeConfiguredDurationChange(null, 6_000L, null, null))
        applyCrossfadeConfiguredDurationChange(null, 6_000L, graph.runtime, null)
        applyCrossfadeConfiguredDurationChange(null, 6_000L, null, graph.timingDriver)
        assertTrue(scheduler.all.isEmpty())
        applyCrossfadeConfiguredDurationChange(6_000L, 0L, null, null)
        applyCrossfadeConfiguredDurationChange(6_000L, 0L, null, graph.timingDriver) // stops whatever exists
    }
}
