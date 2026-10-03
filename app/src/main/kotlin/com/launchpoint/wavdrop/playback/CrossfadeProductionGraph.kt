package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem

/** The primary's physical duration when usable; Media3's C.TIME_UNSET (negative) and 0 are unknown (null). */
internal fun usableCrossfadePrimaryDuration(durationMs: Long): Long? = durationMs.takeIf { it > 0L }

/** Production adapter: delegates exactly once, unchanged, to the primary reconciliation (PlayerController). */
internal fun crossfadePrimaryReconciler(
    reconcile: (CrossfadeTransitionKey, SecondaryHandoffSnapshot) -> CrossfadePrimaryReconciliationResult,
): CrossfadePrimaryReconciler = CrossfadePrimaryReconciler { key, snapshot -> reconcile(key, snapshot) }

/** The service-owned runtime plus the (never started here) timing driver built against that exact runtime. */
internal class CrossfadeProductionGraph(
    val runtime: CrossfadePreparationRuntime,
    val timingDriver: CrossfadeTimingDriver,
)

/**
 * Composition only. Builds the runtime and a timing driver over that same runtime; it does NOT start the driver
 * (the service-owned activation policy, CF-2E2, starts/stops it from the persisted duration). The configured duration
 * is a plain synchronous provider supplied by the service (no DataStore here). Nothing here touches a player.
 */
internal fun createCrossfadeProductionGraph(
    snapshotProvider: () -> CrossfadeRuntimeSnapshot,
    backendFactory: () -> SecondaryPlayerBackend,
    primaryGainBackend: PrimaryGainBackend,
    reconcilePrimary: (CrossfadeTransitionKey, SecondaryHandoffSnapshot) -> CrossfadePrimaryReconciliationResult,
    scheduler: CrossfadeTimingScheduler,
    clock: CrossfadeMonotonicClock,
    configuredDurationMsProvider: () -> Long,
    primaryDurationMs: () -> Long,
    primaryPositionMs: () -> Long,
    mediaItemFactory: (com.launchpoint.wavdrop.data.model.Song) -> MediaItem = { it.toPlaybackMediaItem() },
): CrossfadeProductionGraph {
    val runtime = CrossfadePreparationRuntime(
        snapshotProvider = snapshotProvider,
        backendFactory = backendFactory,
        mediaItemFactory = mediaItemFactory,
        primaryGainBackend = primaryGainBackend,
        primaryReconciler = crossfadePrimaryReconciler(reconcilePrimary),
    )
    val driver = CrossfadeTimingDriver(
        runtime = runtime,
        scheduler = scheduler,
        clock = clock,
        configuredDurationMsProvider = configuredDurationMsProvider,
        currentDurationMsProvider = { usableCrossfadePrimaryDuration(primaryDurationMs()) },
        currentPositionMsProvider = primaryPositionMs,
    )
    return CrossfadeProductionGraph(runtime, driver)
}

/**
 * Teardown order: invalidate timing callbacks FIRST, then close the runtime (which restores any lowered primary
 * gain and releases the secondary). The caller must still release the primary player only afterwards. Both
 * closes are idempotent, so calling this twice is harmless.
 */
internal fun closeCrossfadeGraph(driver: CrossfadeTimingDriver?, runtime: CrossfadePreparationRuntime?) {
    driver?.close()
    runtime?.close()
}

/**
 * CF-2F1: key-less, synchronous cleanup when the authoritative primary player reports a playback error. The runtime
 * owns the cleanup (Fading/HandoffPending restore primary gain before abandoning the secondary; Armed/Ready abandon
 * the secondary; Idle is harmless). The timing driver is deliberately left alone: activation belongs to the
 * persisted-duration policy and bad-media queue recovery stays with PlayerController. A null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromPrimaryPlaybackError(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.PlaybackError)
}

/**
 * CF-2G1: key-less, synchronous crossfade cleanup for an explicit pause that reached the session player. Must run
 * BEFORE the pause is forwarded to the primary so a Fading/HandoffPending cleanup can still restore the primary
 * gain. Same runtime-owned cleanup as other cancellations; the timing driver is left running and a null runtime
 * (gate false) is a no-op. Deliberately not wired to onIsPlayingChanged (that can fire for non-user reasons).
 */
internal fun recoverCrossfadeFromExplicitPause(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.Pause)
}

/**
 * CF-2G2: key-less, synchronous crossfade cleanup for an explicit same-track position seek (app UI seek or an
 * external-controller scrub), run BEFORE the seek is applied. Never used for crossfade-owned seeks (CF-2D2 primary
 * reconciliation) or other internal app-controller seeks. Uses [CrossfadeCancelReason.Seek]; ManualNavigation is
 * reserved for next/previous. The timing driver is left running; a null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromExplicitSeek(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.Seek)
}

/**
 * CF-2G3: key-less, synchronous crossfade cleanup for an explicit user NEXT or PREVIOUS command (app skipToNext/
 * skipToPrevious or an external controller), run BEFORE navigation is applied or deferred. Also used when PREVIOUS
 * resolves to restart-current (the initiating command was PREVIOUS, not a scrub, so never [CrossfadeCancelReason.Seek]).
 * Not used for bad-media recovery or natural transitions. The timing driver is left running; a null runtime (gate
 * false) is a no-op.
 */
internal fun recoverCrossfadeFromExplicitNavigation(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(CrossfadeCancelReason.ManualNavigation)
}

/** CF-2H1: the cancel reason of an explicit repeat-mode change (a named seam so a pure test can assert it). */
internal val REPEAT_CHANGE_CANCEL_REASON = CrossfadeCancelReason.RepeatChanged

/**
 * CF-2H1: key-less, synchronous crossfade cleanup for an explicit user repeat-mode change, run BEFORE the new repeat
 * mode is applied to logical state or Media3. Ownership ends from user intent (even when the new mode would not
 * invalidate the planned target); the runtime's own `crossfadeOwnershipLossReason` stays as the defensive fallback.
 * The timing driver is left running; a null runtime (gate false) is a no-op.
 */
internal fun recoverCrossfadeFromRepeatChange(runtime: CrossfadePreparationRuntime?) {
    runtime?.cancel(REPEAT_CHANGE_CANCEL_REASON)
}
