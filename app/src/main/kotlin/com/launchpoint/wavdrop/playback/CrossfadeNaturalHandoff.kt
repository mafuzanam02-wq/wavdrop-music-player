package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player

/**
 * CF-2L1: a Media3 media-item transition fact from the authoritative primary. [mediaItemIndex] is the physical index
 * the primary is on after the transition; [reason] is the raw Media3 reason. Only [isNaturalAutoTransition] reasons can
 * ever contribute to a crossfade handoff.
 */
internal data class CrossfadeNaturalTransitionObservation(
    val mediaItemIndex: Int,
    val reason: Int,
)

/** Only Media3's genuine automatic transition qualifies; SEEK, PLAYLIST_CHANGED, REPEAT and manual changes never do. */
internal fun isNaturalAutoTransition(reason: Int): Boolean = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO

/**
 * Physical facts about the authoritative primary, read synchronously on the main thread from the real player.
 * [isReady] means playbackState == READY (a usable, buffered state), never "a command returned".
 */
internal data class PrimaryTakeoverFacts(
    val physicalIndex: Int,
    val isReady: Boolean,
    val positionMs: Long,
    /**
     * CF-2L4 correction: the primary's media clock is known to be progressing right now (see [isPrimaryPlaybackAdvancing]). Only
     * while this holds may its coarse raw position be projected forward; READY alone is not proof (it can be paused or suppressed).
     */
    val isAdvancing: Boolean,
)

/**
 * The narrowest truthful "the media clock is advancing" fact for the authoritative primary: READY, playWhenReady, and no playback
 * suppression. This is exactly Media3's `Player.isPlaying` definition, computed from the three raw facts so the rule is
 * JVM-testable and BUFFERING, a pause, an audio-focus/route suppression or an ended/idle player can never be projected through.
 */
internal fun isPrimaryPlaybackAdvancing(playbackState: Int, playWhenReady: Boolean, playbackSuppressionReason: Int): Boolean =
    playbackState == Player.STATE_READY && playWhenReady && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

/** What a terminal fade (HandoffPending) is still waiting for. All waits are state driven, never timed. */
internal enum class CrossfadeHandoffWait {
    /** The authoritative Media3 AUTO transition to the exact target has not been observed yet. */
    NaturalTransition,

    /** The primary is not yet physically on the target occurrence in a READY state. */
    PrimaryReady,

    /** A same-item reposition was issued and the primary has not landed at the requested position yet. */
    PrimarySeek,

    /** CF-2L2: the short internal soft ownership transfer (secondary B -> primary B) is in progress; the secondary stays the audible authority until it completes. */
    OwnershipTransfer,

    /** CF-2L4: a stream's projected position is not (yet) trustworthy (first sample, no raw change seen, a discontinuity or an expired projection); the secondary stays audible and nothing is sought. */
    PositionConfidence,
}

/**
 * Production seams for the natural-AUTO handoff (CF-2L1): [primaryFacts] reads the real primary and [reconciler] performs
 * the same-current-item reposition of the primary onto the secondary's position. When absent the runtime keeps the
 * legacy immediate handoff used by earlier primitives' tests; production wiring always supplies both.
 */
internal class CrossfadeNaturalHandoffSeams(
    val primaryFacts: () -> PrimaryTakeoverFacts?,
    val reconciler: CrossfadePrimaryReconciler,
    // CF-2L2: the same monotonic crossfade clock the timing driver uses; the soft transfer's progress is derived from it.
    val nowElapsedRealtimeMs: () -> Long = { 0L },
    // CF-2L2: DEBUG-only concise transfer observability (null in release). Never carries file names or paths.
    val transferLog: ((String) -> Unit)? = null,
)

/**
 * Reconciliation bound (NOT permission to mix two copies of B): a landed same-item reposition counts as landed when the
 * primary is within this distance of the requested target, and a Pause-style cancellation only carries the fresh secondary
 * position when the silent primary is further than this away. It never authorises audible ownership transfer; that needs
 * [NATURAL_TRANSFER_ENTRY_TOLERANCE_MS].
 */
internal const val NATURAL_TAKEOVER_MAX_LAG_MS = 350L

/**
 * CF-2L2: maximum |primary - FRESH secondary| position difference at which the soft ownership-transfer envelope may BEGIN.
 * A primary outside it never becomes audible; reconciliation simply continues (no retry count or elapsed time bypasses it).
 * An engineering starting point, not a product promise.
 */
internal const val NATURAL_TRANSFER_ENTRY_TOLERANCE_MS = 80L

/**
 * CF-2L2: once the transfer has begun, a primary/secondary difference beyond this ABORTS it (secondary back to full authority,
 * primary back to silent, reconciliation resumes). Larger than the entry tolerance to avoid oscillation; it is never a success
 * permission: entry is always the stricter [NATURAL_TRANSFER_ENTRY_TOLERANCE_MS].
 */
internal const val NATURAL_TRANSFER_ABORT_TOLERANCE_MS = 200L

/**
 * CF-2L2: duration of the INTERNAL gain envelope that hands audible authority from the secondary B to the primary B (equal
 * power, reusing [CrossfadeGainCurve]). A gain envelope advanced by driver evaluations on the monotonic clock: never a wait,
 * never the user's crossfade duration, never a setting.
 */
internal const val NATURAL_TAKEOVER_TRANSFER_DURATION_MS = 150L

/** Gains of the two copies of B during the soft ownership transfer. */
internal data class NaturalTransferGains(val primary: Float, val secondary: Float)

/** Normalized transfer progress in 0..1 from the monotonic clock; a clock that went backwards counts as progress 0. */
internal fun naturalTransferProgress(startElapsedRealtimeMs: Long, nowElapsedRealtimeMs: Long): Float {
    val elapsed = nowElapsedRealtimeMs - startElapsedRealtimeMs
    if (elapsed <= 0L) return 0f
    if (elapsed >= NATURAL_TAKEOVER_TRANSFER_DURATION_MS) return 1f
    return elapsed.toFloat() / NATURAL_TAKEOVER_TRANSFER_DURATION_MS.toFloat()
}

/**
 * Complementary equal-power gains for transfer [progress] on the SAME [CrossfadeGainCurve] as the musical crossfade (primary =
 * incoming rising 0 -> 1, secondary = outgoing falling 1 -> 0). The endpoints are exact: the raw curve ends at ~6e-17, never an
 * exact 0, and completion must hand over a truly silent secondary.
 */
internal fun naturalTransferGains(progress: Float): NaturalTransferGains {
    if (progress.isNaN() || progress <= 0f) return NaturalTransferGains(primary = 0f, secondary = 1f)
    if (progress >= 1f) return NaturalTransferGains(primary = 1f, secondary = 0f)
    val g = CrossfadeGainCurve.equalPower(progress)
    return NaturalTransferGains(primary = g.incoming, secondary = g.outgoing)
}

/**
 * Cap on the learned reposition lead. A same-item seek costs latency during which the still-playing secondary moves on; when a
 * landed reposition is still behind, the next one aims this much further ahead so it converges. It only shapes the seek target
 * and is never a delay and never permission to take over.
 */
internal const val NATURAL_TAKEOVER_MAX_LEAD_MS = 3_000L

internal sealed interface NaturalTakeoverDecision {
    /** A reposition was issued and the primary has not yet landed near it; keep the secondary audible. */
    data object AwaitSeekLanding : NaturalTakeoverDecision

    /** Reposition the (already current, silent) primary to the fresh secondary position. */
    data class SeekPrimary(val positionMs: Long, val leadMs: Long) : NaturalTakeoverDecision

    /**
     * The primary is on the target, READY and within [NATURAL_TRANSFER_ENTRY_TOLERANCE_MS] of the fresh secondary: it is qualified
     * to BEGIN the soft ownership transfer (CF-2L2). It is never an instant restore-and-abandon any more.
     */
    data object TakeOver : NaturalTakeoverDecision
}

/**
 * Pure takeover decision for a primary already known to be on the exact target occurrence and READY. [secondaryPositionMs]
 * must be a FRESH secondary snapshot (the terminal-fade snapshot goes stale while the secondary keeps playing).
 * [seekTargetMs] is the position of the last issued reposition, if any, and [seekLeadMs] the lead it carried.
 *
 * Hard continuity invariant: [NaturalTakeoverDecision.TakeOver] only when the primary is within
 * [NATURAL_TRANSFER_ENTRY_TOLERANCE_MS] of the fresh secondary position; nothing (no retry count, no elapsed time) can authorise a
 * takeover that would move the audible timeline backward (or jump it forward). Otherwise the primary is repositioned, one
 * reposition at a time: while an issued one has not landed the decision is to wait (no seek spam), and once it landed but is
 * still outside tolerance the next one leads by the observed lag so it converges.
 */
internal fun decideNaturalTakeover(
    facts: PrimaryTakeoverFacts,
    secondaryPositionMs: Long,
    seekTargetMs: Long?,
    seekLeadMs: Long,
): NaturalTakeoverDecision {
    if (seekTargetMs != null && facts.positionMs < seekTargetMs - NATURAL_TAKEOVER_MAX_LAG_MS) {
        return NaturalTakeoverDecision.AwaitSeekLanding
    }
    val lagMs = secondaryPositionMs - facts.positionMs
    if (kotlin.math.abs(lagMs) <= NATURAL_TRANSFER_ENTRY_TOLERANCE_MS) return NaturalTakeoverDecision.TakeOver
    val leadMs = if (seekTargetMs == null) 0L else (seekLeadMs + lagMs).coerceIn(0L, NATURAL_TAKEOVER_MAX_LEAD_MS)
    return NaturalTakeoverDecision.SeekPrimary(secondaryPositionMs + leadMs, leadMs)
}

/**
 * CF-2L1: decides whether the primary, which has ALREADY naturally transitioned onto [CrossfadeTransitionKey.toPlaybackIndex],
 * may be repositioned (same current item) to the secondary's [snapshot] position. Distinct from
 * [planCrossfadePrimaryReconciliation], which validates the pre-transition source occurrence and a cross-item seek. Every
 * condition must hold or nothing is sought: live controller, exact queue generation, clean physical queue, a valid snapshot,
 * a distinct in-bounds target, the exact logical AND physical TARGET occurrence, and the automatic-next of the key's source
 * still resolving to the key's target. Positional only (no song ids).
 */
internal fun planCrossfadePostAutoReconciliation(
    key: CrossfadeTransitionKey,
    snapshot: SecondaryHandoffSnapshot,
    queueGeneration: Long,
    playbackQueueSize: Int,
    currentPlaybackIndex: Int?,
    physicalCurrentIndex: Int?,
    repeatMode: RepeatMode,
    playerQueueNeedsSync: Boolean,
    controllerAvailable: Boolean,
): CrossfadePrimaryReconciliationPlan {
    fun reject(reason: CrossfadePrimaryReconciliationRejection) = CrossfadePrimaryReconciliationPlan.Reject(reason)
    if (!controllerAvailable) return reject(CrossfadePrimaryReconciliationRejection.ControllerUnavailable)
    if (queueGeneration != key.queueGeneration) return reject(CrossfadePrimaryReconciliationRejection.QueueGenerationChanged)
    if (playerQueueNeedsSync) return reject(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty)
    if (snapshot.positionMs < 0L || snapshot.durationMs <= 0L || snapshot.positionMs > snapshot.durationMs) {
        return reject(CrossfadePrimaryReconciliationRejection.InvalidSnapshot)
    }
    if (key.toPlaybackIndex !in 0 until playbackQueueSize) return reject(CrossfadePrimaryReconciliationRejection.TargetOutOfBounds)
    if (key.fromPlaybackIndex !in 0 until playbackQueueSize || key.toPlaybackIndex == key.fromPlaybackIndex) {
        return reject(CrossfadePrimaryReconciliationRejection.TargetMismatch)
    }
    if (QueueNavigator.automaticNextIndex(playbackQueueSize, key.fromPlaybackIndex, repeatMode) != key.toPlaybackIndex) {
        return reject(CrossfadePrimaryReconciliationRejection.TargetMismatch)
    }
    if (currentPlaybackIndex != key.toPlaybackIndex) return reject(CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch)
    if (physicalCurrentIndex != key.toPlaybackIndex) return reject(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
    return CrossfadePrimaryReconciliationPlan.Seek(key.toPlaybackIndex, snapshot.positionMs)
}

/**
 * Production helper: forwards an authoritative primary transition fact to the crossfade runtime. A null runtime (rollout
 * gate false) or any non-owned fact is a no-op; the runtime decides ownership.
 */
internal fun observeCrossfadeNaturalTransition(runtime: CrossfadePreparationRuntime?, mediaItemIndex: Int, reason: Int) {
    runtime?.onPrimaryNaturalTransition(CrossfadeNaturalTransitionObservation(mediaItemIndex, reason))
}

/** Production helper: re-evaluates a pending natural handoff after an authoritative primary state change (e.g. READY). */
internal fun advanceCrossfadeHandoff(runtime: CrossfadePreparationRuntime?) {
    runtime?.advancePendingHandoff()
}
