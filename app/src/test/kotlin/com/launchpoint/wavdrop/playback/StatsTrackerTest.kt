package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StatsTracker] play-counting and threshold logic.
 *
 * Uses a [FakePlayEventWriter] for assertions and overrides [StatsTracker.scope] with
 * [Dispatchers.Unconfined] so coroutine launches execute synchronously — no extra
 * test-coroutines dependency required.
 */
class StatsTrackerTest {

    private lateinit var fakeWriter: FakePlayEventWriter
    private lateinit var tracker: StatsTracker
    private var fakeClockMs: Long = 0L

    @Before
    fun setUp() {
        fakeWriter = FakePlayEventWriter()
        tracker = StatsTracker(fakeWriter).also {
            // Unconfined makes scope.launch { } execute on the calling thread immediately.
            it.scope = CoroutineScope(Dispatchers.Unconfined)
            it.clock = { fakeClockMs }
        }
        fakeClockMs = 0L
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun song(id: Long, durationMs: Long = 120_000L) = Song(
        id = id, title = "Song $id", artist = "Artist", album = "Album",
        albumId = 0L, duration = durationMs, uri = "content://media/$id",
        dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    /** Simulate [ms] milliseconds of uninterrupted playback for the currently selected song. */
    private fun playFor(ms: Long) {
        tracker.onPlaybackStarted()
        fakeClockMs += ms
        tracker.onPlaybackPaused()
    }

    // ── threshold / first play ────────────────────────────────────────────────

    @Test
    fun `first play crossing threshold records exactly one play`() {
        tracker.onSongSelected(song(1))
        playFor(30_000L)

        assertEquals(1, fakeWriter.plays.size)
        assertEquals(1L, fakeWriter.plays[0].songId)
    }

    @Test
    fun `play below threshold records no play`() {
        tracker.onSongSelected(song(1))
        playFor(5_000L)

        assertTrue(fakeWriter.plays.isEmpty())
    }

    @Test
    fun `play and listening time are always recorded together`() {
        tracker.onSongSelected(song(1))
        playFor(45_000L)

        val play = fakeWriter.plays.single()
        assertEquals(1L, play.songId)
        assertTrue("listenedMs must be positive", play.listenedMs > 0L)
        assertEquals(45_000L, play.listenedMs)
    }

    // ── Bug 2: same-song replay ───────────────────────────────────────────────

    @Test
    fun `same song replayed after first play increments play count beyond 1`() {
        tracker.onSongSelected(song(1))
        playFor(30_000L)                     // first play — count: 1

        // Re-select the same song (user taps it again or it loops back in a 1-song queue).
        tracker.onSongSelected(song(1))
        playFor(30_000L)                     // second play — count must be 2

        assertEquals(2, fakeWriter.plays.size)
        assertTrue(fakeWriter.plays.all { it.songId == 1L })
    }

    @Test
    fun `third replay of same song records third play`() {
        repeat(3) {
            tracker.onSongSelected(song(1))
            playFor(30_000L)
        }

        assertEquals(3, fakeWriter.plays.size)
    }

    // ── Bug 2: auto-advance ───────────────────────────────────────────────────

    @Test
    fun `auto-advance to new song records play for the new song`() {
        // Song A qualifies.
        tracker.onSongSelected(song(1))
        playFor(30_000L)

        // Auto-advance: PlayerController calls onSongSelected + onPlaybackStarted while playing.
        tracker.onSongSelected(song(2))
        playFor(30_000L)               // song 2 crosses threshold

        assertEquals(2, fakeWriter.plays.size)
        assertEquals(1L, fakeWriter.plays[0].songId)
        assertEquals(2L, fakeWriter.plays[1].songId)
    }

    @Test
    fun `a play event is inserted for each distinct qualified play`() {
        for (id in 1L..3L) {
            tracker.onSongSelected(song(id))
            playFor(30_000L)
        }
        // Finalise last song by selecting a 4th (or just pause — playFor already pauses).
        // The last song's play was already recorded on pause inside playFor.

        assertEquals(3, fakeWriter.plays.size)
        assertEquals(listOf(1L, 2L, 3L), fakeWriter.plays.map { it.songId })
    }

    // ── pause / resume accumulation ───────────────────────────────────────────

    @Test
    fun `accumulated time across pause and resume crosses threshold`() {
        tracker.onSongSelected(song(1))
        playFor(15_000L)     // 15 s — below 30 s threshold
        assertTrue(fakeWriter.plays.isEmpty())

        playFor(20_000L)     // +20 s = 35 s total — threshold crossed
        assertEquals(1, fakeWriter.plays.size)
    }

    @Test
    fun `pausing and resuming does not double-count a single play`() {
        tracker.onSongSelected(song(1))
        playFor(30_000L)     // threshold crossed on first pause
        playFor(30_000L)     // resume and pause again — should not re-record

        assertEquals(1, fakeWriter.plays.size)
    }

    // ── REPEAT_ONE / same-song loop (PlayerController fix) ───────────────────

    /**
     * Mirrors the REPEAT_ONE / song-loop scenario. When PlayerController detects
     * onMediaItemTransition it now always calls onSongSelected + onPlaybackStarted,
     * even when the song ID hasn't changed (RepeatMode.ONE). The two-call sequence
     * (onSongSelected then onPlaybackStarted) is what this test verifies.
     */
    @Test
    fun `REPEAT_ONE loop increments play count on second loop`() {
        // First loop: normal play
        tracker.onSongSelected(song(1))
        tracker.onPlaybackStarted()
        fakeClockMs += 30_000L
        tracker.onPlaybackPaused()
        assertEquals(1, fakeWriter.plays.size)

        // Media3 fires onMediaItemTransition for the repeat → PlayerController calls
        // onSongSelected(same song) then onPlaybackStarted() because isPlaying stays true.
        tracker.onSongSelected(song(1))      // session reset
        tracker.onPlaybackStarted()          // new session starts immediately
        fakeClockMs += 30_000L
        tracker.onPlaybackPaused()

        assertEquals(2, fakeWriter.plays.size)
        assertTrue(fakeWriter.plays.all { it.songId == 1L })
    }

    @Test
    fun `REPEAT_ONE loop does not double-count if threshold not crossed on second pass`() {
        tracker.onSongSelected(song(1))
        tracker.onPlaybackStarted()
        fakeClockMs += 30_000L
        tracker.onPlaybackPaused()            // first play counted

        tracker.onSongSelected(song(1))       // loop starts
        tracker.onPlaybackStarted()
        fakeClockMs += 5_000L                 // only 5 s — below threshold
        tracker.onPlaybackPaused()

        assertEquals(1, fakeWriter.plays.size)  // still only one play
    }

    // ── skip logic ────────────────────────────────────────────────────────────

    @Test
    fun `skipping away before threshold records a skip not a play`() {
        tracker.onSongSelected(song(1))
        playFor(5_000L)

        tracker.onSongSelected(song(2))   // finalises song 1 as a skip

        assertTrue(fakeWriter.plays.isEmpty())
        assertEquals(1, fakeWriter.skips.size)
        assertEquals(1L, fakeWriter.skips[0].songId)
    }

    // ── fake helpers ──────────────────────────────────────────────────────────

    private data class PlayCall(val songId: Long, val listenedMs: Long)
    private data class SkipCall(val songId: Long)

    private class FakePlayEventWriter : PlayEventWriter {
        val plays = mutableListOf<PlayCall>()
        val skips = mutableListOf<SkipCall>()

        override suspend fun recordPlay(
            songId: Long,
            contentUri: String,
            listenedMs: Long,
            durationMs: Long,
        ) {
            plays += PlayCall(songId, listenedMs)
        }

        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) {
            skips += SkipCall(songId)
        }
    }
}
