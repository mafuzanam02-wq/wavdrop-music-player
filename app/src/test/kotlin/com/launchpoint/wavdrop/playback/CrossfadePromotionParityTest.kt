package com.launchpoint.wavdrop.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.launchpoint.wavdrop.ui.widget.WidgetPlaybackSnapshot
import java.io.File
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
 * CF-2M5 logical parity and architecture guards. Through a REAL MediaSession and MediaController (and the real widget listener)
 * a promotion + overlap looks to every logical consumer exactly like ONE native gapless AUTO transition: same event stream, no
 * second transition at fade end, no play/pause edge, stable session and token. Source guards pin the service's role-awareness and
 * that the production M path never seeks, hands off, swaps via the test seam, adds a player or adds a rollout flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadePromotionParityTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val toRelease = mutableListOf<() -> Unit>()
    private var sessionCounter = 0

    @After fun tearDown() {
        toRelease.reversed().forEach { runCatching { it() } }
        toRelease.clear()
    }

    private fun session(player: Player): MediaSession =
        MediaSession.Builder(context, player).setId("cf2m5-${sessionCounter++}").build().also { s -> toRelease += { s.release() } }

    private fun connect(session: MediaSession): MediaController {
        val future = MediaController.Builder(context, session.token).setConnectionHints(Bundle()).buildAsync()
        var guard = 0
        while (!future.isDone && guard++ < 50) idleMainLooper()
        val controller = future.get(5, TimeUnit.SECONDS)
        toRelease += { controller.release() }
        idleMainLooper()
        return controller
    }

    private val titles = listOf("A", "B", "C", "D")

    private class Sink : WidgetStateSink {
        val calls = mutableListOf<String>()
        override suspend fun save(snapshot: WidgetPlaybackSnapshot) { calls += "save(${snapshot.title},playing=${snapshot.isPlaying},active=${snapshot.hasActiveMedia})" }
        override suspend fun updateIsPlaying(isPlaying: Boolean) { calls += "isPlaying($isPlaying)" }
        override suspend fun clear() { calls += "clear" }
        override fun requestUpdate() { calls += "update" }
    }

    /** The reference: ONE native gapless AUTO transition from index 1 to 2 on a bare façade. */
    private fun nativeBaseline(): Pair<List<String>, List<String>> {
        val p = ScriptedPlayer("N", titles = titles, index = 1, playing = true, state = Player.STATE_READY)
        val facade = SessionFacade(p)
        val session = session(facade)
        val controller = connect(session)
        val sink = Sink()
        facade.addListener(WidgetPlaybackStateListener(facade, CoroutineScope(Dispatchers.Unconfined), sink))
        idleMainLooper()
        val events = EventRecorder().also { controller.addListener(it) }
        p.mutate { setCurrentMediaItemIndex(2).setContentPositionMs(0L).setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0L) }
        idleMainLooper()
        return events.events.toList() to sink.calls.toList()
    }

    @Test fun promotionAndOverlapLookLikeOneNativeGaplessTransitionToEveryLogicalConsumer() {
        val (nativeEvents, nativeWidget) = nativeBaseline()

        val rig = PromotionRig(titles = titles, from = 1).playAndPrepare()
        val facade = rig.f.facade
        val session = session(facade)
        val tokenBefore = session.token
        val controller = connect(session)
        val sink = Sink()
        facade.addListener(WidgetPlaybackStateListener(facade, CoroutineScope(Dispatchers.Unconfined), sink))
        idleMainLooper()
        val events = EventRecorder().also { controller.addListener(it) }
        sink.calls.clear()

        assertTrue(rig.promote() is PromotionStartResult.Promoted)
        rig.engine.stripRetiringTail()
        idleMainLooper()
        val atPromotion = events.events.toList()
        // The whole overlap: gains, then retirement. None of it may produce another logical event.
        for (p in listOf(0.2f, 0.5f, 0.8f)) {
            val g = CrossfadeGainCurve.equalPower(p)
            rig.engine.setFadeGains(g.outgoing, g.incoming)
            idleMainLooper()
        }
        rig.engine.setFadeGains(0f, 1f)
        rig.engine.finishRetirement()
        idleMainLooper()

        assertEquals("promotion == one native AUTO transition at the controller", nativeEvents, atPromotion)
        assertEquals("nothing further at fade end or retirement", atPromotion, events.events.toList())
        assertEquals("one B selection (transition) for stats/persistence", 1, events.events.count { it.startsWith("transition(") })
        assertEquals(1, events.events.count { it.startsWith("discontinuity(") })
        assertFalse("no fake pause/play edge: ${events.events}", events.events.any { it.startsWith("isPlaying") || it.startsWith("playWhenReady") })
        assertTrue(controller.isPlaying)
        assertEquals("NowPlaying index is the exact target", 2, controller.currentMediaItemIndex)
        assertEquals("C", controller.currentMediaItem?.mediaId)
        assertEquals(titles.size, controller.mediaItemCount)
        assertEquals("the widget follows B exactly like the native transition", nativeWidget, sink.calls)
        assertSame("same session player object", facade, session.player)
        assertEquals("same session token", tokenBefore, session.token)
    }

    @Test fun aDuplicateBOccurrenceStaysPositional() {
        val dup = listOf("A", "B", "A", "B", "A")
        val rig = PromotionRig(titles = dup, from = 2).playAndPrepare() // from the later A to the later B (index 3)
        val facade = rig.f.facade
        val events = EventRecorder().also { facade.addListener(it) }
        assertTrue(rig.promote() is PromotionStartResult.Promoted)
        assertEquals(3, facade.currentMediaItemIndex)
        assertEquals("B", facade.currentMediaItem?.mediaId)
        assertEquals(1, events.events.count { it.startsWith("transition(") })
        assertTrue(events.events.single { it.startsWith("discontinuity(") }.contains("2@0->3@0"))
    }

    // ── source guards: service role-awareness and the production M path ─────────────────────────────────────────────────

    private fun source(name: String) = File("src/main/kotlin/com/launchpoint/wavdrop/playback/$name").readText()
    private fun code(name: String) = source(name).lines()
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")

    @Test fun theServicePhysicalObserverFollowsTheLogicalCurrentNotTheInitialPlayer() {
        val s = source("PlaybackService.kt")
        assertFalse("a permanent listener on the initial primary", s.contains("player.addListener("))
        assertTrue(s.contains("assembly.addCurrentPlayerListener("))
        val block = s.substringAfter("// PHYSICAL / crossfade observer").substringBefore("enhancementController = AudioEnhancementController(")
        assertTrue("observer block found", block.length > 500)
        val stale = Regex("\\bplayer\\.").findAll(block).map { it.value }.toList()
        assertTrue("the observer reads the initial `player` where the logical current is meant: $stale", stale.isEmpty())
        assertTrue(block.contains("assembly.currentPlayer"))
    }

    @Test fun onlyEngineTopologyBuildsThePromotionRuntimeAndItsDurationPolicyIsWired() {
        val s = source("PlaybackService.kt")
        assertEquals(1, Regex("CrossfadePromotionRuntime\\(").findAll(s).count())
        assertTrue(s.contains("assembly.engine?.let { engine ->"))
        assertTrue(s.contains("applyPromotionConfiguredDurationChange(previous, duration, promotionRuntime)"))
        assertTrue(s.contains("promotionRuntime?.cancel(reason)"))
        assertTrue("service teardown closes it before the engine releases the players", s.indexOf("promotionRuntime?.close()") in 0 until s.indexOf("engine?.release()"))
    }

    @Test fun productionMPathNeverSeeksHandsOffReconcilesOrUsesTheTestSeam() {
        for (file in listOf("PlayerEngine.kt", "CrossfadePromotionRuntime.kt")) {
            val c = code(file)
            listOf(".seekTo(", "seekToDefaultPosition", "seekToNext", ".prepare()", "setMediaItem(", "addMediaItem").forEach {
                assertFalse("$file must not contain `$it` in the promotion path", c.contains(it))
            }
        }
        val runtime = code("CrossfadePromotionRuntime.kt")
        assertFalse(runtime.contains("swapRolesForTest"))
        assertFalse(runtime.contains("reconcile"))
        assertFalse(runtime.contains("HandoffPending"))
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "PlayerEngine.kt" }
            .filter { it.readText().lines().any { l -> l.contains("swapRolesForTest") && !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") } }
            .map { it.name }.toList()
        assertTrue("$offenders", offenders.isEmpty())
    }

    @Test fun noThirdPlayerAndNoNewRolloutFlag() {
        val builders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ExoPlayer.Builder(") }.map { it.name }.toSet()
        assertEquals("only the assembly (two physicals) and the dormant CF-2L secondary construct ExoPlayers", setOf("PlaybackAssembly.kt", "CrossfadeSecondaryPlayer.kt"), builders)
        val policy = source("CrossfadeRolloutPolicy.kt")
        assertEquals("exactly one rollout constant", 1, Regex("const val").findAll(policy).count())
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
        assertFalse(code("CrossfadePromotionRuntime.kt").contains("RUNTIME_ENABLED"))
    }

    @Test fun onlyTheComposerWritesPhysicalVolumeInTheEngineTopology() {
        val writers = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { l -> (l.contains(".volume = ") || l.contains("setVolume(")) && !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") } }
            .map { it.name }.toSet()
        assertEquals("PlayerEngine is the only writer in the M topology; the others are the dormant CF-2L paths", setOf("PlayerEngine.kt", "CrossfadeSecondaryPlayer.kt", "PlaybackService.kt"), writers - setOf("PlayerControllerVolume.kt"))
        assertEquals(1, Regex("[.]volume = ").findAll(code("PlayerEngine.kt")).count())
        val service = source("PlaybackService.kt")
        assertTrue("the service volume write lives only in the legacy graph block", service.indexOf("player.volume = gain") > service.indexOf("constructsLegacyCrossfadeGraph"))
    }

    @Test fun gateFalseTopologyIsUnchangedAndHasNoPromotionMachinery() {
        val assembly = assemblePlayback(context, rolloutEnabled = false, audioAttributes = androidx.media3.common.AudioAttributes.DEFAULT)
        assertEquals(null, assembly.engine)
        assertSame(assembly.primaryPlayer, assembly.currentPlayer)
        assertEquals(1, assembly.topology.physicalPlayerCount)
        assembly.primaryPlayer.release()
    }
}
