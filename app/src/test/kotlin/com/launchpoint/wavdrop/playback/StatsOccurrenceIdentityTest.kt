package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.repository.PlayOccurrence
import com.launchpoint.wavdrop.data.repository.StatsWriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-STATS-19B - what [StatsTracker] hands the writer: a distinct id per logical occurrence, and an occurrence time captured at
 * detection rather than inside a delayed write. Retry scheduling is NOT tested here because it does not exist yet.
 */
class StatsOccurrenceIdentityTest {

    private var clockMs = 0L
    private var wallMs = 1_000_000L
    private var idSeq = 0

    private class Recording : PlayEventWriter {
        data class Play(val occ: PlayOccurrence, val songId: Long, val listenedMs: Long)
        data class Skip(val occ: PlayOccurrence, val songId: Long)
        val plays = mutableListOf<Play>()
        val skips = mutableListOf<Skip>()
        val listenAt = mutableListOf<Long>()
        override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = error("legacy path must not be used")
        override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = error("legacy path must not be used")
        override suspend fun recordListenStart(songId: Long, contentUri: String) = error("legacy path must not be used")
        override suspend fun recordPlayOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, listenedMs: Long, durationMs: Long): StatsWriteResult {
            plays += Play(occurrence, songId, listenedMs); return StatsWriteResult.APPLIED
        }
        override suspend fun recordSkipOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, durationMs: Long): StatsWriteResult {
            skips += Skip(occurrence, songId); return StatsWriteResult.APPLIED
        }
        override suspend fun recordListenStartAt(occurredAtMs: Long, songId: Long, contentUri: String) { listenAt += occurredAtMs }
    }

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 120_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private fun tracker(w: PlayEventWriter) = StatsTracker(w).also {
        it.scope = CoroutineScope(Dispatchers.Unconfined)
        it.clock = { clockMs }
        it.wallClock = { wallMs }
        it.newEventId = { "id-${++idSeq}" }
    }

    private fun StatsTracker.playFor(ms: Long) { onPlaybackStarted(); clockMs += ms; wallMs += ms; onPlaybackPaused() }

    @Test fun `every PLAY and SKIP gets its own non-blank id, across songs and repeat-one boundaries`() {
        val w = Recording(); val t = tracker(w)
        t.onSongSelected(song(1)); t.playFor(30_000L)          // PLAY song 1
        t.onSongSelected(song(1)); t.playFor(30_000L)          // repeat-one boundary: a second, separate PLAY
        t.onSongSelected(song(2)); t.playFor(2_000L)           // song 2 abandoned early ...
        t.onSongSelected(song(3))                              // ... SKIP of song 2
        t.playFor(2_000L); t.onSongSelected(song(4))           // ... and SKIP of song 3

        val ids = w.plays.map { it.occ.eventId } + w.skips.map { it.occ.eventId }
        assertEquals(2, w.plays.size); assertEquals(2, w.skips.size)
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals("no id is ever reused for a different occurrence", ids.size, ids.toSet().size)
    }

    @Test fun `the occurrence time is captured at detection, not when a delayed write finally runs`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<PlayOccurrence>()
        val w = object : PlayEventWriter {
            override suspend fun recordPlay(songId: Long, contentUri: String, listenedMs: Long, durationMs: Long) = Unit
            override suspend fun recordSkip(songId: Long, contentUri: String, durationMs: Long) = Unit
            override suspend fun recordListenStart(songId: Long, contentUri: String) = Unit
            override suspend fun recordPlayOccurrence(occurrence: PlayOccurrence, songId: Long, contentUri: String, listenedMs: Long, durationMs: Long): StatsWriteResult {
                gate.await()                       // the write is delayed (e.g. waiting on SQLite)
                seen += occurrence
                return StatsWriteResult.APPLIED
            }
        }
        val t = tracker(w)
        t.onSongSelected(song(1)); t.playFor(30_000L)
        val detectedAt = wallMs
        wallMs += 600_000L                         // ten minutes pass before the write runs
        gate.complete(Unit)
        assertEquals(listOf(PlayOccurrence("id-1", detectedAt)), seen)
    }

    @Test fun `listen start carries the time of detection`() {
        val w = Recording(); val t = tracker(w)
        t.onSongSelected(song(1)); t.playFor(5_000L)
        assertEquals(listOf(wallMs), w.listenAt)
    }

    @Test fun `the tracker uses only the idempotent write path`() {
        val w = Recording(); val t = tracker(w)          // the legacy methods throw if called
        t.onSongSelected(song(1)); t.playFor(30_000L); t.onSongSelected(song(2)); t.playFor(2_000L); t.onSongSelected(song(3))
        assertEquals(1, w.plays.size); assertEquals(1, w.skips.size); assertEquals(1, w.listenAt.size)
    }
}
