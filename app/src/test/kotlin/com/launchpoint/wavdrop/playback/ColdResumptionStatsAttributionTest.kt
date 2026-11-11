package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-AUDIT-16 - does a cold playback restoration through Media3's `onPlaybackResumption` register the restored song with the REAL
 * [StatsTracker], so the listening time that follows is attributed to it (and not to the song tracked before)?
 *
 * Production pieces, unchanged: [PlaybackResumptionHandler] attached to Media3's callback exactly as `MediaSessionImpl` does
 * ([ResumptionOrderRig.Media3Request]: apply the result, then Media3's own PLAY), the production [PlayerController] (with its real
 * `adoptPlaybackResumption`, controller callbacks and [ColdPlayAuthority]), [PreviousBehaviorPlayer], real ExoPlayers behind a real
 * MediaSession, and the real [StatsTracker] writing to a recording writer under a hand-advanced clock.
 *
 * What is simulated: this JVM has no codecs, so the physical player never reaches `isPlaying = true`. The play/pause transitions that a
 * real device delivers are therefore fed to the PRODUCTION `PlayerController.playerListener.onIsPlayingChanged` (the code that calls
 * `StatsTracker.onPlaybackStarted/Paused`) by [listen], after the real restoration and Media3 PLAY have completed. Consequently the
 * ORDER between "song registered" and "isPlaying became true" on a device is NOT proven here (see the limitation in the report).
 * Nothing here is physical-device verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ColdResumptionStatsAttributionTest {

    private val rigs = mutableListOf<ResumptionOrderRig>()
    private fun rig(bounded: Boolean = false, route: Boolean = true) = ResumptionOrderRig(bounded, route).also { rigs += it }

    @After fun tearDown() {
        rigs.forEach { it.release() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    private val queueSize = 5
    private val savedIndex = 2          // saved current song id = 3
    private val listenMs = 40_000L      // above the 30 s meaningful-play threshold of a 180 s song

    private fun listener(r: ResumptionOrderRig): Player.Listener =
        PlayerController::class.java.getDeclaredField("playerListener").apply { isAccessible = true }.get(r.pc) as Player.Listener

    /** The device's isPlaying true, then [ms] of listening, then false, delivered to the production controller listener. */
    private fun listen(r: ResumptionOrderRig, ms: Long) {
        listener(r).onIsPlayingChanged(true)
        r.statsNowMs += ms
        listener(r).onIsPlayingChanged(false)
    }

    /** A different song the tracker was already following, with real earlier listening time, before the cold restoration. */
    private fun trackPreviousSong(r: ResumptionOrderRig, id: Long = 99L, ms: Long = 10_000L) {
        r.tracker.onSongSelected(RealMedia3QueueRig.song(id))
        listen(r, ms)
        r.statsEvents.clear()
    }

    private fun cold(r: ResumptionOrderRig): ResumptionOrderRig.Media3Request = r.Media3Request().also { it.awaitCompletion(); r.quiesce() }

    private fun plays(r: ResumptionOrderRig) = r.statsEvents.filter { it.startsWith("play:") }

    // -- 1-3, 5-7: persisted queue restored, adopted, played on request, and the right song gets the listening time ---

    @Test fun mediaOwnedResumptionRegistersTheRestoredSongAndAttributesAllListeningTimeToIt() {
        val r = rig().also { it.saveSimpleSession(queueSize, savedIndex) }
        trackPreviousSong(r)

        val request = cold(r)
        assertTrue("1: restoration adopted by the controller", r.log.any { it.startsWith("onPlaybackResumption adopted") })
        assertEquals("1: persisted queue restored", queueSize, r.logical.queue.size)
        assertEquals("2: the saved occurrence is current", savedIndex, r.logical.currentIndex)
        assertEquals("2: and it is the saved song", 3L, r.logical.song?.id)
        assertFalse("4: restoring alone does not start playback", r.playWhenReady)

        assertEquals("4: Media3 PLAY is delivered once", 1, request.deliver()); r.quiesce()
        assertTrue(r.playWhenReady)
        assertEquals("the physical player holds the saved song", "3", r.currentMediaId)

        listen(r, listenMs)
        assertEquals("5/6: the restored song gets the listening time", listOf("play:3:$listenMs"), plays(r))
        assertTrue(
            "7: only the restored song's own events, plus the earlier song's closing skip",
            r.statsEvents.all { it == "play:3:$listenMs" || it == "listen:3" || it == "skip:99" },
        )
        assertTrue("7: no play event or listening time for the previously tracked song", r.statsEvents.none { it.startsWith("play:99") || it == "listen:99" })
    }

    @Test fun theSameHoldsForABoundedPhysicalWindowRestoration() {
        val r = rig(bounded = true).also { it.saveSimpleSession(2_000, 1_234) }
        trackPreviousSong(r)
        val request = cold(r)
        assertEquals(1_234, r.logical.currentIndex)
        assertEquals(1, request.deliver()); r.quiesce()
        assertEquals("1235", r.currentMediaId)
        listen(r, listenMs)
        assertEquals(listOf("play:1235:$listenMs"), plays(r))
    }

    // -- 8: restoring metadata is not a play ------------------------------------------------------------------------

    @Test fun restoringTheQueueAndMetadataGeneratesNoPlayEventAndAccumulatesNoTime() {
        val r = rig().also { it.saveSimpleSession(queueSize, savedIndex) }
        trackPreviousSong(r)
        cold(r)                                   // adopted; the Media3 callback (and so its PLAY) has NOT run
        r.statsNowMs += 120_000L                  // a long time passes with nothing playing
        assertTrue("no statistics event of any kind from restoration alone", r.statsEvents.isEmpty())
        assertFalse(r.playWhenReady)
        assertEquals(3L, r.logical.song?.id)
    }

    // -- 9: duplicate occurrences -----------------------------------------------------------------------------------

    @Test fun aDuplicateOccurrenceRestoresTheSavedPositionAndStillAttributesToThatSong() {
        val r = rig()
        r.library = r.songs(5)
        r.saveSession(queueIds = listOf(1L, 2L, 3L, 2L, 5L), currentIndex = 3)   // song 2 appears twice; the SECOND is current
        trackPreviousSong(r)
        val request = cold(r)
        assertEquals("the saved occurrence (not the first copy of the song)", 3, r.logical.currentIndex)
        assertEquals(2L, r.logical.song?.id)
        assertEquals(1, request.deliver()); r.quiesce()
        assertEquals("2", r.currentMediaId)
        listen(r, listenMs)
        assertEquals(listOf("play:2:$listenMs"), plays(r))
    }

    // -- 10: a superseding Pause ------------------------------------------------------------------------------------

    @Test fun aSupersedingPauseBeforeCompletionPreventsPlaybackAndAnyStatistics() {
        val r = rig().also { it.saveSimpleSession(queueSize, savedIndex) }
        trackPreviousSong(r)
        val gate = CompletableDeferred<Unit>().also { r.snapshotGate = it }
        val request = r.Media3Request()
        r.sessionPlayer.pause()                   // arrives while the saved session is still loading
        gate.complete(Unit); request.awaitCompletion(); r.quiesce()

        assertEquals("the restored queue is kept (restoration is not playback)", queueSize, r.logical.queue.size)
        assertEquals("the Media3 PLAY was refused", 0, request.deliver()); r.quiesce()
        assertFalse(r.playWhenReady)
        assertEquals(1, r.suppressed())
        r.statsNowMs += 120_000L
        assertTrue("no play or listening event for the restored song; at most the earlier song closing skip " + r.statsEvents, r.statsEvents.all { it == "skip:99" })
    }

    @Test fun aPauseAfterMedia3PlayStopsTheAccumulationAtTheActualListeningTime() {
        val r = rig().also { it.saveSimpleSession(queueSize, savedIndex) }
        val request = cold(r)
        request.deliver(); r.quiesce()
        listen(r, 31_000L)                        // played, then paused
        r.statsNowMs += 600_000L                  // long paused: nothing more may accrue
        assertEquals(listOf("play:3:31000"), plays(r))
    }

    // -- 11: repeated callbacks -------------------------------------------------------------------------------------

    @Test fun repeatedResumptionCallbacksCountTheListeningTimeOnce() {
        val r = rig().also { it.saveSimpleSession(queueSize, savedIndex) }
        val first = cold(r)
        val second = cold(r)
        first.deliver(); second.deliver(); r.quiesce()
        listen(r, listenMs)
        assertEquals("one play event with the real time, not doubled", listOf("play:3:$listenMs"), plays(r))
        assertEquals("one listen-start", 1, r.statsEvents.count { it == "listen:3" })
        assertEquals(3L, r.logical.song?.id)
    }

    // -- 12: the existing cold explicit-Play path -------------------------------------------------------------------

    @Test fun theColdExplicitPlayPathStillRegistersAndAttributesCorrectly() {
        val r = rig(route = false).also { it.saveSimpleSession(queueSize, savedIndex) }   // Media3 resumption unavailable: hydration route
        trackPreviousSong(r)
        val ext = r.externalController()
        ext.play(); r.quiesce(); TestMediaSource.settle(500)
        assertEquals("3", r.currentMediaId)
        assertTrue(r.playWhenReady)
        listen(r, listenMs)
        assertEquals(listOf("play:3:$listenMs"), plays(r))
        assertTrue(r.statsEvents.none { it.startsWith("play:99") || it == "listen:99" })
    }
}
