package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * P5-F16B: a PARTIAL restore through the production PlayerController must protect the original saved session from every ordinary
 * event, may be released only by an explicit new queue, and must restore paused. Isolated temp DataStores only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PartialRestoreProtectionTest {

    private val rigs = mutableListOf<AuthorityRig>()

    @After fun tearDown() {
        rigs.forEach { it.releaseAll() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    private fun partialRig(
        currentIndex: Int = 3,
        positionMs: Long = 12_000L,
        repeat: RepeatMode = RepeatMode.ALL,
        shuffle: Boolean = false,
        order: List<Int>? = null,
    ): Pair<AuthorityRig, PlaybackSessionSnapshot> {
        val rig = AuthorityRig("partial").also { rigs += it }
        val original = PlaybackSessionSnapshot(
            queueSongIds = rig.queue.map { it.id }, // 1..5
            playbackOrder = order,
            currentSongId = rig.queue[currentIndex].id,
            currentIndex = currentIndex,
            positionMs = positionMs,
            repeatMode = repeat,
            shuffleEnabled = shuffle,
            updatedAtMs = 1L,
        )
        runBlocking { rig.sessionRepo.save(original) }
        rig.demandController()
        rig.connectApp()
        return rig to original
    }

    private fun persisted(rig: AuthorityRig) = runBlocking { rig.sessionRepo.load() }

    @Test fun partialRestoreInstallsSurvivorsPausedAndWithoutAutoplay() {
        val (rig, _) = partialRig(currentIndex = 3, positionMs = 12_000L)
        val library = rig.queue.filter { it.id != 2L } // song 2 missing
        val result = runBlocking { rig.pc.ensurePlayerHydratedFromSession(library) }
        rig.settle(300)
        assertEquals(PlayerHydrationResult.Hydrated, result)
        assertEquals(listOf("1", "3", "4", "5"), rig.physicalIds)
        assertEquals("4", rig.currentMediaId)
        assertFalse(rig.physical.isPlaying)
        assertFalse(rig.physical.playWhenReady)
        assertEquals(0, rig.count("play"))
        assertEquals(RepeatMode.ALL, rig.pc.nowPlayingState.value.repeatMode)
        assertTrue(rig.pc.isSessionProtected)
    }

    @Test fun ordinaryEventsDoNotOverwriteTheProtectedSnapshot() {
        val (rig, original) = partialRig()
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 2L }) }
        rig.settle(300)
        rig.pc.cycleRepeatMode()
        rig.pc.toggleShuffle()
        rig.pc.seekTo(20_000L)
        rig.pc.togglePlayPause()
        rig.pc.togglePlayPause()
        rig.pc.playNext(rig.queue[0])
        rig.settle(500)
        rig.advance(15_000) // position checkpoint ticker territory
        assertEquals("persisted session must be byte-identical", original, persisted(rig))
        assertTrue(rig.pc.isSessionProtected)
    }

    @Test fun currentOccurrenceMissingRestoresNextSurvivorAtZeroPaused() {
        val (rig, _) = partialRig(currentIndex = 1, positionMs = 50_000L) // song 2 is current and missing
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 2L }) }
        rig.settle(300)
        assertEquals("3", rig.currentMediaId)
        assertEquals(0L, rig.pc.nowPlayingState.value.positionMs)
        assertFalse(rig.physical.playWhenReady)
    }

    @Test fun savedPositionIsPreservedWhenTheCurrentOccurrenceSurvives() {
        val (rig, _) = partialRig(currentIndex = 3, positionMs = 12_000L)
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 5L }) }
        rig.settle(300)
        assertEquals("4", rig.currentMediaId)
        assertEquals(12_000L, rig.pc.nowPlayingState.value.positionMs)
    }

    @Test fun shuffledSavedOrderStaysValidAfterPartialRestore() {
        val (rig, _) = partialRig(currentIndex = 4, shuffle = true, order = listOf(4, 2, 0, 3, 1))
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 3L }) } // old index 2 missing
        rig.settle(300)
        // 4,2,0,3,1 -> drop 2 -> 4,0,3,1 -> maps (0->0,1->1,3->2,4->3) -> 3,0,2,1 => songs 5,1,4,2
        assertEquals(listOf("5", "1", "4", "2"), rig.physicalIds)
        assertEquals("5", rig.currentMediaId)
        assertTrue(rig.pc.nowPlayingState.value.shuffleEnabled)
    }

    @Test fun invalidSavedOrderFallsBackToAConservativeRebuild() {
        val (rig, _) = partialRig(currentIndex = 4, order = listOf(0, 0, 1, 2, 3))
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 1L }) }
        rig.settle(300)
        assertEquals(listOf("2", "3", "4", "5"), rig.physicalIds)
        assertEquals("5", rig.currentMediaId)
    }

    @Test fun explicitQueueReplacementReleasesProtectionAndPersistsTheNewQueue() {
        val (rig, original) = partialRig()
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 2L }) }
        rig.settle(300)
        assertEquals(original, persisted(rig))
        rig.pc.playFromQueue(rig.queue.subList(0, 3), startIndex = 1)
        rig.settle(500)
        assertFalse(rig.pc.isSessionProtected)
        val saved = persisted(rig)
        assertNotNull(saved)
        assertEquals(listOf(1L, 2L, 3L), saved!!.queueSongIds)
        assertEquals(1, saved.currentIndex)
    }

    @Test fun aRejectedQueueStartDoesNotReleaseProtection() {
        val (rig, original) = partialRig()
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 2L }) }
        rig.settle(300)
        rig.pc.playFromQueue(rig.queue, startIndex = 99) // invalid start index: rejected before any commit
        rig.pc.playFromQueue(emptyList(), startIndex = 0)
        rig.pc.playFromQueue(rig.queue.subList(0, 2), startSong = rig.queue[4]) // absent start song
        rig.settle(500)
        assertTrue(rig.pc.isSessionProtected)
        assertEquals(original, persisted(rig))
    }

    @Test fun completeRestoreIsNotProtectedAndSavesAsBefore() {
        val (rig, _) = partialRig()
        val result = runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue) }
        rig.settle(300)
        assertEquals(PlayerHydrationResult.Hydrated, result)
        assertFalse(rig.pc.isSessionProtected)
        rig.pc.cycleRepeatMode()
        rig.settle(400)
        assertEquals(RepeatMode.ONE, persisted(rig)!!.repeatMode)
    }

    @Test fun libraryEntriesReturningAfterRestartRestoreTheFullQueue() {
        val (rig, original) = partialRig()
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 2L }) }
        rig.settle(300)
        assertEquals(original, persisted(rig))
        // "Restart": a new controller over the SAME untouched snapshot, with the library complete again.
        val rig2 = AuthorityRig("partial-restart").also { rigs += it }
        runBlocking { rig2.sessionRepo.save(persisted(rig)!!) }
        rig2.demandController()
        rig2.connectApp()
        val result = runBlocking { rig2.pc.ensurePlayerHydratedFromSession(rig2.queue) }
        rig2.settle(300)
        assertEquals(PlayerHydrationResult.Hydrated, result)
        assertEquals(listOf("1", "2", "3", "4", "5"), rig2.physicalIds)
        assertFalse(rig2.pc.isSessionProtected)
    }

    @Test fun partialRestoreMessageIsEmittedOncePerRecoveryEpisode() {
        val (rig, _) = partialRig()
        val seen = mutableListOf<PlaybackUserMessage>()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        scope.launch { rig.pc.userMessages.collect { seen += it } }
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 2L }) }
        rig.settle(300)
        rig.pc.cycleRepeatMode()
        rig.pc.togglePlayPause()
        rig.settle(300)
        scope.cancel()
        assertEquals(listOf(PlaybackUserMessage.UNAVAILABLE_SONGS_SKIPPED), seen)
        assertFalse("already delivered live, not pending", rig.pc.takePartialRestoreNotice())
    }

    @Test fun partialRestoreWithNoCollectorIsHeldForOneLateDelivery() {
        val (rig, _) = partialRig()
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue.filter { it.id != 2L }) }
        rig.settle(300)
        assertTrue(rig.pc.takePartialRestoreNotice())
        assertFalse(rig.pc.takePartialRestoreNotice())
    }

    @Test fun completeRestoreShowsNoMessage() {
        val (rig, _) = partialRig()
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(rig.queue) }
        rig.settle(300)
        assertFalse(rig.pc.takePartialRestoreNotice())
    }
}
