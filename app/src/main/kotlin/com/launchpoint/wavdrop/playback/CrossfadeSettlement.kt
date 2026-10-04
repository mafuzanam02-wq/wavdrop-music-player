package com.launchpoint.wavdrop.playback

/**
 * CF-2L3: read-only, internal proof of what a crossfade runtime still holds. Never user-facing and never exposes a player.
 * [state] being Idle is NOT proof of settlement: bookkeeping, a gain owner or a secondary can survive logical completion.
 */
internal data class CrossfadeSettlementSnapshot(
    val state: CrossfadeState,
    val hasNaturalObservedKey: Boolean,
    val hasSeekTarget: Boolean,
    val seekLeadMs: Long,
    val transferActive: Boolean,
    /** The key that still owns (a possibly lowered) primary gain, or null when none. */
    val primaryGainOwnerKey: CrossfadeTransitionKey?,
    /** The secondary still holds an active transition key (preparing, prepared or started). */
    val secondaryOwned: Boolean,
    val secondaryStarted: Boolean,
    val closed: Boolean,
    /** Reconciliation (same-item) seeks issued for the CURRENT transition; reset whenever the transition ends. */
    val reconcileRequestCount: Int = 0,
)

/**
 * CF-2L3 settlement invariant: a finished (or cancelled) transition is fully dead only when it is Idle AND every piece of
 * natural-handoff bookkeeping, primary gain ownership and secondary ownership is gone. A restore that failed deliberately keeps
 * its owner, so it is never reported as settled.
 */
internal fun isCrossfadeFullySettled(snapshot: CrossfadeSettlementSnapshot): Boolean =
    snapshot.state == CrossfadeState.Idle &&
        !snapshot.hasNaturalObservedKey &&
        !snapshot.hasSeekTarget &&
        snapshot.seekLeadMs == 0L &&
        !snapshot.transferActive &&
        snapshot.primaryGainOwnerKey == null &&
        !snapshot.secondaryOwned &&
        snapshot.reconcileRequestCount == 0

/** Coordinator phase name only (no keys beyond what the caller adds, no media identity). */
internal fun crossfadeStateName(state: CrossfadeState): String = when (state) {
    CrossfadeState.Idle -> "Idle"
    is CrossfadeState.Armed -> "Armed"
    is CrossfadeState.Ready -> "Ready"
    is CrossfadeState.Fading -> "Fading"
    is CrossfadeState.HandoffPending -> "HandoffPending"
}

/** Concise DEBUG summary fragment used by the transport / primary-state diagnostics. No titles, paths or ids. */
internal fun formatCrossfadeSettlementSummary(snapshot: CrossfadeSettlementSnapshot?): String =
    if (snapshot == null) {
        "crossfade=off"
    } else {
        "crossfade=${crossfadeStateName(snapshot.state)} transferActive=${snapshot.transferActive} " +
            "seekTargetPresent=${snapshot.hasSeekTarget} seekLeadMs=${snapshot.seekLeadMs} " +
            "primaryGainOwned=${snapshot.primaryGainOwnerKey != null} secondaryOwned=${snapshot.secondaryOwned} " +
            "settled=${isCrossfadeFullySettled(snapshot)}"
    }
