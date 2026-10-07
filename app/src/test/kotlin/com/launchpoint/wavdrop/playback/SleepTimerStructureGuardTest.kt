package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ST-1: structural facts that are not observable through behaviour on the JVM: the service wiring, the single boundary engine,
 * and the "in-memory, session-lifetime only" product rule.
 */
class SleepTimerStructureGuardTest {

    private val main = File("src/main/kotlin/com/launchpoint/wavdrop")
    private fun src(path: String) = File(main, path).readText()
    private fun strip(text: String) = text.lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")
    private fun code(path: String) = strip(src(path))

    /** The ST-1 section of PlayerController (sliced on the raw source: the markers live in comments). */
    private fun boundarySection(): String {
        val raw = src("playback/PlayerController.kt")
        val start = raw.indexOf("ST-1: sleep timer / terminal boundary")
        val end = raw.indexOf("Called by the 500 ms position ticker")
        check(start > 0 && end > start) { "section markers moved" }
        return strip(raw.substring(start, end))
    }

    @Test fun `the service arms and releases the physical hold and the crossfade cancel through the one boundary listener`() {
        val s = code("playback/PlaybackService.kt")
        val registration = s.substringAfter("playerController.setSleepBoundaryListener { armed ->").substringBefore("}\n")
        assertTrue(registration.contains("if (armed) recoverCrossfadeFromSleepBoundary(crossfadeCancelSink)"))
        assertTrue(registration.contains("assembly.setPauseAtEndOfMediaItems(armed)"))
        assertTrue("cleared on teardown so the singleton never retains the service", s.contains("playerController.setSleepBoundaryListener(null)"))
    }

    @Test fun `PlayerController has one boundary engine - the old per-callback END_OF_CURRENT_SONG triggers are gone`() {
        val c = code("playback/PlayerController.kt")
        assertFalse(c.contains("triggerSleepTimer"))
        val uses = Regex("""SleepTimerOption\.END_OF_CURRENT_SONG""").findAll(c).count()
        assertEquals("only setSleepTimer decides what the standalone option means", 1, uses)
        // every Media3 observation of a natural completion goes through the policy
        assertTrue(c.contains("handleSleepTransition(sleepTransitionKind(reason)"))
        assertTrue(c.contains("handleSleepTransition(SleepTransitionKind.AUTOMATIC"))
        assertTrue(c.contains("handleSleepTransition(SleepTransitionKind.REPEAT_WRAP"))
        assertEquals(3, Regex("""handleSleepPlaybackStopped\(\)""").findAll(c).count() - 1) // 3 call sites + the declaration
    }

    @Test fun `the sleep boundary code never rewrites the saved repeat or shuffle preference`() {
        val section = boundarySection()
        for (forbidden in listOf("repeatMode =", "shuffleEnabled =", "repeatMode=", "saveRepeat", "setRepeatMode", ".repeatMode")) {
            assertFalse("the boundary section must not touch `$forbidden`", section.contains(forbidden))
        }
    }

    @Test fun `the sleep timer is session-lifetime only - never persisted, scheduled, backed up or resurrected`() {
        val timerFiles = listOf("playback/SleepTimer.kt", "playback/SleepBoundaryListenerRegistry.kt")
        for (f in timerFiles) {
            val t = code(f)
            for (forbidden in listOf("DataStore", "SharedPreferences", "AlarmManager", "WorkManager", "JobScheduler", "Room", "@Entity")) {
                assertFalse("$f must not use $forbidden", t.contains(forbidden))
            }
        }
        val controllerSleep = boundarySection()
        for (forbidden in listOf("AlarmManager", "WorkManager", "dataStore", "preferences", "sessionRepository")) {
            assertFalse("the boundary section must not use $forbidden", controllerSleep.contains(forbidden))
        }
        // the persisted session snapshot and the backup model do not know about the timer
        for (f in File(main, "data").walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            assertFalse("${f.name} must not reference the sleep timer", f.readText().contains("SleepTimer"))
        }
    }

    @Test fun `the timer state is cleared when the controller is released`() {
        val c = code("playback/PlayerController.kt")
        val release = c.substringAfter("fun release() {").substringBefore("private fun startPositionTicker")
        assertTrue(release.contains("_sleepTimerState.value = SleepTimerState()"))
        assertTrue(release.contains("sleepBoundaryListeners.notifyArmed(false)"))
    }

    @Test fun `no new permission dependency or schema for the sleep timer`() {
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("SCHEDULE_EXACT_ALARM"))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("INTERNET"))
    }
}
