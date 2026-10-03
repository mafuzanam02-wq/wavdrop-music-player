package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2G3: an explicit next/previous (incl. previous restart-current) synchronously cancels any owned crossfade before navigation is applied; the driver is left alone. */
class CrossfadeManualNavigationRecoveryTest {

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
    private var position = 0L
    private var now = 10_000L

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = 5L, playbackQueue = queue, currentPlaybackIndex = currentIndex,
                repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
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

    private fun navigate() = recoverCrossfadeFromExplicitNavigation(runtime)

    @Test fun nullRuntimeIsHarmless() {
        recoverCrossfadeFromExplicitNavigation(null)
    }

    @Test fun idleStaysIdleWithNoEffects() {
        navigate()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun armedAbandonsSecondaryWithoutPrimaryWrites() {
        toArmed()
        navigate()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun readyAbandonsSecondary() {
        toReady()
        navigate()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun fadingRestoresPrimaryBeforeAbandoningSecondary() {
        toFading()
        events.clear()
        navigate()
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
        navigate()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(0, reconcileCalls)
    }

    @Test fun repeatedNavigationIsIdempotent() {
        toFading()
        navigate()
        val writes = primaryWrites.size
        navigate()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(writes, primaryWrites.size)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    @Test fun driverKeepsRunningAndTheNextPulseNeverResumesTheOldFade() {
        toFading()
        val pending = scheduler.activeNow.single()
        navigate()
        assertTrue(pending.active) // the driver was not stopped
        currentIndex = 2 // navigation moved the live occurrence
        position = 0L
        val gainsBefore = backend.gains.size
        val writesBefore = primaryWrites.size
        now += 1_000L
        scheduler.runNext()
        assertTrue(runtime.state !is CrossfadeState.Fading && runtime.state !is CrossfadeState.HandoffPending)
        assertEquals(gainsBefore, backend.gains.size) // no tick of the cancelled fade
        assertEquals(writesBefore, primaryWrites.size) // no extra attenuation
        assertEquals(0, reconcileCalls) // no handoff
        assertEquals(1, scheduler.activeNow.size)
    }

    @Test fun handoffPendingStaleCallbackNeverHandsOff() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        navigate()
        currentIndex = 2
        position = 0L
        scheduler.runNext()
        assertTrue(runtime.state !is CrossfadeState.HandoffPending)
        assertEquals(0, reconcileCalls)
    }

    @Test fun nextPlansFreshlyFromTheNewCurrentOccurrence() {
        toFading()
        navigate()
        currentIndex = 2 // NEXT moved current 1 -> 2
        position = 0L
        scheduler.runNext()
        val armed = runtime.state as CrossfadeState.Armed
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), armed.key) // never the old (5, 1, 2)
        assertEquals(0, reconcileCalls)
    }

    @Test fun previousMoveToPlansFreshlyFromTheEarlierOccurrence() {
        toFading()
        navigate()
        currentIndex = 0 // PREVIOUS moved current 1 -> 0
        position = 0L
        scheduler.runNext()
        val armed = runtime.state as CrossfadeState.Armed
        assertEquals(CrossfadeTransitionKey(5L, 0, 1), armed.key)
        assertEquals(0, reconcileCalls)
    }

    @Test fun previousRestartCurrentDropsTheOldTimingWindow() {
        toFading()
        navigate()
        position = 0L // same occurrence restarted at 0 ms
        scheduler.runNext()
        val armed = runtime.state as CrossfadeState.Armed
        assertEquals(keyA, armed.key) // freshly planned for the same live occurrence
        backend.ready()
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Ready) // waits for the window from position 0
        assertEquals(0, reconcileCalls)
    }
}
