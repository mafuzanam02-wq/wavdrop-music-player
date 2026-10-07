package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ST-1: the UI states the timer truthfully (counting down vs waiting for the song) and carries the independent modifier. */
class SleepTimerUiStructureTest {

    private val ui = File("src/main/kotlin/com/launchpoint/wavdrop/ui")
    private fun src(path: String) = File(ui, path).readText()

    @Test fun `the dialog offers the independent finish current track modifier with the correct meaning`() {
        val d = src("components/SleepTimerDialog.kt")
        assertTrue(d.contains("\"Finish current track\""))
        assertTrue("it is the song playing when the countdown EXPIRES", d.contains("After the timer ends, stop when the current song finishes."))
        assertFalse("never described as the song playing when the timer was set", d.contains("when the timer is set"))
        assertTrue(d.contains("Switch(checked = finishCurrentTrack"))
        // the modifier travels with every duration choice and is only an argument, never a new SleepTimerOption
        assertTrue(d.contains("onOptionSelected: (SleepTimerOption, Boolean) -> Unit"))
        assertTrue(d.contains("onCustomDurationSelected: (Long, Boolean) -> Unit"))
        assertTrue(d.contains("onCustomDurationSelected(minutes!! * 60_000L, finishCurrentTrack)"))
        // every existing choice is preserved (15/30/45/60, custom, end of current song, off)
        val options = SleepTimerOption.entries.map { it.name }
        assertEquals(listOf("OFF", "MINUTES_15", "MINUTES_30", "MINUTES_45", "MINUTES_60", "END_OF_CURRENT_SONG"), options)
        assertTrue(d.contains("Custom (1–240 min)"))
    }

    @Test fun `an armed boundary shows finishing current track instead of a countdown`() {
        val n = src("screen/nowplaying/NowPlayingScreen.kt")
        assertTrue(n.contains("sleepTimerState.isFinishingCurrentTrack -> \"Sleep Timer: Finishing current track\""))
        // the per-second countdown only runs while a deadline exists; an armed boundary has none, so nothing shows 0:00
        assertTrue(n.contains("if (!sleepTimerState.isActive || sleepTimerState.endsAtMs == null) return@LaunchedEffect"))
        assertTrue(n.contains("then finish song"))
        val s = src("screen/settings/SettingsPlaybackScreen.kt")
        assertTrue(s.contains("isFinishingCurrentTrack -> \"Finishing current track\""))
    }

    @Test fun `all three entry points pass the modifier to the controller`() {
        for (f in listOf("screen/nowplaying/NowPlayingScreen.kt", "screen/home/HomeScreen.kt", "screen/settings/SettingsPlaybackScreen.kt")) {
            val t = src(f)
            assertTrue("$f", t.contains("onOptionSelected = { option, finishCurrentTrack ->"))
            assertTrue("$f", t.contains("viewModel.setSleepTimer(option, finishCurrentTrack)"))
            assertTrue("$f", t.contains("viewModel.setCustomSleepTimer(durationMs, finishCurrentTrack)"))
        }
        for (f in listOf("screen/nowplaying/NowPlayingViewModel.kt", "screen/home/HomeViewModel.kt", "screen/settings/SettingsViewModel.kt")) {
            val t = src(f)
            assertTrue("$f", t.contains("finishCurrentTrack: Boolean = false"))
            assertTrue("$f", t.contains("playerController.setSleepTimer(option, finishCurrentTrack)"))
        }
    }

    @Test fun `the dialog hides the countdown selection once the timer has expired and is only finishing the song`() {
        val d = src("components/SleepTimerDialog.kt")
        assertTrue(d.contains("Finishing current track. Playback will stop when this song ends."))
        assertTrue(d.contains("val showSelection = state.phase == SleepTimerPhase.COUNTDOWN"))
    }
}
