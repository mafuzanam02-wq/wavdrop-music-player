package com.launchpoint.wavdrop.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * CF-2M3: gate-bound topology. Real ExoPlayers (Robolectric) prove the shipping path stays single-player and the gated path
 * builds exactly two, sharing one audio-session id assigned before any prepare. Source scans prove production never swaps
 * roles, prepares NEXT or builds a third (CF-2L) player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackAssemblyTest {

    private class Construction(val handleFocus: Boolean, val handleNoisy: Boolean, val player: ExoPlayer)

    private fun counting(constructed: MutableList<Construction>) = PhysicalPlayerFactory { attributes, focus, noisy ->
        defaultPhysicalPlayerFactory(RuntimeEnvironment.getApplication()).create(attributes, focus, noisy)
            .also { constructed += Construction(focus, noisy, it) }
    }

    @Test fun rolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
        assertEquals(PlaybackTopology.SINGLE_PLAYER, PlaybackTopology.forGate(CrossfadeRolloutPolicy.RUNTIME_ENABLED))
    }

    @Test fun shippingGateFalseBuildsOneStandalonePlayerWithItsOwnFocusAndNoisyHandling() {
        val constructed = mutableListOf<Construction>()
        val assembly = assemblePlayback(RuntimeEnvironment.getApplication(), false, AudioAttributes.DEFAULT, counting(constructed))
        assertEquals(1, constructed.size)
        assertTrue(constructed[0].handleFocus)
        assertTrue(constructed[0].handleNoisy)
        assertNull(assembly.engine)
        assertSame("shipping chain has no façade: ExoPlayer -> PreviousBehaviorPlayer", assembly.primaryPlayer, assembly.logicalPlayer)
        assertEquals(1, assembly.topology.physicalPlayerCount)
        assembly.primaryPlayer.release()
    }

    @Test fun gatedPathBuildsExactlyTwoPlayersNeitherHandlingFocusOrNoisy() {
        val constructed = mutableListOf<Construction>()
        val assembly = assemblePlayback(
            RuntimeEnvironment.getApplication(), true, AudioAttributes.DEFAULT, counting(constructed), { SHARED_SESSION_ID },
        )
        assertEquals("exactly two physical players (no third CF-2L secondary)", 2, constructed.size)
        assertTrue(constructed.none { it.handleFocus || it.handleNoisy })
        val engine = assembly.engine!!
        assertEquals(setOf(constructed[0].player, constructed[1].player), setOf(engine.currentPlayer, engine.nextPlayer))
        assertSame(engine.facade, assembly.logicalPlayer)
        assertSame(engine.currentPlayer, assembly.primaryPlayer)
        assertEquals(2, assembly.topology.physicalPlayerCount)
        engine.release()
    }

    @Test fun gatedPathAssignsOneSessionIdToBothRealPlayersBeforeUse() {
        val assembly = assemblePlayback(
            RuntimeEnvironment.getApplication(), true, AudioAttributes.DEFAULT, sessionIdProvider = { SHARED_SESSION_ID },
        )
        val engine = assembly.engine!!
        assertEquals(SHARED_SESSION_ID, engine.currentPlayer.audioSessionId)
        assertEquals(SHARED_SESSION_ID, engine.nextPlayer.audioSessionId)
        assertEquals(SHARED_SESSION_ID, engine.audioSessionId)
        assertEquals(SHARED_SESSION_ID, engine.facade.audioSessionId)
        // NEXT really is inert on a real ExoPlayer.
        assertEquals(0, engine.nextPlayer.mediaItemCount)
        assertEquals(Player.STATE_IDLE, engine.nextPlayer.playbackState)
        assertFalse(engine.nextPlayer.playWhenReady)
        assertEquals(1f, engine.nextPlayer.volume, 0f)
        engine.release()
    }

    @Test fun neitherTopologyConstructsTheLegacyCrossfadeGraph() {
        assertFalse(PlaybackTopology.SINGLE_PLAYER.constructsLegacyCrossfadeGraph)
        assertFalse(PlaybackTopology.TWO_SLOT_ENGINE.constructsLegacyCrossfadeGraph)
        // The legacy builder is only reachable through the topology flag in the service.
        val service = source("PlaybackService.kt")
        val guard = service.indexOf("assembly.topology.constructsLegacyCrossfadeGraph")
        val build = service.indexOf("createCrossfadeProductionGraph(")
        assertTrue(guard in 0 until build)
        assertEquals("the graph builder is called exactly once, behind the guard", 1, Regex("createCrossfadeProductionGraph\\(").findAll(service).count())
    }

    @Test fun realExoPlayerEngineSessionStaysUsableThroughTheFacade() {
        val assembly = assemblePlayback(RuntimeEnvironment.getApplication(), true, AudioAttributes.DEFAULT, sessionIdProvider = { SHARED_SESSION_ID })
        val engine = assembly.engine!!
        val facade = engine.facade
        facade.repeatMode = Player.REPEAT_MODE_ALL
        idleMainLooper()
        assertEquals(Player.REPEAT_MODE_ALL, engine.currentPlayer.repeatMode)
        assertEquals(Player.REPEAT_MODE_OFF, engine.nextPlayer.repeatMode)
        assertNotNull(facade.currentTimeline)
        engine.release()
    }

    // ── production never swaps, prepares NEXT, or builds a third player ─────────────────────────────────────────────────

    private fun source(name: String): String =
        File("src/main/kotlin/com/launchpoint/wavdrop/playback/$name").also { assertTrue("missing ${it.absolutePath}", it.exists()) }.readText()

    @Test fun productionNeverSwapsRolesOrReplacesTheFacadeDelegate() {
        val dir = File("src/main/kotlin")
        val offenders = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "PlayerEngine.kt" && it.name != "SessionFacade.kt" }
            .filter { val t = it.readText(); t.contains("swapRolesForTest") || t.contains("replaceDelegate(") || t.contains("swapRoles(") && it.name != "PlayerSlots.kt" }
            .map { it.name }.toList()
        assertTrue("production code must not swap roles/delegates in CF-2M3: $offenders", offenders.isEmpty())
        assertTrue(source("PlayerEngine.kt").lines().filter { it.contains("swapRolesForTest()") && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") }.size == 1)
    }

    @Test fun productionNeverPreparesOrLoadsNext() {
        val forbidden = listOf(".prepare(", "setMediaItem", "addMediaItem", "setMediaSource", "addMediaSource", ".play()", ".seekTo(", "setPlaybackParameters")
        for (file in listOf("PlayerEngine.kt", "PlaybackAssembly.kt", "PlayerSlots.kt")) {
            val code = source(file).lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")
            forbidden.forEach { assertFalse("$file must not contain `$it` in CF-2M3", code.contains(it)) }
        }
    }
}
