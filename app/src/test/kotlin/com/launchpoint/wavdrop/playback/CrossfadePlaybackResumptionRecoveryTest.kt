package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2H3G: an explicit service-owned playback resumption adoption (Ready + isForPlayback) synchronously cancels any owned crossfade (QueueMutation) before the queue mutation and generation bump; the driver is left alone. */
class CrossfadePlaybackResumptionRecoveryTest {

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

    private fun resumptionAdopted() = recoverCrossfadeFromPlaybackResumption(runtime)

    @Test fun nullRuntimeIsHarmless() {
        recoverCrossfadeFromPlaybackResumption(null)
    }

    @Test fun idleStaysIdleWithNoEffects() {
        resumptionAdopted()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun armedAbandonsSecondaryWithoutPrimaryWrites() {
        toArmed()
        resumptionAdopted()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun readyAbandonsSecondary() {
        toReady()
        resumptionAdopted()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun fadingRestoresPrimaryBeforeAbandoningSecondary() {
        toFading()
        events.clear()
        resumptionAdopted()
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
        resumptionAdopted()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(0, reconcileCalls)
    }

    @Test fun repeatedResumptionCancelIsIdempotent() {
        toFading()
        resumptionAdopted()
        val writes = primaryWrites.size
        resumptionAdopted()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(writes, primaryWrites.size)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    // The queue the adopted plan installs: generation 5 -> 6, current at [index].
    private fun adopted(index: Int, vararg ids: Long) {
        generation = 6L
        currentIndex = index
        queue = ids.map { song(it) }
    }

    private fun assertFreshKey(key: CrossfadeTransitionKey, targetSongId: Long) {
        position = 0L
        now += 1_000L
        scheduler.runNext()
        val armed = runtime.state as CrossfadeState.Armed
        assertEquals(key, armed.key)
        assertEquals(targetSongId.toString(), backend.preparedMediaIds.last())
    }

    @Test fun helperUsesTheQueueMutationReason() {
        assertEquals(CrossfadeCancelReason.QueueMutation, PLAYBACK_RESUMPTION_CANCEL_REASON)
        toArmed()
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.Cancel(PLAYBACK_RESUMPTION_CANCEL_REASON))
        assertEquals(CrossfadeCancelReason.QueueMutation, reduction.cancelReason)
    }

    @Test fun generationMismatchFallbackStillReportsQueueMutation() {
        adopted(1, 11, 12, 13)
        val snap = CrossfadeRuntimeSnapshot(
            queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = 1,
            repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
            playerQueueNeedsSync = false, controllerConnected = true,
        )
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(snap, keyA))
    }

    // Service policy matrix: only a Ready mapping with isForPlayback == true is adopted, so only it cancels.
    @Test fun onlyAReadyAdoptingResumptionCancels() {
        assertTrue(shouldCancelCrossfadeForPlaybackResumption(resultReady = true, isForPlayback = true))
        assertFalse(shouldCancelCrossfadeForPlaybackResumption(resultReady = true, isForPlayback = false)) // query only
        assertFalse(shouldCancelCrossfadeForPlaybackResumption(resultReady = false, isForPlayback = true)) // Unavailable / failure
        assertFalse(shouldCancelCrossfadeForPlaybackResumption(resultReady = false, isForPlayback = false))
    }

    @Test fun aQueryOnlyResumptionLeavesAnActiveFadeUntouched() {
        toFading()
        // isForPlayback == false: the service neither cancels nor adopts.
        if (shouldCancelCrossfadeForPlaybackResumption(resultReady = true, isForPlayback = false)) resumptionAdopted()
        assertTrue(runtime.state is CrossfadeState.Fading)
        assertEquals(0, backend.resets)
    }

    @Test fun adoptionRebindsOwnershipToTheNewGenerationAndQueue() {
        toFading()
        val pending = scheduler.activeNow.single()
        resumptionAdopted()
        adopted(1, 11, 12, 13) // [X, Y, Z], current Y
        assertTrue(pending.active) // driver not stopped
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFreshKey(CrossfadeTransitionKey(6L, 1, 2), 13L) // never the old (5, 1, 2)
        assertEquals(0, reconcileCalls)
    }

    @Test fun anIdenticalLookingQueueIsStillANewGenerationAndCancels() {
        toFading()
        resumptionAdopted()
        adopted(1, 1, 2, 3, 4)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFreshKey(CrossfadeTransitionKey(6L, 1, 2), 3L)
    }

    @Test fun duplicateSongOccurrencesStayPositional() {
        toFading()
        resumptionAdopted()
        adopted(1, 7, 7, 7)
        assertFreshKey(CrossfadeTransitionKey(6L, 1, 2), 7L)
    }

    @Test fun staleOldKeyTickAndHandoffCallbacksAreRefusedAfterAdoption() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        resumptionAdopted()
        adopted(1, 11, 12, 13)
        val writesBefore = primaryWrites.size
        val gainsBefore = backend.gains.size
        runtime.executeFadeTick(keyA, now + 1_000L)
        assertEquals(writesBefore, primaryWrites.size)
        assertEquals(gainsBefore, backend.gains.size)
        assertTrue(runtime.state !is CrossfadeState.Fading && runtime.state !is CrossfadeState.HandoffPending)
        assertEquals(0, reconcileCalls)
    }
}
