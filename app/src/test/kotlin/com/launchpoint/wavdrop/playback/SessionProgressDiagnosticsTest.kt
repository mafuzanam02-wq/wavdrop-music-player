package com.launchpoint.wavdrop.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** LS-1: the DEBUG-only session-progress evidence is bounded, read-only and carries no media identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SessionProgressDiagnosticsTest {

    private val rigs = mutableListOf<SessionProgressRig>()
    private val diags = mutableListOf<SessionProgressDiagnostics>()
    @After fun tearDown() { diags.forEach { runCatching { it.close() } }; rigs.forEach { runCatching { it.release() } } }

    private class Harness(val rig: SessionProgressRig, val lines: MutableList<String>, val diagnostics: SessionProgressDiagnostics)

    private fun harness(): Harness {
        val rig = SessionProgressRig().also { rigs += it }
        val lines = mutableListOf<String>()
        val d = SessionProgressDiagnostics(
            sessionPlayer = rig.policy,
            physicalCurrent = { rig.engine.currentPlayer },
            promotionActive = { rig.engine.promotionActive },
            elapsedRealtimeMs = { SystemClock.elapsedRealtime() },
            log = { lines += it },
        ).also { diags += it }
        d.start()
        return Harness(rig, lines, d)
    }

    private fun Harness.field(line: String, name: String): String =
        Regex("""\b$name=(\S+)""").find(line)!!.groupValues[1]

    // ── pure policy ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `event lines are rate limited and the first one always passes`() {
        assertTrue(SessionProgressDiagnostics.shouldEmitEventLine(Long.MIN_VALUE, 0L))
        assertFalse(SessionProgressDiagnostics.shouldEmitEventLine(1_000L, 1_100L))
        assertTrue(SessionProgressDiagnostics.shouldEmitEventLine(1_000L, 1_000L + SessionProgressDiagnostics.MIN_EVENT_GAP_MS))
        assertTrue("the heartbeat is slow and bounded", SessionProgressDiagnostics.HEARTBEAT_MS >= 5_000L)
    }

    @Test fun `the line format has the required fields and no media identity`() {
        val line = formatSessionProgressLine("transition", 123L, 4_000L, 4_001L, 3, true, true, 2, false)
        assertEquals("reason=transition t=123 physical=4000 session=4001 state=3 playWhenReady=true isPlaying=true index=2 promotion=false", line)
        assertEquals("WavdropSessionProgress", SessionProgressDiagnostics.TAG)
    }

    // ── behaviour against the real chain ───────────────────────────────────────────────────────────────────────────────

    @Test fun `steady playback is silent between state changes and the heartbeat logs only while playing`() {
        val h = harness()
        h.rig.command { play() }
        h.rig.advance(500)
        val afterStart = h.lines.size
        h.rig.advance(8_000)
        assertEquals("no per-tick lines during steady playback: ${h.lines}", afterStart, h.lines.size)

        h.diagnostics.heartbeat()
        val beat = h.lines.last()
        assertTrue(beat, beat.startsWith("reason=heartbeat"))
        assertEquals("physical and session positions agree", h.field(beat, "physical"), h.field(beat, "session"))
        assertEquals("true", h.field(beat, "isPlaying"))

        h.rig.command { pause() }
        val paused = h.lines.size
        h.diagnostics.heartbeat()
        assertEquals("no heartbeat line while paused", paused, h.lines.size)
    }

    @Test fun `play pause and seek each produce bounded evidence`() {
        val h = harness()
        h.rig.command { play() }
        h.rig.advance(1_000)
        h.rig.command { pause() }
        h.rig.advance(1_000)
        h.rig.command { seekTo(60_000L) }
        h.rig.advance(1_000)
        val reasons = h.lines.map { h.field(it, "reason") }
        assertTrue(reasons.toString(), reasons.containsAll(listOf("start", "isPlaying")))
        assertTrue("bounded: ${h.lines.size} lines for three commands", h.lines.size <= 12)
        assertTrue(h.lines.all { it.contains("physical=") && it.contains("session=") && it.contains("promotion=") })
    }

    @Test fun `promotion is reported while the overlap is active and not after it`() {
        val h = harness()
        h.rig.command { play() }
        h.rig.advance(2_000)
        val key = h.rig.prepareNext(0)
        h.rig.promote(key)
        h.rig.advance(300)
        h.diagnostics.heartbeat()
        assertEquals("true", h.field(h.lines.last(), "promotion"))
        assertEquals("1", h.field(h.lines.last(), "index"))
        h.rig.finishOverlap()
        h.rig.advance(300)
        h.diagnostics.heartbeat()
        assertEquals("false", h.field(h.lines.last(), "promotion"))
        assertTrue("physical CURRENT is the promoted player and it matches the session", h.field(h.lines.last(), "physical") == h.field(h.lines.last(), "session"))
    }

    @Test fun `after close nothing is logged and the listener is gone`() {
        val h = harness()
        h.diagnostics.close()
        val n = h.lines.size
        h.rig.command { play() }
        h.rig.advance(1_000)
        h.diagnostics.heartbeat()
        assertEquals(n, h.lines.size)
    }

    // ── it is read-only and DEBUG-gated ─────────────────────────────────────────────────────────────────────────────────

    private fun src(path: String) = File("src/main/kotlin/com/launchpoint/wavdrop/$path").readText()
    private fun code(path: String) = src(path).lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n")

    @Test fun `the diagnostics only read the player and the service builds them only in DEBUG`() {
        val d = code("playback/SessionProgressDiagnostics.kt")
        for (forbidden in listOf("seekTo", ".play()", ".pause()", "invalidate", "setMediaItem", "setCustomLayout", "setPlayWhenReady", ".playWhenReady =", "Handler", "postDelayed", "mediaMetadata", "title", "uri", "Uri", "mediaId", "path")) {
            assertFalse("diagnostics must not use $forbidden", d.contains(forbidden))
        }
        val service = code("playback/PlaybackService.kt")
        val gate = service.substringAfter("SessionProgressDiagnostics(").substringBefore("diagnostics.start()")
        assertTrue(gate.isNotEmpty())
        val before = service.substringBefore("SessionProgressDiagnostics(").takeLast(200)
        assertTrue("created inside a BuildConfig.DEBUG block: $before", before.contains("if (BuildConfig.DEBUG)"))
        assertTrue(service.contains("sessionProgressDiagnostics?.close()"))
        assertNull("heartbeat is the only loop and it is inside the DEBUG block", Regex("""while \(true\)""").findAll(service).drop(1).firstOrNull())
    }
}
