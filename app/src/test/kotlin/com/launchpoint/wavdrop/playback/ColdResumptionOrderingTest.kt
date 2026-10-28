package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F5-1: command ordering on a COLD playback resumption, against REAL Media3 on the production topology ([ResumptionOrderRig]).
 *
 * A PLAY that reaches a session whose player holds no media makes Media3 call `onPlaybackResumption(isForPlayback = true)`; when that
 * request ends (restored, dropped, unavailable or failed) Media3 ALWAYS issues one PLAY on the session player, blind to a PAUSE that
 * reached the still-empty player meanwhile. These tests send the commands through a genuine external MediaController, so that PLAY is
 * issued by Media3 itself, and gate the saved-session load with a CompletableDeferred so a newer command lands while the restoration
 * is unfinished. No test asserts on elapsed time: every wait is on an observable condition.
 *
 * Invariants checked throughout: the restoration is never withheld because the intent changed (the saved occurrence, order and position
 * are restored and the persisted session is untouched); the bounded window is installed and verified exactly once; the newest command
 * decides playback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ColdResumptionOrderingTest {

    private val rigs = mutableListOf<ResumptionOrderRig>()
    private fun rig(bounded: Boolean = true, resumptionRoute: Boolean = true) = ResumptionOrderRig(bounded, resumptionRoute).also { rigs += it }

    @After fun tearDown() {
        rigs.forEach { it.release() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    private val queueSize = 2_000
    private val savedIndex = 1_234
    private val savedPositionMs = 33_000L

    /** A saved, paused 2,000-entry session (Bounded) and an EMPTY player: the cold precondition. */
    private fun cold(bounded: Boolean = true, resumptionRoute: Boolean = true): ResumptionOrderRig =
        rig(bounded, resumptionRoute).also { it.saveSimpleSession(queueSize, savedIndex, savedPositionMs) }

    private fun assertRestoredAndBoundedOnce(r: ResumptionOrderRig, label: String) {
        assertEquals("$label: logical queue restored", queueSize, r.logical.queue.size)
        assertEquals("$label: saved occurrence restored", savedIndex, r.logical.currentIndex)
        assertEquals("$label: saved song is current", "${savedIndex + 1}", r.currentMediaId)
        assertTrue("$label: bounded window only (${r.engine.currentPlayer.mediaItemCount})", r.engine.currentPlayer.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertNotNull("$label: window verified in-process", r.adapter.active)
        assertEquals("$label: exactly one physical install", 1, r.windowInstalls())
        assertEquals("$label: exactly one verified window", 1, r.windowVerified())
        assertEquals("$label: nothing rejected", 0, r.windowRejections())
        // (The recording player sits ABOVE the façade, so it sees Media3's own install attempt even though the façade swallows it in
        // Bounded mode; "Media3 did not install the queue" is therefore proven by the window staying <= W_MAX with one adapter install.)
        assertEquals("$label: the saved position is kept", true, r.engine.currentPlayer.currentPosition in (savedPositionMs - 1_000L)..(savedPositionMs + 8_000L))
    }

    private fun assertAuthorityIdle(r: ResumptionOrderRig, label: String) {
        assertFalse("$label: no cold PLAY pending", r.pc.coldPlayAuthority.hasPendingPlay)
    }

    // ── 1. a cold PLAY alone ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun coldPlayAloneRestoresTheSavedOccurrenceAndPlays() {
        val r = cold()
        val ext = r.externalController()
        ext.play()
        r.awaitCompleted(1); r.quiesce()

        assertTrue("playing", r.playWhenReady)
        assertEquals("exactly one PLAY reached the player", 1, r.recording.count("play"))
        assertEquals(0, r.suppressed())
        assertRestoredAndBoundedOnce(r, "play alone")
        assertAuthorityIdle(r, "play alone")
    }

    // ── 2. PLAY then PAUSE before the restoration completes ──────────────────────────────────────────────────────────────

    @Test fun coldPlayFollowedByPauseBeforeRestorationCompletesEndsPausedWithTheSessionRestored() {
        val r = cold()
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val ext = r.externalController()

        ext.play(); r.awaitInvoked(1)
        assertTrue("the cold PLAY is pending", r.pc.coldPlayAuthority.hasPendingPlay)
        ext.pause()
        TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay } // the PAUSE reached the still empty player
        assertEquals("nothing restored yet", 0, r.logical.queue.size)
        gate.complete(Unit)
        r.awaitCompleted(1); r.quiesce()

        assertFalse("the newer PAUSE wins over Media3's PLAY", r.playWhenReady)
        assertEquals("Media3's PLAY was refused, never forwarded", 0, r.recording.count("play"))
        assertEquals("exactly that one PLAY was suppressed", 1, r.suppressed())
        assertFalse("UI is not presented as playing", r.logical.isPlaying)
        assertRestoredAndBoundedOnce(r, "pause during restore")
        assertAuthorityIdle(r, "pause during restore")
    }

    @Test fun pausingDuringRestorationNeverDiscardsOrCorruptsTheSavedSession() {
        val r = cold()
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val ext = r.externalController()
        ext.play(); r.awaitInvoked(1)
        ext.pause(); TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        gate.complete(Unit)
        r.awaitCompleted(1); r.quiesce()

        val saved = kotlinx.coroutines.runBlocking { r.sessionRepo.load() }
        assertNotNull("the persisted session survives", saved)
        assertEquals(queueSize, saved!!.queueSongIds.size)
        assertEquals(savedIndex, saved.currentIndex)
        assertTrue("the saved position is not rewound or lost (${saved.positionMs})", saved.positionMs in savedPositionMs..(savedPositionMs + 8_000L))
        assertEquals("and the controller still presents the complete logical queue", queueSize, r.externalController().mediaItemCount)
    }

    // ── 3. a PAUSE landing at adoption ──────────────────────────────────────────────────────────────────────────────────

    @Test fun aPauseThatLandsAtAdoptionAlsoEndsPaused() {
        val r = cold()
        val ext = r.externalController()
        // The hook runs on the main thread immediately before adoption; the controller's command is queued behind it.
        r.beforeAdoption = { ext.pause() }
        ext.play()
        r.awaitCompleted(1); r.quiesce()
        TestMediaSource.awaitCondition { r.recording.count("pause") >= 1 }

        assertFalse("PAUSE wins whichever order the looper ran it relative to Media3's PLAY", r.playWhenReady)
        assertRestoredAndBoundedOnce(r, "pause at adoption")
        assertAuthorityIdle(r, "pause at adoption")
    }

    // ── 4./5. a later valid PLAY supersedes an earlier PAUSE ─────────────────────────────────────────────────────────────

    @Test fun playPauseThenPlayAgainEndsPlayingWithOneInstall() {
        val r = cold()
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val ext = r.externalController()

        ext.play(); r.awaitInvoked(1)
        ext.pause(); TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        ext.play(); r.awaitInvoked(2)
        gate.complete(Unit)
        r.awaitCompleted(2); r.quiesce()

        assertTrue("the newest command was PLAY", r.playWhenReady)
        assertEquals("exactly one PLAY reached the player: the newest request's", 1, r.recording.count("play"))
        assertEquals("the older request's PLAY was refused", 1, r.suppressed())
        assertRestoredAndBoundedOnce(r, "play-pause-play")
        assertAuthorityIdle(r, "play-pause-play")
    }

    @Test fun playPausePlayPauseEndsPaused() {
        val r = cold()
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val ext = r.externalController()

        ext.play(); r.awaitInvoked(1)
        ext.pause(); TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        ext.play(); r.awaitInvoked(2)
        ext.pause(); TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        gate.complete(Unit)
        r.awaitCompleted(2); r.quiesce()

        assertFalse("the newest command was PAUSE", r.playWhenReady)
        assertEquals("no PLAY reached the player", 0, r.recording.count("play"))
        assertEquals("both owed PLAYs were refused", 2, r.suppressed())
        assertRestoredAndBoundedOnce(r, "play-pause-play-pause")
        assertAuthorityIdle(r, "play-pause-play-pause")
    }

    @Test fun aPlayAfterAnEarlierPauseOnAnEmptySessionRestoresAndPlays() {
        val r = cold()
        val ext = r.externalController()
        ext.pause(); TestMediaSource.awaitCondition { r.recording.count("pause") >= 1 }
        assertEquals("a PAUSE alone restores nothing", 0, r.logical.queue.size)
        ext.play(); r.awaitCompleted(1); r.quiesce()

        assertTrue(r.playWhenReady)
        assertEquals(1, r.recording.count("play"))
        assertEquals(0, r.suppressed())
        assertRestoredAndBoundedOnce(r, "pause then play")
    }

    // ── 6. a PAUSE after the restoration has completed ───────────────────────────────────────────────────────────────────

    @Test fun pauseAfterRestorationCompletedStillWorksAndKeepsTheQueue() {
        val r = cold()
        val ext = r.externalController()
        ext.play(); r.awaitCompleted(1); r.quiesce()
        assertTrue(r.playWhenReady)

        ext.pause()
        TestMediaSource.awaitCondition { !r.playWhenReady }
        r.quiesce()
        assertFalse(r.playWhenReady)
        assertEquals("the PAUSE is an ordinary PAUSE: nothing suppressed", 0, r.suppressed())
        assertRestoredAndBoundedOnce(r, "pause after restore")
        assertAuthorityIdle(r, "pause after restore")
    }

    // ── 7. no autoplay without a PLAY command ───────────────────────────────────────────────────────────────────────────

    @Test fun nothingPlaysOrIsRestoredWithoutAPlayCommand() {
        val r = cold()
        val ext = r.externalController()
        ext.pause(); TestMediaSource.awaitCondition { r.recording.count("pause") >= 1 }
        r.quiesce()
        assertFalse(r.playWhenReady)
        assertEquals(0, r.logical.queue.size)
        assertEquals(0, r.windowInstalls())
        assertEquals(0, r.recording.count("play"))
        assertTrue("no resumption was even requested", r.log.none { it.startsWith("onPlaybackResumption") })
        assertAuthorityIdle(r, "no play")
    }

    @Test fun aQueryOwesNoPlayAndLeavesTheAuthorityIdle() {
        val r = cold()
        val future = r.newHandler().resume(isForPlayback = false) { error("a query never applies transport state") }
        TestMediaSource.awaitCondition { future.isDone }
        assertNotNull(future.get())
        r.quiesce()
        assertAuthorityIdle(r, "query")
        assertEquals(0, r.logical.queue.size)
        assertEquals(0, r.windowInstalls())
    }

    // ── 8. duplicate occurrences ────────────────────────────────────────────────────────────────────────────────────────

    private fun duplicates(): ResumptionOrderRig = rig().also {
        it.library = it.songs(500)
        // song 7 occurs at 6, 500 and 502; the saved occurrence is the LAST one, between 8 and the end
        it.saveSession(List(500) { i -> i + 1L } + listOf(7L, 8L, 7L), currentIndex = 502, positionMs = 12_000L)
    }

    private fun assertDuplicateOccurrence(r: ResumptionOrderRig, label: String) {
        assertEquals("$label: occurrence, not song", 502, r.logical.currentIndex)
        assertEquals("$label: queue size", 503, r.logical.queue.size)
        assertEquals("$label: current song id", "7", r.currentMediaId)
        val p = r.engine.currentPlayer
        assertEquals("$label: its neighbour before is the entry at 501 (song 8), not the song-7 entry at 500", "8", p.getMediaItemAt(p.currentMediaItemIndex - 1).mediaId)
        assertEquals("$label: exactly one install", 1, r.windowInstalls())
    }

    @Test fun duplicateOccurrenceIsRestoredByAColdPlay() {
        val r = duplicates()
        r.externalController().play(); r.awaitCompleted(1); r.quiesce()
        assertTrue(r.playWhenReady)
        assertDuplicateOccurrence(r, "play")
    }

    @Test fun duplicateOccurrenceIsRestoredWhenAPauseSupersedesThePlay() {
        val r = duplicates()
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val ext = r.externalController()
        ext.play(); r.awaitInvoked(1)
        ext.pause(); TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        gate.complete(Unit); r.awaitCompleted(1); r.quiesce()
        assertFalse(r.playWhenReady)
        assertDuplicateOccurrence(r, "pause")
    }

    // ── 9. the failure paths still end deterministically ────────────────────────────────────────────────────────────────

    @Test fun aRequestWithNoSavedSessionLeavesNothingOwedOrPending() {
        val r = rig() // no snapshot saved
        r.library = r.songs(10)
        val ext = r.externalController()
        ext.play()
        r.awaitCompleted(1); r.quiesce()
        TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        assertFalse("nothing to play", r.playWhenReady)
        assertEquals(0, r.logical.queue.size)
        assertAuthorityIdle(r, "unavailable")
    }

    @Test fun aDroppedRequestWhoseMappingEndsAfterANewerSelectionStillOwesNothingAndPlaysNoStaleSong() {
        val r = cold()
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val ext = r.externalController()
        ext.play(); r.awaitInvoked(1)
        // the user starts something else while the saved session is still being loaded
        r.pc.playFromQueue(r.songs(5_000), 3_000)
        r.quiesce()
        gate.complete(Unit)
        r.awaitCompleted(1); r.quiesce()
        assertEquals("the newer selection wins the queue", 5_000, r.logical.queue.size)
        assertEquals(3_000, r.logical.currentIndex)
        assertEquals("3001", r.currentMediaId)
        assertAuthorityIdle(r, "dropped")
    }

    // ── 10. explicit-PLAY hydration is unchanged ─────────────────────────────────────────────────────────────────────────

    @Test fun explicitPlayHydrationStillRestoresAndPlaysOnce() {
        val r = cold(resumptionRoute = false)
        r.externalController().play()
        TestMediaSource.awaitCondition { r.playWhenReady && r.currentMediaId == "${savedIndex + 1}" }
        r.quiesce()
        assertEquals(1, r.recording.count("play"))
        assertEquals("the resumption route was not involved", 0, r.suppressed())
        assertEquals(queueSize, r.logical.queue.size)
        assertEquals(savedIndex, r.logical.currentIndex)
        assertEquals(1, r.windowInstalls())
        assertEquals(1, r.windowVerified())
        assertAuthorityIdle(r, "explicit play")
    }

    @Test fun explicitPlayHydrationStillHonoursANewerPause() {
        val r = cold(resumptionRoute = false)
        val gate = CompletableDeferred<Unit>().also { r.songsGate = it }
        val ext = r.externalController()
        ext.play(); TestMediaSource.awaitCondition { r.pc.coldPlayAuthority.hasPendingPlay }
        ext.pause(); TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        gate.complete(Unit)
        TestMediaSource.awaitCondition { r.log.any { it.startsWith("explicit PLAY dropped before hydration") } }
        r.quiesce()
        assertFalse(r.playWhenReady)
        assertEquals("a stale explicit PLAY does not even hydrate", 0, r.windowInstalls())
        assertEquals(0, r.recording.count("play"))
        assertAuthorityIdle(r, "explicit pause")
    }

    // ── 11. Full mode ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun fullCold(): ResumptionOrderRig = rig(bounded = false).also { it.saveSimpleSession(200, 50, 7_000L) }

    private fun assertFullRestored(r: ResumptionOrderRig, label: String) {
        assertEquals("$label: whole queue restored", 200, r.logical.queue.size)
        assertEquals("$label: saved occurrence", 50, r.logical.currentIndex)
        assertEquals("$label: saved song current", "51", r.currentMediaId)
        assertEquals("$label: Full installs the whole queue through Media3 once", 1, r.recording.count("setMediaItems"))
        assertEquals("$label: no bounded window operation in Full mode", 0, r.trace.count { it.startsWith("window_") })
        assertEquals(200, r.engine.currentPlayer.mediaItemCount)
    }

    @Test fun fullModeColdPlayRestoresAndPlays() {
        val r = fullCold()
        r.externalController().play(); r.awaitCompleted(1); r.quiesce()
        assertTrue(r.playWhenReady)
        assertEquals(1, r.recording.count("play"))
        assertFullRestored(r, "Full play")
        assertAuthorityIdle(r, "Full play")
    }

    @Test fun fullModePauseBeforeRestorationCompletesEndsPausedWithTheQueueRestored() {
        val r = fullCold()
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val ext = r.externalController()
        ext.play(); r.awaitInvoked(1)
        ext.pause(); TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        gate.complete(Unit); r.awaitCompleted(1); r.quiesce()
        assertFalse(r.playWhenReady)
        assertEquals(0, r.recording.count("play"))
        assertEquals(1, r.suppressed())
        assertFullRestored(r, "Full pause")
        assertAuthorityIdle(r, "Full pause")
    }

    @Test fun fullModePlayPauseThenPlayEndsPlaying() {
        val r = fullCold()
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val ext = r.externalController()
        ext.play(); r.awaitInvoked(1)
        ext.pause(); TestMediaSource.awaitCondition { !r.pc.coldPlayAuthority.hasPendingPlay }
        ext.play(); r.awaitInvoked(2)
        gate.complete(Unit); r.awaitCompleted(2); r.quiesce()
        assertTrue(r.playWhenReady)
        assertEquals(1, r.recording.count("play"))
        assertAuthorityIdle(r, "Full play-pause-play")
    }

    @Test fun theSessionPlayerRefusesNothingWhenNoResumptionIsOwedAPlay() {
        val r = cold()
        // A direct PLAY on a player that already holds a queue is untouched by the new gate.
        r.pc.playFromQueue(r.songs(500), 100)
        r.quiesce()
        r.appController.pause(); TestMediaSource.awaitCondition { !r.playWhenReady }
        val ext = r.externalController()
        ext.play(); TestMediaSource.awaitCondition { r.playWhenReady }
        assertEquals(0, r.suppressed())
        assertNull("a direct PLAY owes and consumes nothing", r.log.firstOrNull { it.startsWith("resumption PLAY suppressed") })
        assertAuthorityIdle(r, "direct play")
    }
}
