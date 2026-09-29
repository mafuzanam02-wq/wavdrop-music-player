package com.launchpoint.wavdrop.playback

/** Why an automatic-resume request lost (or never had) authority to play. */
internal enum class ResumeInvalidation {
    /** The user explicitly played/paused after the request began. Definitive. */
    USER_TRANSPORT,

    /** The Bluetooth output disappeared. The request is void, but the device may return. */
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
}

/**
 * Request ownership for automatic Bluetooth resume. Precedence:
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

    /** Bluetooth output disappeared: voids every request begun so far. */
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
    ): PlayAuthorization = when {
        !isCurrent(token) -> PlayAuthorization.STALE
        !routeConnected -> PlayAuthorization.ROUTE_LOST
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
