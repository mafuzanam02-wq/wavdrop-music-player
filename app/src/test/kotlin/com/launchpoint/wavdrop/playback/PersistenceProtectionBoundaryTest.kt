package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-F16B persistence boundary: a SAVE/CLEAR scheduled BEFORE partial/unresolved protection became active must not run after it and
 * replace the original snapshot. Ordering is made deterministic by HOLDING the production persistence gate (no sleeps): operations
 * queue behind the hold in FIFO order, and are released only after the transition under test happened. Isolated temp DataStores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PersistenceProtectionBoundaryTest {

    private val rigs = mutableListOf<AuthorityRig>()
    private val bg = CoroutineScope(Dispatchers.Default + Job())

    @After fun tearDown() {
        bg.coroutineContext[Job]?.cancel()
        rigs.forEach { it.releaseAll() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    private fun rig(name: String, currentIndex: Int = 3): AuthorityRig = AuthorityRig(name).also {
        rigs += it
        it.saveSession(currentIndex)
        it.demandController()
        it.connectApp()
    }

    private fun gateOf(rig: AuthorityRig) = rig.field("sessionPersistenceGate").get(rig.pc) as PlaybackSessionPersistenceGate
    private fun persisted(rig: AuthorityRig) = runBlocking { rig.sessionRepo.load() }
    private fun libraryWithout(rig: AuthorityRig, id: Long) = rig.queue.filter { it.id != id }

    /** Takes the gate and keeps it until the returned deferred is completed. */
    private fun holdGate(rig: AuthorityRig): CompletableDeferred<Unit> {
        val gate = gateOf(rig)
        val release = CompletableDeferred<Unit>()
        val acquired = CompletableDeferred<Unit>()
        bg.launch { gate.mutex.withLock { acquired.complete(Unit); release.await() } }
        runBlocking { acquired.await() }
        return release
    }

    private fun hydrateAsync(rig: AuthorityRig, library: List<com.launchpoint.wavdrop.data.model.Song>): Deferred<PlayerHydrationResult> =
        CoroutineScope(Dispatchers.Main + Job()).async { rig.pc.ensurePlayerHydratedFromSession(library) }

    private fun <T> await(rig: AuthorityRig, job: Deferred<T>): T {
        repeat(200) { if (job.isCompleted) return@repeat else rig.settle(30) }
        assertTrue("job did not complete", job.isCompleted)
        return runBlocking { job.await() }
    }

    // A / F: SAVE scheduled earlier, then a service-owned partial resumption activates protection.
    @Test fun queuedSaveThenServiceOwnedPartialResumptionCannotOverwriteTheSnapshot() {
        val rig = rig("save-then-partial")
        val original = persisted(rig)!!
        val release = holdGate(rig)
        rig.pc.playFromQueue(rig.queue.subList(0, 2), startIndex = 0) // queues SAVE(s) of a 2-song queue behind the hold
        rig.settle(100)
        val plan = (PlaybackResumptionMapper.map(original, ResumeBehaviorSettings(), libraryWithout(rig, 2L)) as PlaybackResumptionResult.Ready).plan
        assertTrue(plan.restoredPartially)
        rig.pc.adoptPlaybackResumption(plan)
        assertTrue(rig.pc.isSessionProtected)
        release.complete(Unit)
        rig.settle(500)
        assertEquals("queued SAVE must not replace the original", original, persisted(rig))
    }

    // B: CLEAR scheduled (empty queue) ahead of the unresolved-session protection, queued behind the hydration.
    @Test fun queuedClearThenUnresolvedProtectionCannotEraseTheSnapshot() {
        val rig = rig("clear-then-unresolved")
        val original = persisted(rig)!!
        val release = holdGate(rig)
        val hydration = hydrateAsync(rig, emptyList())
        rig.settle(100)
        assertFalse("hydration waits for the gate", hydration.isCompleted)
        rig.pc.cycleRepeatMode() // empty queue -> CLEAR scheduled, queued behind the hydration
        release.complete(Unit)
        assertEquals(PlayerHydrationResult.NoResolvableSong, await(rig, hydration))
        rig.settle(500)
        assertTrue(rig.pc.isSessionProtected)
        assertEquals(original, persisted(rig))
    }

    // Service-owned unresolved resumption: protection activation invalidates a CLEAR already waiting.
    @Test fun queuedClearThenServiceOwnedUnresolvedProtectionCannotEraseTheSnapshot() {
        val rig = rig("clear-then-service-unresolved")
        val original = persisted(rig)!!
        val release = holdGate(rig)
        rig.pc.cycleRepeatMode() // CLEAR queued behind the hold
        rig.settle(100)
        rig.pc.protectUnresolvedSession()
        release.complete(Unit)
        rig.settle(500)
        assertEquals(original, persisted(rig))
    }

    // C: a write already EXECUTING when hydration starts is ordered before the snapshot is read.
    @Test fun inFlightWriteFinishesBeforeHydrationReadsTheSnapshot() {
        val rig = rig("in-flight")
        val other = PlaybackSessionSnapshot(
            queueSongIds = rig.queue.take(3).map { it.id },
            currentSongId = rig.queue[1].id,
            currentIndex = 1,
            positionMs = 0L,
            repeatMode = RepeatMode.OFF,
            shuffleEnabled = false,
            updatedAtMs = 2L,
        )
        val gate = gateOf(rig)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        bg.launch {
            gate.runIfLatest(gate.nextRevision()) { started.complete(Unit); finish.await(); rig.sessionRepo.save(other) }
        }
        runBlocking { started.await() }
        val hydration = hydrateAsync(rig, rig.queue)
        rig.settle(100)
        assertFalse("hydration must not read while a write is executing", hydration.isCompleted)
        finish.complete(Unit)
        assertEquals(PlayerHydrationResult.Hydrated, await(rig, hydration))
        rig.settle(300)
        assertEquals("the read observed the completed write", listOf("1", "2", "3"), rig.physicalIds)
    }

    // E / notification: an explicit whole-queue replacement releases protection, persists the new queue, and drops the stale notice.
    @Test fun explicitReplacementAfterPartialRestoreDropsTheStaleNotice() {
        val rig = rig("replace-notice")
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(libraryWithout(rig, 2L)) } // no collector -> notice held
        rig.settle(300)
        assertTrue(rig.pc.isSessionProtected)
        rig.pc.playFromQueue(rig.queue.subList(0, 3), startIndex = 1)
        rig.settle(500)
        assertFalse(rig.pc.isSessionProtected)
        assertFalse("notice described the replaced queue", rig.pc.takePartialRestoreNotice())
        assertEquals(listOf(1L, 2L, 3L), persisted(rig)!!.queueSongIds)
    }

    // E (negative): a rejected replacement keeps both the protection and the notice, and the snapshot.
    @Test fun rejectedReplacementKeepsProtectionNoticeAndSnapshot() {
        val rig = rig("rejected-notice")
        val original = persisted(rig)!!
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(libraryWithout(rig, 2L)) }
        rig.settle(300)
        rig.pc.playFromQueue(rig.queue, startIndex = 99)
        rig.settle(500)
        assertTrue(rig.pc.isSessionProtected)
        assertEquals(original, persisted(rig))
        assertTrue(rig.pc.takePartialRestoreNotice())
    }

    // The ordinary path is unchanged: a complete restore stays unprotected and later saves still land.
    @Test fun completeRestoreStillPersistsAfterTheGateChange() {
        val rig = rig("complete")
        assertEquals(PlayerHydrationResult.Hydrated, runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue) })
        rig.settle(300)
        assertFalse(rig.pc.isSessionProtected)
        rig.pc.cycleRepeatMode()
        rig.settle(400)
        assertNotNull(persisted(rig))
        assertEquals(RepeatMode.ALL, persisted(rig)!!.repeatMode)
    }

    /** Waits until every persistence operation scheduled so far has finished (deterministic: the gate is FIFO). */
    private fun drainPersistence(rig: AuthorityRig) {
        rig.settle(100) // let launched jobs reach the gate
        runBlocking { gateOf(rig).runExclusive { } }
    }

    // Pre-hydration: an ordinary event with an empty in-memory queue is NOT proof the user cleared the saved queue.
    @Test fun clearBeforeHydrationStartsCannotEraseTheSavedSession() {
        val rig = rig("clear-before-hydration")
        val original = persisted(rig)!!
        rig.pc.cycleRepeatMode()
        rig.pc.toggleShuffle()
        rig.pc.seekTo(5_000L)
        drainPersistence(rig)
        assertEquals("ordinary pre-hydration events must not erase the session", original, persisted(rig))
        assertEquals(PlayerHydrationResult.Hydrated, runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue) })
        rig.settle(300)
        assertEquals(listOf("1", "2", "3", "4", "5"), rig.physicalIds)
    }

    // No resolvable songs, then a restart with the library back: the session survived both stages.
    @Test fun unresolvedThenRestartRestoresTheFullQueue() {
        val rig = rig("unresolved-restart")
        rig.pc.cycleRepeatMode() // pre-hydration event
        drainPersistence(rig)
        assertEquals(PlayerHydrationResult.NoResolvableSong, runBlocking { rig.pc.ensurePlayerHydratedFromSession(emptyList()) })
        rig.pc.toggleShuffle() // post-hydration event (protected)
        drainPersistence(rig)
        val survived = persisted(rig)!!
        val rig2 = AuthorityRig("unresolved-restart-2").also { rigs += it }
        runBlocking { rig2.sessionRepo.save(survived) }
        rig2.demandController()
        rig2.connectApp()
        assertEquals(PlayerHydrationResult.Hydrated, runBlocking { rig2.pc.ensurePlayerHydratedFromSession(rig2.queue) })
        rig2.settle(300)
        assertEquals(listOf("1", "2", "3", "4", "5"), rig2.physicalIds)
    }

    // Intentional clearing is preserved. No public operation empties an owned queue today, so the owned-then-empty state is forced
    // directly: once this process has held a queue, an empty queue still CLEARs the saved session (no permanent retention).
    @Test fun emptyingAnOwnedQueueStillClearsTheSession() {
        val rig = rig("explicit-clear")
        rig.pc.playFromQueue(rig.queue.subList(0, 1), startIndex = 0)
        rig.settle(300)
        assertEquals(listOf(1L), persisted(rig)!!.queueSongIds)
        rig.field("libraryQueue").set(rig.pc, emptyList<com.launchpoint.wavdrop.data.model.Song>())
        rig.pc.cycleRepeatMode()
        drainPersistence(rig)
        assertEquals(null, persisted(rig))
    }

    // Complete restoration followed by ordinary persistence is unchanged.
    @Test fun completeRestoreThenOrdinaryEventStillSaves() {
        val rig = rig("restored-then-saves")
        assertEquals(PlayerHydrationResult.Hydrated, runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue) })
        rig.settle(300)
        rig.pc.cycleRepeatMode()
        drainPersistence(rig)
        assertEquals(RepeatMode.ALL, persisted(rig)!!.repeatMode)
        assertEquals(5, persisted(rig)!!.queueSongIds.size)
    }

    // The user's "remember last track = off" opt-out is an intentional clear: the saved session is still deleted, not retained forever.
    @Test fun rememberLastTrackOffStillClearsTheSavedSessionOnAnOrdinaryEvent() {
        val rig = rig("opt-out")
        runBlocking { rig.resumeRepo.setRememberLastTrack(false) }
        assertEquals(PlayerHydrationResult.FilteredBySettings, runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue) })
        rig.pc.cycleRepeatMode()
        drainPersistence(rig)
        assertEquals(null, persisted(rig))
    }
}
