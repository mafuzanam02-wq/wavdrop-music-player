package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ST-1: the pure sleep-timer / terminal-boundary policy. Exact occurrences, never song ids alone. */
class SleepTimerPolicyTest {

    private val now = 1_000_000L
    private fun occ(index: Int, generation: Long = 7L, songId: Long = 100L + index) = SleepTerminalOccurrence(generation, index, songId)

    private fun countdown(finish: Boolean, option: SleepTimerOption = SleepTimerOption.MINUTES_15, custom: Long? = null) =
        SleepTimerPolicy.startDuration(now, option, custom, finish)

    private fun armedAt(terminal: SleepTerminalOccurrence) = SleepTimerPolicy.onCountdownExpired(countdown(finish = true), terminal, playbackActive = true).state

    // ── configuration ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `off clears everything and releases an armed boundary`() {
        val armed = armedAt(occ(2))
        val t = SleepTimerPolicy.off(armed)
        assertEquals(SleepTimerState(), t.state)
        assertEquals(SleepTimerAction.DisarmBoundary, t.action)
        assertEquals(SleepTimerAction.None, SleepTimerPolicy.off(SleepTimerState()).action)
        assertEquals(SleepTimerAction.None, SleepTimerPolicy.off(countdown(false)).action)
    }

    @Test fun `a duration timer starts a countdown that is not armed and carries the modifier`() {
        val s = countdown(finish = true)
        assertEquals(SleepTimerPhase.COUNTDOWN, s.phase)
        assertTrue(s.isActive && s.isCountingDown && !s.isFinishingCurrentTrack)
        assertTrue(s.finishCurrentTrack)
        assertEquals(now + 15 * 60_000L, s.endsAtMs)
        assertNull(s.terminal)
        assertFalse("the modifier is not a duration", countdown(finish = false).finishCurrentTrack)
    }

    @Test fun `a custom duration keeps the option off and carries the modifier`() {
        val s = countdown(finish = true, option = SleepTimerOption.OFF, custom = 90_000L)
        assertEquals(90_000L, s.customDurationMs)
        assertEquals(SleepTimerOption.OFF, s.option)
        assertEquals(now + 90_000L, s.endsAtMs)
        assertTrue(s.finishCurrentTrack)
        assertEquals(SleepTimerState(), SleepTimerPolicy.startDuration(now, SleepTimerOption.OFF, null, true))
        assertEquals(SleepTimerState(), SleepTimerPolicy.startDuration(now, SleepTimerOption.MINUTES_15, 0L, true))
    }

    @Test fun `standalone end of current song arms immediately against the exact occurrence`() {
        val t = SleepTimerPolicy.startStandalone(now, occ(3))
        assertEquals(SleepTimerPhase.FINISHING_CURRENT_TRACK, t.state.phase)
        assertEquals(SleepTimerOption.END_OF_CURRENT_SONG, t.state.option)
        assertEquals(occ(3), t.state.terminal)
        assertNull("no countdown", t.state.endsAtMs)
        assertEquals(SleepTimerAction.ArmBoundary(occ(3)), t.action)
    }

    @Test fun `standalone with no resolvable occurrence fails closed to idle`() {
        val t = SleepTimerPolicy.startStandalone(now, null)
        assertEquals(SleepTimerState(), t.state)
        assertFalse(t.state.isActive)
    }

    // ── countdown expiry ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `expiry with finish OFF pauses now and clears`() {
        val t = SleepTimerPolicy.onCountdownExpired(countdown(finish = false), occ(1), playbackActive = true)
        assertEquals(SleepTimerAction.PauseNow, t.action)
        assertEquals(SleepTimerState(), t.state)
    }

    @Test fun `expiry with finish ON arms the exact current occurrence and ends the countdown`() {
        val before = countdown(finish = true)
        val t = SleepTimerPolicy.onCountdownExpired(before, occ(7, songId = 42L), playbackActive = true)
        assertEquals(SleepTimerAction.ArmBoundary(occ(7, songId = 42L)), t.action)
        assertEquals(SleepTimerPhase.FINISHING_CURRENT_TRACK, t.state.phase)
        assertEquals(occ(7, songId = 42L), t.state.terminal)
        assertNull("no negative/zero countdown is shown and no second countdown exists", t.state.endsAtMs)
        assertEquals("the selected duration is untouched; the phase carries the difference", before.option, t.state.option)
        assertTrue(t.state.isFinishingCurrentTrack && !t.state.isCountingDown)
    }

    @Test fun `expiry with an unresolvable occurrence pauses safely and clears`() {
        val t = SleepTimerPolicy.onCountdownExpired(countdown(finish = true), current = null, playbackActive = true)
        assertEquals(SleepTimerAction.PauseNow, t.action)
        assertEquals(SleepTimerState(), t.state)
    }

    @Test fun `expiry while nothing is playing pauses (a no-op) and clears instead of arming a dormant boundary`() {
        val t = SleepTimerPolicy.onCountdownExpired(countdown(finish = true), occ(1), playbackActive = false)
        assertEquals(SleepTimerAction.PauseNow, t.action)
        assertEquals(SleepTimerState(), t.state)
    }

    @Test fun `a stale countdown callback after the timer changed does nothing`() {
        val idle = SleepTimerState()
        assertEquals(SleepTimerTransition(idle), SleepTimerPolicy.onCountdownExpired(idle, occ(1), true))
        val armed = armedAt(occ(1))
        assertEquals(SleepTimerTransition(armed), SleepTimerPolicy.onCountdownExpired(armed, occ(1), true))
    }

    // ── natural boundary ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `natural end of the armed occurrence stops and consumes the boundary exactly once`() {
        val armed = armedAt(occ(4))
        val first = SleepTimerPolicy.onNaturalBoundary(armed, occ(4))
        assertEquals(SleepTimerAction.StopAtBoundary, first.action)
        assertEquals(SleepTimerState(), first.state)
        val second = SleepTimerPolicy.onNaturalBoundary(first.state, occ(4))
        assertEquals("a repeated callback is harmless", SleepTimerAction.None, second.action)
        assertEquals(SleepTimerState(), second.state)
    }

    @Test fun `a boundary callback for another occurrence is ignored and the boundary is neither consumed nor moved`() {
        val armed = armedAt(occ(4))
        val wrong = SleepTimerPolicy.onNaturalBoundary(armed, occ(5))
        assertEquals(SleepTimerTransition(armed), wrong)
        assertEquals("same song id elsewhere in the queue is a different occurrence", SleepTimerTransition(armed), SleepTimerPolicy.onNaturalBoundary(armed, occ(9, songId = armed.terminal!!.songId)))
        assertEquals(SleepTimerTransition(armed), SleepTimerPolicy.onNaturalBoundary(armed, null))
    }

    @Test fun `a boundary callback from a stale queue generation is ignored`() {
        val armed = armedAt(occ(4, generation = 7L))
        assertEquals(SleepTimerTransition(armed), SleepTimerPolicy.onNaturalBoundary(armed, occ(4, generation = 6L)))
        assertEquals(SleepTimerTransition(armed), SleepTimerPolicy.onNaturalBoundary(armed, occ(4, generation = 8L)))
    }

    @Test fun `a natural boundary with nothing armed or only a countdown does nothing`() {
        assertEquals(SleepTimerAction.None, SleepTimerPolicy.onNaturalBoundary(SleepTimerState(), occ(1)).action)
        assertEquals(SleepTimerAction.None, SleepTimerPolicy.onNaturalBoundary(countdown(true), occ(1)).action)
    }

    @Test fun `standalone end of song consumes through the same primitive`() {
        val s = SleepTimerPolicy.startStandalone(now, occ(2)).state
        val t = SleepTimerPolicy.onNaturalBoundary(s, occ(2))
        assertEquals(SleepTimerAction.StopAtBoundary, t.action)
        assertEquals(SleepTimerState(), t.state)
    }

    // ── playback stopped while armed ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a stop at the natural end of the armed occurrence consumes it`() {
        val armed = armedAt(occ(4))
        val t = SleepTimerPolicy.onPlaybackStopped(armed, occ(4), atNaturalEnd = true)
        assertEquals(SleepTimerAction.StopAtBoundary, t.action)
        assertEquals(SleepTimerState(), t.state)
        assertEquals("idempotent", SleepTimerAction.None, SleepTimerPolicy.onPlaybackStopped(t.state, occ(4), true).action)
    }

    @Test fun `a manual pause before the end clears the boundary and the timer does not resurrect`() {
        val armed = armedAt(occ(4))
        val t = SleepTimerPolicy.onPlaybackStopped(armed, occ(4), atNaturalEnd = false)
        assertEquals(SleepTimerAction.DisarmBoundary, t.action)
        assertEquals(SleepTimerState(), t.state)
        // playing again: nothing is armed any more
        assertEquals(SleepTimerAction.None, SleepTimerPolicy.onBoundaryEscaped(t.state).action)
        assertEquals(SleepTimerAction.None, SleepTimerPolicy.onNaturalBoundary(t.state, occ(4)).action)
    }

    @Test fun `a stop at the end of a different occurrence never consumes it as the terminal one - it clears`() {
        val armed = armedAt(occ(4))
        val t = SleepTimerPolicy.onPlaybackStopped(armed, occ(5), atNaturalEnd = true)
        assertEquals(SleepTimerAction.DisarmBoundary, t.action)
        assertEquals("never stuck armed behind a stopped player", SleepTimerState(), t.state)
    }

    @Test fun `a stop after a queue mutation bumped the generation is accepted when the same song ended`() {
        val armed = armedAt(occ(4, generation = 7L, songId = 55L))
        val t = SleepTimerPolicy.onPlaybackStopped(armed, occ(3, generation = 8L, songId = 55L), atNaturalEnd = true)
        assertEquals(SleepTimerAction.StopAtBoundary, t.action)
        val other = SleepTimerPolicy.onPlaybackStopped(armed, occ(3, generation = 8L, songId = 56L), atNaturalEnd = true)
        assertEquals(SleepTimerAction.DisarmBoundary, other.action)
    }

    @Test fun `a stop while only counting down is not the policy's business`() {
        val c = countdown(true)
        assertEquals(SleepTimerTransition(c), SleepTimerPolicy.onPlaybackStopped(c, occ(1), true))
    }

    // ── transitions ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `an automatic advance or a repeat wrap while armed means the boundary escaped and stops now`() {
        val armed = armedAt(occ(4))
        for (kind in listOf(SleepTransitionKind.AUTOMATIC, SleepTransitionKind.REPEAT_WRAP)) {
            val t = SleepTimerPolicy.onTransition(armed, kind, occ(5))
            assertEquals(kind.name, SleepTimerAction.PauseNow, t.action)
            assertEquals(SleepTimerState(), t.state)
        }
        assertEquals("a late duplicate finds nothing armed", SleepTimerAction.None, SleepTimerPolicy.onTransition(SleepTimerState(), SleepTransitionKind.AUTOMATIC, occ(5)).action)
    }

    @Test fun `an explicit navigation to another occurrence clears the boundary, to the same occurrence retains it`() {
        val armed = armedAt(occ(4))
        val other = SleepTimerPolicy.onTransition(armed, SleepTransitionKind.NAVIGATION, occ(6))
        assertEquals(SleepTimerAction.DisarmBoundary, other.action)
        assertEquals(SleepTimerState(), other.state)
        assertEquals(SleepTimerTransition(armed), SleepTimerPolicy.onTransition(armed, SleepTransitionKind.NAVIGATION, occ(4)))
        assertEquals(SleepTimerTransition(armed), SleepTimerPolicy.onTransition(armed, SleepTransitionKind.PLAYLIST_CHANGED, occ(9)))
    }

    // ── user intent ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `manual next and previous clear an armed boundary and leave a running countdown alone`() {
        val armed = armedAt(occ(4))
        val t = SleepTimerPolicy.onExplicitNavigation(armed)
        assertEquals(SleepTimerAction.DisarmBoundary, t.action)
        assertEquals(SleepTimerState(), t.state)
        val c = countdown(true)
        assertEquals(SleepTimerTransition(c), SleepTimerPolicy.onExplicitNavigation(c))
        assertEquals(SleepTimerTransition(SleepTimerState()), SleepTimerPolicy.onExplicitNavigation(SleepTimerState()))
    }

    @Test fun `selecting another song or replacing the queue clears an armed boundary`() {
        val armed = armedAt(occ(4))
        val t = SleepTimerPolicy.onQueueReplaced(armed)
        assertEquals(SleepTimerAction.DisarmBoundary, t.action)
        assertEquals(SleepTimerState(), t.state)
        val standalone = SleepTimerPolicy.startStandalone(now, occ(1)).state
        assertEquals(SleepTimerAction.DisarmBoundary, SleepTimerPolicy.onQueueReplaced(standalone).action)
        assertEquals("a countdown survives a song change", countdown(true), SleepTimerPolicy.onQueueReplaced(countdown(true)).state)
    }

    @Test fun `a seek within the same occurrence and repeat changes retain the boundary`() {
        val armed = armedAt(occ(4))
        assertSame(armed, SleepTimerPolicy.onSameOccurrenceSeek(armed).state)
        assertSame(armed, SleepTimerPolicy.onRepeatChanged(armed).state)
        assertEquals(SleepTimerAction.None, SleepTimerPolicy.onRepeatChanged(armed).action)
    }

    // ── queue identity changes (shuffle toggle, structural mutation) ───────────────────────────────────────────────────

    @Test fun `after a shuffle or mutation the boundary is re-bound to the same song now playing at its new exact occurrence`() {
        val armed = armedAt(occ(4, generation = 7L, songId = 55L))
        val moved = occ(0, generation = 8L, songId = 55L)
        val t = SleepTimerPolicy.onQueueIdentityChanged(armed, moved)
        assertEquals(moved, t.state.terminal)
        assertEquals(SleepTimerPhase.FINISHING_CURRENT_TRACK, t.state.phase)
        assertEquals(SleepTimerAction.None, t.action)
    }

    @Test fun `a queue that now plays a different song means the terminal occurrence is gone - cleared never moved`() {
        val armed = armedAt(occ(4, songId = 55L))
        val t = SleepTimerPolicy.onQueueIdentityChanged(armed, occ(4, generation = 8L, songId = 56L))
        assertEquals(SleepTimerAction.DisarmBoundary, t.action)
        assertEquals(SleepTimerState(), t.state)
    }

    @Test fun `an unresolved occurrence after a mutation keeps waiting and never moves the boundary`() {
        val armed = armedAt(occ(4))
        assertEquals(SleepTimerTransition(armed), SleepTimerPolicy.onQueueIdentityChanged(armed, null))
        assertEquals(SleepTimerTransition(armed), SleepTimerPolicy.onQueueIdentityChanged(armed, armed.terminal))
    }

    @Test fun `a duplicate song is two different occurrences`() {
        // song 55 at positions 2 and 7; the timer expires while position 7 plays.
        val armed = SleepTimerPolicy.onCountdownExpired(countdown(true), occ(7, songId = 55L), true).state
        assertEquals(7, armed.terminal!!.playbackIndex)
        assertEquals("the first occurrence of the same song ending is not the boundary", SleepTimerAction.None, SleepTimerPolicy.onNaturalBoundary(armed, occ(2, songId = 55L)).action)
        assertEquals(SleepTimerAction.StopAtBoundary, SleepTimerPolicy.onNaturalBoundary(armed, occ(7, songId = 55L)).action)
    }

    // ── configuring while armed ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `opening the dialog while armed - a new duration replaces it, standalone re-arms against the current occurrence`() {
        val armed = armedAt(occ(4))
        val replaced = countdown(finish = false, option = SleepTimerOption.MINUTES_30)
        assertEquals(SleepTimerPhase.COUNTDOWN, replaced.phase)
        assertNull(replaced.terminal)
        val rearmed = SleepTimerPolicy.startStandalone(now, occ(5)).state
        assertEquals(occ(5), rearmed.terminal)
        assertEquals("toggling the modifier only affects a NEW timer", true, armed.finishCurrentTrack)
        assertEquals(false, replaced.finishCurrentTrack)
    }
}
