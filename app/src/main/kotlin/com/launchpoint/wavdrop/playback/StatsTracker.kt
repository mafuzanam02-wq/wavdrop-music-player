package com.launchpoint.wavdrop.playback

import android.os.SystemClock
import android.util.Log
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.repository.PlayEventWriter
import com.launchpoint.wavdrop.data.repository.PlayOccurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID
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

        // Minimum accumulated playback time required to update lastListenedAt.
        // Deliberately lower than the meaningful-play threshold so that rapid Next
        // still registers in Recently Played without inflating play counts.
        const val LISTEN_THRESHOLD_MS = 5_000L
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

    // Guard: once lastListenedAt has been written for the current song we don't write it
    // again during the same continuous song session. Resets on song change (including
    // same-song REPEAT_ONE loop boundary) so each fresh selection can qualify again.
    private var listenStartWrittenForCurrent: Boolean = false

    // Overridable in tests (internal visibility keeps it out of production call sites).
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    // P5-STATS-19B: wall-clock epoch ms used to stamp an occurrence AT DETECTION (clock above is monotonic elapsed time, for durations).
    internal var wallClock: () -> Long = { System.currentTimeMillis() }

    // P5-STATS-19B: a new id per detected PLAY/SKIP occurrence. Generated here, before persistence, so every later attempt to persist
    // the same occurrence can pass the same id (retry scheduling itself is out of scope for this slice).
    internal var newEventId: () -> String = { UUID.randomUUID().toString() }

    private fun newOccurrence() = PlayOccurrence(newEventId(), wallClock())

    // P5-STATS-19C: every write goes through ONE bounded, serialized, retrying queue. Callbacks only offer to it (never blocks); the
    // single consumer runs on [scope]. Created lazily so tests can set [scope] / [deliveryPolicy] / [deliveryListener] first.
    internal var deliveryPolicy: StatsDeliveryPolicy = StatsDeliveryPolicy()
    internal var retryDelayer: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    internal var deliveryListener: (StatsDeliveryOutcome) -> Unit = ::logDelivery
    private val delivery by lazy {
        StatsDeliveryQueue({ scope }, playEventWriter, deliveryPolicy, { retryDelayer(it) }) { deliveryListener(it) }
    }

    /** Diagnostic counters (successful / already-applied / retrying / unresolved / abandoned / failed / rejected). */
    internal fun deliveryCount(state: StatsDeliveryState): Int = delivery.count(state)
    internal val deliveryParkedCount: Int get() = delivery.parkedCount

    private fun logDelivery(o: StatsDeliveryOutcome) {
        when (o.state) {
            StatsDeliveryState.APPLIED, StatsDeliveryState.ALREADY_APPLIED -> if (DEBUG_STATS) Log.d(TAG, "delivery $o")
            StatsDeliveryState.RETRYING, StatsDeliveryState.UNRESOLVED -> Log.w(TAG, "delivery $o")
            StatsDeliveryState.ABANDONED, StatsDeliveryState.FAILED, StatsDeliveryState.REJECTED -> Log.e(TAG, "STATS DATA LOST: $o")
        }
    }

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
            checkAndRecordListenStart()
            accumulatedMs                = 0L
            sessionStartedAt             = -1L
            playCountedForCurrent        = false
            listenStartWrittenForCurrent = false
            if (DEBUG_STATS) Log.d(TAG, "[onSongSelected] same-song reset done")
            return
        }
        finalizeCurrentSong(explicitSkip = !playCountedForCurrent)
        currentSong                  = newSong
        accumulatedMs                = 0L
        sessionStartedAt             = -1L
        playCountedForCurrent        = false
        listenStartWrittenForCurrent = false
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
        checkAndRecordListenStart()
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
            val occurrence = newOccurrence()   // identity + time fixed NOW, not inside the (possibly delayed) write
            delivery.submit(StatsTask.Play(occurrence, song.id, song.uri, listenedMs, durationMs))
        }
    }

    /**
     * Check whether the 5-second listen threshold has been crossed and, if so,
     * write lastListenedAt exactly once per song selection.
     * Shares [accumulatedMs] with the meaningful-play check — no separate timer needed.
     */
    private fun checkAndRecordListenStart() {
        if (listenStartWrittenForCurrent) return
        val song = currentSong ?: return
        if (accumulatedMs < LISTEN_THRESHOLD_MS) return
        listenStartWrittenForCurrent = true
        if (DEBUG_STATS) Log.d(TAG, "[checkAndRecordListenStart] songId=${song.id} accumulatedMs=$accumulatedMs → writing lastListenedAt")
        val listenedAt = wallClock()
        delivery.submit(StatsTask.ListenStart(listenedAt, song.id, song.uri))
    }

    /**
     * Flush pending time, attempt a final threshold check, then optionally
     * record a skip if the song was abandoned before the threshold.
     */
    private fun finalizeCurrentSong(explicitSkip: Boolean) {
        flushSession()
        checkAndRecordPlay()        // might push over threshold right at the end
        checkAndRecordListenStart() // might push over 5 s threshold right at the end

        if (explicitSkip && !playCountedForCurrent) {
            val song = currentSong ?: return
            // Only count as a skip if there was any engagement at all.
            if (accumulatedMs > 0L) {
                val occurrence = newOccurrence()
                delivery.submit(StatsTask.Skip(occurrence, song.id, song.uri, song.duration))
            }
        }
    }
}
