package com.launchpoint.wavdrop.playback

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.model.Song
import java.util.WeakHashMap

/**
 * DEBUG-ONLY playback startup evidence (P0 large-library latency investigation).
 *
 * Correlates one playback request end to end with a per-request id and a monotonic clock, and logs one line per stage under
 * [TAG]. It observes only: it never changes queue construction, ordering, Media3 calls, crossfade or persistence, and every
 * entry point is a no-op unless [enabled] (initialised from `BuildConfig.DEBUG`, so false in release builds). Call sites pass
 * constants, ints and the existing song list / player / error objects themselves; every string is built here, after the
 * [enabled] check, so release builds do no string work for it. The only unconditional release cost is this object's
 * one-time static initialisation.
 *
 * ## Attribution (what may count as evidence for a request)
 * There is ONE active trace, but the service owns up to two physical ExoPlayers (CURRENT and, with crossfade, NEXT) and
 * callbacks are asynchronous. A callback is never assumed to belong to the active request. The request moves through phases
 * `BEGUN -> INSTALLING -> INSTALLED -> PREPARED` (driven by this controller's own calls). `INSTALLING` opens just BEFORE
 * `setMediaItems`, because a player's listeners can be invoked synchronously inside that very call, i.e. before the
 * controller's install-completion mark; that ordering is not relied on in either direction.
 *
 * The physical player that received the request (its *owner*) is identified, not assumed, by that player's OWN analytics
 * listener (so the player identity is intrinsic to the callback, never looked up later): its `PLAYLIST_CHANGED` timeline must
 *  1. arrive while the request's install window is open or later (an earlier callback is stale and ignored),
 *  2. have the same window count, and
 *  3. have the same ordered media-id content fingerprint as the list this request is about to install
 *     ([fingerprint]: every id up to [FULL_HASH_LIMIT] windows, otherwise [FULL_HASH_LIMIT] evenly strided ids plus the last).
 * Equal lengths alone never bind. If a second, different player later reports a matching playlist before the trace ends,
 * ownership is marked ambiguous and nothing is attributed (e.g. the crossfade NEXT player receiving a graft of the same queue).
 *
 *  * `audio_position_advancing` completes a trace only if the trace is PREPARED, an owner is bound and unambiguous, the event
 *    came from the owner's own listener, and the event's media item is the request's start item. Anything else is logged as
 *    `audio_position_advancing_unattributed reason=...` and never completes the trace.
 *  * Controller/service state callbacks (`*_state_*`, `*_is_playing_true`, errors) are recorded for timeline context only.
 *    Before the request's own `prepare()` they carry `attr=pre_prepare`. They are never completion evidence, except that a
 *    no-play-intent session restore ends at controller `READY` after its own prepare (`basis=ctl_ready_after_prepare`, a
 *    state-only basis).
 *
 * Known limits (documented, not hidden): the fingerprint of a queue longer than [FULL_HASH_LIMIT] is sampled, so two playlists
 * that agree on count and every sampled id would not be told apart (the two-player ambiguity rule still applies); the
 * controller exposes no player or request identity, so controller-side callbacks after `prepare()` are observations, not proof;
 * the owner is bound once and not re-evaluated across a crossfade promotion.
 *
 * Privacy: log lines carry only the request id, a fixed entry-point/stage vocabulary, queue/item counts, player serials, the
 * Media3 error *code name* and elapsed milliseconds. No titles, artists, paths, URIs or song ids are logged; the expected
 * start item id and the content fingerprint are held in memory only to compare against callbacks.
 *
 * All state is guarded by [lock]; callers run on the application looper (controller and service share the process).
 */
internal object PlaybackStartupTrace {

    const val TAG = "WavdropStartTrace"

    /** Marks arriving later than this after the request are dropped and the trace closed (stale-callback noise guard). */
    internal const val TRACE_EXPIRY_MS = 30_000L

    /** An entry-point hint older than this is ignored (the tap did not lead to a request). */
    private const val HINT_EXPIRY_MS = 5_000L

    /** Playlists up to this many windows are fingerprinted in full; longer ones are sampled at this many strided positions. */
    internal const val FULL_HASH_LIMIT = 512

    private const val NO_ID = Long.MIN_VALUE
    private const val NO_SERIAL = -1

    private enum class Phase { BEGUN, INSTALLING, INSTALLED, PREPARED }

    @Volatile internal var enabled: Boolean = BuildConfig.DEBUG
    internal var clockNanos: () -> Long = System::nanoTime
    internal var sink: (String) -> Unit = { Log.d(TAG, it) }

    private class Active(
        val id: Int,
        val entry: String,
        val cold: Boolean,
        val queueSize: Int,
        val playIntended: Boolean,
        val startNanos: Long,
        var parked: Boolean = false,
        var phase: Phase = Phase.BEGUN,
        var expectedMediaId: Long = NO_ID,
        var installedItems: Int = -1,
        var installFingerprint: Long = 0L,
        var ownerSerial: Int = NO_SERIAL,
        var ownerAmbiguous: Boolean = false,
    )

    private val lock = Any()
    private var seq = 0
    private var active: Active? = null
    private var hintEntry: String? = null
    private var hintAtNanos = 0L
    private var draining = false

    // Debug-only identity of each physical player (no strong reference, so a released player is never kept alive).
    private var playerSerials: WeakHashMap<Player, Int>? = null
    private var nextPlayerSerial = 1

    /** Test seam: clears all state and restores the production clock/sink/enabled defaults. */
    internal fun resetForTest() = synchronized(lock) {
        seq = 0
        active = null
        hintEntry = null
        hintAtNanos = 0L
        draining = false
        playerSerials = null
        nextPlayerSerial = 1
        enabled = BuildConfig.DEBUG
        clockNanos = System::nanoTime
        sink = { Log.d(TAG, it) }
    }

    // ---- content fingerprint -----------------------------------------------------------------------------------------

    /**
     * Order-sensitive content fingerprint of a playlist of [count] items whose ids are read through [idAt]. Pure, allocation
     * free (inlined). Full for `count <= FULL_HASH_LIMIT`; otherwise [FULL_HASH_LIMIT] evenly strided positions plus the last.
     */
    internal inline fun fingerprint(count: Int, idAt: (Int) -> Long): Long {
        var h = count.toLong() * -7046029254386353131L
        if (count <= FULL_HASH_LIMIT) {
            for (i in 0 until count) {
                h = (h xor idAt(i)) * 1099511628211L
                h = h xor (h ushr 29)
            }
        } else {
            for (k in 0 until FULL_HASH_LIMIT) {
                val i = (k.toLong() * count / FULL_HASH_LIMIT).toInt()
                h = (h xor idAt(i)) * 1099511628211L
                h = h xor (h ushr 29)
            }
            h = (h xor idAt(count - 1)) * 1099511628211L
            h = h xor (h ushr 29)
        }
        return h
    }

    // ---- request lifecycle ------------------------------------------------------------------------------------------

    /**
     * UI-side tap hint: names the entry point (`songs_list`, `search_tap`, ...) and starts the tap-to-request clock. A second
     * hint within the expiry only refines the name; the original tap time is kept so asynchronous steps between the tap and
     * the controller request (e.g. a settings read) are included in tap-to-request.
     */
    fun hintEntry(entry: String) {
        if (!enabled) return
        synchronized(lock) {
            val now = clockNanos()
            if (hintEntry == null || elapsedMs(hintAtNanos, now) > HINT_EXPIRY_MS) hintAtNanos = now
            hintEntry = entry
        }
    }

    /**
     * Starts a trace for a fresh playback request received by PlayerController. While a pending request is being drained
     * ([withPendingDrain]) the parked trace is resumed instead, so the pending delay and the original start time survive.
     */
    fun begin(defaultEntry: String, queueSize: Int, controllerConnected: Boolean, playIntended: Boolean = true) {
        if (!enabled) return
        synchronized(lock) {
            val now = clockNanos()
            val parked = active
            if (draining && parked != null && parked.parked) return // resumed; no new trace
            if (parked != null) emit(parked, "superseded", now)
            val hint = hintEntry?.takeIf { elapsedMs(hintAtNanos, now) <= HINT_EXPIRY_MS }
            val tapToRequestMs = if (hint != null) elapsedMs(hintAtNanos, now) else -1.0
            hintEntry = null
            seq += 1
            val trace = Active(
                id = seq,
                entry = hint ?: defaultEntry,
                cold = !controllerConnected,
                queueSize = queueSize,
                playIntended = playIntended,
                startNanos = now,
            )
            active = trace
            emit(trace, "request_received", now, if (tapToRequestMs >= 0) "tapToRequestMs=${fmt(tapToRequestMs)}" else "")
        }
    }

    /** A request refused before it could start (invalid or ambiguous start). Begins and immediately ends a trace. */
    fun rejected(defaultEntry: String, queueSize: Int, reason: String) {
        if (!enabled) return
        begin(defaultEntry, queueSize, controllerConnected = true)
        abort(reason)
    }

    /** Records the item the request intends to start with, held in memory only, to match against later callbacks. */
    fun expectStart(mediaId: Long) {
        if (!enabled) return
        synchronized(lock) { active?.expectedMediaId = mediaId }
    }

    fun mark(stage: String) = markInternal(stage, "")

    fun mark(stage: String, value: Int) {
        if (!enabled) return // checked before the template so release builds allocate nothing
        markInternal(stage, "value=$value")
    }

    private fun markInternal(stage: String, detail: String) {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            emit(trace, stage, clockNanos(), detail)
        }
    }

    fun abort(reason: String) {
        if (!enabled) return
        synchronized(lock) {
            val trace = active ?: return
            emit(trace, "aborted", clockNanos(), "reason=$reason")
            active = null
        }
    }

    // ---- install / prepare (controller side, driven by this request's own calls) --------------------------------------

    fun onMaterialized(operation: String, items: Int, cacheHits: Int, cacheMisses: Int) {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            emit(trace, "materialize_done", clockNanos(), "op=$operation items=$items hits=$cacheHits misses=$cacheMisses")
        }
    }

    /**
     * Opens the install window; call immediately BEFORE the controller's `setMediaItems` (the player's listeners can fire inside
     * that call). Records the count and content fingerprint of [songs] (ids equal the installed `MediaItem` media ids). Only a
     * request's FIRST install defines its playlist; a later install (e.g. a re-sync) is logged `reinstall=true` and changes
     * nothing.
     */
    fun onInstallStarting(operation: String, songs: List<Song>) {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            if (trace.phase != Phase.BEGUN) {
                emit(trace, "playlist_install_started", clockNanos(), "op=$operation items=${songs.size} reinstall=true")
                return
            }
            trace.installedItems = songs.size
            trace.installFingerprint = fingerprint(songs.size) { songs[it].id }
            trace.phase = Phase.INSTALLING
            emit(trace, "playlist_install_started", clockNanos(), "op=$operation items=${songs.size}")
        }
    }

    /** `setMediaItems` returned. Moves INSTALLING to INSTALLED; never touches an owner that was bound during the call. */
    fun onPlaylistInstalled(operation: String, items: Int, mutationMs: Long) {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            val detail = when (trace.phase) {
                Phase.INSTALLING -> { trace.phase = Phase.INSTALLED; "op=$operation items=$items mutationMs=$mutationMs" }
                Phase.BEGUN -> "op=$operation items=$items mutationMs=$mutationMs attr=no_install_start"
                else -> "op=$operation items=$items mutationMs=$mutationMs reinstall=true"
            }
            emit(trace, "playlist_install_done", clockNanos(), detail)
        }
    }

    fun onPrepareIssued() {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            if (trace.phase == Phase.INSTALLED) {
                trace.phase = Phase.PREPARED
                emit(trace, "prepare_issued", clockNanos())
            } else {
                emit(trace, "prepare_issued", clockNanos(), "attr=no_install_for_request")
            }
        }
    }

    // ---- pending (cold) requests ---------------------------------------------------------------------------------------

    /** The controller was unavailable: the request is parked until the pending drain. */
    fun onControllerParked() {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            trace.parked = true
            emit(trace, "controller_unavailable_parked", clockNanos())
        }
    }

    /**
     * Brackets the pending-request drain. The end is in a `finally`, so the drain flag is cleared even if the drained
     * command throws (the exception still propagates unchanged).
     */
    inline fun withPendingDrain(block: () -> Unit) {
        onPendingDrainStart()
        try {
            block()
        } finally {
            onPendingDrainEnd()
        }
    }

    fun onPendingDrainStart() {
        if (!enabled) return
        synchronized(lock) {
            draining = true
            val trace = activeOrExpire() ?: return
            if (trace.parked) emit(trace, "controller_connected_pending_drain", clockNanos())
        }
    }

    fun onPendingDrainEnd() {
        if (!enabled) return
        synchronized(lock) {
            draining = false
            active?.parked = false
        }
    }

    // ---- state callbacks (context only; never completion evidence except the documented restore basis) ------------------

    /** Controller-side ([source] = `ctl`) or service-side (`svc`) Media3 playback-state callback. */
    fun onPlayerState(source: String, playbackState: Int) {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            val name = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            emit(trace, "${source}_state_$name", clockNanos(), prePrepareDetail(trace))
            if (source == "ctl" && playbackState == Player.STATE_READY && !trace.playIntended && trace.phase == Phase.PREPARED) {
                finish(trace, "ctl_ready_after_prepare")
            }
        }
    }

    /** `onIsPlayingChanged(true)`: a Media3 state callback (playWhenReady && READY && not suppressed), NOT proof of audible output. */
    fun onIsPlaying(source: String) {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            emit(trace, "${source}_is_playing_true", clockNanos(), prePrepareDetail(trace))
        }
    }

    /** Media3 error code name only (read here, after the enabled check). The message is never logged: it can embed a file path. */
    fun onPlayerError(source: String, error: PlaybackException) {
        if (!enabled) return
        recordPlayerError(source, error.errorCodeName)
    }

    internal fun recordPlayerError(source: String, errorCodeName: String) {
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            emit(trace, "${source}_player_error", clockNanos(), "code=$errorCodeName" + prePrepareDetail(trace).let { if (it.isEmpty()) "" else " $it" })
        }
    }

    private fun prePrepareDetail(trace: Active) = if (trace.phase == Phase.PREPARED) "" else "attr=pre_prepare"

    // ---- physical-player attribution ---------------------------------------------------------------------------------------

    /** Registers [player] and returns its stable debug serial (debug builds only; weakly held). */
    internal fun registerPlayer(player: Player): Int = synchronized(lock) {
        val map = playerSerials ?: WeakHashMap<Player, Int>().also { playerSerials = it }
        map.getOrPut(player) { nextPlayerSerial++ }
    }

    internal fun serialOf(player: Player): Int = synchronized(lock) { playerSerials?.get(player) ?: NO_SERIAL }

    /**
     * Player [serial]'s own listener reported a timeline change. Only `PLAYLIST_CHANGED` can bind ownership, and only through
     * [bindOwner]; the fingerprint is computed here, after the cheap checks, and only while a trace is waiting for an owner.
     */
    internal fun onPlayerTimelineChanged(serial: Int, timeline: Timeline, reason: Int) {
        if (!enabled || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        // 0 = no trace to attribute to; 1 = before the install window (stale, no fingerprint needed); 2 = fingerprint needed.
        val need = synchronized(lock) {
            val trace = active
            if (trace == null) 0 else if (trace.phase == Phase.BEGUN) 1 else 2
        }
        if (need == 0) return
        val windows = timeline.windowCount
        val observed = if (need == 1) 0L else {
            val window = Timeline.Window() // one reusable holder instead of one allocation per window
            fingerprint(windows) { i -> timeline.getWindow(i, window).mediaItem.mediaId.toLongOrNull() ?: NO_ID }
        }
        bindOwner(serial, windows, observed)
    }

    /**
     * Pure binding rule (also the test seam). Stale (before the install window), length-only and different-content callbacks
     * never bind; a second, different player with a matching playlist makes ownership ambiguous.
     */
    internal fun bindOwner(serial: Int, windowCount: Int, observedFingerprint: Long) {
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            if (trace.phase == Phase.BEGUN) {
                emit(trace, "playlist_change_ignored", clockNanos(), "reason=before_install_window player=$serial windows=$windowCount")
                return
            }
            if (serial == NO_SERIAL || windowCount != trace.installedItems || observedFingerprint != trace.installFingerprint) {
                emit(trace, "playlist_change_unmatched", clockNanos(), "player=$serial windows=$windowCount expected=${trace.installedItems}")
                return
            }
            when {
                trace.ownerSerial == NO_SERIAL -> {
                    trace.ownerSerial = serial
                    emit(trace, "playlist_visible_on_player", clockNanos(), "owner=$serial windows=$windowCount")
                }
                trace.ownerSerial != serial && !trace.ownerAmbiguous -> {
                    trace.ownerAmbiguous = true
                    emit(trace, "owner_ambiguous", clockNanos(), "owner=${trace.ownerSerial} also=$serial")
                }
            }
        }
    }

    /**
     * ExoPlayer audio playout position started advancing on the player with [serial], for the window whose media id is
     * [mediaId] ([NO_ID] when unknown). Completes the trace ONLY when attributable; otherwise logged as unattributed.
     */
    internal fun onAudioPositionAdvancing(serial: Int, mediaId: Long) {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return // delayed callback after completion/abort/supersession: ignored
            val reason = when {
                trace.phase != Phase.PREPARED -> "before_prepare"
                trace.ownerSerial == NO_SERIAL -> "no_owner"
                trace.ownerAmbiguous -> "owner_ambiguous"
                serial != trace.ownerSerial -> "other_player"
                trace.expectedMediaId == NO_ID || mediaId == NO_ID -> "media_unknown"
                mediaId != trace.expectedMediaId -> "media_mismatch"
                else -> null
            }
            if (reason == null) {
                emit(trace, "audio_position_advancing", clockNanos(), "owner=$serial")
                finish(trace, "audio_position_advancing")
            } else {
                emit(trace, "audio_position_advancing_unattributed", clockNanos(), "reason=$reason player=$serial")
            }
        }
    }

    /** Underrun / sink error from player [serial]: context only, labelled by whether it came from the owner. Never completes. */
    internal fun onAudioDiagnostic(stage: String, serial: Int, detail: String) {
        if (!enabled) return
        synchronized(lock) {
            val trace = activeOrExpire() ?: return
            val attr = if (trace.ownerSerial != NO_SERIAL && !trace.ownerAmbiguous && serial == trace.ownerSerial) "owner" else "unattributed"
            emit(trace, stage, clockNanos(), "attr=$attr player=$serial $detail".trim())
        }
    }

    /** Media id (as a Long) of [windowIndex] in [timeline], or [NO_ID] when out of range or not numeric. Pure; unit-testable. */
    internal fun eventMediaId(timeline: Timeline, windowIndex: Int): Long {
        if (windowIndex < 0 || windowIndex >= timeline.windowCount) return NO_ID
        return timeline.getWindow(windowIndex, Timeline.Window()).mediaItem.mediaId.toLongOrNull() ?: NO_ID
    }

    /**
     * Observation-only analytics for ONE physical player (DEBUG builds only, created by [defaultPhysicalPlayerFactory]). The
     * listener closes over its own player's serial, so a callback is always attributed to the player that produced it. It
     * never touches player state, so it is independent of crossfade slot promotion.
     */
    @UnstableApi
    internal fun analyticsListenerFor(player: Player): AnalyticsListener {
        val serial = registerPlayer(player)
        return object : AnalyticsListener {
            override fun onTimelineChanged(eventTime: AnalyticsListener.EventTime, reason: Int) {
                if (!PlaybackStartupTrace.enabled) return
                PlaybackStartupTrace.onPlayerTimelineChanged(serial, eventTime.timeline, reason)
            }

            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                if (!PlaybackStartupTrace.enabled) return
                PlaybackStartupTrace.onAudioPositionAdvancing(serial, eventMediaId(eventTime.timeline, eventTime.windowIndex))
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                if (!PlaybackStartupTrace.enabled) return
                PlaybackStartupTrace.onAudioDiagnostic("audio_underrun", serial, "bufferSize=$bufferSize")
            }

            override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                if (!PlaybackStartupTrace.enabled) return
                PlaybackStartupTrace.onAudioDiagnostic("audio_sink_error", serial, "type=${audioSinkError.javaClass.simpleName}")
            }
        }
    }

    // ---- internals ------------------------------------------------------------------------------------------------------

    private fun finish(trace: Active, basis: String) {
        emit(trace, "complete", clockNanos(), "basis=$basis")
        if (active === trace) active = null
    }

    private fun activeOrExpire(): Active? {
        val trace = active ?: return null
        if (elapsedMs(trace.startNanos, clockNanos()) > TRACE_EXPIRY_MS) {
            emit(trace, "expired", clockNanos())
            active = null
            return null
        }
        return trace
    }

    private fun emit(trace: Active, stage: String, nowNanos: Long, detail: String = "") {
        val line = "id=${trace.id} entry=${trace.entry} cold=${trace.cold} queue=${trace.queueSize} " +
            "stage=$stage +${fmt(elapsedMs(trace.startNanos, nowNanos))}ms" + if (detail.isEmpty()) "" else " $detail"
        sink(line)
    }

    private fun elapsedMs(fromNanos: Long, toNanos: Long): Double = (toNanos - fromNanos) / 1_000_000.0

    private fun fmt(ms: Double) = "%.2f".format(java.util.Locale.ROOT, ms)
}
