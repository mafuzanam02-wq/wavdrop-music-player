package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2H1: an explicit repeat-mode change synchronously cancels any owned crossfade before the new mode is applied; the driver is left alone. */
class CrossfadeRepeatChangeRecoveryTest {

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
        val gains = mutableListOf<Float>()
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean { gains += gain; return true }
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
    private val scheduler = FakeScheduler()
    private val backend = FakeBackend()
    private val primaryWrites = mutableListOf<Float>()
    private var reconcileCalls = 0
    private var currentIndex = 1
    private var repeat = RepeatMode.ALL
    private var position = 0L
    private var now = 10_000L

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = 5L, playbackQueue = queue, currentPlaybackIndex = currentIndex,
                repeatMode = repeat, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
                playerQueueNeedsSync = false, controllerConnected = true,
            )
        },
        backendFactory = { backend },
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
        position = startA + 1_000L // late begin so the primary is genuinely attenuated
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Fading)
        assertTrue(primaryWrites.last() < 1f)
    }

    private fun repeatChanged() = recoverCrossfadeFromRepeatChange(runtime)

    @Test fun nullRuntimeIsHarmless() {
        recoverCrossfadeFromRepeatChange(null)
    }

    @Test fun idleStaysIdleWithNoEffects() {
        repeatChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun armedAbandonsSecondaryWithoutPrimaryWrites() {
        toArmed()
        repeatChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun readyAbandonsSecondary() {
        toReady()
        repeatChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun fadingRestoresPrimaryBeforeAbandoningSecondary() {
        toFading()
        events.clear()
        repeatChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    @Test fun handoffPendingCleansUpWithoutHandoff() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        events.clear()
        repeatChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(0, reconcileCalls)
    }

    @Test fun repeatedRepeatChangeIsIdempotent() {
        toFading()
        repeatChanged()
        val writes = primaryWrites.size
        repeatChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(writes, primaryWrites.size)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    @Test fun helperUsesTheRepeatChangedReason() {
        assertEquals(CrossfadeCancelReason.RepeatChanged, REPEAT_CHANGE_CANCEL_REASON)
        // The reducer reports the reason it was given, so the named seam is what the runtime cleanup runs with.
        toArmed()
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.Cancel(REPEAT_CHANGE_CANCEL_REASON))
        assertEquals(CrossfadeCancelReason.RepeatChanged, reduction.cancelReason)
    }

    // Repeat semantic ownership loss (runtime defensive fallback stays) maps to RepeatChanged.
    @Test fun ownershipLossFallbackReportsRepeatChanged() {
        fun snap(repeat: RepeatMode, index: Int = 1) = CrossfadeRuntimeSnapshot(
            queueGeneration = 5L, playbackQueue = queue, currentPlaybackIndex = index,
            repeatMode = repeat, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
            playerQueueNeedsSync = false, controllerConnected = true,
        )
        assertEquals(CrossfadeCancelReason.RepeatChanged, crossfadeOwnershipLossReason(snap(RepeatMode.ONE), keyA))
        // last -> first only exists under Repeat ALL
        assertEquals(
            CrossfadeCancelReason.RepeatChanged,
            crossfadeOwnershipLossReason(snap(RepeatMode.OFF, index = 3), CrossfadeTransitionKey(5L, 3, 0)),
        )
        // a harmless mode change keeps ownership of an unchanged target
        assertEquals(null, crossfadeOwnershipLossReason(snap(RepeatMode.OFF), keyA))
    }

    @Test fun offToAllStillCancelsEvenWhenTheTargetIsUnchanged() {
        repeat = RepeatMode.OFF
        toArmed()
        repeatChanged() // user intent, not target comparison
        repeat = RepeatMode.ALL
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun allToOneCancelsThenStaysIdleOnLaterPulse() {
        toFading()
        val pending = scheduler.activeNow.single()
        repeatChanged()
        repeat = RepeatMode.ONE
        assertTrue(pending.active) // driver not stopped
        assertEquals(CrossfadeState.Idle, runtime.state)
        val gainsBefore = backend.gains.size
        now += 1_000L
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state) // Repeat ONE is crossfade-ineligible
        assertEquals(gainsBefore, backend.gains.size)
        assertEquals(0, reconcileCalls)
        assertEquals(1, scheduler.activeNow.size)
    }

    @Test fun oneToOffPlansFreshlyOnALaterPulse() {
        repeat = RepeatMode.ONE
        graph.timingDriver.start()
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        repeatChanged() // harmless while Idle
        repeat = RepeatMode.OFF
        assertEquals(CrossfadeState.Idle, runtime.state) // no forced arming
        scheduler.runNext()
        assertEquals(keyA, (runtime.state as CrossfadeState.Armed).key)
    }

    @Test fun lastTrackAllToOffDropsTheWrapKeyAndLaterPulseHasNoNext() {
        currentIndex = 3
        repeat = RepeatMode.ALL
        toArmed()
        assertEquals(CrossfadeTransitionKey(5L, 3, 0), (runtime.state as CrossfadeState.Armed).key)
        repeatChanged()
        repeat = RepeatMode.OFF
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state) // NoNextOccurrence under OFF
    }

    @Test fun staleHandoffCallbackNeverHandsOffAfterRepeatChange() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        repeatChanged()
        repeat = RepeatMode.ONE
        scheduler.runNext()
        assertTrue(runtime.state !is CrossfadeState.HandoffPending)
        assertEquals(0, reconcileCalls)
    }
}
