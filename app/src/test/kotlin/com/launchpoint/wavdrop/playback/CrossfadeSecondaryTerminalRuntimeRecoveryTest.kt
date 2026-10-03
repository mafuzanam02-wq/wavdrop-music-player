package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2F3: an unexpected secondary STATE_IDLE/STATE_ENDED, delivered through the real state mapping, ends crossfade ownership via the existing SecondaryError path; the timing driver is left running. */
class CrossfadeSecondaryTerminalRuntimeRecoveryTest {

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

    /** Delivers an unexpected secondary state through the real mapping into the live attempt. */
    private fun terminal(state: Int = Player.STATE_ENDED) =
        dispatchSecondaryPlaybackState(backend.prepared.last(), state, { 0L }, backend.callbacks!!)

    @Test fun idleRuntimeHasNoSecondaryToFail() {
        // No attempt exists, so nothing can be delivered; the runtime is untouched.
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

    @Test fun fadingFailsTheSecondaryThenRestoresThePrimary() {
        toFading()
        events.clear()
        terminal()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("s:reset", "p:1.0"), events)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    @Test fun handoffPendingCleansUpWithoutHandoffOrReconciliation() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        events.clear()
        terminal()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("s:reset", "p:1.0"), events)
        assertEquals(0, reconcileCalls)
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


    @Test fun bufferingAndReadyDoNotDisturbAnActiveFade() {
        toFading()
        terminal(Player.STATE_BUFFERING)
        terminal(Player.STATE_READY) // a stale duplicate ready for the started attempt is ignored by the owner
        assertTrue(runtime.state is CrossfadeState.Fading)
        assertEquals(0, backend.resets)
    }

    @Test fun idleStateFailsTheSameWayAsEnded() {
        toFading()
        terminal(Player.STATE_IDLE)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(1, backend.resets)
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
}
