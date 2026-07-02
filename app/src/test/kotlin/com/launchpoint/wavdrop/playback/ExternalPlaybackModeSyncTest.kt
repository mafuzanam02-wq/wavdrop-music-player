package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure decision helpers backing PlayerController's onRepeatModeChanged /
 * onShuffleModeEnabledChanged listeners. The listener wiring itself needs a live
 * MediaController and is exercised manually; the decision logic that guards against
 * drift and callback loops is verified here.
 */
class ExternalPlaybackModeSyncTest {

    @Test
    fun `player repeat modes map to Wavdrop repeat modes`() {
        assertEquals(RepeatMode.OFF, repeatModeFromPlayerMode(Player.REPEAT_MODE_OFF))
        assertEquals(RepeatMode.ALL, repeatModeFromPlayerMode(Player.REPEAT_MODE_ALL))
        assertEquals(RepeatMode.ONE, repeatModeFromPlayerMode(Player.REPEAT_MODE_ONE))
    }

    @Test
    fun `unknown player repeat mode falls back to OFF`() {
        assertEquals(RepeatMode.OFF, repeatModeFromPlayerMode(Int.MIN_VALUE))
    }

    @Test
    fun `external repeat change is applied when it differs from logical state`() {
        assertEquals(
            RepeatMode.ALL,
            externalRepeatModeUpdate(current = RepeatMode.OFF, incoming = RepeatMode.ALL),
        )
        assertEquals(
            RepeatMode.OFF,
            externalRepeatModeUpdate(current = RepeatMode.ONE, incoming = RepeatMode.OFF),
        )
    }

    @Test
    fun `matching repeat change is a no-op to avoid re-emit and callback loops`() {
        // Mirrors the case where Wavdrop itself wrote controller.repeatMode: the callback
        // fires with a value that already equals the logical field, so nothing is re-applied.
        RepeatMode.entries.forEach { mode ->
            assertNull(externalRepeatModeUpdate(current = mode, incoming = mode))
        }
    }

    @Test
    fun `native shuffle enabled must be reasserted false`() {
        assertTrue(shouldReassertMediaShuffleOff(shuffleModeEnabled = true))
    }

    @Test
    fun `native shuffle disabled is left alone so reassertion does not loop`() {
        assertFalse(shouldReassertMediaShuffleOff(shuffleModeEnabled = false))
    }
}
