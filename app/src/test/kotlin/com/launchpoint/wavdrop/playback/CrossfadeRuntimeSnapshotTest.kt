package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeRuntimeSnapshotTest {

    private fun song(id: Long, durationMs: Long = 200_000L, tag: Long = 0L) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = durationMs, uri = "content://media/$id",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1), song(2), song(3), song(4))

    private fun snap(
        queue: List<Song> = this.queue,
        index: Int? = 1,
        generation: Long = 5L,
        repeat: RepeatMode = RepeatMode.OFF,
        playing: Boolean = true,
        external: Boolean = false,
        dirty: Boolean = false,
        connected: Boolean = true,
    ) = CrossfadeRuntimeSnapshot(
        queueGeneration = generation,
        playbackQueue = queue,
        currentPlaybackIndex = index,
        repeatMode = repeat,
        shuffleEnabled = false,
        isPlaying = playing,
        isExternalPlayback = external,
        playerQueueNeedsSync = dirty,
        controllerConnected = connected,
    )

    private fun plan(s: CrossfadeRuntimeSnapshot, configured: Long = 6_000L, override: Long? = null) =
        planCrossfadeFromRuntimeSnapshot(configured, s, override)

    private fun unavailable(r: CrossfadeUnavailableReason) = CrossfadeTransitionPlan.Unavailable(r)

    private fun eligible(s: CrossfadeRuntimeSnapshot): CrossfadeTransitionPlan.Eligible =
        plan(s) as CrossfadeTransitionPlan.Eligible

    // ── CF-1 delegation ─────────────────────────────────────────────────────────

    @Test fun validSnapshotIsEligible() {
        assertEquals(CrossfadeTransitionPlan.Eligible(2, 6_000L, 194_000L), plan(snap()))
    }

    @Test fun matchesDirectCf1Call() {
        val s = snap(index = 2, repeat = RepeatMode.ALL)
        val direct = planCrossfadeTransition(6_000L, queue, 2, RepeatMode.ALL, true, false, false, 90_000L)
        assertEquals(direct, plan(s, override = 90_000L))
    }

    @Test fun disabledStaysDisabled() = assertEquals(unavailable(CrossfadeUnavailableReason.Disabled), plan(snap(), 0L))

    @Test fun repeatOneUnavailable() =
        assertEquals(unavailable(CrossfadeUnavailableReason.RepeatOne), plan(snap(repeat = RepeatMode.ONE)))

    @Test fun repeatOffAtEndUnavailable() =
        assertEquals(unavailable(CrossfadeUnavailableReason.NoNextOccurrence), plan(snap(index = 3)))

    @Test fun repeatAllWrapsToZero() {
        assertEquals(0, eligible(snap(index = 3, repeat = RepeatMode.ALL)).nextPlaybackIndex)
    }

    @Test fun externalUnavailable() =
        assertEquals(unavailable(CrossfadeUnavailableReason.ExternalPlayback), plan(snap(external = true)))

    @Test fun dirtyUnavailable() =
        assertEquals(unavailable(CrossfadeUnavailableReason.PlayerQueueNeedsSync), plan(snap(dirty = true)))

    @Test fun notPlayingUnavailable() =
        assertEquals(unavailable(CrossfadeUnavailableReason.NotPlaying), plan(snap(playing = false)))

    @Test fun nullCurrentOccurrenceFailsClosed() =
        assertEquals(unavailable(CrossfadeUnavailableReason.InvalidCurrentIndex), plan(snap(index = null)))

    @Test fun invalidCurrentOccurrenceFailsClosed() {
        assertEquals(unavailable(CrossfadeUnavailableReason.InvalidCurrentIndex), plan(snap(index = 9)))
        assertEquals(unavailable(CrossfadeUnavailableReason.InvalidCurrentIndex), plan(snap(index = -3)))
    }

    @Test fun durationBehaviourRemainsCf1() {
        val unknown = snap(queue = listOf(song(1), song(2, durationMs = 0L)), index = 0)
        assertEquals(unavailable(CrossfadeUnavailableReason.UnknownDuration), plan(unknown))
        val p = plan(snap(), configured = 12_000L, override = 8_000L) as CrossfadeTransitionPlan.Eligible
        assertEquals(4_000L, p.effectiveDurationMs)
        assertEquals(4_000L, p.startAtPositionMs)
        assertEquals(unavailable(CrossfadeUnavailableReason.DurationTooShort), plan(snap(), override = 1_500L))
    }

    @Test fun controllerConnectionDoesNotAffectPlanning() {
        assertEquals(plan(snap(connected = true)), plan(snap(connected = false)))
    }

    // ── Binding ─────────────────────────────────────────────────────────────────

    private fun fakePlan(next: Int) = CrossfadeTransitionPlan.Eligible(next, 6_000L, 100_000L)

    @Test fun bindsGenerationFromAndTo() {
        val s = snap(index = 1, generation = 9L)
        assertEquals(CrossfadeTransitionKey(9L, 1, 2), bindCrossfadeTransition(s, eligible(s)))
    }

    @Test fun bindingRejectsUnsafeSnapshots() {
        assertNull(bindCrossfadeTransition(snap(index = null), fakePlan(2)))
        assertNull(bindCrossfadeTransition(snap(index = 9), fakePlan(2)))
        assertNull(bindCrossfadeTransition(snap(index = -1), fakePlan(2)))
        assertNull(bindCrossfadeTransition(snap(), fakePlan(9)))
        assertNull(bindCrossfadeTransition(snap(), fakePlan(-1)))
        assertNull(bindCrossfadeTransition(snap(index = 1), fakePlan(1)))
        assertNull(bindCrossfadeTransition(snap(external = true), fakePlan(2)))
        assertNull(bindCrossfadeTransition(snap(dirty = true), fakePlan(2)))
        assertNull(bindCrossfadeTransition(snap(playing = false), fakePlan(2)))
        assertNull(bindCrossfadeTransition(snap(generation = -1L), fakePlan(2)))
    }

    @Test fun bindingIgnoresControllerConnection() {
        assertEquals(CrossfadeTransitionKey(5L, 1, 2), bindCrossfadeTransition(snap(connected = false), fakePlan(2)))
    }

    // ── Ownership ───────────────────────────────────────────────────────────────

    private val key = CrossfadeTransitionKey(5L, 1, 2)

    @Test fun exactKeyIsOwned() = assertTrue(snap().ownsCrossfadeSource(key))

    @Test fun generationMismatchRejects() {
        assertFalse(snap(generation = 6L).ownsCrossfadeSource(key))
        assertFalse(snap(generation = 4L).ownsCrossfadeSource(key))
        assertFalse(snap(generation = 5L).ownsCrossfadeSource(key.copy(queueGeneration = 6L)))
    }

    @Test fun differentFromIndexRejects() {
        assertFalse(snap(index = 2).ownsCrossfadeSource(key))
        assertFalse(snap(index = null).ownsCrossfadeSource(key))
    }

    @Test fun unsafeStateRejects() {
        assertFalse(snap(dirty = true).ownsCrossfadeSource(key))
        assertFalse(snap(external = true).ownsCrossfadeSource(key))
        assertFalse(snap(playing = false).ownsCrossfadeSource(key))
    }

    @Test fun invalidKeyIndicesReject() {
        assertFalse(snap(index = 9).ownsCrossfadeSource(CrossfadeTransitionKey(5L, 9, 2)))
        assertFalse(snap(index = -1).ownsCrossfadeSource(CrossfadeTransitionKey(5L, -1, 2)))
        assertFalse(snap().ownsCrossfadeSource(CrossfadeTransitionKey(5L, 1, 9)))
        assertFalse(snap().ownsCrossfadeSource(CrossfadeTransitionKey(5L, 1, -1)))
    }

    @Test fun controllerConnectionDoesNotAlterOccurrenceIdentity() {
        assertTrue(snap(connected = false).ownsCrossfadeSource(key))
        assertTrue(snap(connected = true).ownsCrossfadeSource(key))
    }

    // ── Duplicate song ids stay positional ──────────────────────────────────────

    @Test fun duplicateSongIdsBindAndOwnByPosition() {
        // [A1, B, A2, A3]: positions 0, 2, 3 share one song id.
        val a = 10L
        val dup = listOf(song(a, tag = 1), song(20), song(a, tag = 2), song(a, tag = 3))
        val s = snap(queue = dup, index = 2)
        val p = eligible(s)
        assertEquals(3, p.nextPlaybackIndex)
        val k = bindCrossfadeTransition(s, p)
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), k)
        assertEquals(dup[2].id, dup[3].id)
        assertTrue(s.ownsCrossfadeSource(k!!))
        // Other occurrences of the same song id do not own this key.
        assertFalse(snap(queue = dup, index = 0).ownsCrossfadeSource(k))
        assertFalse(snap(queue = dup, index = 3).ownsCrossfadeSource(k))
        // Repeat All wrap between two same-id occurrences: 3 -> 0.
        val wrap = snap(queue = dup, index = 3, repeat = RepeatMode.ALL)
        assertEquals(CrossfadeTransitionKey(5L, 3, 0), bindCrossfadeTransition(wrap, eligible(wrap)))
    }

    @Test fun identicalSongsAtAdjacentPositionsAreValid() {
        val same = song(7)
        val s = snap(queue = listOf(same, same), index = 0)
        assertEquals(CrossfadeTransitionKey(5L, 0, 1), bindCrossfadeTransition(s, eligible(s)))
        assertTrue(s.ownsCrossfadeSource(CrossfadeTransitionKey(5L, 0, 1)))
    }
}
