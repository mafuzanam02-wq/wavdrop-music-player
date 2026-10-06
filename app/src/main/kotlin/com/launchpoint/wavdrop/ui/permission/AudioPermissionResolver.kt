package com.launchpoint.wavdrop.ui.permission

/** What the last runtime-permission request in this session ended in (null = none yet, or it was granted). */
enum class AudioPermissionRequestOutcome {
    /** Denied, but Android would still show the request again. */
    DeniedCanRetry,

    /** Denied and Android will not show the request again: only Settings can fix it. */
    Blocked,
}

/** Pure policy for the audio-permission gate, so it is testable without Compose. */
object AudioPermissionResolver {

    /**
     * @param hasPermission the current Android permission bit (re-read on every resume).
     * @param hasEverGranted the persisted, device-local "was granted before" fact.
     * @param lastRequest the outcome of this session's last runtime request, if any.
     */
    fun resolve(
        hasPermission: Boolean,
        hasEverGranted: Boolean,
        lastRequest: AudioPermissionRequestOutcome?,
    ): AudioPermissionStatus = when {
        hasPermission -> AudioPermissionStatus.Granted
        hasEverGranted -> AudioPermissionStatus.Revoked
        lastRequest == AudioPermissionRequestOutcome.DeniedCanRetry -> AudioPermissionStatus.Denied
        lastRequest == AudioPermissionRequestOutcome.Blocked -> AudioPermissionStatus.PermanentlyDenied
        else -> AudioPermissionStatus.NotRequested
    }

    /** Outcome to remember after a request result (null when granted). */
    fun outcomeOf(granted: Boolean, shouldShowRationale: Boolean): AudioPermissionRequestOutcome? = when {
        granted -> null
        shouldShowRationale -> AudioPermissionRequestOutcome.DeniedCanRetry
        else -> AudioPermissionRequestOutcome.Blocked
    }
}
