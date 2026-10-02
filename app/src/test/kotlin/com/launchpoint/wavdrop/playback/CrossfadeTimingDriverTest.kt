package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2C7D: deterministic timing-driver tests. No real Handler, sleep or clock. */
class CrossfadeTimingDriverTest {

    private class FakeScheduler : CrossfadeTimingScheduler {
        class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }

        val all = mutableListOf<Pending>()
        var maxActive = 0
        val activeNow get() = all.filter { it.active }

        override fun postDelayed(delayMs: Long, block: () -> Unit) {
            all += Pending(delayMs, block)
            maxActive = maxOf(maxActive, activeNow.size)
        }

        override fun cancelAll() { all.forEach { it.active = false } }

        val pendingDelay: Long? get() = activeNow.singleOrNull()?.delayMs

        /** Runs the single active callback (consuming it), like a looper turn. */
        fun runNext() {
            val p = activeNow.single()
            p.active = false
            p.block()
        }

        /** Runs a callback reference even if it was cancelled or consumed (stale delivery). */
        fun runStale(p: Pending) = p.block()
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
        var handoffSnap: SecondaryHandoffSnapshot? = SecondaryHandoffSnapshot(6_125L, 180_000L)
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = handoffSnap
        override fun setGain(gain: Float): Boolean { gains += gain; return true }
        override fun reset() { resets++ }
        override fun release() {}
        fun ready() = callbacks!!.onReady(prepared.last(), 180_000L)
    }

    private class FakePrimary : PrimaryGainBackend {
        var failWhen: (Float) -> Boolean = { false }
        val gains = mutableListOf<Float>()
        override fun setGain(gain: Float): Boolean { gains += gain; return !failWhen(gain) }
    }

    private inner class FakeReconciler : CrossfadePrimaryReconciler {
        val calls = mutableListOf<Pair<CrossfadeTransitionKey, SecondaryHandoffSnapshot>>()
        var result: CrossfadePrimaryReconciliationResult = CrossfadePrimaryReconciliationResult.Succeeded
        var onReconcile: () -> Unit = {}
        override fun reconcile(key: CrossfadeTransitionKey, snapshot: SecondaryHandoffSnapshot): CrossfadePrimaryReconciliationResult {
            calls += key to snapshot
            onReconcile()
            return result
        }
    }

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private fun snapshot(
        queue: List<Song> = listOf(song(1, 0), song(2, 1), song(3, 2), song(4, 3)),
        index: Int? = 1,
        generation: Long = 5L,
    ) = CrossfadeRuntimeSnapshot(
        queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = index,
        repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
        playerQueueNeedsSync = false, controllerConnected = true,
    )

    private var snap = snapshot()
    private val backend = FakeBackend()
    private val primary = FakePrimary()
    private val reconciler = FakeReconciler()
    private var throwSnapshot = false
    private val runtime = CrossfadePreparationRuntime(
        { if (throwSnapshot) throw IllegalStateException("snapshot") else snap },
        { backend },
        primaryGainBackend = primary,
        primaryReconciler = reconciler,
    )
    private val scheduler = FakeScheduler()

    private var now = 10_000L
    private var clockReads = 0
    private var configured = 6_000L
    private var positionReads = 0
    private var position = 0L
    private var configReads = 0
    private var throwOnConfig = false
    private var throwOnPosition = false

    private val driver = CrossfadeTimingDriver(
        runtime = runtime,
        scheduler = scheduler,
        clock = { clockReads++; now },
        configuredDurationMsProvider = {
            configReads++
            if (throwOnConfig) { throwOnConfig = false; throw IllegalStateException("config") }
            configured
        },
        currentDurationMsProvider = { null },
        currentPositionMsProvider = {
            positionReads++
            if (throwOnPosition) { throwOnPosition = false; throw IllegalStateException("position") }
            position
        },
    )

    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val startA = 194_000L
    private val pre = CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS
    private val fade = CrossfadeTimingDriver.FADE_TICK_INTERVAL_MS

    /** Drives start -> Armed -> Ready (secondary ready) with the position before the window. */
    private fun toReady() {
        driver.start()
        scheduler.runNext() // evaluate -> Armed
        assertTrue(runtime.state is CrossfadeState.Armed)
        backend.ready()
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    /** Ready + position at the window start -> next pulse begins the fade. */
    private fun toFading(lateness: Long = 0L) {
        toReady()
        position = startA + lateness
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    // -- Lifecycle ------------------------------------------------------------------

    @Test fun startIsIdempotentAndSchedulesOnePulse() {
        driver.start()
        driver.start()
        assertEquals(1, scheduler.activeNow.size)
        assertEquals(0L, scheduler.pendingDelay)
    }

    @Test fun stopInvalidatesPendingCallbackAndStaleRunIsNoOp() {
        driver.start()
        val stale = scheduler.all.single()
        driver.stop()
        assertTrue(scheduler.activeNow.isEmpty())
        scheduler.runStale(stale)
        assertEquals(0, configReads)
        assertTrue(scheduler.activeNow.isEmpty())
        assertEquals(CrossfadeState.Idle, runtime.state)
        driver.stop() // idempotent
    }

    @Test fun restartInvalidatesOldGeneration() {
        driver.start()
        val staleA = scheduler.all.single()
        driver.stop()
        driver.start()
        val liveB = scheduler.activeNow.single()
        scheduler.runStale(staleA)
        assertEquals(0, configReads)
        assertTrue(liveB.active)
        assertEquals(1, scheduler.activeNow.size)
        scheduler.runNext()
        assertEquals(1, configReads)
    }

    @Test fun closeIsTerminalAndIdempotent() {
        driver.start()
        driver.close()
        driver.close()
        driver.start()
        assertTrue(scheduler.activeNow.isEmpty())
        assertEquals(1, scheduler.all.size)
    }

    @Test fun stopAndCloseDoNotTouchTheRuntime() {
        toFading()
        val state = runtime.state
        driver.stop()
        driver.close()
        assertEquals(state, runtime.state)
        assertEquals(0, backend.resets)
    }

    // -- Pre-fade ---------------------------------------------------------------------

    @Test fun idlePollsAtPreFadeCadenceWithoutClockReads() {
        configured = 0L // nothing eligible
        driver.start()
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(pre, scheduler.pendingDelay)
        assertEquals(0, clockReads)
        scheduler.runNext()
        assertEquals(pre, scheduler.pendingDelay)
    }

    @Test fun armedDoesNotObserveBeginOrTick() {
        driver.start()
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Armed)
        assertEquals(pre, scheduler.pendingDelay)
        scheduler.runNext() // still Armed
        assertEquals(0, positionReads)
        assertEquals(0, clockReads)
        assertTrue(runtime.state is CrossfadeState.Armed)
        assertEquals(pre, scheduler.pendingDelay)
    }

    @Test fun readyWaitingKeepsPreFadeCadence() {
        toReady()
        position = startA - 1
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Ready)
        assertEquals(1, positionReads)
        assertEquals(0, clockReads)
        assertEquals(pre, scheduler.pendingDelay)
    }

    @Test fun readyDueBeginsFadeWithMonotonicNowAndSwitchesCadence() {
        toReady()
        position = startA
        now = 77_000L
        scheduler.runNext()
        val fading = runtime.state as CrossfadeState.Fading
        assertEquals(77_000L, fading.beganAtElapsedRealtimeMs)
        assertEquals(0L, fading.initialElapsedMs)
        assertEquals(fade, scheduler.pendingDelay)
    }

    @Test fun dueLatenessIsPreserved() {
        toFading(lateness = 1_000L)
        assertEquals(1_000L, (runtime.state as CrossfadeState.Fading).initialElapsedMs)
        // Begin gain came from the coordinator at 1000/6000 progress.
        assertEquals(CrossfadeGainCurve.equalPower(1_000f / 6_000f).outgoing, primary.gains.last(), 0f)
    }

    // -- Fading -----------------------------------------------------------------------

    @Test fun fadingPulseEvaluatesThenTicks() {
        toFading()
        val reads = configReads
        now += 3_000L
        scheduler.runNext()
        assertEquals(reads + 1, configReads) // evaluation ran before the tick
        assertTrue(runtime.state is CrossfadeState.Fading)
        val expected = CrossfadeGainCurve.equalPower(0.5f)
        assertEquals(listOf(expected.incoming), backend.gains)
        assertEquals(expected.outgoing, primary.gains.last(), 0f)
        assertEquals(fade, scheduler.pendingDelay)
    }

    @Test fun multiplePulsesProgressWithOneCallbackAtATime() {
        toFading()
        var last = 0f
        repeat(5) {
            now += 1_000L
            scheduler.runNext()
            assertEquals(1, scheduler.activeNow.size)
            assertEquals(fade, scheduler.pendingDelay)
            assertTrue(backend.gains.last() > last)
            last = backend.gains.last()
        }
        assertEquals(5, backend.gains.size)
        assertEquals(1, scheduler.maxActive)
    }


    @Test fun offDuringFadingCancelsWithoutOldTickAndResumesPreFadeCadence() {
        toFading()
        configured = 0L
        now += 1_000L
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.gains.isEmpty()) // no tick from the old fade
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(1, backend.resets)
        assertEquals(pre, scheduler.pendingDelay)
    }

    @Test fun ownershipLossDuringFadingCancelsWithoutOldTick() {
        toFading()
        snap = snapshot(generation = 6L)
        now += 1_000L
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.gains.isEmpty())
        assertEquals(1, backend.resets)
        assertEquals(pre, scheduler.pendingDelay)
    }

    // -- Robustness ---------------------------------------------------------------------

    @Test fun configProviderExceptionIsContainedAndLoopContinues() {
        driver.start()
        throwOnConfig = true
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(pre, scheduler.pendingDelay)
        scheduler.runNext() // next pulse succeeds and arms
        assertTrue(runtime.state is CrossfadeState.Armed)
    }

    @Test fun positionProviderExceptionIsContainedWithoutChangingRuntime() {
        toReady()
        throwOnPosition = true
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Ready)
        assertEquals(pre, scheduler.pendingDelay)
        position = startA - 1
        scheduler.runNext()
        assertEquals(pre, scheduler.pendingDelay)
        assertEquals(1, scheduler.maxActive)
    }

    @Test fun neverMoreThanOneCallbackAcrossAFullLifecycle() {
        toFading()
        driver.stop()
        driver.start()
        scheduler.runNext()
        now += 6_000L
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state) // handoff completed in the terminal pulse
        assertEquals(pre, scheduler.pendingDelay)
        assertEquals(1, scheduler.maxActive)
    }

    @Test fun duplicateSongQueueUsesOnlyPositionalKeys() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3))
        snap = snapshot(queue = dup, index = 2)
        driver.start()
        scheduler.runNext()
        backend.ready()
        position = startA
        scheduler.runNext()
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (runtime.state as CrossfadeState.Fading).key)
        snap = snapshot(queue = dup, index = 3) // same song id, different occurrence
        now += 1_000L
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.gains.isEmpty())
        assertFalse(scheduler.activeNow.isEmpty())
    }

    // -- CF-2D4: handoff execution and continuation ------------------------------------------

    /** Runs the terminal tick pulse (fade complete). */
    private fun terminalPulse() {
        now += 6_000L
        scheduler.runNext()
    }

    /** Leaves the runtime in HandoffPending (terminal tick executed directly) and the driver restarted. */
    private fun pendingThenRestart() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        driver.stop()
        driver.start()
        assertEquals(0L, scheduler.pendingDelay)
    }

    @Test fun terminalTickRunsHandoffInTheSamePulse() {
        toFading()
        val callbacksBefore = scheduler.all.size
        terminalPulse()
        assertEquals(1, reconciler.calls.size)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(pre, scheduler.pendingDelay)
        assertEquals(callbacksBefore + 1, scheduler.all.size) // no intermediate callback just for handoff
    }

    @Test fun handoffForwardsTheExactKeyAndSnapshot() {
        toFading()
        terminalPulse()
        assertEquals(listOf(keyA to SecondaryHandoffSnapshot(6_125L, 180_000L)), reconciler.calls)
    }

    @Test fun successContinuesWithFreshEvaluationForTheNewOccurrence() {
        toFading()
        terminalPulse()
        val configs = configReads
        snap = snapshot(index = 2) // primary now on the incoming occurrence
        scheduler.runNext()
        assertEquals(configs + 1, configReads)
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (runtime.state as CrossfadeState.Armed).key)
        assertEquals(pre, scheduler.pendingDelay)
    }

    @Test fun alreadyPendingEntryHandsOffBeforeAnyProviderRead() {
        pendingThenRestart()
        val configs = configReads
        val clocks = clockReads
        val positions = positionReads
        scheduler.runNext()
        assertEquals(1, reconciler.calls.size)
        assertEquals(configs, configReads)
        assertEquals(clocks, clockReads)
        assertEquals(positions, positionReads)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(pre, scheduler.pendingDelay)
    }

    private fun assertHaltedAndRestartable() {
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(scheduler.activeNow.isEmpty())
        driver.start()
        assertEquals(0L, scheduler.pendingDelay)
        assertEquals(1, scheduler.activeNow.size)
    }

    @Test fun primaryRejectionHaltsTheRunAndIsRestartable() {
        toFading()
        reconciler.result = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty)
        terminalPulse()
        assertEquals(1, reconciler.calls.size)
        assertEquals(1, backend.resets)
        assertHaltedAndRestartable()
    }

    @Test fun unavailableSecondarySnapshotHaltsTheRun() {
        toFading()
        backend.handoffSnap = null
        terminalPulse()
        assertTrue(reconciler.calls.isEmpty())
        assertEquals(1, backend.resets)
        assertHaltedAndRestartable()
    }

    @Test fun primaryRestoreFailureHaltsEvenIfCleanupRetrySucceeds() {
        toFading()
        var failed = false
        primary.failWhen = { g -> if (g == 1f && !failed) { failed = true; true } else false }
        terminalPulse()
        assertTrue(failed)
        assertEquals(1f, primary.gains.last(), 0f)
        assertHaltedAndRestartable()
    }

    @Test fun ownershipLossBeforeHandoffIsCancelledNotAFailureAndResumesPolling() {
        pendingThenRestart()
        snap = snapshot(generation = 6L)
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(reconciler.calls.isEmpty())
        assertEquals(pre, scheduler.pendingDelay)
    }

    @Test fun manualNavigationBeforeHandoffResumesPolling() {
        pendingThenRestart()
        snap = snapshot(index = 2)
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(reconciler.calls.isEmpty())
        assertEquals(pre, scheduler.pendingDelay)
    }

    @Test fun reentrantInactiveHandoffIsNotRetriedAndFollowsRuntimeState() {
        toFading()
        reconciler.onReconcile = { runtime.cancel(CrossfadeCancelReason.Pause) }
        terminalPulse()
        assertEquals(1, reconciler.calls.size) // one attempt only
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(pre, scheduler.pendingDelay)
    }

    @Test fun unexpectedHandoffExceptionIsContainedAndLeavesNoCallback() {
        pendingThenRestart()
        throwSnapshot = true // executeHandoff throws before any effect; state stays HandoffPending
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertTrue(scheduler.activeNow.isEmpty())
        assertTrue(reconciler.calls.isEmpty())
    }

    @Test fun staleCallbacksAfterRestartCannotHandOffAgain() {
        toFading()
        val stale = scheduler.activeNow.single()
        driver.stop()
        driver.start()
        terminalPulseOnRestart()
        scheduler.runStale(stale)
        assertEquals(1, reconciler.calls.size)
        assertEquals(1, backend.resets)
        assertEquals(1, scheduler.activeNow.size)
        assertEquals(1, scheduler.maxActive)
    }

    private fun terminalPulseOnRestart() {
        now += 6_000L
        scheduler.runNext() // evaluate, tick (terminal) and hand off
    }

    @Test fun duplicateSongOccurrenceHandsOffWithTheExactPositionalKey() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3))
        snap = snapshot(queue = dup, index = 2)
        driver.start()
        scheduler.runNext()
        backend.ready()
        position = startA
        scheduler.runNext()
        terminalPulse()
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), reconciler.calls.single().first)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }
}
