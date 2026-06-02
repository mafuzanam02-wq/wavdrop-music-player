package com.launchpoint.wavdrop.playback

import android.os.SystemClock
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
     * Must be called when the user selects a song, before the controller starts loading it.
     * Finalises stats for the previous song.
     *
     * If the same song is re-selected (e.g. user taps it again or it loops back), the session
     * is reset so the replay can be counted — but [finalizeCurrentSong] is NOT called, which
     * would otherwise record a spurious skip.
     */
    fun onSongSelected(newSong: Song) {
        if (currentSong?.id == newSong.id) {
            // Same song re-selected: reset session so a fresh play can be counted.
            accumulatedMs = 0L
            sessionStartedAt = -1L
            playCountedForCurrent = false
            return
        }
        finalizeCurrentSong(explicitSkip = !playCountedForCurrent)
        currentSong           = newSong
        accumulatedMs         = 0L
        sessionStartedAt      = -1L
        playCountedForCurrent = false
    }

    /** Called when isPlaying transitions to true. */
    fun onPlaybackStarted() {
        sessionStartedAt = clock()
    }

    /**
     * Called when isPlaying transitions to false (pause, audio focus loss,
     * headphone disconnect, etc.).
     */
    fun onPlaybackPaused() {
        flushSession()
        checkAndRecordPlay()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Accumulate the current in-progress play session into accumulatedMs. */
    private fun flushSession() {
        val start = sessionStartedAt
        if (start < 0L) return
        accumulatedMs    += clock() - start
        sessionStartedAt  = -1L
    }

    /**
     * Check whether the meaningful-play threshold has been crossed and, if so,
     * record a play event exactly once per song selection.
     */
    private fun checkAndRecordPlay() {
        if (playCountedForCurrent) return
        val song = currentSong ?: return
        // threshold = min(30 s, 50 % of track duration)
        val threshold = minOf(30_000L, song.duration / 2L)
        if (threshold <= 0L) return             // malformed duration, skip
        if (accumulatedMs >= threshold) {
            playCountedForCurrent = true
            val listenedMs = accumulatedMs
            val durationMs = song.duration
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
