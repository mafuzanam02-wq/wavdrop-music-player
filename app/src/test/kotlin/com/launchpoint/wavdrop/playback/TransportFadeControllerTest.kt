package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic virtual time: a monotonic clock and a scheduler that runs due tasks when time is advanced. */
internal class VirtualFadeTime : CrossfadeMonotonicClock, CrossfadeTimingScheduler {
    var now = 0L
    private class Task(val due: Long, val block: () -> Unit, var cancelled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    val everPosted = mutableListOf<() -> Unit>()

    override fun nowMs(): Long = now
    override fun postDelayed(delayMs: Long, block: () -> Unit) {
        tasks += Task(now + delayMs.coerceAtLeast(0L), block); everPosted += block
    }
    override fun cancelAll() { tasks.forEach { it.cancelled = true }; tasks.clear() }
    val pendingCount: Int get() = tasks.size

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled }.minByOrNull { it.due } ?: break
            if (next.due > end) break
            tasks.remove(next)
            now = maxOf(now, next.due)
            next.block()
        }
        now = end
    }
}

class TransportFadeControllerTest {
    private val time = VirtualFadeTime()
    private val gains = mutableListOf<Float>()
    private var config = TransportFadeConfig(enabled = true, durationMs = 300L)
    private val controller = TransportFadeController(
        sink = TransportGainSink { gains += it },
        clock = time,
        scheduler = time,
        config = { config },
    )
    private val log = mutableListOf<String>()
    private fun log(what: String) { log += "$what@${time.now}" }

    // 1. disabled = exactly today's behavior -----------------------------------------------------------------
    @Test fun `disabled play pause and navigation run immediately with no gain writes`() {
        config = TransportFadeConfig(false, 300L)
        controller.play(isPlaying = false) { log("play") }
        controller.pause(isPlaying = true) { log("pause") }
        controller.navigate(isPlaying = true) { log("nav"); true }
        assertEquals(listOf("play@0", "pause@0", "nav@0"), log)
        assertTrue(gains.isEmpty())
        assertEquals(0, time.pendingCount)
    }

    // 2/4. Play: starts at zero gain, no artificial pre-silence, ramps to one over the duration -----------------
    @Test fun `play starts immediately at zero gain then ramps to one over the duration`() {
        for (d in listOf(100L, 300L, 800L)) {
            val t = VirtualFadeTime(); val g = mutableListOf<Float>(); val l = mutableListOf<String>()
            val c = TransportFadeController({ g += it }, t, t) { TransportFadeConfig(true, d) }
            c.play(isPlaying = false) { l += "play@${t.now}:gain=${g.lastOrNull()}" }
            assertEquals("d=$d play issued with no delay at zero gain", listOf("play@0:gain=0.0"), l)
            t.advance(d / 2)
            assertTrue("d=$d midway", g.last() in 0.3f..0.7f)
            t.advance(d - d / 2)
            assertEquals("d=$d full gain at end", 1f, g.last(), 0f)
            assertEquals(0, t.pendingCount)
        }
    }

    @Test fun `play while already playing and not fading does not dip the gain`() {
        controller.play(isPlaying = true) { log("play") }
        assertEquals(listOf("play@0"), log)
        assertTrue(gains.isEmpty())
    }

    // 5. Pause: ramps down first, pauses at the end, restores baseline gain --------------------------------------
    @Test fun `pause fades out then pauses then restores baseline gain`() {
        controller.pause(isPlaying = true) { log("pause") }
        assertTrue(log.isEmpty())
        time.advance(299); assertTrue(log.isEmpty())
        time.advance(1)
        assertEquals(listOf("pause@300"), log)
        assertEquals(1f, gains.last(), 0f) // baseline restored for the next play
        assertTrue(gains.any { it == 0f }) // reached silence before pausing
    }

    @Test fun `pause on a player that is not playing is immediate`() {
        controller.pause(isPlaying = false) { log("pause") }
        assertEquals(listOf("pause@0"), log)
        assertTrue(gains.isEmpty())
    }

    // 8/15/16/18. The navigation callback owns every policy; the fade only restores gain -------------------------
    @Test fun `navigation that does not move (restart current or queue boundary) still ends at full gain`() {
        controller.navigate(isPlaying = true) { log("nav"); false }
        time.advance(300)
        assertEquals(listOf("nav@0"), log) // the callback (policy) ran exactly once, immediately
        assertEquals(1f, controller.gain, 0f)
    }

    @Test fun `failed navigation never leaves the player muted`() {
        controller.navigate(isPlaying = true) { log("nav"); error("boom") }
        time.advance(300)
        assertEquals(1f, controller.gain, 0f)
        assertEquals(0, time.pendingCount)
        // the controller is still usable afterwards
        controller.navigate(isPlaying = true) { log("nav2"); true }
        time.advance(300)
        assertEquals(1f, controller.gain, 0f)
        assertTrue(log.contains("nav2@300"))
    }

    // 19. Paused navigation neither fades nor starts anything ---------------------------------------------------
    @Test fun `navigation while paused runs directly without any gain change`() {
        controller.navigate(isPlaying = false) { log("nav"); true }
        assertEquals(listOf("nav@0"), log)
        assertTrue(gains.isEmpty())
    }

    // 9. Rapid actions: every press navigates exactly once, in order -------------------------------------------
    @Test fun `rapid next presses each navigate once in order and finish at full gain`() {
        controller.navigate(true) { log("n1"); true }
        time.advance(40)
        controller.navigate(true) { log("n2"); true }
        time.advance(40)
        controller.navigate(true) { log("n3"); true }
        time.advance(2_000)
        assertEquals(listOf("n1", "n2", "n3"), log.map { it.substringBefore('@') })
        assertEquals(1f, gains.last(), 0f)
        assertEquals(0, time.pendingCount)
    }

    // 10. Play/Pause reversal ---------------------------------------------------------------------------------
    @Test fun `play during a pause fade cancels the pause and ramps back up without restarting`() {
        controller.pause(isPlaying = true) { log("pause") }
        time.advance(150)
        controller.play(isPlaying = true) { log("play") }
        time.advance(1_000)
        assertFalse(log.any { it.startsWith("pause") })
        assertEquals(1f, gains.last(), 0f)
    }

    @Test fun `pause during a play fade-in fades down from the current gain and then pauses`() {
        controller.play(isPlaying = false) { log("play") }
        time.advance(150)
        val mid = gains.last()
        controller.pause(isPlaying = true) { log("pause") }
        assertTrue("no jump up", gains.last() <= mid + 1e-6f)
        time.advance(1_000)
        assertTrue(log.any { it.startsWith("pause") })
        assertEquals(1f, gains.last(), 0f)
    }

    @Test fun `pause after an immediate navigation keeps the order navigation then pause`() {
        controller.navigate(true) { log("nav"); true }
        time.advance(50)
        controller.pause(isPlaying = true) { log("pause") }
        time.advance(2_000)
        assertEquals(listOf("nav", "pause"), log.map { it.substringBefore('@') })
        assertEquals(1f, gains.last(), 0f)
    }

    // 11/12. Cancellation never leaves a permanent mute ---------------------------------------------------------
    @Test fun `interrupt restores full gain and drops pending work`() {
        controller.pause(isPlaying = true) { log("pause") }
        time.advance(100)
        controller.interrupt()
        assertEquals(1f, gains.last(), 0f)
        time.advance(1_000)
        assertTrue(log.isEmpty())
    }

    @Test fun `release restores full gain and cancels all callbacks`() {
        controller.navigate(true) { log("nav"); true }
        time.advance(50)
        controller.release()
        assertEquals(1f, gains.last(), 0f)
        time.advance(1_000)
        assertEquals("the navigation ran once at tap time and never again", listOf("nav@0"), log)
    }

    // 20. No stale callback may change the volume --------------------------------------------------------------
    @Test fun `stale scheduled callbacks after a cancellation are inert`() {
        controller.pause(isPlaying = true) { log("pause") }
        time.advance(100)
        controller.interrupt()
        val before = gains.size
        time.everPosted.toList().forEach { it() } // even if a cancelled callback somehow fires
        assertEquals(before, gains.size)
        assertTrue(log.isEmpty())
    }

    // settings change while idle / mid-fade ------------------------------------------------------------------
    @Test fun `disabling while a fade is in flight finishes that fade then behaves as disabled`() {
        controller.pause(isPlaying = true) { log("pause") }
        time.advance(100)
        config = TransportFadeConfig(false, 300L)
        time.advance(300)
        assertEquals(listOf("pause@300"), log)
        controller.pause(isPlaying = true) { log("pause2") }
        assertEquals("pause2@400", log.last())
    }
}
