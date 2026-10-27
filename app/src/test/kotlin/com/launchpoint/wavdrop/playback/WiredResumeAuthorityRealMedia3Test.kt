package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wired-headphone automatic resume against the PRODUCTION [PlayerController] over REAL Media3, plus the Bluetooth regression
 * set that proves the shared request authority did not weaken the Bluetooth guarantees. Time is the virtual main-looper clock
 * (the 1 s wired settle wait and the 700 ms hot-resume wait are advanced deterministically). Every assertion is on the Media3
 * `play` commands that reached the physical player, or on the persisted interruption entitlement.
 *
 * Boundary (physical QA): real headset insertion/removal broadcasts and AudioDeviceCallback timing, real audio focus/routing
 * (the in-process player has none), and the process lifecycle of the receiver-started service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class WiredResumeAuthorityRealMedia3Test {

    private val rigs = mutableListOf<AuthorityRig>()

    @After fun tearDown() {
        rigs.forEach { it.releaseAll() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    /** The entitlement write completes on an IO thread; wait (bounded) for it rather than racing a fixed delay. */
    private fun assertConsumed(message: String, pending: () -> Boolean) {
        runCatching { TestMediaSource.awaitCondition(5_000L) { !pending() } }
        assertFalse(message, pending())
    }

    private fun newRig(name: String) = AuthorityRig(name).also { rigs += it }

    /** Paused, installed session; wired mode [mode]; [interrupted] records the removal-while-playing entitlement. */
    private fun wiredRig(
        mode: HeadphoneResumeMode = HeadphoneResumeMode.ALWAYS_RESUME,
        interrupted: Boolean = false,
    ): AuthorityRig = newRig("wired").also {
        it.setWiredMode(mode)
        it.loadPausedSession(interruptedByWired = interrupted)
    }

    private fun AuthorityRig.reconnectWired() {
        pc.resumeForWiredHeadphones(queue)
        settle(300) // lets the settings/entitlement reads finish so the 1 s wait is the next thing the request does
    }

    private fun AuthorityRig.reconnectBluetooth() {
        pc.resumeForBluetooth(queue)
        settle(300)
    }

    // ---------------------------------------------------------------------------------------------- happy path

    @Test fun eligibleWiredReconnectResumesOnce() {
        val rig = wiredRig()
        rig.reconnectWired()

        rig.advance(500)
        assertEquals("nothing plays inside the settle wait", 0, rig.count("play"))
        rig.advance(700)
        assertEquals(1, rig.count("play"))
        assertTrue(rig.physical.playWhenReady)
        rig.advance(3_000)
        assertEquals("no duplicate start or retry once playing", 1, rig.count("play"))
    }

    @Test fun anInterruptedSessionResumesAndConsumesItsEntitlement() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        assertTrue("precondition: the removal recorded the entitlement", rig.wiredPending())
        rig.reconnectWired()
        rig.advance(2_000)

        assertEquals(1, rig.count("play"))
        assertConsumed("a played request consumed the entitlement (definitive)") { rig.wiredPending() }
    }

    // ---------------------------------------------------------------------------------------------- invalidation

    @Test fun reconnectFollowedByExplicitPauseNeverResumes() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        val ext = rig.newExternalController()
        rig.reconnectWired()
        rig.advance(400)
        ext.pause()
        rig.settle(100)
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
        assertConsumed("the user took over: definitive, entitlement consumed") { rig.wiredPending() }
    }

    @Test fun reconnectFollowedByExplicitPlayDoesNotIssueASecondStart() {
        val rig = wiredRig()
        val ext = rig.newExternalController()
        rig.reconnectWired()
        rig.advance(400)
        ext.play()
        rig.settle(150)
        rig.advance(2_500)

        assertEquals("only the user's PLAY; the automatic request was superseded", 1, rig.count("play"))
    }

    @Test fun reconnectFollowedByANewerSelectionNeverStartsThePreviousSong() {
        val rig = wiredRig()
        rig.reconnectWired()
        rig.advance(400)
        rig.pc.playFromQueue(listOf(20L, 21L, 22L, 23L).map(RealMedia3QueueRig::song), startIndex = 1)
        rig.settle(300)
        rig.advance(2_500)

        assertEquals(listOf("20", "21", "22", "23"), rig.physicalIds)
        assertEquals("21", rig.currentMediaId)
        assertEquals("only the new selection's own play", 1, rig.count("play"))
        assertFalse("the previously selected song never entered the player", rig.physicalIds.contains("2"))
    }

    @Test fun reconnectFollowedByANavigationNeverResumesTheOldOccurrence() {
        val rig = wiredRig()
        rig.reconnectWired()
        rig.advance(400)
        rig.pc.skipToNext()
        rig.settle(200)
        rig.advance(2_500)

        // The explicit Next is a selection and starts the new song itself (once); the automatic resume bound to the old occurrence adds nothing.
        assertEquals(1, rig.count("play"))
        assertTrue(rig.physical.playWhenReady)
    }

    @Test fun headsetRemovalDuringTheWaitInvalidatesTheRequest() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        rig.reconnectWired()
        rig.advance(900) // inside the one-second wait
        rig.wiredConnected = false
        rig.pc.onWiredDeviceRemoved()
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertTrue("route loss is not definitive: the entitlement survives for the next reconnect", rig.wiredPending())
    }

    @Test fun removalBeforeTheFirstSliceOfTheWaitNeverPlays() {
        val rig = wiredRig()
        rig.pc.resumeForWiredHeadphones(rig.queue)
        rig.wiredConnected = false
        rig.pc.onWiredDeviceRemoved()
        rig.settle(150)
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
    }

    @Test fun theOutputIsRequeriedImmediatelyBeforePlayNotTrustedFromTheConnectEvent() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        rig.reconnectWired()
        rig.advance(500)
        rig.wiredConnected = false // the route vanished; no removal callback was ever delivered
        rig.advance(2_500)

        assertEquals("the fresh route query denied the play", 0, rig.count("play"))
        assertTrue(rig.wiredPending())
    }

    @Test fun aSecondReconnectSupersedesTheOlderAttempt() {
        val rig = wiredRig()
        rig.reconnectWired()
        rig.advance(400)
        rig.reconnectWired()
        rig.advance(3_000)

        assertEquals("exactly one resume, owned by the newer request", 1, rig.count("play"))
    }

    @Test fun repeatedDisconnectReconnectEventsNeverStartMoreThanOnce() {
        val rig = wiredRig()
        repeat(3) {
            rig.reconnectWired()
            rig.advance(250)
            rig.wiredConnected = false
            rig.pc.onWiredDeviceRemoved()
            rig.advance(100)
            rig.wiredConnected = true
        }
        rig.reconnectWired()
        rig.advance(3_000)

        assertEquals("only the final, never-invalidated request played", 1, rig.count("play"))
    }

    @Test fun aQueueGenerationChangeInvalidatesTheRequestAndKeepsTheEntitlement() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        rig.reconnectWired()
        rig.advance(400)
        PlayerController::class.java.getDeclaredMethod("bumpQueueGeneration").apply { isAccessible = true }.invoke(rig.pc)
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertTrue("QUEUE_CHANGED is transient", rig.wiredPending())
    }

    @Test fun duplicateSongOccurrencesAreNotConfused() {
        val rig = newRig("dup")
        rig.setWiredMode(HeadphoneResumeMode.ALWAYS_RESUME)
        val a = RealMedia3QueueRig.song(1)
        val b = RealMedia3QueueRig.song(2)
        rig.loadPausedSession(startIndex = 2, songs = listOf(a, b, a)) // the SECOND copy of song 1
        assertEquals(2, rig.physical.currentMediaItemIndex)

        rig.reconnectWired()
        rig.advance(400)
        rig.pc.jumpToQueueItem(0) // the OTHER copy of the same song id
        rig.settle(200)
        rig.advance(2_500)

        // The explicit selection of the other copy plays it once; the automatic request bound to the old occurrence adds no second Play.
        assertEquals("exactly the selection's own Play", 1, rig.count("play"))
        assertEquals(0, rig.physical.currentMediaItemIndex)
        assertTrue(rig.physical.playWhenReady)
    }

    // ---------------------------------------------------------------------------------------------- controller + lifecycle

    @Test fun controllerUnavailablePreservesRetryEligibilityAndALaterReconnectResumes() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        rig.disconnectApp(rig.appController!!)
        rig.reconnectWired()
        rig.advance(4_500) // the 3 s controller wait times out

        assertEquals(0, rig.count("play"))
        assertTrue("CONTROLLER_UNAVAILABLE is transient: the entitlement survives", rig.wiredPending())

        rig.connectApp() // the demand-driven attempt finally completes
        rig.reconnectWired()
        rig.advance(2_500)
        assertEquals(1, rig.count("play"))
        assertConsumed("entitlement consumed") { rig.wiredPending() }
    }

    @Test fun aControllerReplacedDuringTheWaitCannotBeActedOn() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        val old = rig.appController!!
        rig.reconnectWired()
        rig.advance(300)
        rig.disconnectApp(old)
        rig.demandController()
        rig.connectApp()
        rig.advance(3_000)

        assertEquals("the old attempt never played through its stale controller", 0, rig.count("play"))
        assertTrue(rig.wiredPending())
    }

    @Test fun teardownDuringTheWaitNeverPlays() {
        val rig = wiredRig()
        rig.reconnectWired()
        rig.advance(300)
        rig.pc.release()
        rig.advance(3_000)

        assertEquals(0, rig.count("play"))
    }

    // ---------------------------------------------------------------------------------------------- settings / entitlement

    @Test fun aModeThatRejectsResumeNeverPlaysAndConsumesAStalePendingFlag() {
        val rig = wiredRig(HeadphoneResumeMode.OFF)
        rig.setWiredPending(true)
        rig.reconnectWired()
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
        assertConsumed("skipped by settings is definitive") { rig.wiredPending() }
    }

    @Test fun rememberLastTrackOffPreventsPlayback() {
        val rig = wiredRig()
        rig.setRememberLastTrack(false)
        rig.setWiredPending(true)
        rig.reconnectWired()
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertConsumed("entitlement consumed") { rig.wiredPending() }
    }

    @Test fun aPausedSessionStaysPausedWhenResumeIsNotPermitted() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = false)
        rig.reconnectWired()
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
        assertFalse(rig.physical.isPlaying)
    }

    @Test fun anAlreadyPlayingSessionIsLeftAloneAndSettledDefinitively() {
        val rig = wiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        rig.appController!!.play() // the user restarted playback themselves before the reconnect
        rig.settle(300)
        rig.clearCalls()
        rig.reconnectWired()
        rig.advance(2_500)

        assertEquals("no second play for an already-playing session", 0, rig.count("play"))
        assertConsumed("already playing is definitive") { rig.wiredPending() }
    }

    // ---------------------------------------------------------------------------------------------- 700 ms retry

    @Test fun theControlledRetryStillFiresForAnUninterruptedRequest() {
        val rig = wiredRig()
        rig.seekNearEnd() // the item ends right after play: playWhenReady stays true while isPlaying is false (the retry condition)
        rig.reconnectWired()
        rig.advance(1_100)
        assertEquals(1, rig.count("play"))
        rig.advance(1_000)

        assertEquals("one initial play + one controlled retry", 2, rig.count("play"))
    }

    @Test fun aStaleRetryAfterTheSevenHundredMsDelayCannotPlay() {
        val rig = wiredRig()
        val ext = rig.newExternalController()
        rig.seekNearEnd()
        rig.reconnectWired()
        rig.advance(1_100)
        assertEquals("the initial play happened", 1, rig.count("play"))
        ext.pause() // inside the 700 ms window
        rig.settle(100)
        rig.advance(1_500)

        assertEquals("the retry was never issued", 1, rig.count("play"))
        assertFalse(rig.physical.playWhenReady)
    }

    @Test fun routeLossDuringTheSevenHundredMsDelayCancelsTheRetry() {
        val rig = wiredRig()
        rig.seekNearEnd()
        rig.reconnectWired()
        rig.advance(1_100)
        rig.wiredConnected = false
        rig.pc.onWiredDeviceRemoved()
        rig.advance(1_500)

        assertEquals(1, rig.count("play"))
    }

    @Test fun teardownDuringTheSevenHundredMsDelayCancelsTheRetry() {
        val rig = wiredRig()
        rig.seekNearEnd()
        rig.reconnectWired()
        rig.advance(1_100)
        rig.pc.release()
        rig.advance(1_500)

        assertEquals(1, rig.count("play"))
    }

    // ---------------------------------------------------------------------------------------------- cold hydration

    private fun coldWiredRig(mode: HeadphoneResumeMode = HeadphoneResumeMode.ALWAYS_RESUME): AuthorityRig =
        newRig("coldwired").also {
            it.saveSession()
            it.setWiredMode(mode)
            it.demandController()
            it.connectApp()
        }

    @Test fun coldWiredResumeHydratesThenPlaysOnce() {
        val rig = coldWiredRig()
        rig.reconnectWired()
        rig.advance(2_500)

        assertEquals(listOf("1", "2", "3", "4", "5"), rig.physicalIds)
        assertEquals("3", rig.currentMediaId)
        assertEquals(1, rig.count("play"))
        assertEquals(1, rig.count("setMediaItems"))
    }

    @Test fun anInvalidatedColdWiredRequestNeitherHydratesNorPlays() {
        val rig = coldWiredRig()
        val ext = rig.newExternalController()
        rig.reconnectWired()
        rig.advance(400)
        ext.pause()
        rig.settle(100)
        rig.advance(2_500)

        assertEquals("an invalidated automatic request must not replace the physical queue", 0, rig.count("setMediaItems"))
        assertEquals(0, rig.count("play"))
        assertTrue(rig.physicalIds.isEmpty())
    }

    @Test fun coldHydrationCannotBypassTheRouteRequery() {
        val rig = coldWiredRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED)
        rig.setWiredPending(true)
        rig.reconnectWired()
        rig.advance(500)
        rig.wiredConnected = false
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertEquals("denied before hydration too", 0, rig.count("setMediaItems"))
        assertTrue(rig.wiredPending())
    }

    @Test fun aSelectionDuringTheWaitDoesNotLetTheColdSavedSessionOverwriteIt() {
        val rig = coldWiredRig()
        rig.reconnectWired()
        rig.advance(400)
        rig.pc.playFromQueue(listOf(30L, 31L, 32L).map(RealMedia3QueueRig::song), startIndex = 0)
        rig.settle(300)
        rig.advance(2_500)

        assertEquals(listOf("30", "31", "32"), rig.physicalIds)
        assertEquals("only the selection's own install and play", 1, rig.count("setMediaItems"))
        assertEquals(1, rig.count("play"))
    }

    // ================================================================================================ Bluetooth regression

    /** Bluetooth has no settle wait, so the controller-unavailable window is the deterministic place to invalidate it. */
    private fun btRig(
        mode: HeadphoneResumeMode = HeadphoneResumeMode.ALWAYS_RESUME,
        interrupted: Boolean = false,
    ): AuthorityRig = newRig("bt").also {
        it.setBluetoothMode(mode)
        it.loadPausedSession(interruptedByBluetooth = interrupted)
    }

    @Test fun bluetoothStillResumesOnce() {
        val rig = btRig()
        rig.reconnectBluetooth()
        rig.advance(2_000)

        assertEquals(1, rig.count("play"))
    }

    @Test fun bluetoothUserPauseInvalidatesTheOlderAutomaticResume() {
        val rig = btRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        val ext = rig.newExternalController()
        rig.disconnectApp(rig.appController!!)
        rig.reconnectBluetooth()
        rig.advance(300) // waiting for a controller
        ext.pause()
        rig.settle(100)
        rig.demandController()
        rig.connectApp()
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertConsumed("the user took over: definitive") { rig.bluetoothPending() }
    }

    @Test fun bluetoothRouteLossInvalidatesTheRequestAndKeepsTheEntitlement() {
        val rig = btRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        rig.disconnectApp(rig.appController!!)
        rig.reconnectBluetooth()
        rig.advance(300)
        rig.bluetoothConnected = false
        rig.pc.onBluetoothDeviceRemoved()
        rig.demandController()
        rig.connectApp()
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertTrue(rig.bluetoothPending())
    }

    @Test fun aWiredRemovalDoesNotVoidABluetoothRequest() {
        val rig = btRig()
        rig.disconnectApp(rig.appController!!)
        rig.reconnectBluetooth()
        rig.advance(300)
        rig.pc.onWiredDeviceRemoved() // an unrelated route
        rig.demandController()
        rig.connectApp()
        rig.advance(2_500)

        assertEquals(1, rig.count("play"))
    }

    @Test fun aNewerBluetoothRequestSupersedesTheOlderOne() {
        val rig = btRig()
        rig.disconnectApp(rig.appController!!)
        rig.reconnectBluetooth()
        rig.advance(300)
        rig.field("lastBluetoothResumeAttemptAtMs").setLong(rig.pc, 0L) // step past the duplicate-event debounce
        rig.reconnectBluetooth()
        rig.advance(300)
        rig.demandController()
        rig.connectApp()
        rig.advance(2_500)

        assertEquals(1, rig.count("play"))
    }

    @Test fun bluetoothEntitlementSettlementIsUnchangedForDefinitiveAndTransientOutcomes() {
        // Transient: no controller within the 3 s window -> entitlement kept.
        val transient = btRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        transient.disconnectApp(transient.appController!!)
        transient.reconnectBluetooth()
        transient.advance(4_500)
        assertEquals(0, transient.count("play"))
        assertTrue(transient.bluetoothPending())

        // Definitive: play issued -> entitlement consumed.
        val definitive = btRig(HeadphoneResumeMode.RESUME_IF_INTERRUPTED, interrupted = true)
        definitive.reconnectBluetooth()
        definitive.advance(2_000)
        assertEquals(1, definitive.count("play"))
        assertConsumed("entitlement consumed") { definitive.bluetoothPending() }

        // Definitive: setting says no -> consumed, no play.
        val skipped = btRig(HeadphoneResumeMode.OFF)
        skipped.setBluetoothPending(true)
        skipped.reconnectBluetooth()
        skipped.advance(2_000)
        assertEquals(0, skipped.count("play"))
        assertConsumed("entitlement consumed") { skipped.bluetoothPending() }
    }

    @Test fun bluetoothDelayedRetryCannotPlayAfterInvalidation() {
        val rig = btRig()
        val ext = rig.newExternalController()
        rig.seekNearEnd()
        rig.reconnectBluetooth()
        rig.advance(300)
        assertEquals("initial play", 1, rig.count("play"))
        ext.pause()
        rig.settle(100)
        rig.advance(1_500)

        assertEquals("no retry after the user paused", 1, rig.count("play"))
    }

    @Test fun bluetoothColdHydrationAllowsItsOwnGenerationBump() {
        val rig = newRig("btcold")
        rig.saveSession()
        rig.setBluetoothMode(HeadphoneResumeMode.ALWAYS_RESUME)
        rig.demandController()
        rig.connectApp()
        rig.reconnectBluetooth()
        rig.advance(2_000)

        assertEquals(listOf("1", "2", "3", "4", "5"), rig.physicalIds)
        assertEquals(1, rig.count("play"))
    }

    @Test fun bluetoothColdRequestWithAnUnknownGenerationChangeDoesNotHydrateOrPlay() {
        val rig = newRig("btcold2")
        rig.saveSession()
        rig.setBluetoothMode(HeadphoneResumeMode.ALWAYS_RESUME)
        rig.demandController()
        val app = rig.connectApp()
        rig.disconnectApp(app) // opens the controller-wait window
        rig.reconnectBluetooth()
        rig.advance(300)
        PlayerController::class.java.getDeclaredMethod("bumpQueueGeneration").apply { isAccessible = true }.invoke(rig.pc)
        rig.demandController()
        rig.connectApp()
        rig.advance(2_500)

        assertEquals(0, rig.count("play"))
        assertEquals(0, rig.count("setMediaItems"))
    }
}
