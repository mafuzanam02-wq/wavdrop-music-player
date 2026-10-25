package com.launchpoint.wavdrop.playback

import android.os.Bundle
import android.os.Handler
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song

/*
 * P5-B: the controlled physical-window INSTALLATION (docs/development/P5-BOUNDED-PHYSICAL-QUEUE-DESIGN.md §13, P5-B).
 *
 * INACTIVE. Only reachable when [PhysicalQueuePolicy.modeFor] answers Bounded, which the committed gate
 * (`BOUNDED_WINDOW_ENABLED = false`) never does. Scope: ONE initial installation (plan, bounded materialization, one bounded
 * `setMediaItems`, verification, publication). Window movement, refill, navigation, queue edits, the logical MediaSession facade
 * and crossfade promotion are P5-C..E and are NOT handled here.
 *
 * Lifecycle of one install: Planned -> Submitted -> Verified -> Active. Only Active is an authoritative mapping; anything else
 * (rejected, stale, incomplete, failed) leaves [PhysicalWindowAdapter.active] null, so no later operation can translate an index
 * through an unproven window.
 */

/** Why a request never reached Media3. Nothing was changed on the player. */
internal enum class BoundedInstallRejection {
    /** The logical queue is empty. */
    EmptyQueue,

    /** The exact logical start index is outside the queue. Never clamped and never guessed from a song id. */
    StartOutOfRange,

    NegativePosition,
    NegativeGeneration,

    /** The queue is small enough for [QueueMode.Full]; the bounded installer refuses it instead of silently changing mode. */
    QueueBelongsToFullMode,
}

/** Why an unfinished install may no longer become active. */
internal enum class StaleReason {
    /** A newer accepted install (or [PhysicalWindowAdapter.reset]) took over before this one was verified. */
    Superseded,

    /** The logical queue moved (`queueGeneration` bumped) before this one was verified. */
    LogicalGenerationMoved,
}

/** Terminal results are everything except [Submitted], which only says "installation issued, not yet verified". */
internal sealed interface BoundedInstallOutcome {
    data class Submitted(val token: PlanToken) : BoundedInstallOutcome

    /** Verified against the observed player and published as the authoritative mapping. */
    data class Active(val installed: InstalledWindow) : BoundedInstallOutcome

    data class Rejected(val reason: BoundedInstallRejection) : BoundedInstallOutcome

    data class Stale(val token: PlanToken, val reason: StaleReason) : BoundedInstallOutcome

    /** The observed player never reflected the intended window. [detail] names the first discrepancy. */
    data class Incomplete(val token: PlanToken, val expectedCount: Int, val observedCount: Int, val detail: String) :
        BoundedInstallOutcome

    /** Materialization or a Media3 call threw. */
    data class Failed(val token: PlanToken, val cause: Throwable) : BoundedInstallOutcome
}

/** The bounded re-anchor input kept after an install that did not verify. The logical current is still authoritative. */
internal data class BoundedInstallRecovery(
    val logicalStart: Int,
    val logicalGeneration: Long,
    val positionMs: Long,
    val repeatMode: RepeatMode,
    val reason: String,
)

internal sealed interface WindowInstallState {
    data object Idle : WindowInstallState
    data class Submitted(val token: PlanToken) : WindowInstallState

    /**
     * The MediaController (client view, which may be OPTIMISTIC) reflects the request, but the physical player has not
     * acknowledged it. Deliberately carries no mapping: it cannot translate any index.
     */
    data class Provisional(val token: PlanToken) : WindowInstallState
    data class Active(val installed: InstalledWindow) : WindowInstallState

    /** An install failed or was incomplete: no mapping is published and a fresh bounded install around [recovery] is required. */
    data class NeedsReinstall(val recovery: BoundedInstallRecovery) : WindowInstallState
}

/**
 * What the AUTHORITATIVE physical player (the session's player, service side) holds, captured on its own thread. [token] echoes the
 * request so an acknowledgement can only ever speak for the install that asked for it. [ids] is capped at one more than the window
 * bound, so an oversized timeline shows up as a count/id mismatch instead of an unbounded payload.
 */
internal data class PhysicalObservation(val token: PlanToken, val count: Int, val currentIndex: Int, val ids: List<String>) {
    fun toBundle(): Bundle = Bundle().apply {
        putLong(KEY_LOGICAL, token.logicalGeneration)
        putLong(KEY_WINDOW, token.windowGeneration)
        putInt(KEY_COUNT, count)
        putInt(KEY_INDEX, currentIndex)
        putStringArrayList(KEY_IDS, ArrayList(ids))
    }

    companion object {
        private const val KEY_LOGICAL = "logicalGeneration"
        private const val KEY_WINDOW = "windowGeneration"
        private const val KEY_COUNT = "count"
        private const val KEY_INDEX = "currentIndex"
        private const val KEY_IDS = "ids"

        fun tokenToBundle(token: PlanToken): Bundle = Bundle().apply {
            putLong(KEY_LOGICAL, token.logicalGeneration)
            putLong(KEY_WINDOW, token.windowGeneration)
        }

        fun tokenFromBundle(bundle: Bundle): PlanToken = PlanToken(bundle.getLong(KEY_LOGICAL, -1L), bundle.getLong(KEY_WINDOW, -1L))

        fun fromBundle(bundle: Bundle): PhysicalObservation = PhysicalObservation(
            token = tokenFromBundle(bundle),
            count = bundle.getInt(KEY_COUNT, -1),
            currentIndex = bundle.getInt(KEY_INDEX, -1),
            ids = bundle.getStringArrayList(KEY_IDS) ?: emptyList(),
        )

        /** Service side: reads the physical [player] (call on its application thread, e.g. inside `onCustomCommand`). */
        fun observe(player: Player, token: PlanToken, idCap: Int = PhysicalWindowPolicy.W_MAX + 1): PhysicalObservation {
            val count = player.mediaItemCount
            return PhysicalObservation(token, count, player.currentMediaItemIndex, List(minOf(count, idCap)) { player.getMediaItemAt(it).mediaId })
        }
    }
}

/**
 * The only thing that may turn a provisional install into an authoritative one: an acknowledgement produced by the physical
 * player itself. Completion may arrive on any thread; the adapter hops to the player's looper.
 */
internal fun interface PhysicalInstallVerifier {
    fun observe(token: PlanToken): ListenableFuture<PhysicalObservation>
}

/**
 * Media3 has no completion callback for `setMediaItems` (it is void, and a controller masks the mutation optimistically). The
 * smallest ordered probe available through the controller abstraction is a session command: it is queued behind the install on
 * the same connection and answered by the session side with the physical player's actual state ([PhysicalObservation]). Correctness
 * does not rely on that ordering: the adapter compares the returned state with the intended window and refuses to activate on any
 * difference.
 */
internal object PhysicalQueueObservationCommand {
    const val ACTION = "com.launchpoint.wavdrop.OBSERVE_PHYSICAL_QUEUE"
    val command: SessionCommand get() = SessionCommand(ACTION, Bundle.EMPTY)

    /**
     * Only Wavdrop's own in-app controller may see or use this probe. Two independent facts are required: the controller carries the
     * app-controller hint (the existing convention, see [ExternalTransportPolicy.APP_CONTROLLER_HINT]; a hint is client-supplied,
     * so it is never enough alone) AND its uid, which the platform attests and a caller cannot choose, is this process's uid.
     * The command name is not a credential.
     */
    fun isAuthorizedController(controller: MediaSession.ControllerInfo, ownUid: Int = Process.myUid()): Boolean =
        controller.uid == ownUid &&
            controller.connectionHints.getBoolean(ExternalTransportPolicy.APP_CONTROLLER_HINT, false)

    /** Service side entry point: refuses (with no playlist data) any controller that is not the in-app one. */
    fun execute(controller: MediaSession.ControllerInfo, player: Player, args: Bundle): ListenableFuture<SessionResult> =
        if (isAuthorizedController(controller)) {
            handle(player, args)
        } else {
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
        }

    /**
     * Service side: answers from the session's physical player. The answer is taken from a task posted to the player's own looper,
     * i.e. behind work already queued there (an install that is still being applied), not inline.
     */
    private fun handle(player: Player, args: Bundle): ListenableFuture<SessionResult> {
        val token = PhysicalObservation.tokenFromBundle(args)
        val future = SettableFuture.create<SessionResult>()
        Handler(player.applicationLooper).post {
            future.set(runCatching { SessionResult(SessionResult.RESULT_SUCCESS, PhysicalObservation.observe(player, token).toBundle()) }
                .getOrElse { SessionResult(SessionResult.RESULT_ERROR_UNKNOWN) })
        }
        return future
    }

    /** Client side: a verifier that asks the session behind [controller]. */
    fun verifier(controller: MediaController): PhysicalInstallVerifier = PhysicalInstallVerifier { token ->
        Futures.transform(
            controller.sendCustomCommand(command, PhysicalObservation.tokenToBundle(token)),
            { result ->
                check(result.resultCode == SessionResult.RESULT_SUCCESS) { "physical observation refused: ${result.resultCode}" }
                PhysicalObservation.fromBundle(result.extras)
            },
            MoreExecutors.directExecutor(),
        )
    }
}

/** A pure installation plan: which window, where the player starts in it, and which physical repeat mode applies. */
internal class BoundedInstallPlan(
    val window: PhysicalWindow,
    val logicalStart: Int,
    val physicalStart: Int,
    val positionMs: Long,
    val repeatMode: RepeatMode,
) {
    /**
     * Physical repeat for the window: ALL must NOT loop inside the window (logical wrapping is window geometry), ONE is native.
     */
    val physicalRepeatMode: Int
        get() = if (repeatMode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
}

internal sealed interface BoundedInstallPlanResult {
    data class Planned(val plan: BoundedInstallPlan) : BoundedInstallPlanResult
    data class Rejected(val reason: BoundedInstallRejection) : BoundedInstallPlanResult
}

/** Pure planning: no Media3, no Song, no queue contents. O(1). */
internal class PhysicalWindowInstallPlanner(private val policy: PhysicalWindowPolicy = PhysicalWindowPolicy.DEFAULT) {

    fun plan(
        queueSize: Int,
        logicalGeneration: Long,
        windowGeneration: Long,
        startIndex: Int,
        positionMs: Long,
        repeatMode: RepeatMode,
    ): BoundedInstallPlanResult {
        if (logicalGeneration < 0L || windowGeneration < 0L) return rejected(BoundedInstallRejection.NegativeGeneration)
        if (queueSize < 1) return rejected(BoundedInstallRejection.EmptyQueue)
        if (startIndex !in 0 until queueSize) return rejected(BoundedInstallRejection.StartOutOfRange)
        if (positionMs < 0L) return rejected(BoundedInstallRejection.NegativePosition)
        if (PhysicalQueuePolicy.modeFor(queueSize, boundedEnabled = true, fullModeMax = policy.fullModeMax) != QueueMode.Bounded) {
            return rejected(BoundedInstallRejection.QueueBelongsToFullMode)
        }
        val window = policy.initialWindow(queueSize, startIndex, repeatMode, logicalGeneration, windowGeneration)
            ?: return rejected(BoundedInstallRejection.StartOutOfRange)
        val physicalStart = window.logicalToPhysical(startIndex)
            ?: return rejected(BoundedInstallRejection.StartOutOfRange) // unreachable: the policy keeps the current inside
        return BoundedInstallPlanResult.Planned(BoundedInstallPlan(window, startIndex, physicalStart, positionMs, repeatMode))
    }

    private fun rejected(reason: BoundedInstallRejection) = BoundedInstallPlanResult.Rejected(reason)
}

/**
 * A VERIFIED, published window. [expectedIds] are the positional song ids captured at submission (index = physical index), so the
 * mapping never consults the live logical queue and a repeated id at another position proves nothing.
 */
internal class InstalledWindow(
    val plan: BoundedInstallPlan,
    private val expectedIds: LongArray,
) {
    val window: PhysicalWindow get() = plan.window
    val token: PlanToken get() = plan.window.token
    val physicalStart: Int get() = plan.physicalStart
    val installedCount: Int get() = expectedIds.size
    val logicalCurrent: Int get() = plan.logicalStart
    val currentSongId: Long get() = expectedIds[plan.physicalStart]
    val positionMs: Long get() = plan.positionMs

    fun expectedSongIdAt(physicalIndex: Int): Long? = expectedIds.getOrNull(physicalIndex)

    fun physicalToLogical(physicalIndex: Int): Int? = window.physicalToLogical(physicalIndex)
    fun logicalToPhysical(logicalIndex: Int): Int? = window.logicalToPhysical(logicalIndex)
}

/**
 * Installs ONE bounded window into a Media3 [Player] (in production a `MediaController`) and decides, from the OBSERVED player
 * state, whether that window may become the authoritative mapping. Main thread only.
 *
 * Completion: `setMediaItems` returning proves nothing (a controller masks the mutation optimistically; listeners may even fire
 * inside the call), and Media3 offers no completion callback for it. Observing the controller can therefore only make a window
 * PROVISIONAL (it carries no mapping). A window becomes ACTIVE solely when the physical player acknowledges it through a
 * [PhysicalInstallVerifier] (in production [PhysicalQueueObservationCommand], answered service-side from the session player) and
 * the acknowledged state equals the intended window. With no verifier an install can never become active. No blocking waits. A
 * newer [install], a moved logical generation, [reset] or [expirePending] ends an unfinished install, and any late acknowledgement
 * is then ignored.
 *
 * @param currentLogicalGeneration live revision of the logical queue (`PlayerController.queueGeneration`).
 * @param materialize converts ONLY the window's songs to Media3 items (production passes the controller's cache-backed version).
 */
internal class PhysicalWindowAdapter(
    private val policy: PhysicalWindowPolicy = PhysicalWindowPolicy.DEFAULT,
    private val currentLogicalGeneration: () -> Long,
    private val verifyTimeoutMs: Long = DEFAULT_VERIFY_TIMEOUT_MS,
    private val materialize: (List<Song>) -> List<MediaItem> = { songs -> songs.map(Song::toPlaybackMediaItem) },
) {
    private val planner = PhysicalWindowInstallPlanner(policy)

    var state: WindowInstallState = WindowInstallState.Idle
        private set

    /** The verified window, or null in every other state. The ONLY mapping later operations may use. */
    val active: InstalledWindow? get() = (state as? WindowInstallState.Active)?.installed

    /** Window generations are monotonic for the adapter's lifetime and are never reused. */
    private var nextWindowGeneration = 0L

    private class Pending(
        val player: Player,
        val plan: BoundedInstallPlan,
        val expectedIds: LongArray,
        val verifier: PhysicalInstallVerifier?,
        val onOutcome: (BoundedInstallOutcome) -> Unit,
    ) {
        var listener: Player.Listener? = null
        var handler: Handler? = null
        var timeout: Runnable? = null
        var submitting = true
        var attempts = 0
    }

    private var pending: Pending? = null

    fun physicalToLogical(physicalIndex: Int): Int? = active?.physicalToLogical(physicalIndex)
    fun logicalToPhysical(logicalIndex: Int): Int? = active?.logicalToPhysical(logicalIndex)

    /**
     * Plans and submits one bounded installation of [queue] around [startIndex]. Returns [BoundedInstallOutcome.Submitted] once
     * issued (the terminal result goes to [onOutcome]), or a terminal [BoundedInstallOutcome.Rejected]/[BoundedInstallOutcome.Failed]
     * right away. The caller keeps ownership of prepare/play/pause, exactly as with the full-queue install.
     */
    fun install(
        player: Player,
        queue: List<Song>,
        logicalGeneration: Long,
        startIndex: Int,
        positionMs: Long,
        repeatMode: RepeatMode,
        verifier: PhysicalInstallVerifier? = null,
        onOutcome: (BoundedInstallOutcome) -> Unit = {},
    ): BoundedInstallOutcome {
        val plan = when (
            val planned = planner.plan(queue.size, logicalGeneration, nextWindowGeneration, startIndex, positionMs, repeatMode)
        ) {
            is BoundedInstallPlanResult.Rejected -> return BoundedInstallOutcome.Rejected(planned.reason)
            is BoundedInstallPlanResult.Planned -> planned.plan
        }
        val window = plan.window
        nextWindowGeneration = window.nextWindowGeneration()

        // A newer accepted install owns the player from here on: end the older one and drop the mapping the player is about to lose.
        endPending(BoundedInstallOutcome.Stale(pending?.plan?.window?.token ?: window.token, StaleReason.Superseded))
        state = WindowInstallState.Submitted(window.token)

        val recovery = BoundedInstallRecovery(startIndex, logicalGeneration, positionMs, repeatMode, "install_failed")
        val expectedIds = LongArray(window.length)
        val songs = ArrayList<Song>(window.length)
        val items: List<MediaItem>
        try {
            for (p in 0 until window.length) {
                val song = queue[window.physicalToLogical(p) ?: error("window position $p unmapped")]
                expectedIds[p] = song.id
                songs += song
            }
            items = materialize(songs)
            check(items.size == window.length && items.size <= policy.config.wMax) {
                "materialized ${items.size} items for a window of ${window.length} (max ${policy.config.wMax})"
            }
        } catch (t: Throwable) {
            state = WindowInstallState.NeedsReinstall(recovery.copy(reason = "materialize_failed"))
            return BoundedInstallOutcome.Failed(window.token, t)
        }

        val entry = Pending(player, plan, expectedIds, verifier, onOutcome)
        pending = entry
        try {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = evaluate(entry)
            }
            entry.listener = listener
            player.addListener(listener)
            player.repeatMode = plan.physicalRepeatMode
            player.shuffleModeEnabled = false
            player.setMediaItems(items, plan.physicalStart, plan.positionMs)
            val handler = Handler(player.applicationLooper)
            entry.handler = handler
            entry.submitting = false
            handler.post { evaluate(entry) }
            requestAcknowledgement(entry)
            if (verifyTimeoutMs > 0L) {
                val timeout = Runnable { if (pending === entry) expirePending() }
                entry.timeout = timeout
                handler.postDelayed(timeout, verifyTimeoutMs)
            }
        } catch (t: Throwable) {
            pending = null
            detach(entry)
            state = WindowInstallState.NeedsReinstall(recovery.copy(reason = "media3_call_failed"))
            return BoundedInstallOutcome.Failed(window.token, t)
        }
        return BoundedInstallOutcome.Submitted(window.token)
    }

    /** Evaluates the pending install against the observed player right now (also driven by player events and a posted check). */
    fun verifyPending() {
        pending?.let { evaluate(it) }
    }

    /**
     * Gives up on an unfinished install (a timeout, or the owner's decision). Never publishes it; leaves an explicit recoverable
     * state. No-op when nothing is pending.
     */
    fun expirePending() {
        val entry = pending ?: return
        val observed = observedIds(entry.player)
        val player = entry.player
        val detail = discrepancy(entry, player.mediaItemCount, player.currentMediaItemIndex, observed)
            ?: "no acknowledgement from the physical player"
        val token = entry.plan.window.token
        state = WindowInstallState.NeedsReinstall(recovery(entry, "incomplete"))
        finish(entry, BoundedInstallOutcome.Incomplete(token, entry.expectedIds.size, observed.size, detail))
    }

    /** Drops the active mapping and any unfinished install (queue cleared, player released, queue replaced by a Full install). */
    fun reset() {
        endPending(BoundedInstallOutcome.Stale(pending?.plan?.window?.token ?: PlanToken(0L, 0L), StaleReason.Superseded))
        state = WindowInstallState.Idle
    }

    // ── verification ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Controller-side check. At most promotes Submitted to Provisional: the controller may be showing its own optimistic state, so
     * this can never publish a mapping. Only [onAcknowledged] can.
     */
    private fun evaluate(entry: Pending) {
        if (pending !== entry || entry.submitting) return
        if (staleByGeneration(entry)) return
        val observed = observedIds(entry.player)
        val player = entry.player
        if (discrepancy(entry, player.mediaItemCount, player.currentMediaItemIndex, observed) != null) return
        if (state is WindowInstallState.Submitted) state = WindowInstallState.Provisional(entry.plan.window.token)
    }

    /**
     * Asks the physical player for its state. The session may answer before it has applied the install (observed: the install
     * is applied asynchronously), so a content mismatch re-probes up to [MAX_ACK_ATTEMPTS] times before the install is called
     * incomplete. No timers: each probe is a fresh round trip to the session.
     */
    private fun requestAcknowledgement(entry: Pending) {
        val verifier = entry.verifier ?: return
        val handler = entry.handler ?: return
        entry.attempts++
        val ack = verifier.observe(entry.plan.window.token)
        ack.addListener({ onAcknowledged(entry, ack) }, { task -> handler.post(task) })
    }

    /** The physical player answered. Activates the window only for the still-pending install whose token it echoes and matches. */
    private fun onAcknowledged(entry: Pending, ack: ListenableFuture<PhysicalObservation>) {
        if (pending !== entry || entry.submitting) return // superseded, reset, expired or already finished: a late ack is ignored
        if (staleByGeneration(entry)) return
        val token = entry.plan.window.token
        val observation = try {
            ack.get()
        } catch (t: Throwable) {
            state = WindowInstallState.NeedsReinstall(recovery(entry, "ack_failed"))
            finish(entry, BoundedInstallOutcome.Failed(token, t.cause ?: t))
            return
        }
        val problem = when {
            observation.token != token -> "acknowledgement for ${observation.token}, expected $token"
            else -> discrepancy(entry, observation.count, observation.currentIndex, observation.ids)
        }
        if (problem != null && observation.token == token && entry.attempts < MAX_ACK_ATTEMPTS) {
            requestAcknowledgement(entry) // the install may simply not have been applied yet
            return
        }
        if (problem != null) {
            state =WindowInstallState.NeedsReinstall(recovery(entry, "incomplete"))
            finish(entry, BoundedInstallOutcome.Incomplete(token, entry.expectedIds.size, observation.count, "physical: $problem"))
            return
        }
        check(token.isValidFor(entry.plan.window, currentLogicalGeneration())) { "ack passed generation check but token invalid" }
        val installed = InstalledWindow(entry.plan, entry.expectedIds)
        state = WindowInstallState.Active(installed)
        finish(entry, BoundedInstallOutcome.Active(installed))
    }

    /** True (and the install is ended) when the logical queue moved since [entry] was planned. */
    private fun staleByGeneration(entry: Pending): Boolean {
        if (currentLogicalGeneration() == entry.plan.window.logicalGeneration) return false
        state = WindowInstallState.Idle // a newer logical intent owns the queue; there is nothing to recover
        finish(entry, BoundedInstallOutcome.Stale(entry.plan.window.token, StaleReason.LogicalGenerationMoved))
        return true
    }

    /** Song ids the player currently holds, by physical index. Capped so a runaway timeline cannot make this unbounded. */
    private fun observedIds(player: Player): List<String> {
        val count = minOf(player.mediaItemCount, policy.config.wMax + 1)
        return List(count) { player.getMediaItemAt(it).mediaId }
    }

    /** Null when the observation equals the intended window exactly (count, current physical index and every positional id). */
    private fun discrepancy(entry: Pending, count: Int, currentIndex: Int, ids: List<String>): String? {
        val expected = entry.expectedIds
        if (count != expected.size) return "count $count != ${expected.size}"
        if (currentIndex != entry.plan.physicalStart) return "current index $currentIndex != ${entry.plan.physicalStart}"
        if (ids.size < expected.size) return "only ${ids.size} ids reported"
        for (p in expected.indices) {
            if (ids[p] != expected[p].toString()) return "id at $p is ${ids[p]}, expected ${expected[p]}"
        }
        return null
    }

    private fun recovery(entry: Pending, reason: String) = BoundedInstallRecovery(
        logicalStart = entry.plan.logicalStart,
        logicalGeneration = entry.plan.window.logicalGeneration,
        positionMs = entry.plan.positionMs,
        repeatMode = entry.plan.repeatMode,
        reason = reason,
    )

    /** Ends [pending] (if any) with [outcome] and clears the published mapping. */
    private fun endPending(outcome: BoundedInstallOutcome) {
        val entry = pending
        if (state is WindowInstallState.Active) state = WindowInstallState.Idle
        if (entry != null) finish(entry, outcome)
    }

    private fun finish(entry: Pending, outcome: BoundedInstallOutcome) {
        if (pending === entry) pending = null
        detach(entry)
        entry.onOutcome(outcome)
    }

    private fun detach(entry: Pending) {
        entry.listener?.let { runCatching { entry.player.removeListener(it) } }
        entry.listener = null
        entry.timeout?.let { entry.handler?.removeCallbacks(it) }
        entry.timeout = null
    }

    companion object {
        const val DEFAULT_VERIFY_TIMEOUT_MS = 5_000L
        const val MAX_ACK_ATTEMPTS = 3
    }
}
