package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2F5: a classified Media3 audio-focus / route interruption synchronously cancels any owned crossfade with Pause; the driver keeps running and nothing is resurrected. */
class CrossfadePrimaryInterruptionRecoveryTest {

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
        val preparedMediaIds = mutableListOf<String>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        val gains = mutableListOf<Float>()
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            preparedMediaIds += item.mediaId
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

    private var queue = listOf(song(1), song(2), song(3), song(4))
    private var generation = 5L
    private var repeat = RepeatMode.OFF
    private var external = false
    private val scheduler = FakeScheduler()
    private val backend = FakeBackend()
    private val primaryWrites = mutableListOf<Float>()
    private var reconcileCalls = 0
    private var currentIndex = 1
    private var position = 0L
    private var now = 10_000L

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = currentIndex,
                repeatMode = repeat, shuffleEnabled = false, isPlaying = true, isExternalPlayback = external,
                playerQueueNeedsSync = false, controllerConnected = true,
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
        position = startA + 1_000L // late begin so the primary is genuinely attenuated
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Fading)
        assertTrue(primaryWrites.last() < 1f)
    }

    private fun terminal() = recoverCrossfadeFromPrimaryInterruption(runtime)

    @Test fun nullRuntimeIsHarmless() {
        recoverCrossfadeFromPrimaryInterruption(null)
    }

    @Test fun idleStaysIdleWithNoEffects() {
        terminal()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun armedAbandonsSecondaryWithoutPrimaryWrites() {
        toArmed()
        terminal()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun readyAbandonsSecondary() {
        toReady()
        terminal()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun fadingRestoresPrimaryBeforeAbandoningSecondary() {
        toFading()
        events.clear()
        terminal()
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
        terminal()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(0, reconcileCalls)
    }

    @Test fun staleHandoffAfterPrimaryInterruptionIsInactiveWithNoReconciliationOrResurrection() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        terminal() // primary-interruption cancellation
        assertEquals(CrossfadeState.Idle, runtime.state)
        val resetsAfterCleanup = backend.resets
        val writesAfterCleanup = primaryWrites.size
        val gainsAfterCleanup = backend.gains.size
        val preparedAfterCleanup = backend.prepared.size

        // The old handoff is actually attempted and must be refused.
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA))

        assertEquals(0, reconcileCalls) // no primary reconciliation
        assertEquals(resetsAfterCleanup, backend.resets) // no extra secondary reset
        assertEquals(writesAfterCleanup, primaryWrites.size) // no primary gain write
        assertEquals(gainsAfterCleanup, backend.gains.size) // no secondary gain write
        assertEquals(preparedAfterCleanup, backend.prepared.size) // no secondary resurrection
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun repeatedTerminalCallbackIsIdempotent() {
        toFading()
        terminal()
        val writes = primaryWrites.size
        terminal()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(writes, primaryWrites.size)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    @Test fun reasonIsPauseAndDistinct() {
        assertEquals(CrossfadeCancelReason.Pause, PRIMARY_INTERRUPTION_CANCEL_REASON)
        assertTrue(PRIMARY_INTERRUPTION_CANCEL_REASON != CrossfadeCancelReason.PrimaryPlaybackTerminated)
        assertTrue(PRIMARY_INTERRUPTION_CANCEL_REASON != CrossfadeCancelReason.PlaybackError)
        toArmed()
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.Cancel(PRIMARY_INTERRUPTION_CANCEL_REASON))
        assertEquals(CrossfadeCancelReason.Pause, reduction.cancelReason)
    }

    @Test fun driverKeepsRunningAndStaleCallbacksAreRefused() {
        toFading()
        val pending = scheduler.activeNow.single()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        terminal()
        assertTrue(pending.active) // driver not stopped
        val writesBefore = primaryWrites.size
        val gainsBefore = backend.gains.size
        runtime.executeFadeTick(keyA, now + 1_000L)
        assertEquals(writesBefore, primaryWrites.size)
        assertEquals(gainsBefore, backend.gains.size)
        assertTrue(runtime.state !is CrossfadeState.Fading && runtime.state !is CrossfadeState.HandoffPending)
        assertEquals(0, reconcileCalls)
    }

    /** Earlier owner cancels first from Fading; a later interruption callback must find Idle and do nothing. */
    private fun assertLaterInterruptionIsNoOp(first: () -> Unit) {
        toFading()
        first()
        assertEquals(CrossfadeState.Idle, runtime.state)
        val resets = backend.resets
        val writes = primaryWrites.size
        val gains = backend.gains.size
        val eventCount = events.size
        terminal()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(resets, backend.resets)
        assertEquals(writes, primaryWrites.size)
        assertEquals(gains, backend.gains.size)
        assertEquals(eventCount, events.size)
        assertEquals(0, reconcileCalls)
    }

    @Test fun playbackErrorThenInterruptionDoesNoExtraCleanup() =
        assertLaterInterruptionIsNoOp { recoverCrossfadeFromPrimaryPlaybackError(runtime) }

    @Test fun explicitPauseThenInterruptionDoesNoExtraCleanup() =
        assertLaterInterruptionIsNoOp { recoverCrossfadeFromExplicitPause(runtime) }

    @Test fun terminalStateThenInterruptionDoesNoExtraCleanup() =
        assertLaterInterruptionIsNoOp { recoverCrossfadeFromPrimaryTerminalState(runtime) }

    @Test fun controllerDisconnectThenInterruptionDoesNoExtraCleanup() =
        assertLaterInterruptionIsNoOp { recoverCrossfadeFromControllerDisconnected(runtime) }

    @Test fun interruptionThenLaterOwnersAreNoOps() {
        toFading()
        terminal()
        val writes = primaryWrites.size
        val resets = backend.resets
        recoverCrossfadeFromPrimaryPlaybackError(runtime)
        recoverCrossfadeFromExplicitPause(runtime)
        recoverCrossfadeFromPrimaryTerminalState(runtime)
        assertEquals(writes, primaryWrites.size)
        assertEquals(resets, backend.resets)
    }

    @Test fun focusRegainDoesNotResurrectTransition() {
        toFading()
        terminal()
        val prepared = backend.prepared.size
        // suppression NONE / playWhenReady=true are unclassified, so nothing calls the helper; runtime stays Idle.
        assertEquals(PrimaryPlaybackInterruption.None, classifyPrimarySuppressionInterruption(Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(prepared, backend.prepared.size)
    }
}
