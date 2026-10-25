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
 * P5-C extends this with verified window MOVEMENT (append/trim/prepend/re-anchor), exact-occurrence navigation and current-occurrence
 * resolution; see PhysicalWindowAdapter below.
 *
 * P5-B: the controlled physical-window INSTALLATION (docs/development/P5-BOUNDED-PHYSICAL-QUEUE-DESIGN.md §13, P5-B).
 *
 * INACTIVE. Only reachable when [PhysicalQueuePolicy.modeFor] answers Bounded, which the committed gate
 * (`BOUNDED_WINDOW_ENABLED = false`) never does. Scope: ONE initial installation (plan, bounded materialization, one bounded
 * `setMediaItems`, verification, publication). P5-C adds window movement and navigation. Queue edits (P5-D), the logical MediaSession facade
 * and crossfade promotion (P5-E) are NOT handled here.
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

    /** An incremental adjustment that shifts physical indices is in flight: NO mapping is available until it is acknowledged. */
    data class Adjusting(val token: PlanToken) : WindowInstallState

    /** An install failed or was incomplete: no mapping is published and a fresh bounded install around [recovery] is required. */
    data class NeedsReinstall(val recovery: BoundedInstallRecovery) : WindowInstallState
}

/**
 * What the AUTHORITATIVE physical player (the session's player, service side) holds, captured on its own thread. [token] echoes the
 * request so an acknowledgement can only ever speak for the install that asked for it. [ids] is capped at one more than the window
 * bound, so an oversized timeline shows up as a count/id mismatch instead of an unbounded payload. [autoTransitions] is the physical
 * player's own monotonic count of NATURAL (Media3 AUTO) item transitions since it was first observed; it is the ordering evidence that
 * lets an adjustment tell a legitimate natural advance from an unexpected current-item change (see the adjustment rule in
 * [PhysicalWindowAdapter]).
 */
internal data class PhysicalObservation(
    val token: PlanToken,
    val count: Int,
    val currentIndex: Int,
    val ids: List<String>,
    val autoTransitions: Long = 0L,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putLong(KEY_LOGICAL, token.logicalGeneration)
        putLong(KEY_WINDOW, token.windowGeneration)
        putInt(KEY_COUNT, count)
        putInt(KEY_INDEX, currentIndex)
        putStringArrayList(KEY_IDS, ArrayList(ids))
        putLong(KEY_AUTO, autoTransitions)
    }

    companion object {
        private const val KEY_LOGICAL = "logicalGeneration"
        private const val KEY_WINDOW = "windowGeneration"
        private const val KEY_COUNT = "count"
        private const val KEY_INDEX = "currentIndex"
        private const val KEY_IDS = "ids"
        private const val KEY_AUTO = "autoTransitions"

        /** Physical player -> natural-transition tally. Weak, so a released player is never retained. */
        private val tallies = java.util.WeakHashMap<Player, Tally>()

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
            autoTransitions = bundle.getLong(KEY_AUTO, 0L),
        )

        private class Tally(val count: LongArray, val listener: Player.Listener)

        /** Starts counting natural transitions of [player] on first sight (idempotent). Server side, on the player's thread. */
        private fun tallyOf(player: Player): Tally = synchronized(tallies) {
            tallies[player] ?: run {
                val counter = LongArray(1)
                val listener = object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) counter[0]++
                    }
                }
                player.addListener(listener)
                Tally(counter, listener).also { tallies[player] = it }
            }
        }

        /**
         * The listener that counts [player]'s natural transitions. Exposed so a test that cannot let a trackless JVM source play to
         * its end can deliver the AUTO notification through the very listener production uses, right after a REAL physical move.
         */
        internal fun naturalTransitionListener(player: Player): Player.Listener = tallyOf(player).listener

        /** Service side: reads the physical [player] (call on its application thread, e.g. inside `onCustomCommand`). */
        fun observe(player: Player, token: PlanToken, idCap: Int = PhysicalWindowPolicy.W_MAX + 1): PhysicalObservation {
            val counter = tallyOf(player).count
            val count = player.mediaItemCount
            return PhysicalObservation(
                token, count, player.currentMediaItemIndex,
                List(minOf(count, idCap)) { player.getMediaItemAt(it).mediaId },
                counter[0],
            )
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
 * mapping never consults the live logical queue and a repeated id at another position proves nothing. [physicalStart] is the
 * physical current index at the moment the physical player acknowledged this window.
 */
internal class InstalledWindow(
    val window: PhysicalWindow,
    private val expectedIds: LongArray,
    val physicalStart: Int,
    val positionMs: Long,
) {
    val token: PlanToken get() = window.token
    val installedCount: Int get() = expectedIds.size
    val logicalCurrent: Int get() = window.physicalToLogical(physicalStart) ?: error("current outside its window")
    val currentSongId: Long get() = expectedIds[physicalStart]

    fun expectedSongIdAt(physicalIndex: Int): Long? = expectedIds.getOrNull(physicalIndex)

    /** Null when a physical observation holds exactly this window (count and every positional id); otherwise the first difference. */
    fun firstMismatch(count: Int, ids: List<String>): String? {
        if (count != expectedIds.size) return "count $count != ${expectedIds.size}"
        if (ids.size < expectedIds.size) return "only ${ids.size} ids reported"
        for (p in expectedIds.indices) if (ids[p] != expectedIds[p].toString()) return "id at $p is ${ids[p]}, expected ${expectedIds[p]}"
        return null
    }

    fun physicalToLogical(physicalIndex: Int): Int? = window.physicalToLogical(physicalIndex)
    fun logicalToPhysical(logicalIndex: Int): Int? = window.logicalToPhysical(logicalIndex)
}

/** The logical occurrence the physical player is on, PROVEN through the active window, or why it cannot be proven. */
internal sealed interface CurrentResolution {
    data class Proven(val logical: Int, val physical: Int) : CurrentResolution

    /** Never a guess: no search for another occurrence of the same song id. */
    data class Unproven(val reason: String) : CurrentResolution
}

internal sealed interface BoundedNavigationResult {
    /** The target was inside the verified window: a plain physical seek at the mapped index was issued. */
    data class SeekIssued(val physicalIndex: Int) : BoundedNavigationResult

    /** The target was outside the window, or the mapping was not verified: a bounded install around the exact target was submitted. */
    data class Reanchoring(val token: PlanToken) : BoundedNavigationResult

    /** An adjustment is in flight: the target is held and applied when it settles (latest wins). Treated like an issued seek by callers. */
    data class Held(val target: Int) : BoundedNavigationResult

    data class Rejected(val reason: String) : BoundedNavigationResult
    data class Failed(val cause: Throwable) : BoundedNavigationResult
}

internal sealed interface MaintenanceResult {
    data object NoChange : MaintenanceResult

    /** Another window operation is in flight; nothing is stacked. The caller re-asks when it finishes. */
    data object Busy : MaintenanceResult
    data class Unprovable(val reason: String) : MaintenanceResult
    data class Submitted(val token: PlanToken, val reanchor: Boolean) : MaintenanceResult
    data class Failed(val cause: Throwable) : MaintenanceResult
}

/**
 * Owns the bounded physical window of ONE Media3 [Player] (in production a `MediaController`): its installation, movement
 * (append / trim / prepend / re-anchor), the mapping between logical and physical positions, and the cancellation of stale work.
 * Main thread only. The complete logical queue is NEVER held or copied here: callers pass it per call and only the window's songs
 * are read.
 *
 * Completion: `setMediaItems`/`addMediaItems`/`removeMediaItems` returning proves nothing (a controller masks the mutation
 * optimistically; listeners may even fire inside the call), and Media3 offers no completion callback for them. Observing the
 * controller can therefore only make an INSTALL PROVISIONAL (it carries no mapping). A window becomes ACTIVE solely when the
 * physical player acknowledges it through a [PhysicalInstallVerifier] (in production [PhysicalQueueObservationCommand], answered
 * service-side from the session player) and the acknowledged state equals the intended window. With no verifier nothing ever
 * becomes active. No blocking waits.
 *
 * ONE pending operation at a time ([pending]): an install, or an incremental adjustment. A newer install supersedes it; an
 * adjustment is never stacked on another. The operation, its token and its acknowledgement are bound to the logical and window
 * generations it was planned under, so a late acknowledgement after a newer install, [reset], a replaced player or a moved
 * logical generation is ignored.
 *
 * Mapping availability while an operation is pending:
 *  - an append-only adjustment leaves every existing physical index unchanged, so the previous window stays active;
 *  - a front trim/prepend shifts indices: state is [WindowInstallState.Adjusting] and there is NO active mapping, so index-bearing
 *    navigation re-anchors around its exact logical target instead of translating through an unverified mapping.
 *
 * @param currentLogicalGeneration live revision of the logical queue (`PlayerController.queueGeneration`).
 * @param materialize converts ONLY the songs it is given to Media3 items (production passes the controller's cache-backed version).
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

    /** The verified window, or null in every other state. The ONLY mapping index-bearing operations may use. */
    val active: InstalledWindow? get() = (state as? WindowInstallState.Active)?.installed

    /** True while an install or adjustment awaits its physical acknowledgement. */
    val isBusy: Boolean get() = pending != null

    /**
     * The logical occurrence the adapter last PROVED or accepted as the target of a navigation/install. Owned here (not by the
     * physical index) and used for display and as the exact anchor of a recovery re-anchor; never to translate an index.
     */
    var trackedLogical: Int = -1
        private set

    /** Window generations are monotonic for the adapter's lifetime and are never reused. */
    private var nextWindowGeneration = 0L

    /** Consecutive automatic recoveries since the last verified window; bounded so a failing player cannot cause a repair loop. */
    private var recoveryAttempts = 0

    /**
     * Install: replaces the playlist. Baseline: the first half of an adjustment - NOTHING is mutated; it only asks the physical player
     * for the exact current occurrence and its natural-transition count. Adjust: the incremental mutation, acknowledged against the
     * occurrence derived from that baseline.
     */
    private enum class Kind { Install, Baseline, Adjust }

    private class Pending(
        val kind: Kind,
        val player: Player,
        val queue: List<Song>,
        val window: PhysicalWindow,
        val expectedIds: LongArray,
        val minCurrent: Int,
        val maxCurrent: Int,
        val positionMs: Long,
        val repeatMode: RepeatMode,
        val recoveryAnchor: Int,
        val verifier: PhysicalInstallVerifier?,
        val onOutcome: (BoundedInstallOutcome) -> Unit,
        /** Adjust only: the PHYSICALLY observed logical current and natural-transition count at the baseline. */
        val baselineLogical: Int = -1,
        val baselineAuto: Long = 0L,
    ) {
        var listener: Player.Listener? = null
        var handler: Handler? = null
        var timeout: Runnable? = null
        var submitting = true
        var attempts = 0
    }

    private var pending: Pending? = null

    /** Latest in-window navigation that arrived while an adjustment was in flight; applied when it settles. Latest wins. */
    private class HeldNavigation(
        val player: Player,
        val queue: List<Song>,
        val target: Int,
        val positionMs: Long,
        val repeatMode: RepeatMode,
        val verifier: PhysicalInstallVerifier?,
        val onOutcome: (BoundedInstallOutcome) -> Unit,
    )

    private var held: HeldNavigation? = null

    fun physicalToLogical(physicalIndex: Int): Int? = active?.physicalToLogical(physicalIndex)
    fun logicalToPhysical(logicalIndex: Int): Int? = active?.logicalToPhysical(logicalIndex)

    // ── current occurrence ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Proves the logical occurrence [player] is on through the ACTIVE window: the physical current index must map into the window
     * and the song id there must equal the id the window was verified to hold at that position. Anything else is unproven.
     */
    fun resolveCurrent(player: Player): CurrentResolution {
        val a = active ?: return CurrentResolution.Unproven("no_active_window")
        if (a.window.logicalGeneration != currentLogicalGeneration()) return CurrentResolution.Unproven("logical_generation_moved")
        val physical = player.currentMediaItemIndex
        val logical = a.physicalToLogical(physical) ?: return CurrentResolution.Unproven("outside_window")
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        if (id == null || id != a.expectedSongIdAt(physical)) return CurrentResolution.Unproven("id_mismatch")
        if (held == null) trackedLogical = logical // a held navigation target outranks the (stale) physical position
        return CurrentResolution.Proven(logical, physical)
    }

    /**
     * The current logical occurrence for state display: proven through the active window when there is one; the adapter's tracked
     * occurrence while an operation is in flight (display only); null once the occurrence is unproven or the window failed.
     */
    fun currentLogical(player: Player): Int? = when (state) {
        // A held navigation is the newest accepted intent: it IS the authoritative current until it is applied.
        is WindowInstallState.Active -> held?.target ?: (resolveCurrent(player) as? CurrentResolution.Proven)?.logical
        is WindowInstallState.Submitted, is WindowInstallState.Provisional, is WindowInstallState.Adjusting ->
            trackedLogical.takeIf { it >= 0 }
        else -> null
    }

    // ── install (initial window and re-anchor) ──────────────────────────────────────────────────────────────────────────

    /**
     * Plans and submits one bounded installation of [queue] around [startIndex]. Returns [BoundedInstallOutcome.Submitted] once
     * issued (the terminal result goes to [onOutcome]), or a terminal [BoundedInstallOutcome.Rejected]/[BoundedInstallOutcome.Failed]
     * right away. The caller keeps ownership of prepare/play/pause, exactly as with the full-queue install. A newer install
     * supersedes any pending operation.
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
        held = null // a newer install is the newest intent: any held in-window navigation is superseded

        // A newer accepted install owns the player from here on: end the older operation and drop the mapping the player is about to lose.
        endPending(BoundedInstallOutcome.Stale(pending?.window?.token ?: window.token, StaleReason.Superseded))
        state = WindowInstallState.Submitted(window.token)
        trackedLogical = startIndex // the accepted, exact target: the authoritative logical current from now on

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
            state = WindowInstallState.NeedsReinstall(
                BoundedInstallRecovery(startIndex, logicalGeneration, positionMs, repeatMode, "materialize_failed"),
            )
            return BoundedInstallOutcome.Failed(window.token, t)
        }

        val entry = Pending(
            Kind.Install, player, queue, window, expectedIds, plan.physicalStart, plan.physicalStart, positionMs, repeatMode,
            startIndex, verifier, onOutcome,
        )
        pending = entry
        try {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = evaluate(entry)
            }
            entry.listener = listener
            player.addListener(listener)
            player.repeatMode = plan.physicalRepeatMode
            player.shuffleModeEnabled = false
            player.setMediaItems(items, plan.physicalStart, positionMs)
            armAcknowledgement(entry)
        } catch (t: Throwable) {
            pending = null
            detach(entry)
            state = WindowInstallState.NeedsReinstall(recovery(entry, "media3_call_failed"))
            return BoundedInstallOutcome.Failed(window.token, t)
        }
        return BoundedInstallOutcome.Submitted(window.token)
    }

    // ── navigation ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Moves playback to the EXACT logical [target].
     *  - Active window that contains it: a plain physical seek at the mapped index (no queue change).
     *  - Otherwise (outside the window, or the mapping is not verified: provisional, adjusting, failed, no window): a bounded
     *    re-anchor installation around the exact target. The song id is never used to find the target, the full queue is never
     *    installed, and the newest accepted target supersedes any older pending operation.
     */
    fun navigateTo(
        player: Player,
        queue: List<Song>,
        target: Int,
        positionMs: Long,
        repeatMode: RepeatMode,
        verifier: PhysicalInstallVerifier?,
        onOutcome: (BoundedInstallOutcome) -> Unit = {},
    ): BoundedNavigationResult {
        if (target !in queue.indices) return BoundedNavigationResult.Rejected("target_out_of_range")
        val a = active
        if (a != null && a.window.logicalGeneration == currentLogicalGeneration()) {
            val physical = a.logicalToPhysical(target)
            if (physical != null) {
                val inFlight = pending
                if (inFlight != null && inFlight.kind != Kind.Install) {
                    // An adjustment is being verified against the occurrence it was planned from. A seek now would move the current
                    // item underneath that proof, so the newest in-window target is HELD (latest wins) and applied once the window
                    // settles. The target is already the authoritative logical current for state and for any recovery.
                    held = HeldNavigation(player, queue, target, positionMs, repeatMode, verifier, onOutcome)
                    trackedLogical = target
                    return BoundedNavigationResult.Held(target)
                }
                return try {
                    player.seekTo(physical, positionMs)
                    trackedLogical = target
                    BoundedNavigationResult.SeekIssued(physical)
                } catch (t: Throwable) {
                    BoundedNavigationResult.Failed(t)
                }
            }
        }
        return when (val outcome = install(player, queue, currentLogicalGeneration(), target, positionMs, repeatMode, verifier, onOutcome)) {
            is BoundedInstallOutcome.Submitted -> BoundedNavigationResult.Reanchoring(outcome.token)
            is BoundedInstallOutcome.Rejected -> BoundedNavigationResult.Rejected(outcome.reason.name)
            is BoundedInstallOutcome.Failed -> BoundedNavigationResult.Failed(outcome.cause)
            else -> BoundedNavigationResult.Rejected("unexpected_$outcome")
        }
    }

    // ── maintenance (refill, trim, history) ─────────────────────────────────────────────────────────────────────────────

    /**
     * One deterministic window adjustment from the policy, if the current occurrence is PROVEN and no other operation is pending.
     * Submits at most one bounded batch (never stacks). The caller re-invokes it when the operation finishes and after each
     * transition; [MaintenanceResult.NoChange] ends the chain, so there is no polling and no unbounded repeat.
     */
    fun maintain(
        player: Player,
        queue: List<Song>,
        repeatMode: RepeatMode,
        verifier: PhysicalInstallVerifier?,
        onOutcome: (BoundedInstallOutcome) -> Unit = {},
    ): MaintenanceResult {
        if (pending != null) return MaintenanceResult.Busy
        val a = active ?: return MaintenanceResult.Unprovable("no_active_window")
        val current = when (val resolution = resolveCurrent(player)) {
            is CurrentResolution.Proven -> resolution
            is CurrentResolution.Unproven -> return MaintenanceResult.Unprovable(resolution.reason)
        }
        val physicalRepeat = if (repeatMode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (player.repeatMode != physicalRepeat) player.repeatMode = physicalRepeat
        return when (val adjustment = policy.planAdjustment(a.window, current.physical, repeatMode)) {
            is WindowAdjustment.NoChange -> MaintenanceResult.NoChange
            is WindowAdjustment.Reanchor -> {
                val positionMs = player.currentPosition.coerceAtLeast(0L)
                when (val outcome = install(player, queue, currentLogicalGeneration(), current.logical, positionMs, repeatMode, verifier, onOutcome)) {
                    is BoundedInstallOutcome.Submitted -> MaintenanceResult.Submitted(outcome.token, reanchor = true)
                    is BoundedInstallOutcome.Failed -> MaintenanceResult.Failed(outcome.cause)
                    else -> MaintenanceResult.Unprovable("reanchor_$outcome")
                }
            }
            is WindowAdjustment.Adjust -> startBaseline(player, queue, a, repeatMode, verifier, onOutcome)
        }
    }

    /**
     * Phase 1 of an adjustment. NOTHING is mutated: the physical player is asked for its exact current occurrence and its count of
     * natural transitions. The plan is then re-derived from THAT observation (not from the controller's possibly optimistic view),
     * so the occurrence an adjustment must preserve is captured authoritatively at planning time.
     */
    private fun startBaseline(
        player: Player,
        queue: List<Song>,
        a: InstalledWindow,
        repeatMode: RepeatMode,
        verifier: PhysicalInstallVerifier?,
        onOutcome: (BoundedInstallOutcome) -> Unit,
    ): MaintenanceResult {
        if (verifier == null) return MaintenanceResult.Unprovable("no_verifier")
        val entry = Pending(
            Kind.Baseline, player, queue, a.window, LongArray(0), 0, a.installedCount - 1, 0L, repeatMode, trackedLogical,
            verifier, onOutcome,
        )
        pending = entry
        try {
            armAcknowledgement(entry)
        } catch (t: Throwable) {
            pending = null
            detach(entry)
            return MaintenanceResult.Failed(t)
        }
        return MaintenanceResult.Submitted(a.window.token, reanchor = false)
    }

    /** The physical player's baseline for an adjustment: verify the active window is really there, then plan from the physical current. */
    private fun handleBaseline(entry: Pending, o: PhysicalObservation) {
        val a = active
        if (a == null || a.token != entry.window.token) { // the window this baseline belonged to is gone
            if (pending === entry) pending = null
            detach(entry)
            return
        }
        val token = entry.window.token
        val mismatch = if (o.token != token) "acknowledgement for ${o.token}, expected $token" else a.firstMismatch(o.count, o.ids)
        if (mismatch != null) {
            failPending(entry, BoundedInstallOutcome.Incomplete(token, a.installedCount, o.count, "baseline: $mismatch"), "incomplete")
            return
        }
        val physical = o.currentIndex
        val logical = a.physicalToLogical(physical)
        if (logical == null) {
            failPending(entry, BoundedInstallOutcome.Incomplete(token, a.installedCount, o.count, "baseline: current index $physical outside the window"), "incomplete")
            return
        }
        trackedLogical = logical
        when (val adjustment = policy.planAdjustment(a.window, physical, entry.repeatMode)) {
            is WindowAdjustment.NoChange -> {
                if (pending === entry) pending = null
                detach(entry)
                runHeld()
            }
            is WindowAdjustment.Reanchor -> {
                if (pending === entry) pending = null
                detach(entry)
                install(entry.player, entry.queue, currentLogicalGeneration(), logical, entry.player.currentPosition.coerceAtLeast(0L), entry.repeatMode, entry.verifier, entry.onOutcome)
            }
            is WindowAdjustment.Adjust -> submitAdjust(entry, a, adjustment, logical, o.autoTransitions)
        }
    }

    /**
     * Phase 2: the bounded mutation. It is acknowledged only if the physical current is EXACTLY the occurrence captured at the
     * baseline advanced by the natural transitions the physical player itself counted since (see [discrepancy]).
     */
    private fun submitAdjust(
        baseline: Pending,
        a: InstalledWindow,
        adjust: WindowAdjustment.Adjust,
        baselineLogical: Int,
        baselineAuto: Long,
    ) {
        val player = baseline.player
        val queue = baseline.queue
        if (!adjust.token.isValidFor(a.window, currentLogicalGeneration())) {
            if (pending === baseline) pending = null
            detach(baseline)
            return
        }
        val geometry = adjust.resultWindow
        val newWindow = PhysicalWindow(
            geometry.logicalGeneration, nextWindowGeneration, geometry.queueSize, geometry.start, geometry.length, geometry.wraps,
            policy.config.wMax,
        )
        nextWindowGeneration = newWindow.nextWindowGeneration()
        val shifts = adjust.trimFront > 0 || adjust.prependCount > 0
        val expectedIds = LongArray(newWindow.length)
        val prepend = ArrayList<Song>(adjust.prependCount)
        val append = ArrayList<Song>(adjust.appendCount)
        val prependItems: List<MediaItem>
        val appendItems: List<MediaItem>
        try {
            check(adjust.prependCount <= policy.config.wMax && adjust.appendCount <= policy.config.wMax) { "unbounded batch" }
            for (p in 0 until newWindow.length) {
                val song = queue[newWindow.physicalToLogical(p) ?: error("window position $p unmapped")]
                expectedIds[p] = song.id
                if (p < adjust.prependCount) prepend += song
                if (p >= newWindow.length - adjust.appendCount) append += song
            }
            prependItems = if (prepend.isEmpty()) emptyList() else materialize(prepend)
            appendItems = if (append.isEmpty()) emptyList() else materialize(append)
        } catch (t: Throwable) {
            failPending(baseline, BoundedInstallOutcome.Failed(baseline.window.token, t), "materialize_failed")
            return
        }
        val oldLength = a.window.length
        val entry = Pending(
            Kind.Adjust, player, queue, newWindow, expectedIds, 0, newWindow.length - 1, 0L, baseline.repeatMode, baselineLogical,
            baseline.verifier, baseline.onOutcome, baselineLogical = baselineLogical, baselineAuto = baselineAuto,
        )
        detach(baseline)
        pending = entry
        if (shifts) state = WindowInstallState.Adjusting(newWindow.token) // indices move: no active mapping until verified
        try {
            // Removals first, so the playlist never exceeds the larger of the old and new window (<= W_MAX at every step).
            if (adjust.trimTail > 0) player.removeMediaItems(oldLength - adjust.trimTail, oldLength)
            if (adjust.trimFront > 0) player.removeMediaItems(0, adjust.trimFront)
            if (prependItems.isNotEmpty()) player.addMediaItems(0, prependItems)
            if (appendItems.isNotEmpty()) player.addMediaItems(appendItems)
            armAcknowledgement(entry)
        } catch (t: Throwable) {
            failPending(entry, BoundedInstallOutcome.Failed(newWindow.token, t), "media3_call_failed")
        }
    }

    /** Applies the newest navigation that arrived while an adjustment was in flight (latest wins), against the settled window. */
    private fun runHeld() {
        val h = held ?: return
        held = null
        navigateTo(h.player, h.queue, h.target, h.positionMs, h.repeatMode, h.verifier, h.onOutcome)
    }

    /**
     * The current occurrence cannot be proven (id mismatch, outside the window): fail closed by re-anchoring ONCE around the
     * last proven/accepted occurrence ([trackedLogical]). Bounded by [MAX_RECOVERY_ATTEMPTS] until a window verifies again.
     */
    fun recoverUnprovable(
        player: Player,
        queue: List<Song>,
        repeatMode: RepeatMode,
        verifier: PhysicalInstallVerifier?,
        onOutcome: (BoundedInstallOutcome) -> Unit = {},
    ): BoundedNavigationResult {
        if (trackedLogical !in queue.indices) return BoundedNavigationResult.Rejected("no_tracked_occurrence")
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return BoundedNavigationResult.Rejected("recovery_exhausted")
        recoveryAttempts++
        return when (val outcome = install(player, queue, currentLogicalGeneration(), trackedLogical, 0L, repeatMode, verifier, onOutcome)) {
            is BoundedInstallOutcome.Submitted -> BoundedNavigationResult.Reanchoring(outcome.token)
            is BoundedInstallOutcome.Rejected -> BoundedNavigationResult.Rejected(outcome.reason.name)
            is BoundedInstallOutcome.Failed -> BoundedNavigationResult.Failed(outcome.cause)
            else -> BoundedNavigationResult.Rejected("unexpected_$outcome")
        }
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Evaluates the pending install against the observed controller right now (also driven by player events). */
    fun verifyPending() {
        pending?.let { evaluate(it) }
    }

    /**
     * Gives up on an unfinished operation (a timeout, or the owner's decision). Never publishes it; leaves an explicit recoverable
     * state. No-op when nothing is pending.
     */
    fun expirePending() {
        val entry = pending ?: return
        val observed = observedIds(entry.player)
        val player = entry.player
        val detail = (if (entry.kind == Kind.Install) discrepancy(entry, player.mediaItemCount, player.currentMediaItemIndex, observed) else null)
            ?: "no acknowledgement from the physical player"
        failPending(entry, BoundedInstallOutcome.Incomplete(entry.window.token, entry.expectedIds.size, observed.size, detail), "incomplete")
    }

    /** Drops the active mapping and any unfinished operation (queue cleared, queue replaced by a Full install, mode change). */
    fun reset() {
        held = null
        endPending(BoundedInstallOutcome.Stale(pending?.window?.token ?: PlanToken(0L, 0L), StaleReason.Superseded))
        state = WindowInstallState.Idle
        trackedLogical = -1
        recoveryAttempts = 0
    }

    /**
     * The Media3 controller was replaced (reconnect). Whatever the old one acknowledged says nothing about the new player: the
     * mapping and any pending operation are dropped, and a bounded re-install around [trackedLogical] is owed.
     */
    fun onPlayerReplaced() {
        held = null
        val anchor = trackedLogical
        val generation = currentLogicalGeneration()
        val repeat = pending?.repeatMode ?: RepeatMode.OFF
        endPending(BoundedInstallOutcome.Stale(pending?.window?.token ?: PlanToken(0L, 0L), StaleReason.Superseded))
        state = if (anchor >= 0) {
            WindowInstallState.NeedsReinstall(BoundedInstallRecovery(anchor, generation, 0L, repeat, "player_replaced"))
        } else {
            WindowInstallState.Idle
        }
    }

    // ── verification ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun armAcknowledgement(entry: Pending) {
        val handler = Handler(entry.player.applicationLooper)
        entry.handler = handler
        entry.submitting = false
        if (entry.kind == Kind.Install) handler.post { evaluate(entry) }
        // The authoritative probe is queued right behind the mutation; it answers from the physical player, not the controller.
        requestAcknowledgement(entry)
        if (verifyTimeoutMs > 0L) {
            val timeout = Runnable { if (pending === entry) expirePending() }
            entry.timeout = timeout
            handler.postDelayed(timeout, verifyTimeoutMs)
        }
    }

    /**
     * Controller-side check. At most promotes an install from Submitted to Provisional: the controller may be showing its own
     * optimistic state, so this can never publish a mapping. Only [onAcknowledged] can.
     */
    private fun evaluate(entry: Pending) {
        if (pending !== entry || entry.submitting || entry.kind != Kind.Install) return
        if (staleByGeneration(entry)) return
        val observed = observedIds(entry.player)
        val player = entry.player
        if (discrepancy(entry, player.mediaItemCount, player.currentMediaItemIndex, observed) != null) return
        if (state is WindowInstallState.Submitted) state = WindowInstallState.Provisional(entry.window.token)
    }

    /**
     * Asks the physical player for its state. The session may answer before it has applied the mutation (observed: it is applied
     * asynchronously), so a content mismatch re-probes up to [MAX_ACK_ATTEMPTS] times before the operation is called incomplete.
     * No timers: each probe is a fresh round trip to the session.
     */
    private fun requestAcknowledgement(entry: Pending) {
        val verifier = entry.verifier ?: return
        val handler = entry.handler ?: return
        entry.attempts++
        val ack = verifier.observe(entry.window.token)
        ack.addListener({ onAcknowledged(entry, ack) }, { task -> handler.post(task) })
    }

    /** The physical player answered. Activates the window only for the still-pending operation whose token it echoes and matches. */
    private fun onAcknowledged(entry: Pending, ack: ListenableFuture<PhysicalObservation>) {
        if (pending !== entry || entry.submitting) return // superseded, reset, expired or already finished: a late ack is ignored
        if (staleByGeneration(entry)) return
        val token = entry.window.token
        val observation = try {
            ack.get()
        } catch (t: Throwable) {
            failPending(entry, BoundedInstallOutcome.Failed(token, t.cause ?: t), "ack_failed")
            return
        }
        if (entry.kind == Kind.Baseline) {
            handleBaseline(entry, observation)
            return
        }
        val problem = when {
            observation.token != token -> "acknowledgement for ${observation.token}, expected $token"
            else -> discrepancy(entry, observation.count, observation.currentIndex, observation.ids, observation.autoTransitions)
        }
        if (problem != null && observation.token == token && entry.attempts < MAX_ACK_ATTEMPTS) {
            requestAcknowledgement(entry) // the mutation may simply not have been applied yet
            return
        }
        if (problem != null) {
            failPending(entry, BoundedInstallOutcome.Incomplete(token, entry.expectedIds.size, observation.count, "physical: $problem"), "incomplete")
            return
        }
        check(token.isValidFor(entry.window, currentLogicalGeneration())) { "ack passed generation check but token invalid" }
        val installed = InstalledWindow(entry.window, entry.expectedIds, observation.currentIndex, entry.positionMs)
        state = WindowInstallState.Active(installed)
        trackedLogical = installed.logicalCurrent
        recoveryAttempts = 0
        if (entry.kind == Kind.Adjust) {
            // The user's newest in-window target (held while this adjustment was verified) is applied first, against the settled window.
            if (pending === entry) pending = null
            detach(entry)
            runHeld()
            entry.onOutcome(BoundedInstallOutcome.Active(installed))
        } else {
            finish(entry, BoundedInstallOutcome.Active(installed))
        }
    }

    /** True (and the operation is ended) when the logical queue moved since [entry] was planned. */
    private fun staleByGeneration(entry: Pending): Boolean {
        if (currentLogicalGeneration() == entry.window.logicalGeneration) return false
        state = WindowInstallState.Idle // a newer logical intent owns the queue; there is nothing to recover
        finish(entry, BoundedInstallOutcome.Stale(entry.window.token, StaleReason.LogicalGenerationMoved))
        return true
    }

    /**
     * Ends [entry] as unverified: nothing is published, the state is the explicit recoverable [WindowInstallState.NeedsReinstall],
     * and a failed ADJUSTMENT triggers at most [MAX_RECOVERY_ATTEMPTS] bounded re-anchor around the last proven occurrence.
     */
    private fun failPending(entry: Pending, outcome: BoundedInstallOutcome, reason: String) {
        state = WindowInstallState.NeedsReinstall(recovery(entry, reason))
        finish(entry, outcome)
        if (entry.kind != Kind.Install && recoveryAttempts < MAX_RECOVERY_ATTEMPTS &&
            entry.window.logicalGeneration == currentLogicalGeneration() && trackedLogical in entry.queue.indices
        ) {
            // Fail closed: the physical state could not be proven, so ONE bounded re-anchor around the last authoritative occurrence
            // (a held navigation target, when there is one, IS that occurrence). Never the full queue.
            recoveryAttempts++
            install(
                entry.player, entry.queue, entry.window.logicalGeneration, trackedLogical,
                runCatching { entry.player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L),
                entry.repeatMode, entry.verifier, entry.onOutcome,
            )
        } else {
            runHeld() // nothing to recover with: honour the newest navigation through the normal (bounded) path
        }
    }

    /** Song ids the player currently holds, by physical index. Capped so a runaway timeline cannot make this unbounded. */
    private fun observedIds(player: Player): List<String> {
        val count = minOf(player.mediaItemCount, policy.config.wMax + 1)
        return List(count) { player.getMediaItemAt(it).mediaId }
    }

    /**
     * Null when the observation equals the intended window exactly: count, every positional id AND the current occurrence.
     *  - Install: the physical current is exactly the install's start index.
     *  - Adjust: the physical current must be the occurrence captured at the physical baseline, advanced by exactly the number of
     *    NATURAL transitions the physical player counted since (each by the logical automatic-next rule, so a repeat-all wrap is
     *    followed), located in the RESULTING window. Matching the whole playlist is not enough: a current item that moved for any other
     *    reason (a seek, a removal under it, a skipped or missing transition) lands on a different index and is rejected.
     */
    private fun discrepancy(entry: Pending, count: Int, currentIndex: Int, ids: List<String>, autoTransitions: Long = 0L): String? {
        val expected = entry.expectedIds
        if (count != expected.size) return "count $count != ${expected.size}"
        if (entry.kind == Kind.Adjust) {
            val steps = autoTransitions - entry.baselineAuto
            if (steps < 0L || steps > entry.window.length) return "natural transition count $steps is not credible"
            var logical = entry.baselineLogical
            for (i in 0 until steps.toInt()) {
                logical = QueueNavigator.automaticNextIndex(entry.window.queueSize, logical, entry.repeatMode)
                    ?: return "baseline occurrence ${entry.baselineLogical} cannot advance $steps natural transitions"
            }
            val expectedPhysical = entry.window.logicalToPhysical(logical)
                ?: return "expected occurrence $logical is not in the resulting window"
            if (currentIndex != expectedPhysical) {
                return "current index $currentIndex != $expectedPhysical (occurrence $logical = baseline ${entry.baselineLogical} + $steps natural transitions)"
            }
        } else if (currentIndex !in entry.minCurrent..entry.maxCurrent) {
            return "current index $currentIndex not in ${entry.minCurrent}..${entry.maxCurrent}"
        }
        if (ids.size < expected.size) return "only ${ids.size} ids reported"
        for (p in expected.indices) {
            if (ids[p] != expected[p].toString()) return "id at $p is ${ids[p]}, expected ${expected[p]}"
        }
        return null
    }

    private fun recovery(entry: Pending, reason: String) = BoundedInstallRecovery(
        logicalStart = trackedLogical.takeIf { it >= 0 } ?: entry.recoveryAnchor,
        logicalGeneration = entry.window.logicalGeneration,
        positionMs = entry.positionMs,
        repeatMode = entry.repeatMode,
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
        const val MAX_RECOVERY_ATTEMPTS = 1
    }
}
