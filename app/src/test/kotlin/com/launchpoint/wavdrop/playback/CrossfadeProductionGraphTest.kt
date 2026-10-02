package com.launchpoint.wavdrop.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2E1: dormant production composition (graph construction, adapter, providers, teardown order). */
class CrossfadeProductionGraphTest {

    private val events = mutableListOf<String>()

    private class FakeScheduler(private val events: MutableList<String>) : CrossfadeTimingScheduler {
        class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }
        val all = mutableListOf<Pending>()
        val activeNow get() = all.filter { it.active }
        override fun postDelayed(delayMs: Long, block: () -> Unit) { all += Pending(delayMs, block) }
        override fun cancelAll() { events += "driver:cancelAll"; all.forEach { it.active = false } }
    }

    private inner class FakeBackend : SecondaryPlayerBackend {
        var prepares = 0
        var resets = 0
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) { prepares++ }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean = true
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = null
        override fun reset() { resets++ }
        override fun release() { events += "runtime:secondaryRelease" }
    }

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun runtimeSnapshot() = CrossfadeRuntimeSnapshot(
        queueGeneration = 1L, playbackQueue = listOf(song(1), song(2), song(3)), currentPlaybackIndex = 0,
        repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
        playerQueueNeedsSync = false, controllerConnected = true,
    )

    private val scheduler = FakeScheduler(events)
    private val backend = FakeBackend()
    private var snapshotCalls = 0
    private var primaryGainWrites = 0
    private var reconcileCalls = 0
    private var durationReads = 0
    private var rawDuration = 180_000L
    private var rawPosition = 6_125L
    private var clockReads = 0

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = { snapshotCalls++; runtimeSnapshot() },
        backendFactory = { backend },
        primaryGainBackend = PrimaryGainBackend { primaryGainWrites++; events += "primary:restore"; true },
        reconcilePrimary = { _, _ -> reconcileCalls++; CrossfadePrimaryReconciliationResult.Succeeded },
        scheduler = scheduler,
        clock = { clockReads++; 1L },
        configuredDurationMsProvider = { 0L },
        primaryDurationMs = { durationReads++; rawDuration },
        primaryPositionMs = { rawPosition },
    )

    // -- Pure helpers ----------------------------------------------------------------------

    @Test fun primaryDurationUsesPhysicalValuesAndNullsUnknown() {
        assertEquals(180_000L, usableCrossfadePrimaryDuration(180_000L))
        assertEquals(1L, usableCrossfadePrimaryDuration(1L))
        assertNull(usableCrossfadePrimaryDuration(0L))
        assertNull(usableCrossfadePrimaryDuration(-1L))
        assertNull(usableCrossfadePrimaryDuration(C.TIME_UNSET))
    }

    @Test fun reconcilerAdapterDelegatesOnceAndReturnsTheExactResult() {
        val key = CrossfadeTransitionKey(5L, 1, 2)
        val snap = SecondaryHandoffSnapshot(6_125L, 180_000L)
        for (result in listOf(
            CrossfadePrimaryReconciliationResult.Succeeded,
            CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty),
        )) {
            val calls = mutableListOf<Pair<CrossfadeTransitionKey, SecondaryHandoffSnapshot>>()
            val adapter = crossfadePrimaryReconciler { k, s -> calls += k to s; result }
            assertEquals(result, adapter.reconcile(key, snap))
            assertEquals(listOf(key to snap), calls)
        }
    }

    // -- Dormancy ----------------------------------------------------------------------------

    @Test fun constructionStartsNothing() {
        assertTrue(scheduler.all.isEmpty())
        assertEquals(0, snapshotCalls)
        assertEquals(0, backend.prepares)
        assertEquals(0, primaryGainWrites)
        assertEquals(0, reconcileCalls)
        assertEquals(CrossfadeState.Idle, graph.runtime.state)
    }

    @Test fun explicitStartSchedulesOnePulseAndConfiguredOffStaysIdle() {
        graph.timingDriver.start()
        assertEquals(0L, scheduler.activeNow.single().delayMs)
        scheduler.activeNow.single().let { it.active = false; it.block() }
        assertEquals(1, durationReads) // production duration provider reads the primary physical duration
        assertEquals(CrossfadeState.Idle, graph.runtime.state) // 0 ms configured = OFF: never arms
        assertEquals(0, backend.prepares)
        assertEquals(0, primaryGainWrites)
        assertEquals(1, scheduler.activeNow.size)
        assertEquals(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS, scheduler.activeNow.single().delayMs)
    }

    // -- Teardown --------------------------------------------------------------------------------

    @Test fun teardownInvalidatesTheDriverBeforeTheRuntimeAndIsIdempotent() {
        graph.timingDriver.start()
        events.clear()
        closeCrossfadeGraph(graph.timingDriver, graph.runtime)
        assertEquals("driver:cancelAll", events.first())
        closeCrossfadeGraph(graph.timingDriver, graph.runtime) // second call is harmless
        assertEquals(1, events.count { it == "driver:cancelAll" })
        assertTrue(scheduler.activeNow.isEmpty())
        graph.timingDriver.start() // closed driver never restarts
        assertTrue(scheduler.activeNow.isEmpty())
    }

    @Test fun teardownWithNothingConstructedIsHarmless() {
        closeCrossfadeGraph(null, null)
    }
}
