package com.launchpoint.wavdrop.playback

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
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
 * P5-E: physical verification is fully in-process (the [PhysicalWindowPort] verifier), so the session no longer carries ANY
 * physical-queue diagnostic command: not advertised to the in-app controller, not to an external one, and not executable by name.
 * Real MediaSession + real MediaControllers, wired through the production connection builder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PhysicalQueueCommandAuthorizationTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()
    private val extra = mutableListOf<MediaController>()
    private val legacyAction = "com.launchpoint.wavdrop.OBSERVE_PHYSICAL_QUEUE"

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
        val future = controller.sendCustomCommand(SessionCommand(legacyAction, Bundle.EMPTY), Bundle.EMPTY)
        TestMediaSource.awaitCondition { future.isDone }
        return future.get()
    }

    @Test fun noControllerIsAdvertisedAPhysicalQueueCommandButKeepsTheExistingOnes() {
        val r = rig()
        val legacy = SessionCommand(legacyAction, Bundle.EMPTY)
        for (c in listOf(r.controller, connect(r, hint = false), connect(r, hint = true))) {
            assertFalse(c.isSessionCommandAvailable(legacy))
            assertTrue(c.isSessionCommandAvailable(SessionCommand("com.launchpoint.wavdrop.TOGGLE_SHUFFLE", Bundle.EMPTY)))
            assertTrue(c.isSessionCommandAvailable(SessionCommand("com.launchpoint.wavdrop.CYCLE_REPEAT", Bundle.EMPTY)))
        }
    }

    @Test fun theLegacyActionCannotBeExecutedByNameAndReturnsNoPlaylistData() {
        val r = rig()
        for (c in listOf(r.controller, connect(r, hint = false))) {
            val result = send(c)
            assertNotEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
            assertFalse(result.extras.containsKey("ids"))
        }
    }

    @Test fun gateStaysOffAndTheServiceHasNoPhysicalDiagnosticCommand() {
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
        val service = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlaybackService.kt").readText()
        assertFalse(service.contains("PhysicalQueueObservationCommand"))
        assertFalse(service.contains(legacyAction))
        assertTrue("the service owns the in-process port", service.contains("attachPhysicalWindowPort("))
        assertEquals(1, Regex("""EnginePhysicalPort\(""").findAll(service).count())
    }
}
