package com.launchpoint.wavdrop.playback

/** Why an automatic-resume request lost (or never had) authority to play. */
internal enum class ResumeInvalidation {
    /** The user explicitly played/paused after the request began. Definitive. */
    USER_TRANSPORT,

    /** The request's output (Bluetooth or wired) disappeared. The request is void, but the device may return. */
    ROUTE_LOST,

    /** A newer automatic request now owns authority. */
    NEWER_REQUEST,
}

/** Result of the final check made immediately before an automatic `play()`. */
internal enum class PlayAuthorization {
    ALLOWED,
    STALE,
    ROUTE_LOST,
    QUEUE_CHANGED,

    /** The MediaController the request captured was released or replaced; it can no longer safely be acted on. */
    CONTROLLER_REPLACED,
}

/**
 * Request ownership for automatic resume (one instance per output kind: Bluetooth, wired). Precedence:
 * explicit user play/pause > newer automatic request > older automatic request, and route loss
 * voids the pending request. At most one token is current; every other token is a no-op.
 *
 * This is request identity, not queue identity: it never replaces `queueGeneration`.
 */
internal class AutomaticResumeAuthority {
    private var latestGeneration = 0L
    private var activeToken = NO_TOKEN
    private var userSupersededUpTo = NO_TOKEN
    private var routeLostUpTo = NO_TOKEN

    /** Starts a request. Any previous request becomes stale. */
    @Synchronized
    fun begin(): Long {
        latestGeneration++
        activeToken = latestGeneration
        return activeToken
    }

    /** Explicit user play/pause/queue-replacing play: voids every request begun so far. */
    @Synchronized
    fun supersedeByUser() {
        userSupersededUpTo = latestGeneration
        activeToken = NO_TOKEN
    }

    /** This instance's output disappeared: voids every request begun so far. */
    @Synchronized
    fun supersedeByRouteLoss() {
        routeLostUpTo = latestGeneration
        activeToken = NO_TOKEN
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token != NO_TOKEN && token == activeToken

    /** Null while [token] is still current. */
    @Synchronized
    fun invalidationOf(token: Long): ResumeInvalidation? = when {
        isCurrent(token) -> null
        token <= userSupersededUpTo -> ResumeInvalidation.USER_TRANSPORT
        token <= routeLostUpTo -> ResumeInvalidation.ROUTE_LOST
        else -> ResumeInvalidation.NEWER_REQUEST
    }

    /**
     * Final gate immediately before an automatic play. [routeConnected] must be a fresh route
     * query, never an earlier readiness result. [queueGenerationAllowedBumps] is how many
     * queue-generation bumps this request itself may have caused (session hydration bumps once).
     */
    @Synchronized
    fun authorizePlay(
        token: Long,
        routeConnected: Boolean,
        capturedQueueGeneration: Long,
        currentQueueGeneration: Long,
        queueGenerationAllowedBumps: Int = 0,
        controllerCurrent: Boolean = true,
    ): PlayAuthorization = when {
        !isCurrent(token) -> PlayAuthorization.STALE
        !routeConnected -> PlayAuthorization.ROUTE_LOST
        !controllerCurrent -> PlayAuthorization.CONTROLLER_REPLACED
        currentQueueGeneration != capturedQueueGeneration + queueGenerationAllowedBumps ->
            PlayAuthorization.QUEUE_CHANGED
        else -> PlayAuthorization.ALLOWED
    }

    private companion object {
        const val NO_TOKEN = 0L
    }
}

/** Every way an automatic Bluetooth resume request can end. */
internal enum class AutomaticResumeOutcome {
    PLAY_ISSUED,
    SKIPPED_BY_SETTING,
    NO_SESSION,
    ALREADY_PLAYING,
    CONTROLLER_UNAVAILABLE,
    SETUP_FAILED,
    ROUTE_LOST,
    QUEUE_CHANGED,
    SUPERSEDED_BY_USER,
    SUPERSEDED_BY_NEWER_REQUEST,
}

/**
 * Entitlement policy shared by the Bluetooth and wired automatic-resume requests (the name predates the wired route; the
 * semantics are identical for both on purpose: one table, one set of settlement rules).
 */
internal object BluetoothEntitlementPolicy {
    /**
     * The persisted "interrupted by Bluetooth" entitlement is consumed only when the request
     * reached a definitive end: play issued, setting says no, nothing to resume, already playing,
     * or the user explicitly took over. Transient failures and non-user invalidation keep it so
     * the next reconnect can still resume.
     */
    fun shouldConsume(outcome: AutomaticResumeOutcome): Boolean = when (outcome) {
        AutomaticResumeOutcome.PLAY_ISSUED,
        AutomaticResumeOutcome.SKIPPED_BY_SETTING,
        AutomaticResumeOutcome.NO_SESSION,
        AutomaticResumeOutcome.ALREADY_PLAYING,
        AutomaticResumeOutcome.SUPERSEDED_BY_USER -> true

        AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE,
        AutomaticResumeOutcome.SETUP_FAILED,
        AutomaticResumeOutcome.ROUTE_LOST,
        AutomaticResumeOutcome.QUEUE_CHANGED,
        AutomaticResumeOutcome.SUPERSEDED_BY_NEWER_REQUEST -> false
    }

    /**
     * Final outcome of a request: any invalidation that happened before settlement overrides the
     * raw outcome, so a stale request can never clear an entitlement it no longer owns
     * (e.g. play issued, then route loss recorded a fresh interruption).
     */
    fun settle(rawOutcome: AutomaticResumeOutcome, invalidation: ResumeInvalidation?): AutomaticResumeOutcome =
        invalidation?.let(::outcomeFor) ?: rawOutcome

    /**
     * Decision after the suspending clear returns: route loss or a newer request that took
     * ownership during the write must leave the entitlement pending; user takeover or no
     * invalidation leaves it cleared.
     */
    fun shouldRestoreAfterClear(invalidationAfterClear: ResumeInvalidation?): Boolean =
        invalidationAfterClear == ResumeInvalidation.ROUTE_LOST ||
            invalidationAfterClear == ResumeInvalidation.NEWER_REQUEST

    fun outcomeFor(invalidation: ResumeInvalidation): AutomaticResumeOutcome = when (invalidation) {
        ResumeInvalidation.USER_TRANSPORT -> AutomaticResumeOutcome.SUPERSEDED_BY_USER
        ResumeInvalidation.ROUTE_LOST -> AutomaticResumeOutcome.ROUTE_LOST
        ResumeInvalidation.NEWER_REQUEST -> AutomaticResumeOutcome.SUPERSEDED_BY_NEWER_REQUEST
    }

    fun outcomeFor(denial: PlayAuthorization, invalidation: ResumeInvalidation?): AutomaticResumeOutcome =
        when (denial) {
            PlayAuthorization.ALLOWED -> AutomaticResumeOutcome.PLAY_ISSUED
            PlayAuthorization.ROUTE_LOST -> AutomaticResumeOutcome.ROUTE_LOST
            PlayAuthorization.QUEUE_CHANGED -> AutomaticResumeOutcome.QUEUE_CHANGED
            PlayAuthorization.CONTROLLER_REPLACED -> AutomaticResumeOutcome.CONTROLLER_UNAVAILABLE
            PlayAuthorization.STALE -> outcomeFor(invalidation ?: ResumeInvalidation.NEWER_REQUEST)
        }
}

/**
 * Identifies explicit external user transport at the Player boundary
 * (`PreviousBehaviorPlayer.play()/pause()` + `MediaSession.controllerForCurrentRequest`).
 *
 * Wavdrop's own [PlayerController] connects a MediaController flagged with [APP_CONTROLLER_HINT];
 * its calls are not external here because its UI entry points supersede explicitly and its
 * automatic resume `play()` must never invalidate its own request. Any other controller
 * (notification, lock screen, media buttons, widgets, system/external) is explicit user intent.
 * A null controller means the call did not come from a session request: not external.
 */
internal object ExternalTransportPolicy {
    const val APP_CONTROLLER_HINT = "com.launchpoint.wavdrop.APP_CONTROLLER"

    fun isExternalUserController(hasController: Boolean, isAppController: Boolean): Boolean =
        hasController && !isAppController
}

/**
 * Rides the coroutine context of the hydration a cold PLAY runs, so the production hydration path can revalidate, right
 * before its first mutation, that the logical queue has not been moved by anything other than a session hydration since that
 * PLAY began (a newer selection or edit). Hydration STATE may be shared and reused by later PLAYs; PLAYBACK AUTHORITY never is.
 */
internal class OwnedHydration(
    val isQueueUndisturbed: () -> Boolean = { true },
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    internal companion object Key : kotlin.coroutines.CoroutineContext.Key<OwnedHydration>
}

/** Captured when a cold (empty-player) PLAY begins; proves later that the same PLAY is still the newest decision. */
internal class ColdPlayTicket(
    val epoch: Long,
    /** Queue generation / hydration count at the start of this PLAY's uninterrupted run of PLAYs (its baseline). */
    val baseGeneration: Long,
    val baseHydrations: Long,
    val ownedHydration: OwnedHydration = OwnedHydration(),
)

internal enum class ColdPlayAuthorization {
    ALLOWED,

    /** A newer explicit PLAY / PAUSE / selection happened after this PLAY began. */
    SUPERSEDED,

    /** The queue generation moved by something other than a session hydration. */
    QUEUE_CHANGED,
}

/**
 * Request ownership for an explicit PLAY that must first hydrate the saved session asynchronously (widget, media button,
 * notification on an empty player).
 *
 * AUTHORITY is the [epoch]: every explicit transport command or selection calls [supersede]/[begin], so an older suspended
 * PLAY can never become authoritative again, synchronously, before any suspension. Only the newest PLAY can ever play, and
 * it plays on its OWN authority, never on a superseded PLAY's.
 *
 * HYDRATION STATE is separate: the loaded saved queue is reusable by whichever PLAY is newest. The queue generation may have
 * moved since the PLAY's baseline only through session hydrations ([hydrations] counts those); any other movement (a queue
 * edit or replacement) rejects the PLAY, so a stale hydration or an old ticket can never excuse an edit. A PLAY that
 * directly supersedes a still-pending one keeps the pending run's baseline (so an edit in between is still caught); after a
 * [supersede] or a completed PLAY the next one starts a fresh baseline. Bounded state: an epoch, a flag and one baseline.
 */
internal class ColdPlayAuthority(
    private val queueGeneration: () -> Long = { 0L },
    private val hydrations: () -> Long = { 0L },
) {
    private var epoch = 0L
    private var pending = false
    private var baseGeneration = 0L
    private var baseHydrations = 0L

    /** True while the newest cold PLAY is still hydrating: the user's DESIRED state is "playing" even though nothing plays yet. */
    val hasPendingPlay: Boolean
        @Synchronized get() = pending

    /**
     * Resolves a PLAY_PAUSE toggle to the state the user currently intends, at the moment the toggle is received (never a
     * stale toggle replayed later): a still-hydrating cold PLAY counts as playing, so the next toggle is a PAUSE.
     */
    @Synchronized
    fun desiredPlaying(physicallyPlaying: Boolean): Boolean = pending || physicallyPlaying

    /** Starts a cold PLAY: every earlier one becomes stale. */
    @Synchronized
    fun begin(): ColdPlayTicket {
        epoch++
        if (!pending) {
            baseGeneration = queueGeneration()
            baseHydrations = hydrations()
        }
        pending = true
        val generation = baseGeneration
        val hydrated = baseHydrations
        return ColdPlayTicket(epoch, generation, hydrated, OwnedHydration { onlyHydrationsSince(generation, hydrated) })
    }

    /** Explicit PAUSE, external transport, selection, navigation or teardown: voids every cold PLAY. */
    @Synchronized
    fun supersede() {
        epoch++
        pending = false
    }

    /** The cold PLAY for [ticket] finished (played or was dropped); only the newest one clears the pending state. */
    @Synchronized
    fun complete(ticket: ColdPlayTicket) {
        if (ticket.epoch == epoch) pending = false
    }

    @Synchronized
    fun isCurrent(ticket: ColdPlayTicket): Boolean = ticket.epoch == epoch

    // ---- the PLAY Media3 owes a playback-resumption request ----------------------------------------------------------------
    //
    // Media3 completes a `onPlaybackResumption(isForPlayback = true)` request by applying the result and then ALWAYS issuing one PLAY
    // on the session player (`MediaSessionImpl` calls `Util.handlePlayButtonAction` after the result AND after a failure). That PLAY
    // knows nothing about commands that arrived while the saved session was being restored, so a PAUSE that reached the (still empty)
    // player in between would be undone. The request therefore owns a cold-PLAY ticket from the moment it is received (a PAUSE,
    // a selection or a newer cold PLAY supersede it exactly as they supersede a hydrating PLAY).
    //
    // OWNERSHIP IS EXPLICIT, never inferred from order. Media3 issues the PLAY synchronously inside the callback it attached to the
    // request's future, so the request wraps the listener Media3 attaches ([runAsResumptionCompletion]): while that listener runs, the
    // thread is marked as "completing request T". [admitResumptionPlay] reads the mark, so the PLAY made by Media3's callback for T is
    // judged against T's ticket and no other, whenever, on whichever thread and in whatever order relative to other requests or
    // commands that callback runs. A PLAY made outside any such callback (a controller's, a widget's, the app's) carries no mark and is
    // always admitted here. Nothing is queued, so nothing can be consumed by the wrong PLAY, outlive its request or need cleanup.
    private val resumptionPlayOwner = ThreadLocal<ColdPlayTicket?>()

    /** Runs [block], Media3's completion callback for the request that owns [ticket]; every PLAY made inside it is that request's. */
    fun <T> runAsResumptionCompletion(ticket: ColdPlayTicket, block: () -> T): T {
        val previous = resumptionPlayOwner.get()
        resumptionPlayOwner.set(ticket)
        try {
            return block()
        } finally {
            if (previous == null) resumptionPlayOwner.remove() else resumptionPlayOwner.set(previous)
        }
    }

    /**
     * Asked for every PLAY that reaches the session player. False only for a PLAY made by the completion callback of a resumption
     * request whose ticket was superseded since it began (the newest command was not a PLAY): that PLAY must not start playback.
     * Every other PLAY is admitted exactly as before.
     */
    fun admitResumptionPlay(): Boolean {
        val owner = resumptionPlayOwner.get() ?: return true
        return isCurrent(owner)
    }

    private fun onlyHydrationsSince(generation: Long, hydrated: Long): Boolean =
        queueGeneration() - generation == hydrations() - hydrated

    @Synchronized
    fun authorize(ticket: ColdPlayTicket): ColdPlayAuthorization = when {
        ticket.epoch != epoch -> ColdPlayAuthorization.SUPERSEDED
        !onlyHydrationsSince(ticket.baseGeneration, ticket.baseHydrations) -> ColdPlayAuthorization.QUEUE_CHANGED
        else -> ColdPlayAuthorization.ALLOWED
    }
}

/**
 * A media-button PLAY_PAUSE is resolved by Media3 against the PHYSICAL player, which is still empty while a cold PLAY
 * hydrates, so it would re-issue PLAY forever. While a cold PLAY is pending the desired state is already "playing", so the
 * toggle must become a PAUSE (resolved now, at receipt).
 */
internal object MediaButtonPlayPausePolicy {
    fun shouldPauseForPendingColdPlay(keyCode: Int, action: Int, repeatCount: Int, hasPendingColdPlay: Boolean): Boolean =
        hasPendingColdPlay &&
            action == android.view.KeyEvent.ACTION_DOWN &&
            repeatCount == 0 &&
            (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK)
}

/**
 * The production handler for a raw media-button intent while a cold PLAY hydrates. The default Media3 notification buttons are
 * delivered exactly this way (an ACTION_MEDIA_BUTTON intent whose key code is derived from the player command), as are headset
 * and media keys, so one entry point serves all of them. Returns true when it consumed the event (as a PAUSE); false leaves
 * Media3's normal PLAY_PAUSE handling untouched.
 */
internal object ColdPlayMediaButton {
    fun handle(
        intent: android.content.Intent,
        authority: ColdPlayAuthority,
        onExplicitTransport: () -> Unit,
        pause: () -> Unit,
    ): Boolean {
        val event = androidx.core.content.IntentCompat.getParcelableExtra(
            intent, android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java,
        ) ?: return false
        if (!MediaButtonPlayPausePolicy.shouldPauseForPendingColdPlay(
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                hasPendingColdPlay = authority.hasPendingPlay,
            )
        ) {
            return false
        }
        onExplicitTransport()
        pause()
        return true
    }
}
