package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-F16B T0: after a restore finds NO resolvable song (empty/incomplete library), the in-memory queue is empty. Ordinary events
 * must not be able to erase the persisted session via the empty-queue CLEAR rule. Uses an isolated temp DataStore (never real data).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class UnresolvedSessionPersistenceTest {

    private val rigs = mutableListOf<AuthorityRig>()

    @After fun tearDown() {
        rigs.forEach { it.releaseAll() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    private fun unresolvedRig(): AuthorityRig = AuthorityRig("unresolved").also {
        rigs += it
        it.saveSession()
        it.demandController()
        it.connectApp()
        val result = runBlocking { it.pc.ensurePlayerHydratedFromSession(emptyList()) }
        assertEquals(PlayerHydrationResult.NoResolvableSong, result)
        it.settle(200)
    }

    private fun persisted(rig: AuthorityRig) = runBlocking { rig.sessionRepo.load() }

    @Test fun unresolvedRestoreAloneLeavesSnapshotIntact() {
        val rig = unresolvedRig()
        assertNotNull(persisted(rig))
    }

    @Test fun repeatCycleWithEmptyQueueDoesNotEraseSnapshot() {
        val rig = unresolvedRig()
        rig.pc.cycleRepeatMode()
        rig.settle(300)
        assertNotNull("repeat cycle must not clear the saved session", persisted(rig))
    }

    @Test fun shuffleToggleWithEmptyQueueDoesNotEraseSnapshot() {
        val rig = unresolvedRig()
        rig.pc.toggleShuffle()
        rig.settle(300)
        assertNotNull("shuffle toggle must not clear the saved session", persisted(rig))
    }

    @Test fun seekAndPauseWithEmptyQueueDoNotEraseSnapshot() {
        val rig = unresolvedRig()
        rig.pc.seekTo(5_000L)
        rig.pc.togglePlayPause()
        rig.settle(300)
        assertNotNull("seek/pause must not clear the saved session", persisted(rig))
    }

    @Test fun explicitQueueReplacementReleasesProtectionAndSaves() {
        val rig = unresolvedRig()
        rig.pc.playFromQueue(rig.queue, startIndex = 1)
        rig.settle(400)
        val saved = persisted(rig)
        assertNotNull(saved)
        assertEquals(rig.queue.map { it.id }, saved!!.queueSongIds)
        assertEquals(1, saved.currentIndex)
    }

    @Test fun noSnapshotStaysAbsent() {
        val rig = AuthorityRig("nosnap").also { rigs += it; it.demandController(); it.connectApp() }
        runBlocking { rig.pc.ensurePlayerHydratedFromSession(emptyList()) }
        rig.pc.cycleRepeatMode()
        rig.settle(300)
        assertNull(persisted(rig))
    }
}
