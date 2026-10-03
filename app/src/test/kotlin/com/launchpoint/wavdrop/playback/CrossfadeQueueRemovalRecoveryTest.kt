package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2H3D: an explicit queue removal or bulk-clear command synchronously cancels any owned crossfade (QueueMutation) before the queue mutation and generation bump; the driver is left alone. */
class CrossfadeQueueRemovalRecoveryTest {

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
                repeatMode = repeat, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
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

    private fun removalIntent() = recoverCrossfadeFromQueueRemoval(runtime)

    @Test fun nullRuntimeIsHarmless() {
        recoverCrossfadeFromQueueRemoval(null)
    }

    @Test fun idleStaysIdleWithNoEffects() {
        removalIntent()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun armedAbandonsSecondaryWithoutPrimaryWrites() {
        toArmed()
        removalIntent()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun readyAbandonsSecondary() {
        toReady()
        removalIntent()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun fadingRestoresPrimaryBeforeAbandoningSecondary() {
        toFading()
        events.clear()
        removalIntent()
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
        removalIntent()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(0, reconcileCalls)
    }

    @Test fun repeatedRemovalIsIdempotent() {
        toFading()
        removalIntent()
        val writes = primaryWrites.size
        removalIntent()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(writes, primaryWrites.size)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    // Successful queue-only edit results: generation 5 -> 6.
    private fun edited(index: Int, vararg ids: Long) {
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
        assertEquals(CrossfadeCancelReason.QueueMutation, QUEUE_REMOVAL_CANCEL_REASON)
        toArmed()
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.Cancel(QUEUE_REMOVAL_CANCEL_REASON))
        assertEquals(CrossfadeCancelReason.QueueMutation, reduction.cancelReason)
    }

    @Test fun generationMismatchFallbackStillReportsQueueMutation() {
        edited(1, 1, 2, 4)
        val snap = CrossfadeRuntimeSnapshot(
            queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = 1,
            repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
            playerQueueNeedsSync = false, controllerConnected = true,
        )
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(snap, keyA))
    }

    @Test fun removingTheImmediateNextPlansFreshlyTowardTheNextSurvivor() {
        toFading()
        val pending = scheduler.activeNow.single()
        removalIntent()
        edited(1, 1, 2, 4) // C removed
        assertTrue(pending.active) // driver not stopped
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFreshKey(CrossfadeTransitionKey(6L, 1, 2), 4L)
        assertEquals(0, reconcileCalls)
    }

    @Test fun removingALaterItemStillCancelsAndUsesTheNewGeneration() {
        queue = listOf(song(1), song(2), song(3), song(4), song(5))
        toFading()
        removalIntent()
        edited(1, 1, 2, 3, 4) // E removed; immediate next unchanged
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFreshKey(CrossfadeTransitionKey(6L, 1, 2), 3L)
    }

    @Test fun removingAnEarlierItemRepairsTheCurrentIndexInTheFreshKey() {
        currentIndex = 2
        toArmed()
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (runtime.state as CrossfadeState.Armed).key)
        removalIntent()
        edited(1, 2, 3, 4) // A removed; current C moves from index 2 to 1
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFreshKey(CrossfadeTransitionKey(6L, 1, 2), 4L) // never the old (5, 2, 3)
    }

    @Test fun attemptingToRemoveTheCurrentItemCancelsButLeavesTheQueueUnchanged() {
        toFading()
        removalIntent() // validation then rejects: queue and generation untouched
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(5L, generation)
        assertFreshKey(keyA, 3L)
    }

    @Test fun clearEarlierRebindsTheKeyToTheNewIndexesAndGeneration() {
        currentIndex = 2
        toArmed()
        removalIntent()
        edited(0, 3, 4) // [A, B, C, D] -> [C, D]
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFreshKey(CrossfadeTransitionKey(6L, 0, 1), 4L) // never (5, 2, 3)
    }

    @Test fun clearUpNextUnderRepeatOffStaysIdleOnLaterPulses() {
        toFading()
        removalIntent()
        edited(1, 1, 2) // [A, B]
        position = 0L
        now += 1_000L
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, reconcileCalls)
    }

    @Test fun clearUpNextUnderRepeatAllFollowsTheExistingWrapTopology() {
        repeat = RepeatMode.ALL
        toFading()
        removalIntent()
        edited(1, 1, 2)
        assertFreshKey(CrossfadeTransitionKey(6L, 1, 0), 1L) // existing wrap rule, nothing special for clear
    }

    @Test fun duplicateSongOccurrencesStayPositional() {
        queue = listOf(song(1), song(7), song(3), song(7))
        toFading()
        removalIntent()
        edited(1, 1, 7, 3) // the later duplicate removed
        assertFreshKey(CrossfadeTransitionKey(6L, 1, 2), 3L)
    }

    @Test fun staleOldKeyCallbacksNeverTickOrHandOffAfterTheRemoval() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        removalIntent()
        edited(1, 1, 2, 4)
        val writesBefore = primaryWrites.size
        val gainsBefore = backend.gains.size
        runtime.executeFadeTick(keyA, now + 1_000L)
        assertEquals(writesBefore, primaryWrites.size)
        assertEquals(gainsBefore, backend.gains.size)
        assertTrue(runtime.state !is CrossfadeState.Fading && runtime.state !is CrossfadeState.HandoffPending)
        assertEquals(0, reconcileCalls)
    }
}
