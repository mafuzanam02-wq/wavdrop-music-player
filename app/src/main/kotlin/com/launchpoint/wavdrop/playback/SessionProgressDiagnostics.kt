package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player

/**
 * LS-1: DEBUG-ONLY evidence for the system-surface (lock screen / media controls) progress bar. It is created by
 * [PlaybackService] only when `BuildConfig.DEBUG`, only READS the session-facing player, and never writes to it: no seek, no
 * invalidation, no metadata or layout update, no position of its own. If a system surface freezes on a physical phone, the log
 * shows whether the physical CURRENT player and WavDrop's in-process session-facing Player continue advancing. If both advance,
 * the freeze is downstream of the session-facing Player; this diagnostic alone cannot identify MediaSession transport, the
 * framework or OEM SystemUI as the cause. If they diverge, reopen the WavDrop session investigation.
 *
 * One line per meaningful state change, plus a slow heartbeat while playing ([HEARTBEAT_MS]). Event lines are rate-limited
 * ([MIN_EVENT_GAP_MS]) so a seek scrub or a crossfade cannot flood the log. Lines carry numbers and flags only: no title,
 * artist, file name or path.
 */
internal class SessionProgressDiagnostics(
    private val sessionPlayer: Player,
    /** The physical player that is the logical CURRENT right now (it changes at promotion, so it is re-read on every line). */
    private val physicalCurrent: () -> Player,
    private val promotionActive: () -> Boolean,
    private val elapsedRealtimeMs: () -> Long,
    private val log: (String) -> Unit,
) : Player.Listener {

    private var lastEventLineAtMs = Long.MIN_VALUE
    private var closed = false

    fun start() {
        sessionPlayer.addListener(this)
        emit("start")
    }

    fun close() {
        if (closed) return
        closed = true
        sessionPlayer.removeListener(this)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (closed) return
        val reason = reasonFor(events) ?: return
        val now = elapsedRealtimeMs()
        if (!shouldEmitEventLine(lastEventLineAtMs, now)) return
        lastEventLineAtMs = now
        emit(reason)
    }

    /** Called on a slow timer by the service while the service lives. Writes a line only while the session is playing. */
    fun heartbeat() {
        if (closed || !sessionPlayer.isPlaying) return
        emit("heartbeat")
    }

    private fun emit(reason: String) {
        val physical = physicalCurrent()
        log(
            formatSessionProgressLine(
                reason = reason,
                elapsedRealtimeMs = elapsedRealtimeMs(),
                physicalPositionMs = physical.currentPosition,
                sessionPositionMs = sessionPlayer.currentPosition,
                playbackState = sessionPlayer.playbackState,
                playWhenReady = sessionPlayer.playWhenReady,
                isPlaying = sessionPlayer.isPlaying,
                mediaItemIndex = sessionPlayer.currentMediaItemIndex,
                promotionActive = promotionActive(),
            ),
        )
    }

    companion object {
        const val TAG = "WavdropSessionProgress"

        /** Slow, bounded: far longer than any UI tick, only while playing, DEBUG builds only. */
        const val HEARTBEAT_MS = 10_000L

        /** At most one event-driven line per this many milliseconds. */
        const val MIN_EVENT_GAP_MS = 250L

        fun shouldEmitEventLine(lastLineAtMs: Long, nowMs: Long): Boolean =
            lastLineAtMs == Long.MIN_VALUE || nowMs - lastLineAtMs >= MIN_EVENT_GAP_MS

        /** The state change that matters to a surface, or null for an event that cannot affect the progress bar. */
        fun reasonFor(events: Player.Events): String? = when {
            events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) -> "transition"
            events.contains(Player.EVENT_POSITION_DISCONTINUITY) -> "discontinuity"
            events.contains(Player.EVENT_IS_PLAYING_CHANGED) -> "isPlaying"
            events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) -> "suppression"
            events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) -> "playWhenReady"
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) -> "state"
            else -> null
        }
    }
}

/** One diagnostic line: numbers and flags only (no title, artist, file name or path). */
internal fun formatSessionProgressLine(
    reason: String,
    elapsedRealtimeMs: Long,
    physicalPositionMs: Long,
    sessionPositionMs: Long,
    playbackState: Int,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    mediaItemIndex: Int,
    promotionActive: Boolean,
): String =
    "reason=$reason t=$elapsedRealtimeMs physical=$physicalPositionMs session=$sessionPositionMs " +
        "state=$playbackState playWhenReady=$playWhenReady isPlaying=$isPlaying index=$mediaItemIndex promotion=$promotionActive"
