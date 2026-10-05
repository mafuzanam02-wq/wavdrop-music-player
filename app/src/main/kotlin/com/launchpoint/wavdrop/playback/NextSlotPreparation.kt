package com.launchpoint.wavdrop.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * CF-2M4: one immutable preparation request, built from ONE coherent logical snapshot. [queue] is the full playback-order
 * materialization of the queue generation named by [key]; the target (B) is the item at `key.toPlaybackIndex`. Identity is
 * positional: duplicate songs (equal mediaIds) are distinguished only by index.
 */
internal class NextSlotRequest(
    val key: CrossfadeTransitionKey,
    val queue: List<MediaItem>,
    /** `Player.REPEAT_MODE_*` to mirror onto NEXT once it holds the full queue. Never enables Media3 shuffle. */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    init {
        require(key.toPlaybackIndex in queue.indices) { "target index ${key.toPlaybackIndex} outside queue of ${queue.size}" }
        require(key.fromPlaybackIndex in queue.indices) { "source index ${key.fromPlaybackIndex} outside queue of ${queue.size}" }
        require(key.fromPlaybackIndex != key.toPlaybackIndex) { "a transition needs two distinct occurrences" }
    }

    val target: MediaItem get() = queue[key.toPlaybackIndex]
}

internal enum class NextSlotFailure {
    /** NEXT reported a physical player error while preparing B (or while Ready). */
    PrepareError,

    /** NEXT reached ENDED before it ever became READY. */
    EndedBeforeReady,

    /** A Media3 insert threw (any graft chunk). */
    GraftError,

    /** The physical timeline did not equal the logical queue (between chunks, or in the final verification). */
    TimelineMismatch,
}

/** Explicit preparation state. At most one request owns NEXT; every state except [Idle] names the owning key and token. */
internal sealed interface NextSlotState {
    data object Idle : NextSlotState

    /** ONLY the target B is loaded on NEXT and preparing; NEXT is paused. */
    data class PreparingTarget(val key: CrossfadeTransitionKey, val token: Long) : NextSlotState

    /** B is READY on NEXT; the graft is about to be scheduled (transient, synchronous with the READY observation). */
    data class TargetReady(val key: CrossfadeTransitionKey, val token: Long) : NextSlotState

    /**
     * The rest of the queue is being inserted around the already-loaded B, in bounded chunks across looper turns. NEXT is
     * partially grafted and is NEVER usable in this state; only the final verification produces [Ready].
     */
    data class Grafting(val key: CrossfadeTransitionKey, val token: Long) : NextSlotState

    /** NEXT mirrors the logical queue, B is current and READY, paused and silent. The only state future promotion may use. */
    data class Ready(val key: CrossfadeTransitionKey, val token: Long) : NextSlotState

    /** This transition cannot be prepared. Transition-local: nothing else is affected; NEXT is inert again. */
    data class Failed(val key: CrossfadeTransitionKey, val reason: NextSlotFailure) : NextSlotState
}

internal val NextSlotState.key: CrossfadeTransitionKey?
    get() = when (this) {
        NextSlotState.Idle -> null
        is NextSlotState.PreparingTarget -> key
        is NextSlotState.TargetReady -> key
        is NextSlotState.Grafting -> key
        is NextSlotState.Ready -> key
        is NextSlotState.Failed -> key
    }

internal enum class NextSlotGraftPhase { Before, After, Verify }

/** Progress of the ACTIVE graft attempt (read-only; for tests and diagnostics). */
internal data class NextSlotGraftProgress(
    val phase: NextSlotGraftPhase,
    val beforeInserted: Int,
    val afterInserted: Int,
    val verified: Int,
)

/** Bounded measurements of the last completed graft (nanoseconds; JVM/Robolectric numbers are shape evidence only). */
internal data class NextSlotGraftTiming(
    val queueSize: Int,
    val beforeCount: Int,
    val afterCount: Int,
    /** Number of Media3 insertion calls (chunks) across both phases. */
    val chunkCount: Int,
    /** The largest single Media3 insertion call: the main-thread mutation burst the chunking bounds. */
    val maxChunkNanos: Long,
    /** The largest single verification turn. */
    val maxVerifyNanos: Long,
    /** Every insertion call duration in order (bounded: one per chunk). */
    val chunkNanos: List<Long>,
    val prependNanos: Long,
    val appendNanos: Long,
    val verifyNanos: Long,
    /** Wall time from the first graft turn to Ready, INCLUDING the yields between turns. */
    val totalNanos: Long,
)

/** Posts work to the NEXT player's application looper, one bounded turn at a time. Never a background thread. */
internal interface NextSlotGraftScheduler {
    fun post(block: () -> Unit)

    /** Drops every posted-but-not-yet-run callback (the token guard still makes any survivor inert). */
    fun cancelAll()
}

internal class LooperNextSlotGraftScheduler(looper: Looper) : NextSlotGraftScheduler {
    private val handler = Handler(looper)
    override fun post(block: () -> Unit) {
        handler.post(block)
    }

    override fun cancelAll() = handler.removeCallbacksAndMessages(null)
}

/**
 * CF-2M4: owns ONLY the lifecycle of NEXT's preparation: one requested key, a monotonic token, the explicit state, "prepare
 * the target B alone, then graft the queue around it in bounded chunks", observing physical READY/failure, and
 * invalidation/reset.
 *
 * It never promotes, starts, fades, owns focus/session/stats/queue planning, or creates a player. It drives the engine's NEXT
 * physical player only (never CURRENT, never the façade), so none of its physical events can become a logical Player event.
 * Main-thread confined: every player mutation (including every graft chunk) runs on the player's application looper.
 *
 * Staleness: every physical callback is delivered by a per-attempt observer that carries the attempt's token, and every
 * scheduled graft turn is bound to the same token and re-validated before it touches the player (owner live, token, state
 * Grafting, same physical NEXT player). Invalidation, supersession, failure and release also drop all pending turns.
 */
internal class NextSlotPreparation<P : Player>(
    private val nextPlayerProvider: () -> P,
    /** DEBUG-style bounded diagnostics (no file names or paths); null in release. */
    private val perfLog: ((String) -> Unit)? = null,
    /** Looper-turn scheduler for graft chunks. Default: a Handler on the NEXT player's application looper. */
    graftScheduler: NextSlotGraftScheduler? = null,
    /**
     * CF-2M5: false while the engine owns NEXT for something else (a retiring player during an overlap, or after release).
     * A request is then refused and the state is left as it is; nothing is prepared on a retiring physical.
     */
    private val canAccept: () -> Boolean = { true },
) {
    private val scheduler: NextSlotGraftScheduler by lazy {
        graftScheduler ?: LooperNextSlotGraftScheduler(nextPlayerProvider().applicationLooper)
    }
    private var schedulerUsed = false

    var state: NextSlotState = NextSlotState.Idle
        private set

    private var token = 0L
    private var released = false
    private var attempt: Attempt? = null

    var preparationsStarted: Int = 0
        private set
    var graftsCompleted: Int = 0
        private set
    var promotionsConsumed: Int = 0
        private set
    var invalidations: Int = 0
        private set
    var lastFailure: NextSlotFailure? = null
        private set
    var lastInvalidationReason: CrossfadeCancelReason? = null
        private set
    var lastGraftTiming: NextSlotGraftTiming? = null
        private set

    /** Progress of the active graft attempt, or null when no graft is in flight. */
    val graftProgress: NextSlotGraftProgress?
        get() = attempt?.takeIf { state is NextSlotState.Grafting }?.let {
            NextSlotGraftProgress(it.phase, it.beforeInserted, it.afterInserted, it.verified)
        }

    private inner class Attempt(
        val token: Long,
        val player: P,
        val request: NextSlotRequest,
    ) : Player.Listener {
        // Graft progress. Bounded and owned by this attempt only: a superseded attempt's counters can never leak into the next.
        var phase = NextSlotGraftPhase.Before
        var beforeInserted = 0
        var afterInserted = 0
        var verified = 0
        var chunks = 0
        val chunkNanos = ArrayList<Long>()
        var maxChunkNanos = 0L
        var maxVerifyNanos = 0L
        var prependNanos = 0L
        var appendNanos = 0L
        var verifyNanos = 0L
        var startedAtNanos = 0L

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> onPhysicalReady(token)
                Player.STATE_ENDED -> onPhysicalEnded(token)
            }
        }

        override fun onPlayerError(error: PlaybackException) = onPhysicalError(token)
    }

    /**
     * Requests preparation of [request]'s transition. Same key while Preparing/Grafting/Ready/Failed is idempotent (no second
     * prepare, no retry storm). A different key supersedes: the old ownership (and every pending graft turn) is invalidated
     * first and a fresh token is assigned.
     */
    /** CF-2M5: whether a preparation request would currently be accepted (false while a retiring player occupies NEXT). */
    val accepting: Boolean get() = !released && canAccept()

    fun request(request: NextSlotRequest): NextSlotState {
        if (released) return state
        if (!canAccept()) return state
        if (state.key == request.key) return state
        if (state !is NextSlotState.Idle) invalidate(null)
        val player = nextPlayerProvider()
        token++
        val attempt = Attempt(token, player, request)
        this.attempt = attempt
        preparationsStarted++
        state = NextSlotState.PreparingTarget(request.key, token)
        // Target-first: NEXT holds ONLY B, paused, silent (no focus: NEXT is never observed by the focus owner).
        resetPhysical(player)
        player.addListener(attempt)
        player.setMediaItem(request.target)
        player.prepare()
        return state
    }

    /** Ends ownership of any prepared/preparing/grafting transition. NEXT returns to empty, idle, paused. Harmless when Idle. */
    fun invalidate(reason: CrossfadeCancelReason?) {
        if (state is NextSlotState.Idle) return
        token++ // every callback and every scheduled graft turn from the ended attempt is now stale
        cancelPendingTurns()
        val ended = attempt
        attempt = null
        state = NextSlotState.Idle
        invalidations++
        lastInvalidationReason = reason
        if (ended != null) {
            ended.player.removeListener(ended)
            resetPhysical(ended.player)
        }
    }

    /**
     * CF-2M5: hands the READY-and-fully-grafted NEXT player to promotion. Verifies the exact [key] is `Ready`, then ends this
     * owner's involvement WITHOUT touching the player: the observer is detached, pending turns are dropped, the token dies and
     * the state becomes Idle, but the player keeps its prepared contents. Returns the player, or null when [key] is not the
     * Ready preparation (stale key, not Ready, released). Unlike [invalidate] this never resets NEXT.
     */
    fun consumeReadyForPromotion(key: CrossfadeTransitionKey): P? {
        if (released) return null
        val ready = state as? NextSlotState.Ready ?: return null
        if (ready.key != key) return null
        val a = attempt ?: return null
        if (a.token != ready.token) return null
        token++
        cancelPendingTurns()
        a.player.removeListener(a)
        attempt = null
        state = NextSlotState.Idle
        promotionsConsumed++
        return a.player
    }

    /** Engine teardown: detach the observer, drop pending turns and stop owning anything. The engine releases the player. */
    fun release() {
        if (released) return
        released = true
        token++
        cancelPendingTurns()
        attempt?.let { it.player.removeListener(it) }
        attempt = null
        state = NextSlotState.Idle
    }

    private fun cancelPendingTurns() {
        if (schedulerUsed) scheduler.cancelAll()
    }

    private fun postGraftTurn(forToken: Long) {
        schedulerUsed = true
        scheduler.post { graftTurn(forToken) }
    }

    // ── physical observation (token-gated) ───────────────────────────────────────────────────────────────────────────────

    internal fun onPhysicalReady(callbackToken: Long) {
        if (released || callbackToken != token) return
        val preparing = state as? NextSlotState.PreparingTarget ?: return
        val current = attempt ?: return
        val player = current.player
        // READY is only the trigger; the live physical facts must agree (guards a READY that raced a newer request).
        if (player.playbackState != Player.STATE_READY || player.mediaItemCount != 1 || player.playWhenReady) return
        state = NextSlotState.TargetReady(preparing.key, preparing.token)
        // The READY callback turn does no insertion: the first chunk runs in its own turn.
        state = NextSlotState.Grafting(preparing.key, preparing.token)
        current.startedAtNanos = System.nanoTime()
        postGraftTurn(current.token)
    }

    internal fun onPhysicalError(callbackToken: Long) {
        if (released || callbackToken != token) return
        val key = state.key ?: return
        if (state is NextSlotState.Failed) return
        fail(key, NextSlotFailure.PrepareError)
    }

    private fun onPhysicalEnded(callbackToken: Long) {
        if (released || callbackToken != token) return
        if (state !is NextSlotState.PreparingTarget) return
        fail(state.key!!, NextSlotFailure.EndedBeforeReady)
    }

    // ── chunked graft ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * ONE bounded unit of graft work, then yield. Ordering (B starts as the sole item at index 0):
     *  - BEFORE phase: chunk k of `queue[0 until to]` is inserted at index `beforeInserted` (immediately before B, which
     *    shifts right each time). Forward chunks at a growing index keep the original order; inserting each chunk at index 0 would reverse it.
     *  - AFTER phase: chunks of `queue[to+1 until end]` are appended in order.
     *  - VERIFY phase: per-index `mediaId` diagnostics in bounded slices, then the final count/index/paused/repeat checks.
     * B is never seeked or re-prepared: Media3 keeps its period while items are inserted around it.
     */
    private fun graftTurn(turnToken: Long) {
        // Stale-work protection: owner live, token, state Grafting, and the same physical NEXT player.
        if (released || turnToken != token) return
        val grafting = state as? NextSlotState.Grafting ?: return
        val a = attempt ?: return
        if (grafting.token != turnToken || a.token != turnToken) return
        if (nextPlayerProvider() !== a.player) {
            invalidate(null)
            return
        }
        val player = a.player
        val queue = a.request.queue
        val to = a.request.key.toPlaybackIndex
        val afterTotal = queue.size - to - 1
        try {
            // Normalize empty phases so a turn never idles.
            if (a.phase == NextSlotGraftPhase.Before && a.beforeInserted >= to) a.phase = NextSlotGraftPhase.After
            if (a.phase == NextSlotGraftPhase.After && a.afterInserted >= afterTotal) a.phase = NextSlotGraftPhase.Verify

            // B must still be the sole anchor of exactly what we inserted so far.
            if (a.phase != NextSlotGraftPhase.Verify &&
                (player.mediaItemCount != 1 + a.beforeInserted + a.afterInserted || player.currentMediaItemIndex != a.beforeInserted)
            ) {
                fail(a.request.key, NextSlotFailure.TimelineMismatch)
                return
            }
            when (a.phase) {
                NextSlotGraftPhase.Before -> {
                    val n = minOf(GRAFT_CHUNK_SIZE, to - a.beforeInserted)
                    val t0 = System.nanoTime()
                    player.addMediaItems(a.beforeInserted, queue.subList(a.beforeInserted, a.beforeInserted + n))
                    val dt = System.nanoTime() - t0
                    a.beforeInserted += n
                    a.chunks++
                    a.prependNanos += dt
                    a.chunkNanos += dt
                    if (dt > a.maxChunkNanos) a.maxChunkNanos = dt
                }
                NextSlotGraftPhase.After -> {
                    val n = minOf(GRAFT_CHUNK_SIZE, afterTotal - a.afterInserted)
                    val start = to + 1 + a.afterInserted
                    val t0 = System.nanoTime()
                    player.addMediaItems(queue.subList(start, start + n))
                    val dt = System.nanoTime() - t0
                    a.afterInserted += n
                    a.chunks++
                    a.appendNanos += dt
                    a.chunkNanos += dt
                    if (dt > a.maxChunkNanos) a.maxChunkNanos = dt
                }
                NextSlotGraftPhase.Verify -> {
                    if (verifySlice(a, player, queue, to)) return // finished (Ready) or failed
                }
            }
        } catch (e: RuntimeException) {
            fail(a.request.key, NextSlotFailure.GraftError)
            return
        }
        postGraftTurn(turnToken)
    }

    /** One bounded verification slice. Returns true when the attempt ended (Ready or Failed); false to keep going. */
    private fun verifySlice(a: Attempt, player: P, queue: List<MediaItem>, to: Int): Boolean {
        val t0 = System.nanoTime()
        if (a.verified == 0 && (player.mediaItemCount != queue.size || player.currentMediaItemIndex != to)) {
            fail(a.request.key, NextSlotFailure.TimelineMismatch)
            return true
        }
        val end = minOf(queue.size, a.verified + VERIFY_SLICE_SIZE)
        // Diagnostic equality only (positional; mediaId is not identity): NEXT's item at every index mirrors the logical queue.
        for (i in a.verified until end) {
            if (player.getMediaItemAt(i).mediaId != queue[i].mediaId) {
                fail(a.request.key, NextSlotFailure.TimelineMismatch)
                return true
            }
        }
        a.verified = end
        val dt = System.nanoTime() - t0
        a.verifyNanos += dt
        if (dt > a.maxVerifyNanos) a.maxVerifyNanos = dt
        if (a.verified < queue.size) return false

        // Final checks, then (and only then) Ready.
        if (player.mediaItemCount != queue.size || player.currentMediaItemIndex != to || player.playWhenReady) {
            fail(a.request.key, NextSlotFailure.TimelineMismatch)
            return true
        }
        if (player.repeatMode != a.request.repeatMode) player.repeatMode = a.request.repeatMode
        graftsCompleted++
        state = NextSlotState.Ready(a.request.key, a.token)
        val timing = NextSlotGraftTiming(
            queueSize = queue.size,
            beforeCount = to,
            afterCount = queue.size - to - 1,
            chunkCount = a.chunks,
            maxChunkNanos = a.maxChunkNanos,
            maxVerifyNanos = a.maxVerifyNanos,
            chunkNanos = a.chunkNanos.toList(),
            prependNanos = a.prependNanos,
            appendNanos = a.appendNanos,
            verifyNanos = a.verifyNanos,
            totalNanos = System.nanoTime() - a.startedAtNanos,
        )
        lastGraftTiming = timing
        perfLog?.invoke(
            "next_graft size=${timing.queueSize} before=${timing.beforeCount} after=${timing.afterCount} chunks=${timing.chunkCount} " +
                "maxChunkMs=${timing.maxChunkNanos / 1_000_000.0} prependMs=${timing.prependNanos / 1_000_000.0} " +
                "appendMs=${timing.appendNanos / 1_000_000.0} verifyMs=${timing.verifyNanos / 1_000_000.0} " +
                "totalMs=${timing.totalNanos / 1_000_000.0}",
        )
        return true
    }

    private fun fail(key: CrossfadeTransitionKey, reason: NextSlotFailure) {
        val ended = attempt
        attempt = null
        token++
        cancelPendingTurns()
        state = NextSlotState.Failed(key, reason)
        lastFailure = reason
        if (ended != null) {
            ended.player.removeListener(ended)
            resetPhysical(ended.player)
        }
    }

    /** Returns NEXT to the CF-2M3 inert shape: paused, stopped, empty, neutral repeat. Touches nothing else. */
    private fun resetPhysical(player: P) {
        if (player.playWhenReady) player.playWhenReady = false
        if (player.playbackState != Player.STATE_IDLE) player.stop()
        if (player.mediaItemCount > 0) player.clearMediaItems()
        if (player.repeatMode != Player.REPEAT_MODE_OFF) player.repeatMode = Player.REPEAT_MODE_OFF
    }

    internal companion object {
        /**
         * Maximum items per Media3 insertion call (one looper turn each). 256 was selected because JVM/Robolectric measurements
         * with real Media3 sources typically keep individual insertions small while limiting a 12,288-item queue to 48 insertion
         * turns. JVM runs also showed occasional larger outliers, so this is not a frame-budget or device-performance guarantee.
         * CF-2M7 must validate physical jank/GC behaviour.
         */
        const val GRAFT_CHUNK_SIZE = 256

        /** Per-index mediaId verification is ~1 us per index; 1,024 per turn keeps each verification turn under ~2 ms. */
        const val VERIFY_SLICE_SIZE = 1_024
    }
}
