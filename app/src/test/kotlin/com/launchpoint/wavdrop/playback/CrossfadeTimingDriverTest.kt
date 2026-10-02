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
        override fun setGain(gain: Float): Boolean { gains += gain; return true }
        override fun reset() { resets++ }
        override fun release() {}
        fun ready() = callbacks!!.onReady(prepared.last(), 180_000L)
    }

    private class FakePrimary : PrimaryGainBackend {
        val gains = mutableListOf<Float>()
        override fun setGain(gain: Float): Boolean { gains += gain; return true }
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
    private val runtime = CrossfadePreparationRuntime({ snap }, { backend }, primaryGainBackend = primary)
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

    @Test fun terminalTickStopsSchedulingAndLeavesHandoffPending() {
        toFading()
        now += 6_000L
        scheduler.runNext()
        assertEquals(CrossfadeState.HandoffPending(keyA, 6_000L), runtime.state)
        assertTrue(scheduler.activeNow.isEmpty())
        assertEquals(0, backend.resets) // no handoff, no teardown
    }

    @Test fun staleCallbackAfterTerminalIsHarmless() {
        toFading()
        val beforeTerminal = scheduler.activeNow.single()
        now += 6_000L
        scheduler.runNext()
        val gains = backend.gains.size
        val primaryWrites = primary.gains.size
        val configs = configReads
        val clocks = clockReads
        val positions = positionReads
        scheduler.runStale(beforeTerminal) // delivered again after HandoffPending
        assertEquals(gains, backend.gains.size)
        assertEquals(primaryWrites, primary.gains.size)
        assertEquals(configs, configReads) // HandoffPending is not polled
        assertEquals(clocks, clockReads)
        assertEquals(positions, positionReads)
        assertTrue(scheduler.activeNow.isEmpty())
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
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
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
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
}
