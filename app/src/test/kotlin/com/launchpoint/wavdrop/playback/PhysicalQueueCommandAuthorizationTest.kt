package com.launchpoint.wavdrop.playback

import android.os.Bundle
import android.os.Process
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The physical-queue probe is an internal diagnostic: only Wavdrop's own in-app controller may be advertised it or execute it.
 * Real MediaSession + real MediaControllers, wired through the production connection builder and executor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PhysicalQueueCommandAuthorizationTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()
    private val extra = mutableListOf<MediaController>()

    @After fun tearDown() {
        extra.forEach { runCatching { it.release() } }
        rigs.forEach { it.releaseAll() }
    }

    private fun rig(): RealMedia3QueueRig =
        RealMedia3QueueRig(RealMedia3QueueRig.songs(1, 2, 3), startIndex = 1, physicalAck = true).also { rigs += it }

    private fun connect(rig: RealMedia3QueueRig, hint: Boolean): MediaController {
        val hints = Bundle().apply { if (hint) putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) }
        val future = MediaController.Builder(RuntimeEnvironment.getApplication(), rig.session.token).setConnectionHints(hints).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 100) idleMainLooper()
        return future.get(5, TimeUnit.SECONDS).also { extra += it; idleMainLooper() }
    }

    private fun send(controller: MediaController): SessionResult {
        val future = controller.sendCustomCommand(
            SessionCommand(PhysicalQueueObservationCommand.ACTION, Bundle.EMPTY),
            PhysicalObservation.tokenToBundle(PlanToken(1L, 2L)),
        )
        TestMediaSource.awaitCondition { future.isDone }
        return future.get()
    }

    private fun info(uid: Int, hint: Boolean) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        RuntimeEnvironment.getApplication().packageName, 0, uid, 0, 0, true,
        Bundle().apply { if (hint) putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) }, true,
    )

    @Test fun inAppControllerIsAdvertisedAndCanRequestPhysicalVerification() {
        val r = rig()
        assertTrue(r.controller.isSessionCommandAvailable(PhysicalQueueObservationCommand.command))
        val result = send(r.controller)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        val observation = PhysicalObservation.fromBundle(result.extras)
        assertEquals(PlanToken(1L, 2L), observation.token)
        assertEquals(listOf("1", "2", "3"), observation.ids)
        assertEquals(1, observation.currentIndex)
    }

    @Test fun externalControllerIsNotAdvertisedTheCommandButKeepsTheExistingOnes() {
        val r = rig()
        val external = connect(r, hint = false)
        assertFalse(external.isSessionCommandAvailable(PhysicalQueueObservationCommand.command))
        // Unchanged session commands remain available to every controller.
        assertTrue(external.isSessionCommandAvailable(SessionCommand("com.launchpoint.wavdrop.TOGGLE_SHUFFLE", Bundle.EMPTY)))
        assertTrue(external.isSessionCommandAvailable(SessionCommand("com.launchpoint.wavdrop.CYCLE_REPEAT", Bundle.EMPTY)))
        val app = r.controller
        assertTrue(app.isSessionCommandAvailable(SessionCommand("com.launchpoint.wavdrop.TOGGLE_SHUFFLE", Bundle.EMPTY)))
        assertTrue(app.isSessionCommandAvailable(SessionCommand("com.launchpoint.wavdrop.CYCLE_REPEAT", Bundle.EMPTY)))
    }

    @Test fun externalControllerCannotExecuteItEvenWithAManuallyBuiltActionAndGetsNoPlaylist() {
        val r = rig()
        val external = connect(r, hint = false)
        val result = send(external) // same action string, hand-built
        assertNotEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertTrue("no playlist contents for an unauthorized caller", PhysicalObservation.fromBundle(result.extras).ids.isEmpty())
        assertFalse(result.extras.containsKey("ids"))
    }

    @Test fun theHintAloneOrTheUidAloneIsNotAuthorization() {
        val ownUid = Process.myUid()
        assertTrue(PhysicalQueueObservationCommand.isAuthorizedController(info(ownUid, hint = true)))
        assertFalse("a spoofed hint from another uid", PhysicalQueueObservationCommand.isAuthorizedController(info(ownUid + 1, hint = true)))
        assertFalse("same uid but not the marked app controller", PhysicalQueueObservationCommand.isAuthorizedController(info(ownUid, hint = false)))
        assertFalse(PhysicalQueueObservationCommand.isAuthorizedController(info(ownUid + 1, hint = false)))
    }

    @Test fun unauthorizedExecutorCallReturnsPermissionDeniedWithoutPlaylistData() {
        val r = rig()
        val spoofed = info(Process.myUid() + 1, hint = true)
        val future = PhysicalQueueObservationCommand.execute(spoofed, r.player, PhysicalObservation.tokenToBundle(PlanToken(1L, 2L)))
        assertTrue(future.isDone)
        val result = future.get()
        assertEquals(SessionResult.RESULT_ERROR_PERMISSION_DENIED, result.resultCode)
        assertTrue(result.extras.isEmpty)
    }

    @Test fun gateStaysOffAndTheServiceRoutesThroughTheAuthorizedPaths() {
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
        val service = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlaybackService.kt").readText()
        assertTrue(service.contains("PhysicalQueueObservationCommand.execute(controller, session.player, args)"))
        assertTrue(service.contains("PhysicalQueueObservationCommand.isAuthorizedController(controller)"))
        assertFalse("the command must not be added unconditionally", Regex("""\.add\(PhysicalQueueObservationCommand\.command\)""")
            .findAll(service).any { m -> !service.substring(maxOf(0, m.range.first - 200), m.range.first).contains("isAuthorizedController") })
    }
}
