package com.launchpoint.wavdrop.playback

import android.os.SystemClock
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.StatsRepository
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
    private val statsRepository: StatsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentSong: Song? = null

    // Wall-clock ms (from SystemClock.elapsedRealtime) when the current play
    // session started. -1 means not currently playing.
    private var sessionStartedAt: Long = -1L

    // Total listening time accumulated for the current song across all
    // play/pause cycles in this play session.
    private var accumulatedMs: Long = 0L

    // Guard: once we've counted a meaningful play we don't count again for the
    // same continuous play session (even if the user pauses and resumes).
    private var playCountedForCurrent: Boolean = false

    // ── Public API (called from PlayerController on the main thread) ──────────

    /**
     * Must be called when the user selects a new song, before the controller
     * starts loading it. Finalises stats for the previous song.
     */
    fun onSongSelected(newSong: Song) {
        if (currentSong?.id == newSong.id) return   // same song re-tapped, ignore
        finalizeCurrentSong(explicitSkip = !playCountedForCurrent)
        currentSong         = newSong
        accumulatedMs       = 0L
        sessionStartedAt    = -1L
        playCountedForCurrent = false
    }

    /** Called when isPlaying transitions to true. */
    fun onPlaybackStarted() {
        sessionStartedAt = SystemClock.elapsedRealtime()
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
        accumulatedMs   += SystemClock.elapsedRealtime() - start
        sessionStartedAt = -1L
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
                statsRepository.recordPlay(song.id, song.uri, listenedMs, durationMs)
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
                scope.launch { statsRepository.recordSkip(song.id, song.uri, song.duration) }
            }
        }
    }
}
