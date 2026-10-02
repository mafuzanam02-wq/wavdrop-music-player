package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem

/**
 * CF-2E1: temporary, explicitly dormant configured duration. 0 ms is Crossfade OFF under the CF-1 rules
 * ([CrossfadeRules.normalizeDurationMs]). It is NOT a default, fallback or recommended duration: it only states that
 * no production duration has been configured yet. A later settings slice replaces this provider.
 */
internal const val DORMANT_CROSSFADE_CONFIGURED_DURATION_MS = 0L

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
 * (start/stop policy and a real configured duration belong to later slices). Nothing here touches a player.
 */
internal fun createCrossfadeProductionGraph(
    snapshotProvider: () -> CrossfadeRuntimeSnapshot,
    backendFactory: () -> SecondaryPlayerBackend,
    primaryGainBackend: PrimaryGainBackend,
    reconcilePrimary: (CrossfadeTransitionKey, SecondaryHandoffSnapshot) -> CrossfadePrimaryReconciliationResult,
    scheduler: CrossfadeTimingScheduler,
    clock: CrossfadeMonotonicClock,
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
        configuredDurationMsProvider = { DORMANT_CROSSFADE_CONFIGURED_DURATION_MS },
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
