package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CF-2M2 integration proof with a REAL [MediaSession] and real [MediaController]s (Robolectric): the request-controller context
 * WavDrop relies on survives `ForwardingSimpleBasePlayer`'s handle* path, the full policy chain behaves identically with and
 * without the façade, and a delegate swap does not touch session or controller identity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SessionFacadeSessionTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val toRelease = mutableListOf<() -> Unit>()
    private var sessionCounter = 0

    @After fun tearDown() {
        toRelease.reversed().forEach { runCatching { it() } }
        toRelease.clear()
    }

    private fun connect(session: MediaSession, appController: Boolean): MediaController {
        val hints = Bundle().apply { if (appController) putBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, true) }
        val future = MediaController.Builder(context, session.token)
            .setConnectionHints(hints)
            .buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 50) idleMainLooper()
        val controller = future.get(5, TimeUnit.SECONDS)
        toRelease += { controller.release() }
        idleMainLooper()
        return controller
    }

    // ── controllerForCurrentRequest through ForwardingSimpleBasePlayer.handle* ──────────────────

    private class ProbeFacade(delegate: Player, val sessionRef: () -> MediaSession?) : ForwardingSimpleBasePlayer(delegate) {
        val seen = mutableListOf<String>()

        private fun who(): String {
            val controller = sessionRef()?.controllerForCurrentRequest ?: return "none"
            return if (controller.connectionHints.getBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, false)) "app" else "external"
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            seen += "setPlayWhenReady($playWhenReady):${who()}"
            return super.handleSetPlayWhenReady(playWhenReady)
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            seen += "seek:${who()}"
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }

        override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
            seen += "repeat:${who()}"
            return super.handleSetRepeatMode(repeatMode)
        }
    }

    @Test fun controllerForCurrentRequestIsUsableInsideTheHandlePathAndDistinguishesAppFromExternal() {
        val physical = ScriptedPlayer("P", titles = listOf("A", "B", "C"), index = 1, positionMs = 5_000L)
        var session: MediaSession? = null
        val probe = ProbeFacade(physical) { session }
        session = MediaSession.Builder(context, probe).setId("cf2m2-probe-${sessionCounter++}").build()
        toRelease += { session.release() }

        val app = connect(session, appController = true)
        val external = connect(session, appController = false)

        app.pause(); idleMainLooper()
        external.play(); idleMainLooper()
        app.seekTo(1, 9_000L); idleMainLooper()
        external.seekTo(1, 8_000L); idleMainLooper()
        external.repeatMode = Player.REPEAT_MODE_ALL; idleMainLooper()

        assertEquals(
            listOf(
                "setPlayWhenReady(false):app",
                "setPlayWhenReady(true):external",
                "seek:app",
                "seek:external",
                "repeat:external",
            ),
            probe.seen,
        )
        // and it is the REAL controller info, with the app hint intact, not merely non-null
        assertEquals(
            listOf("P.setPlayWhenReady(false)", "P.setPlayWhenReady(true)", "P.seek(1,9000)", "P.seek(1,8000)", "P.setRepeatMode(2)"),
            physical.commands,
        )
    }

    // ── full WavDrop policy chain parity: with vs without the façade ────────────────────────────

    private inner class Chain(useFacade: Boolean) {
        val physical = ScriptedPlayer("P", titles = listOf("A", "B", "C"), index = 1, positionMs = 5_000L)
        val facade: SessionFacade? = if (useFacade) SessionFacade(physical) else null
        val calls = mutableListOf<String>()
        var session: MediaSession? = null
        val policy = PreviousBehaviorPlayer(
            player = facade ?: physical,
            thresholdProvider = { 3_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            onExternalTransport = { calls += "externalTransport" },
            hydrateForPlay = { PlayerHydrationResult.Hydrated },
            songsProvider = { emptyList() },
            logResume = {},
            sessionProvider = { session },
            onExplicitPause = { calls += "explicitPause" },
            onExplicitSeek = { calls += "explicitSeek" },
            onExplicitNavigation = { calls += "explicitNavigation" },
            onExplicitRepeatChange = { calls += "explicitRepeat" },
        )

        init {
            session = MediaSession.Builder(context, policy).setId("cf2m2-chain-${sessionCounter++}").build()
            toRelease += { session!!.release() }
        }
    }

    private fun runScript(chain: Chain, appController: Boolean): Pair<List<String>, List<String>> {
        val controller = connect(chain.session!!, appController)
        controller.pause(); idleMainLooper()
        controller.play(); idleMainLooper()
        controller.seekTo(7_000L); idleMainLooper()           // same-track seek (position 7000 afterwards)
        controller.seekToNext(); idleMainLooper()
        controller.seekToPrevious(); idleMainLooper()         // position (7000?) > 3000 threshold -> restart current
        chain.physical.mutate { setContentPositionMs(1_000L) }
        idleMainLooper()
        controller.seekToPrevious(); idleMainLooper()         // within threshold -> previous media item
        controller.repeatMode = Player.REPEAT_MODE_ALL; idleMainLooper()
        return chain.calls.toList() to chain.physical.commands.toList()
    }

    @Test fun policyChainBehavesIdenticallyWithAndWithoutTheFacadeForAnExternalController() {
        val plain = runScript(Chain(useFacade = false), appController = false)
        val facade = runScript(Chain(useFacade = true), appController = false)
        assertEquals("policy callbacks", plain.first, facade.first)
        assertEquals("physical commands", plain.second, facade.second)
        // the behavioural distinction WavDrop actually relies on: an EXTERNAL controller is explicit user intent
        assertEquals(
            listOf(
                "externalTransport", "explicitPause",   // pause
                "externalTransport",                    // play
                "explicitSeek",                         // same-track seek
                "explicitNavigation",                   // next
                "explicitNavigation",                   // previous (restart)
                "explicitNavigation",                   // previous (previous item)
                "explicitRepeat",                       // repeat
            ),
            facade.first,
        )
    }

    @Test fun policyChainBehavesIdenticallyWithAndWithoutTheFacadeForTheAppController() {
        val plain = runScript(Chain(useFacade = false), appController = true)
        val facade = runScript(Chain(useFacade = true), appController = true)
        assertEquals("policy callbacks", plain.first, facade.first)
        assertEquals("physical commands", plain.second, facade.second)
        // the app-marked controller is never explicit external transport; only the unconditional pause hook fires
        assertEquals(listOf("explicitPause"), facade.first)
    }

    @Test fun previousSemanticsAndMaxSeekToPreviousSurviveTheFacade() {
        val chain = Chain(useFacade = true)
        assertEquals(3_000L, chain.policy.maxSeekToPreviousPosition)
        val controller = connect(chain.session!!, appController = true)
        assertEquals(3_000L, controller.maxSeekToPreviousPosition) // the value the session advertises to controllers
        // position 5000 > 3000: PREVIOUS restarts the current item
        controller.seekToPrevious(); idleMainLooper()
        assertEquals("P.seek(1,0)", chain.physical.commands.last())
        // within the threshold: PREVIOUS goes to the previous media item
        chain.physical.mutate { setContentPositionMs(1_000L) }
        idleMainLooper()
        controller.seekToPrevious(); idleMainLooper()
        assertEquals(0, chain.physical.currentMediaItemIndex)
    }

    // ── session and controller identity across a delegate swap ──────────────────────────────────

    @Test fun sessionAndControllerIdentityAreUnchangedByADelegateSwap() {
        val chain = Chain(useFacade = true)
        val facade = chain.facade!!
        val session = chain.session!!
        val tokenBefore = session.token
        val controller = connect(session, appController = true)
        val rec = EventRecorder().also { controller.addListener(it) }
        idleMainLooper()
        assertSame(chain.policy, session.player)
        assertEquals("B", controller.currentMediaItem?.mediaId)

        val p2 = ScriptedPlayer("P2", titles = listOf("A", "B", "C"), index = 2, positionMs = 1_000L)
        facade.replaceDelegate(p2, presentAsAutoTransition = true)
        idleMainLooper()
        idleMainLooper()

        assertSame("same session object keeps the same player", chain.policy, session.player)
        assertEquals(tokenBefore, session.token)
        assertTrue(controller.isConnected)
        assertEquals("C", controller.currentMediaItem?.mediaId)
        assertEquals(1_000L, controller.currentPosition)
        assertTrue("isPlaying never flickered at the controller", controller.isPlaying)
        assertTrue(rec.events.none { it.startsWith("isPlaying") })
        assertEquals(1, rec.events.count { it.startsWith("transition") })
        // raw controller-side event sequence for a playing P1(B) -> playing P2(C) swap presented as AUTO
        assertEquals(
            listOf("discontinuity(1@5000->2@1000,AUTO_TRANSITION)", "transition(C,AUTO)", "metadata(C)"),
            rec.events,
        )

        // commands from the controller now reach ONLY the new physical player
        chain.physical.commands.clear()
        controller.pause(); idleMainLooper()
        controller.seekTo(2, 3_000L); idleMainLooper()
        assertEquals(emptyList<String>(), chain.physical.commands)
        assertEquals(listOf("P2.setPlayWhenReady(false)", "P2.seek(2,3000)"), p2.commands)
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
