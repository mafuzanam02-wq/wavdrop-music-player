package com.launchpoint.wavdrop.playback

import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cold system/widget PLAY authority against REAL Media3: the production [PreviousBehaviorPlayer] and [PlayerController] behind a
 * genuine MediaSession, driven by genuine external MediaControllers (what the widget, notification and media keys are). A gate
 * suspends the session player's songs lookup or hydration so a newer command can arrive while the older PLAY is unfinished.
 * Assertions are on the Media3 commands that actually reached the physical player.
 *
 * Boundary (physical QA): the real PlaybackService process lifecycle, real OEM media-button routing and audio focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ColdPlayRealMedia3Test {

    private val rigs = mutableListOf<AuthorityRig>()

    @After fun tearDown() {
        rigs.forEach { it.releaseAll() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    /** Saved session in storage, app controller connected, physical player EMPTY: the cold-PLAY precondition. */
    private fun coldRig(): AuthorityRig = AuthorityRig("cold").also {
        rigs += it
        it.saveSession()
        it.demandController()
        it.connectApp()
    }

    private fun gateHydration(rig: AuthorityRig) = CompletableDeferred<Unit>().also { rig.hydrateGate = it }

    private fun release(rig: AuthorityRig, gate: CompletableDeferred<Unit>) {
        gate.complete(Unit)
        rig.settle(700)
    }

    /** Exactly what the widget does: resolve the toggle against the desired state at receipt, then issue PLAY or PAUSE. */
    private fun widgetToggle(rig: AuthorityRig, controller: MediaController) {
        if (rig.pc.coldPlayAuthority.desiredPlaying(controller.isPlaying)) controller.pause() else controller.play()
        rig.settle(100)
    }

    private val savedIds = listOf("1", "2", "3", "4", "5")

    @Test fun coldPlayWithoutInterferenceHydratesAndPlaysOnce() {
        val rig = coldRig()
        val ext = rig.newExternalController()
        ext.play()
        rig.settle(700)

        assertEquals(savedIds, rig.physicalIds)
        assertEquals("3", rig.currentMediaId)
        assertEquals(1, rig.count("play"))
        assertTrue(rig.physical.playWhenReady)
        assertFalse(rig.pc.coldPlayAuthority.hasPendingPlay)
    }

    @Test fun pauseWhileHydrationIsSuspendedPreventsPlaybackButKeepsTheSafelyHydratedQueue() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        assertTrue("the cold PLAY is pending", rig.pc.coldPlayAuthority.hasPendingPlay)
        ext.pause(); rig.settle(150)
        assertFalse("PAUSE invalidated it before any suspension point resumed", rig.pc.coldPlayAuthority.hasPendingPlay)
        release(rig, gate)

        assertEquals("no PLAY ever reached the physical player", 0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
        assertEquals("the valid saved queue was retained (hydration is not playback authority)", savedIds, rig.physicalIds)
        assertEquals(1, rig.hydrationCalls)
    }

    @Test fun pauseBeforeTheSongsLookupFinishesNeitherHydratesNorPlays() {
        val rig = coldRig()
        val gate = CompletableDeferred<Unit>().also { rig.songsGate = it }
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        ext.pause(); rig.settle(150)
        release(rig, gate)

        assertEquals("a stale PLAY must not even hydrate", 0, rig.hydrationCalls)
        assertEquals(0, rig.count("setMediaItems"))
        assertEquals(0, rig.count("play"))
        assertTrue(rig.physicalIds.isEmpty())
    }

    @Test fun aNewerPlayIsTheOnlyAuthoritativeOne() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        ext.play(); rig.settle(100)
        release(rig, gate)

        assertEquals("exactly one start", 1, rig.count("play"))
        assertEquals(1, rig.count("setMediaItems"))
        assertEquals(savedIds, rig.physicalIds)
        assertTrue(rig.physical.playWhenReady)
    }

    @Test fun aDifferentSongSelectedMeanwhileIsNeverOverwrittenByTheOlderSession() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        rig.pc.playFromQueue(listOf(10L, 11L, 12L, 13L).map(RealMedia3QueueRig::song), startIndex = 1)
        rig.settle(400)
        assertEquals(listOf("10", "11", "12", "13"), rig.physicalIds)
        val playsBeforeRelease = rig.count("play")
        val installsBeforeRelease = rig.count("setMediaItems")
        release(rig, gate)

        assertEquals("the saved queue never replaced the newer selection", listOf("10", "11", "12", "13"), rig.physicalIds)
        assertEquals("11", rig.currentMediaId)
        assertEquals("the stale cold PLAY issued no further play", playsBeforeRelease, rig.count("play"))
        assertEquals("and no further install", installsBeforeRelease, rig.count("setMediaItems"))
        assertTrue("the newer selection is the one playing", rig.physical.playWhenReady)
    }

    @Test fun aQueueGenerationChangeThatIsNotAHydrationRejectsTheStalePlay() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        // A queue mutation that is not a transport command (so epoch is untouched) but moves the generation.
        PlayerController::class.java.getDeclaredMethod("bumpQueueGeneration").apply { isAccessible = true }.invoke(rig.pc)
        release(rig, gate)

        assertEquals("the unknown generation change voided the PLAY", 0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
    }

    @Test fun ownHydrationBumpIsAccountedForSoAnUndisturbedPlayStillStarts() {
        val rig = coldRig()
        val before = rig.field("queueGeneration").getLong(rig.pc)
        rig.newExternalController().play()
        rig.settle(700)

        assertTrue("hydration did bump the generation", rig.field("queueGeneration").getLong(rig.pc) > before)
        assertEquals(1, rig.count("play"))
    }

    @Test fun playPauseTogglesDuringHydrationPreserveTheNewestDesiredState() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        widgetToggle(rig, ext) // -> PLAY (hydrating)
        widgetToggle(rig, ext) // -> resolved against the DESIRED state: PAUSE, not a second PLAY
        release(rig, gate)
        assertEquals("two taps end paused", 0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)

        widgetToggle(rig, ext) // paused -> PLAY again, which supersedes the earlier pause
        rig.settle(500)
        assertEquals("a later explicit PLAY starts playback normally", 1, rig.count("play"))
        assertTrue(rig.physical.playWhenReady)
    }

    @Test fun threeTogglesBeforeAnyHydrationStartsEndPlayingWithExactlyOneStart() {
        val rig = coldRig()
        val gate = CompletableDeferred<Unit>().also { rig.songsGate = it }
        val ext = rig.newExternalController()

        widgetToggle(rig, ext) // PLAY A
        widgetToggle(rig, ext) // PAUSE (A is dropped before it ever hydrates)
        widgetToggle(rig, ext) // PLAY B: a fresh lineage that hydrates for itself
        release(rig, gate)

        assertEquals(1, rig.count("play"))
        assertEquals(1, rig.count("setMediaItems"))
        assertTrue(rig.physical.playWhenReady)
    }



    @Test fun playSelectionPlayWhileAHydrationIsInFlightNeverLetsTheOldSessionAuthorizeOrOverwrite() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        rig.pc.playFromQueue(listOf(10L, 11L, 12L).map(RealMedia3QueueRig::song), startIndex = 1)
        rig.settle(400)
        ext.play(); rig.settle(150) // B: the physical queue is already the selection's
        release(rig, gate)

        assertEquals(listOf("10", "11", "12"), rig.physicalIds)
        assertEquals("11", rig.currentMediaId)
        assertEquals("the saved session never replaced the selection", 1, rig.count("setMediaItems"))
    }

    @Test fun aStaleHydrationDoesNotMutateALogicalQueueThatMovedAfterItsPlayBegan() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        // The logical queue moves (a newer selection / edit) before the suspended hydration reaches its first mutation.
        PlayerController::class.java.getDeclaredMethod("bumpQueueGeneration").apply { isAccessible = true }.invoke(rig.pc)
        release(rig, gate)

        assertEquals("hydration revalidated and did not mutate", 0, rig.count("setMediaItems"))
        assertTrue(rig.physicalIds.isEmpty())
        assertEquals(0, rig.count("play"))
    }


    @Test fun repeatedWidgetEventsDoNotCreateDuplicatePlaybackStarts() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        repeat(4) { ext.play(); rig.settle(40) }
        release(rig, gate)

        assertEquals("one audible start", 1, rig.count("play"))
        assertEquals("one physical install", 1, rig.count("setMediaItems"))
    }

    @Test fun controllerReconnectionDoesNotResurrectAnOlderPlay() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        ext.pause(); rig.settle(100)
        val app = rig.appController!!
        rig.disconnectApp(app)
        rig.demandController()
        rig.connectApp()
        release(rig, gate)
        rig.settle(500)

        assertEquals("nothing replays after the reconnect", 0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
        assertTrue("no command was parked for the reconnect", (rig.field("deferred").get(rig.pc) as DeferredPlaybackCommands).isEmpty)
    }

    @Test fun serviceTeardownWhileHydratingNeverPlaysLater() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        rig.sessionScope.cancel() // the service's scope dies with the service
        release(rig, gate)

        assertEquals(0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
    }

    // ---------------------------------------------------------------- request-specific hydration ownership


    @Test fun aChangeAfterTheColdPlaysOwnHydrationRejectsIt() {
        val rig = coldRig()
        val hydrate = gateHydration(rig)
        val after = CompletableDeferred<Unit>().also { rig.afterHydrateGate = it }
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        hydrate.complete(Unit)
        rig.settle(500) // own hydration finished; the PLAY is suspended before re-authorizing
        assertEquals(savedIds, rig.physicalIds)
        PlayerController::class.java.getDeclaredMethod("bumpQueueGeneration").apply { isAccessible = true }.invoke(rig.pc)
        release(rig, after)

        assertEquals(0, rig.count("play"))
    }

    @Test fun aNewerSelectionAfterTheColdPlaysOwnHydrationIsNotOverwrittenOrDoubled() {
        val rig = coldRig()
        val hydrate = gateHydration(rig)
        val after = CompletableDeferred<Unit>().also { rig.afterHydrateGate = it }
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        hydrate.complete(Unit)
        rig.settle(500)
        rig.pc.playFromQueue(listOf(10L, 11L, 12L).map(RealMedia3QueueRig::song), startIndex = 2)
        rig.settle(400)
        val playsBefore = rig.count("play")
        release(rig, after)

        assertEquals(listOf("10", "11", "12"), rig.physicalIds)
        assertEquals("12", rig.currentMediaId)
        assertEquals("only the selection's own play", playsBefore, rig.count("play"))
        assertEquals(1, playsBefore)
    }

    @Test fun anUnaffectedColdPlayStillAuthorizesItsOwnHydration() {
        val rig = coldRig()
        val hydrate = gateHydration(rig)
        val after = CompletableDeferred<Unit>().also { rig.afterHydrateGate = it }
        rig.newExternalController().play(); rig.settle(150)
        hydrate.complete(Unit)
        rig.settle(500)
        release(rig, after)

        assertEquals(1, rig.count("play"))
    }

    @Test fun aSessionRestoreRunByAnotherOperationServesTheNewestPlayWithoutASecondInstall() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150) // the cold PLAY is suspended before its own hydration
        rig.sessionScope.launch { rig.pc.restoreSessionIfNeeded(rig.queue) } // a startup restore hydrates the SAME saved session
        rig.settle(500)
        assertEquals(savedIds, rig.physicalIds)
        release(rig, gate)

        assertEquals("the loaded session is reusable state; the PLAY is still authorized on its own", 1, rig.count("play"))
        assertEquals("and nothing was installed twice", 1, rig.count("setMediaItems"))
        assertTrue(rig.physical.playWhenReady)
    }

    @Test fun playPauseThenPlayWhileAHydrationIsInFlightPlaysOnceWithoutAnotherTap() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        widgetToggle(rig, ext) // PLAY A: inside its hydration
        widgetToggle(rig, ext) // PAUSE: A is invalidated synchronously
        widgetToggle(rig, ext) // PLAY B: independent authority
        release(rig, gate) // A's hydration lands

        assertEquals("exactly one start, owned by B", 1, rig.count("play"))
        assertEquals("the queue was installed once", 1, rig.count("setMediaItems"))
        assertEquals(savedIds, rig.physicalIds)
        assertTrue(rig.physical.playWhenReady)
    }

    @Test fun playPauseOnlyDuringHydrationNeverPlays() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        ext.pause(); rig.settle(150)
        release(rig, gate)

        assertEquals(0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
    }

    @Test fun playPausePlayPauseDuringHydrationStaysPaused() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        ext.pause(); rig.settle(100)
        ext.play(); rig.settle(100)
        ext.pause(); rig.settle(100)
        release(rig, gate)

        assertEquals(0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
    }

    @Test fun playPausePlayThenANewSelectionNeverReplaysTheOldSession() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        ext.pause(); rig.settle(100)
        ext.play(); rig.settle(100)
        rig.pc.playFromQueue(listOf(10L, 11L, 12L).map(RealMedia3QueueRig::song), startIndex = 1)
        rig.settle(400)
        release(rig, gate)

        assertEquals(listOf("10", "11", "12"), rig.physicalIds)
        assertEquals("11", rig.currentMediaId)
        assertEquals("the saved session was never installed over the selection", 1, rig.count("setMediaItems"))
    }

    @Test fun playPausePlayThenAnUnrelatedQueueMutationIsNotOverwrittenByStaleWork() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        ext.pause(); rig.settle(100)
        ext.play(); rig.settle(100)
        PlayerController::class.java.getDeclaredMethod("bumpQueueGeneration").apply { isAccessible = true }.invoke(rig.pc)
        release(rig, gate)

        assertEquals("A's hydration revalidated and did not mutate", 0, rig.count("setMediaItems"))
        assertTrue(rig.physicalIds.isEmpty())
        assertEquals("and B was denied by the edit", 0, rig.count("play"))
    }

    @Test fun playNavigationPlayDuringHydrationPlaysOnce() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        rig.pc.skipToNext() // explicit navigation on a still-empty queue changes nothing
        ext.play(); rig.settle(150)
        release(rig, gate)

        assertEquals(1, rig.count("play"))
        assertEquals(savedIds, rig.physicalIds)
    }

    // ---------------------------------------------------------------- notification PLAY_PAUSE (actual Media3 command path)

    @Test fun aNotificationPlayPauseDuringColdHydrationResolvesToPauseAndALaterPlayStillWorks() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(150)
        rig.deliverNotificationPlayPause() // resolved to a PAUSE against the latest intended state
        assertFalse("the cold PLAY lost its authority", rig.pc.coldPlayAuthority.hasPendingPlay)
        release(rig, gate)
        assertEquals("hydration completed but playback stays paused", 0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
        assertEquals(savedIds, rig.physicalIds)

        ext.play(); rig.settle(500) // a subsequent EXPLICIT play is a play, never reinterpreted as a toggle
        assertEquals(1, rig.count("play"))
        assertTrue(rig.physical.playWhenReady)
    }

    @Test fun twoExplicitPlaysDuringHydrationAreNeverTreatedAsToggles() {
        val rig = coldRig()
        val gate = gateHydration(rig)
        val ext = rig.newExternalController()

        ext.play(); rig.settle(100)
        ext.play(); rig.settle(100)
        release(rig, gate)

        assertEquals(1, rig.count("play"))
        assertTrue(rig.physical.playWhenReady)
    }

    @Test fun aNotificationPlayPauseOutsideColdHydrationIsLeftToMedia3() {
        // The harness has no notification controller, so Media3's own default handling cannot be driven end to end here;
        // the boundary that matters is that our handler DECLINES the event (returns false) whenever nothing is hydrating.
        val rig = AuthorityRig("notif").also { rigs += it }
        val intent = androidx.media3.session.PlaybackPendingIntentBuilder.createMediaButtonIntent(
            rig.context, androidx.media3.common.Player.COMMAND_PLAY_PAUSE, android.os.Bundle.EMPTY, "s", PlaybackService::class.java,
        )
        var paused = 0
        var superseded = 0

        assertFalse(
            ColdPlayMediaButton.handle(intent, rig.pc.coldPlayAuthority, { superseded++ }, { paused++ }),
        )
        assertEquals(0, paused)
        assertEquals(0, superseded)

        rig.pc.coldPlayAuthority.begin() // a cold PLAY is now hydrating
        assertTrue(ColdPlayMediaButton.handle(intent, rig.pc.coldPlayAuthority, { superseded++ }, { paused++ }))
        assertEquals(1, paused)
        assertEquals(1, superseded)

        val next = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
            Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT),
        )
        assertFalse("other keys are never touched", ColdPlayMediaButton.handle(next, rig.pc.coldPlayAuthority, { superseded++ }, { paused++ }))
        assertEquals(1, paused)
    }

    // ---------------------------------------------------------------- PAUSE / PLAY with no app controller

    @Test fun aPauseReceivedWithoutAnAppControllerCarriesItsIntentThroughReconnection() {
        val rig = AuthorityRig("nopc").also { rigs += it }
        rig.pc.playFromQueue(rig.queue, startIndex = 1) // accepted while no controller exists: a deferred start
        rig.settle(50)
        val ext = rig.newExternalController()
        ext.pause(); rig.settle(150)

        rig.connectApp(settleMs = 500)

        assertEquals(savedIds, rig.physicalIds)
        assertEquals("2", rig.currentMediaId)
        assertFalse("the newer PAUSE won over the deferred play-by-default start", rig.physical.playWhenReady)
        assertFalse(rig.physical.isPlaying)
    }

    @Test fun aLaterExternalPlayOverwritesAnEarlierExternalPauseWhileDisconnected() {
        val rig = AuthorityRig("nopc2").also { rigs += it }
        rig.pc.playFromQueue(rig.queue, startIndex = 1)
        rig.settle(50)
        val ext = rig.newExternalController()
        ext.pause(); rig.settle(100)
        ext.play(); rig.settle(100)

        rig.connectApp(settleMs = 600)

        assertTrue("PLAY was the newest command", rig.physical.playWhenReady)
        assertEquals("2", rig.currentMediaId)
    }
}
