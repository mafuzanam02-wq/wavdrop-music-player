package com.launchpoint.wavdrop.playback

import android.os.SystemClock
import android.util.Log
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks per-song listening statistics on behalf of the playback layer.
 *
 * Meaningful-play threshold: min(30 s, 50 % of track duration).
 * A play is counted the first time accumulated listening time crosses that
 * threshold. Switching away before the threshold is counted as a skip.
 *
 * All heavy work (Room writes) runs on a private IO coroutine scope so
 * callers never block the main thread.
 */
@Singleton
class StatsTracker @Inject constructor(
    private val playEventWriter: PlayEventWriter,
) {
    private companion object {
        const val TAG = "WavStats-ST"

        const val DEBUG_STATS = false
    }

    // Overridable in tests (Dispatchers.Unconfined makes launch calls synchronous).
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentSong: Song? = null

    // Wall-clock ms when the current play session started. -1 means not currently playing.
    private var sessionStartedAt: Long = -1L

    // Total listening time accumulated for the current song across all play/pause cycles.
    private var accumulatedMs: Long = 0L

    // Guard: once we've counted a meaningful play we don't count again for the same
    // continuous play session (even if the user pauses and resumes).
    private var playCountedForCurrent: Boolean = false

    // Overridable in tests (internal visibility keeps it out of production call sites).
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    // ── Public API (called from PlayerController on the main thread) ──────────

    /**
     * Must be called when the user selects a song or a loop boundary is detected.
     * Finalises stats for the previous song (or previous loop of the same song).
     *
     * Same-song path (REPEAT_ONE loop, user replay): flushes the active session so
     * the completed loop's time is credited before resetting for the next loop.
     * Without the flush, continuous play (no pause) would discard each loop's time.
     */
    fun onSongSelected(newSong: Song) {
        if (DEBUG_STATS) {
            Log.d(TAG, "[onSongSelected] newId=${newSong.id} currentId=${currentSong?.id} " +
                "same=${currentSong?.id == newSong.id} " +
                "playCountedForCurrent=$playCountedForCurrent " +
                "accumulatedMs=$accumulatedMs sessionStartedAt=$sessionStartedAt")
        }
        if (currentSong?.id == newSong.id) {
            // Same song re-selected (REPEAT_ONE loop boundary, user replay, etc.).
            // Flush any in-progress session first so the time already played in this
            // loop is credited and the threshold can be checked — then reset cleanly
            // for the next session. Without the flush, continuous REPEAT_ONE play
            // (no pause between loops) would silently discard each loop's listen time.
            flushSession()
            checkAndRecordPlay()
            accumulatedMs         = 0L
            sessionStartedAt      = -1L
            playCountedForCurrent = false
            if (DEBUG_STATS) Log.d(TAG, "[onSongSelected] same-song reset done")
            return
        }
        finalizeCurrentSong(explicitSkip = !playCountedForCurrent)
        currentSong           = newSong
        accumulatedMs         = 0L
        sessionStartedAt      = -1L
        playCountedForCurrent = false
        if (DEBUG_STATS) Log.d(TAG, "[onSongSelected] switched to new song ${newSong.id}")
    }

    /** Called when isPlaying transitions to true. */
    fun onPlaybackStarted() {
        sessionStartedAt = clock()
        if (DEBUG_STATS) Log.d(TAG, "[onPlaybackStarted] songId=${currentSong?.id} sessionStartedAt=$sessionStartedAt")
    }

    /**
     * Called when isPlaying transitions to false (pause, audio focus loss,
     * headphone disconnect, etc.).
     */
    fun onPlaybackPaused() {
        if (DEBUG_STATS) Log.d(TAG, "[onPlaybackPaused] songId=${currentSong?.id} sessionStartedAt=$sessionStartedAt accumulatedMs=$accumulatedMs")
        flushSession()
        checkAndRecordPlay()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Accumulate the current in-progress play session into accumulatedMs. */
    private fun flushSession() {
        val start = sessionStartedAt
        if (start < 0L) {
            if (DEBUG_STATS) Log.d(TAG, "[flushSession] skipped (sessionStartedAt=$start)")
            return
        }
        val elapsed = clock() - start
        accumulatedMs    += elapsed
        sessionStartedAt  = -1L
        if (DEBUG_STATS) Log.d(TAG, "[flushSession] elapsed=$elapsed newAccumulatedMs=$accumulatedMs songId=${currentSong?.id}")
    }

    /**
     * Check whether the meaningful-play threshold has been crossed and, if so,
     * record a play event exactly once per song selection.
     */
    private fun checkAndRecordPlay() {
        if (playCountedForCurrent) {
            if (DEBUG_STATS) Log.d(TAG, "[checkAndRecordPlay] skipped — already counted for this session (songId=${currentSong?.id})")
            return
        }
        val song = currentSong ?: run {
            if (DEBUG_STATS) Log.d(TAG, "[checkAndRecordPlay] skipped — no current song")
            return
        }
        // threshold = min(30 s, 50 % of track duration)
        val threshold = minOf(30_000L, song.duration / 2L)
        if (threshold <= 0L) {
            if (DEBUG_STATS) Log.d(TAG, "[checkAndRecordPlay] skipped — malformed threshold=$threshold songId=${song.id}")
            return
        }
        val passed = accumulatedMs >= threshold
        if (DEBUG_STATS) Log.d(TAG, "[checkAndRecordPlay] songId=${song.id} accumulatedMs=$accumulatedMs threshold=$threshold passed=$passed")
        if (passed) {
            playCountedForCurrent = true
            val listenedMs = accumulatedMs
            val durationMs = song.duration
            if (DEBUG_STATS) Log.d(TAG, "[checkAndRecordPlay] → calling recordPlay songId=${song.id} listenedMs=$listenedMs durationMs=$durationMs")
            scope.launch {
                playEventWriter.recordPlay(song.id, song.uri, listenedMs, durationMs)
            }
        }
    }

    /**
     * Flush pending time, attempt a final threshold check, then optionally
     * record a skip if the song was abandoned before the threshold.
     */
    private fun finalizeCurrentSong(explicitSkip: Boolean) {
        flushSession()
        checkAndRecordPlay()        // might push over threshold right at the end

        if (explicitSkip && !playCountedForCurrent) {
            val song = currentSong ?: return
            // Only count as a skip if there was any engagement at all.
            if (accumulatedMs > 0L) {
                scope.launch { playEventWriter.recordSkip(song.id, song.uri, song.duration) }
            }
        }
    }
}
