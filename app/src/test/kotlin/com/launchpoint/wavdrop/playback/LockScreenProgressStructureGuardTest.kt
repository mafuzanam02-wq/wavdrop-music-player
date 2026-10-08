package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LS-1: structural facts that keep system-surface progress owned by the player itself. The behavioural proof is
 * [LockScreenProgressRealMedia3Test]; these guards stop a later change from quietly re-introducing a second position
 * authority, a UI-ticker dependency, a speculative timer, or a frozen position snapshot in the façade.
 */
class LockScreenProgressStructureGuardTest {

    private fun src(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText()
    private fun code(path: String) = src(path).lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")

    @Test fun `the facade never rewrites a position field so Media3 live position suppliers are preserved`() {
        val facade = code("playback/SessionFacade.kt")
        for (forbidden in listOf("setContentPositionMs(", "setAdPositionMs(", "setContentBufferedPositionMs(", "setAdBufferedPositionMs(", "setTotalBufferedDurationMs(")) {
            assertFalse("SessionFacade must not call $forbidden (it would replace the live supplier)", facade.contains(forbidden))
        }
        val getState = facade.substringAfter("override fun getState()").substringBefore("private fun withStableTimelineIdentity")
        val builderCalls = Regex("""\.set[A-Za-z]+\(""").findAll(getState).map { it.value }.toSet()
        assertEquals(
            "getState overlays exactly the logical play state, the alias playlist and the one-shot discontinuity",
            setOf(".setPlayWhenReady(", ".setPlaybackSuppressionReason(", ".setPositionDiscontinuity("),
            builderCalls,
        )
    }

    @Test fun `the one-shot promotion pin is always cleared and nothing but the logical owner invalidates state`() {
        val facade = code("playback/SessionFacade.kt")
        val replace = facade.substringAfter("fun replaceDelegate").substringBefore("var commandBoundary")
        assertTrue(replace.contains("finally {") && replace.substringAfter("finally {").contains("pinnedAutoTransitionPositionMs = null"))
        assertEquals("invalidateState is called only by bindPlayWhenReadyOwner and invalidateLogicalState", 2, Regex("""invalidateState\(\)""").findAll(facade).count())
    }

    @Test fun `no session-side component depends on the UI position ticker or runs its own timer`() {
        for (file in listOf("SessionFacade.kt", "PlayerEngine.kt", "PreviousBehaviorPlayer.kt")) {
            val c = code("playback/$file")
            assertFalse("$file must not reference the UI position ticker", c.contains("PositionTicker") || c.contains("positionTicker"))
            assertFalse("$file must not post delayed work", c.contains("postDelayed") || c.contains("Handler("))
            if (file != "PreviousBehaviorPlayer.kt") assertFalse("$file must not keep an elapsed-time counter", c.contains("elapsedRealtime"))
        }
        // the only clock read in the policy wrapper is the timestamp inside its debug transport log line
        val previous = code("playback/PreviousBehaviorPlayer.kt").lines().filter { it.contains("elapsedRealtime") }
        assertEquals(previous.toString(), 1, previous.size)
        assertTrue(previous.single().contains("t="))
        val service = code("playback/PlaybackService.kt")
        assertFalse(service.contains("PositionTicker") || service.contains("positionTicker"))
        assertFalse(service.contains("postDelayed"))
        assertFalse("the service never forces a session state refresh", service.contains("invalidateState") || service.contains("invalidateLogicalState"))
    }

    @Test fun `the custom layout is rebuilt only for the notification setting or shuffle and repeat, never for position`() {
        val service = code("playback/PlaybackService.kt")
        assertEquals("a single call site rebuilds the layout", 1, Regex("""setCustomLayout\(""").findAll(service).count())
        val block = service.substringAfter("combine(").substringBefore("mediaSession?.setCustomLayout")
        assertTrue(block.contains("notificationControlsSetting"))
        assertTrue(block.contains("it.shuffleEnabled to it.repeatMode"))
        assertTrue(block.contains("distinctUntilChanged()"))
        assertFalse(block.contains("position", ignoreCase = true))
    }

    @Test fun `the session is built on the stable session-facing player and the physical delegate is never given to it`() {
        val service = code("playback/PlaybackService.kt")
        assertTrue(service.contains("MediaLibrarySession.Builder(this, sessionPlayer,"))
        assertTrue(service.contains("player = logicalPlayer"))
        val assembly = code("playback/PlaybackAssembly.kt")
        assertTrue("the engine topology hands the stable facade, not a physical player, to the session chain", assembly.contains("PlaybackAssembly(topology, engine.currentPlayer, engine, engine.facade)"))
    }

    @Test fun `the production media item carries no fabricated duration`() {
        val item = code("playback/PlaybackMediaItem.kt")
        assertFalse("duration comes from the player timeline, never from app metadata", item.contains("setDurationMs") || item.contains("durationMs"))
    }
}
