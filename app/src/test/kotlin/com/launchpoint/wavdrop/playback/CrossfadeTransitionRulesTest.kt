package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.Disabled
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.DurationTooShort
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.EqualizerEnabled
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.ExternalPlayback
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.InvalidCurrentIndex
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.NoNextOccurrence
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.NotPlaying
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.PlayerQueueNeedsSync
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.QueueTooShort
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.RepeatOne
import com.launchpoint.wavdrop.playback.CrossfadeUnavailableReason.UnknownDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CrossfadeTransitionRulesTest {

    /** [tag] distinguishes occurrences of the same song id (they are otherwise equal). */
    private fun song(id: Long, durationMs: Long = 200_000L, tag: Long = 0L) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = durationMs, uri = "content://media/$id",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1), song(2), song(3), song(4))

    private fun plan(
        configuredMs: Long = 6_000L,
        queue: List<Song> = this.queue,
        index: Int = 1,
        repeat: RepeatMode = RepeatMode.OFF,
        playing: Boolean = true,
        external: Boolean = false,
        dirty: Boolean = false,
        eq: Boolean = false,
        currentDurationMs: Long? = null,
    ) = planCrossfadeTransition(
        configuredDurationMs = configuredMs,
        playbackQueue = queue,
        currentPlaybackIndex = index,
        repeatMode = repeat,
        isPlaying = playing,
        isExternalPlayback = external,
        playerQueueNeedsSync = dirty,
        equalizerEnabled = eq,
        currentDurationMs = currentDurationMs,
    )

    private fun CrossfadeTransitionPlan.eligible(): CrossfadeTransitionPlan.Eligible {
        assertTrue("expected Eligible but was $this", this is CrossfadeTransitionPlan.Eligible)
        return this as CrossfadeTransitionPlan.Eligible
    }

    private fun assertUnavailable(reason: CrossfadeUnavailableReason, actual: CrossfadeTransitionPlan) {
        assertEquals(CrossfadeTransitionPlan.Unavailable(reason), actual)
    }

    // ── Limits / normalization ──────────────────────────────────────────────────

    @Test
    fun `zero and negative durations normalize to off`() {
        assertEquals(0L, CrossfadeRules.normalizeDurationMs(0L))
        assertEquals(0L, CrossfadeRules.normalizeDurationMs(-1L))
        assertEquals(0L, CrossfadeRules.normalizeDurationMs(Long.MIN_VALUE))
        assertTrue(!CrossfadeRules.isEnabled(0L))
    }

    @Test
    fun `enabled durations are bounded to one through twelve seconds`() {
        assertEquals(1_000L, CrossfadeRules.normalizeDurationMs(1L))
        assertEquals(1_000L, CrossfadeRules.normalizeDurationMs(999L))
        assertEquals(1_000L, CrossfadeRules.normalizeDurationMs(1_000L))
        assertEquals(6_500L, CrossfadeRules.normalizeDurationMs(6_500L))
        assertEquals(12_000L, CrossfadeRules.normalizeDurationMs(12_000L))
        assertEquals(12_000L, CrossfadeRules.normalizeDurationMs(12_001L))
        assertEquals(12_000L, CrossfadeRules.normalizeDurationMs(Long.MAX_VALUE))
        assertTrue(CrossfadeRules.isEnabled(1L))
    }

    // ── Unavailable reasons ─────────────────────────────────────────────────────

    @Test
    fun `off is unavailable`() {
        assertUnavailable(Disabled, plan(configuredMs = 0L))
        assertUnavailable(Disabled, plan(configuredMs = -5L))
    }

    @Test
    fun `not playing is unavailable`() {
        assertUnavailable(NotPlaying, plan(playing = false))
    }

    @Test
    fun `external playback is unavailable`() {
        assertUnavailable(ExternalPlayback, plan(external = true))
    }

    @Test
    fun `a dirty physical queue is unavailable`() {
        assertUnavailable(PlayerQueueNeedsSync, plan(dirty = true))
    }

    @Test
    fun `repeat one stays media3 native and is unavailable`() {
        assertUnavailable(RepeatOne, plan(repeat = RepeatMode.ONE))
        assertUnavailable(RepeatOne, plan(repeat = RepeatMode.ONE, index = 3))
    }

    @Test
    fun `repeat off at the end of the queue has no next occurrence`() {
        assertUnavailable(NoNextOccurrence, plan(index = 3, repeat = RepeatMode.OFF))
    }

    @Test
    fun `repeat all wraps the last occurrence to index zero`() {
        val p = plan(index = 3, repeat = RepeatMode.ALL).eligible()

        assertEquals(0, p.nextPlaybackIndex)
    }

    @Test
    fun `empty and one item queues are unavailable`() {
        assertUnavailable(QueueTooShort, plan(queue = emptyList(), index = 0))
        assertUnavailable(QueueTooShort, plan(queue = listOf(song(1)), index = 0))
        assertUnavailable(QueueTooShort, plan(queue = listOf(song(1)), index = 0, repeat = RepeatMode.ALL))
    }

    @Test
    fun `invalid current indices fail closed`() {
        assertUnavailable(InvalidCurrentIndex, plan(index = -1))
        assertUnavailable(InvalidCurrentIndex, plan(index = 4))
        assertUnavailable(InvalidCurrentIndex, plan(index = Int.MAX_VALUE))
        assertUnavailable(InvalidCurrentIndex, plan(index = Int.MIN_VALUE))
    }

    @Test
    fun `unknown or zero durations fail closed`() {
        assertUnavailable(UnknownDuration, plan(queue = listOf(song(1, 0L), song(2)), index = 0))
        assertUnavailable(UnknownDuration, plan(queue = listOf(song(1), song(2, 0L)), index = 0))
        assertUnavailable(UnknownDuration, plan(queue = listOf(song(1, -1L), song(2)), index = 0))
        assertUnavailable(UnknownDuration, plan(queue = listOf(song(1), song(2, -5L)), index = 0))
        assertUnavailable(UnknownDuration, plan(index = 0, currentDurationMs = 0L))
    }

    // ── Eligible plans / effective duration ─────────────────────────────────────

    @Test
    fun `configured duration shorter than both tracks is used as is`() {
        val p = plan(configuredMs = 6_000L).eligible()

        assertEquals(2, p.nextPlaybackIndex)
        assertEquals(6_000L, p.effectiveDurationMs)
        assertEquals(194_000L, p.startAtPositionMs)
    }

    @Test
    fun `effective duration is capped by half the current track`() {
        val q = listOf(song(1), song(2, durationMs = 8_000L), song(3))
        val p = plan(configuredMs = 12_000L, queue = q, index = 1).eligible()

        assertEquals(4_000L, p.effectiveDurationMs)
        assertEquals(4_000L, p.startAtPositionMs)
    }

    @Test
    fun `effective duration is capped by half the next track`() {
        val q = listOf(song(1), song(2), song(3, durationMs = 6_000L))
        val p = plan(configuredMs = 12_000L, queue = q, index = 1).eligible()

        assertEquals(3_000L, p.effectiveDurationMs)
        assertEquals(197_000L, p.startAtPositionMs)
    }

    @Test
    fun `effective duration below one second is unavailable`() {
        val shortNext = listOf(song(1), song(2), song(3, durationMs = 1_999L))
        assertUnavailable(DurationTooShort, plan(queue = shortNext, index = 1))

        val shortCurrent = listOf(song(1, durationMs = 1_500L), song(2))
        assertUnavailable(DurationTooShort, plan(queue = shortCurrent, index = 0))
    }

    @Test
    fun `effective duration of exactly one second is eligible`() {
        val q = listOf(song(1, durationMs = 2_000L), song(2))
        val p = plan(configuredMs = 6_000L, queue = q, index = 0).eligible()

        assertEquals(1_000L, p.effectiveDurationMs)
        assertEquals(1_000L, p.startAtPositionMs)
    }

    @Test
    fun `live current duration overrides the queued duration`() {
        val q = listOf(song(1, durationMs = 200_000L), song(2))
        val p = plan(configuredMs = 6_000L, queue = q, index = 0, currentDurationMs = 60_000L).eligible()

        assertEquals(6_000L, p.effectiveDurationMs)
        assertEquals(54_000L, p.startAtPositionMs)
    }

    @Test
    fun `configured duration is normalized before planning`() {
        assertEquals(12_000L, plan(configuredMs = 99_000L).eligible().effectiveDurationMs)
        assertEquals(1_000L, plan(configuredMs = 250L).eligible().effectiveDurationMs)
    }

    @Test
    fun `repeat off and all pick the same next occurrence before the end`() {
        assertEquals(2, plan(index = 1, repeat = RepeatMode.OFF).eligible().nextPlaybackIndex)
        assertEquals(2, plan(index = 1, repeat = RepeatMode.ALL).eligible().nextPlaybackIndex)
    }

    // ── Occurrence safety ───────────────────────────────────────────────────────

    @Test
    fun `duplicate song ids select the next occurrence by position`() {
        val a1 = song(1, durationMs = 100_000L, tag = 1)
        val a2 = song(1, durationMs = 100_000L, tag = 2)
        val a3 = song(1, durationMs = 100_000L, tag = 3)
        val b = song(2, durationMs = 100_000L)
        val q = listOf(a1, b, a2, a3)

        // [A1] B A2 A3 → B
        assertEquals(1, plan(queue = q, index = 0).eligible().nextPlaybackIndex)
        // A1 B [A2] A3 → A3, the following occurrence, not the first A
        assertEquals(3, plan(queue = q, index = 2).eligible().nextPlaybackIndex)
        // A1 B A2 [A3] with Repeat All → wraps to index 0 (A1), not to another A by id
        assertEquals(0, plan(queue = q, index = 3, repeat = RepeatMode.ALL).eligible().nextPlaybackIndex)
        // Repeat Off at A3 (an A again, but the end of the queue) → none
        assertUnavailable(NoNextOccurrence, plan(queue = q, index = 3, repeat = RepeatMode.OFF))
    }

    @Test
    fun `a queue of only one repeated song still crossfades into the next occurrence`() {
        val q = listOf(song(1, tag = 1), song(1, tag = 2))

        val p = plan(queue = q, index = 0).eligible()

        assertEquals(1, p.nextPlaybackIndex)
    }

    @Test
    fun `next duration comes from the next occurrence not from a same-id occurrence elsewhere`() {
        // Same id at index 0 (long) and index 2 (short): the next occurrence of index 1 is index 2.
        val long = song(1, durationMs = 300_000L, tag = 1)
        val mid = song(2, durationMs = 300_000L)
        val short = song(1, durationMs = 4_000L, tag = 2)

        val p = plan(configuredMs = 12_000L, queue = listOf(long, mid, short), index = 1).eligible()

        assertEquals(2, p.nextPlaybackIndex)
        assertEquals(2_000L, p.effectiveDurationMs)
    }

    // ── Equal-power gains ───────────────────────────────────────────────────────

    private val tolerance = 1e-4f

    @Test
    fun `gains at progress zero`() {
        val g = CrossfadeGainCurve.equalPower(0f)

        assertEquals(1f, g.outgoing, tolerance)
        assertEquals(0f, g.incoming, tolerance)
    }

    @Test
    fun `gains at the midpoint are both root one half`() {
        val g = CrossfadeGainCurve.equalPower(0.5f)
        val expected = sqrt(0.5f)

        assertEquals(expected, g.outgoing, tolerance)
        assertEquals(expected, g.incoming, tolerance)
        assertEquals(0.7071f, g.outgoing, 1e-3f)
    }

    @Test
    fun `gains at progress one`() {
        val g = CrossfadeGainCurve.equalPower(1f)

        assertEquals(0f, g.outgoing, tolerance)
        assertEquals(1f, g.incoming, tolerance)
    }

    @Test
    fun `progress is clamped below zero and above one`() {
        assertEquals(CrossfadeGainCurve.equalPower(0f), CrossfadeGainCurve.equalPower(-3f))
        assertEquals(CrossfadeGainCurve.equalPower(1f), CrossfadeGainCurve.equalPower(7.5f))
        assertEquals(CrossfadeGainCurve.equalPower(0f), CrossfadeGainCurve.equalPower(Float.NaN))
        assertEquals(CrossfadeGainCurve.equalPower(1f), CrossfadeGainCurve.equalPower(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `gains stay in range and keep constant power across the window`() {
        var previousOutgoing = 1f
        var previousIncoming = 0f
        for (step in 0..100) {
            val g = CrossfadeGainCurve.equalPower(step / 100f)
            assertTrue(g.outgoing in 0f..1f)
            assertTrue(g.incoming in 0f..1f)
            assertEquals(1f, g.outgoing * g.outgoing + g.incoming * g.incoming, tolerance)
            assertTrue(g.outgoing <= previousOutgoing + tolerance)
            assertTrue(g.incoming >= previousIncoming - tolerance)
            previousOutgoing = g.outgoing
            previousIncoming = g.incoming
        }
    }

    // ── CF-2I2: Equalizer compatibility ─────────────────────────────────────────

    @Test fun `eq disabled and otherwise eligible stays eligible`() {
        assertTrue(plan(eq = false) is CrossfadeTransitionPlan.Eligible)
    }

    @Test fun `eq enabled and otherwise eligible is unavailable for EqualizerEnabled`() {
        assertUnavailable(EqualizerEnabled, plan(eq = true))
    }

    @Test fun `eq enabled with crossfade off is Disabled`() {
        assertUnavailable(Disabled, plan(configuredMs = 0L, eq = true))
    }

    @Test fun `eq enabled with external playback is ExternalPlayback`() {
        assertUnavailable(ExternalPlayback, plan(external = true, eq = true))
    }

    @Test fun `eq enabled with dirty queue is PlayerQueueNeedsSync`() {
        assertUnavailable(PlayerQueueNeedsSync, plan(dirty = true, eq = true))
    }

    @Test fun `eq enabled while not playing is NotPlaying`() {
        assertUnavailable(NotPlaying, plan(playing = false, eq = true))
    }

    @Test fun `eq enabled with repeat one is EqualizerEnabled because EQ outranks repeat`() {
        assertUnavailable(EqualizerEnabled, plan(repeat = RepeatMode.ONE, eq = true))
        assertUnavailable(RepeatOne, plan(repeat = RepeatMode.ONE, eq = false))
    }
}
