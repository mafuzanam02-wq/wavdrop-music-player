package com.launchpoint.wavdrop.playback

import android.util.Log
import com.launchpoint.wavdrop.BuildConfig

/**
 * P5-F: DEBUG-ONLY validation log lines under [TAG] for the owner's Full-vs-Bounded device A/B. Observation only: it never decides,
 * gates or changes playback, and every entry point is a no-op unless the build is a debug build. Lines carry only counts, indices,
 * operation names, generation numbers and monotonic durations; no titles, paths, URIs or song ids.
 *
 * Vocabulary (kept distinct on purpose):
 *  * `install_requested`  - the controller asked for a queue install (mode decided here). NOT a physical result.
 *  * `window_submitted`   - the adapter ACTUALLY issued a physical playlist operation (`kind=install` setMediaItems, or `kind=adjust`
 *                           add/remove batch). Never for a baseline observation, logical-only rebase, rejection or callback. NOT verified.
 *  * `baseline_observation` - a mutation/maintenance asked the player for its exact current; nothing was changed yet.
 *  * `window_verified`    - the adapter observed the PHYSICAL player and published the window (BoundedInstallOutcome.Active).
 *  * `window_not_verified`- terminal non-success (stale / incomplete / rejected / failed), with the reason class.
 * `elapsedMs` is a monotonic (nanoTime) duration from submission to the terminal outcome of that one operation.
 * "First audible playback" is NOT logged here: use the existing `WavdropStartTrace audio_position_advancing` line.
 */
internal object ValidationTrace {
    const val TAG = "WavdropValidation"

    @Volatile internal var enabled: Boolean = BuildConfig.DEBUG
    internal var sink: (String) -> Unit = { Log.d(TAG, it) }
    internal var clockNanos: () -> Long = System::nanoTime

    fun nowNanos(): Long = clockNanos()

    fun buildIdentity() {
        if (!enabled) return
        sink(
            "build_identity variant=${PhysicalQueuePolicy.variantLabel} committedGate=${PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED} " +
                "validationOverride=${PhysicalQueuePolicy.VALIDATION_BOUNDED} fullModeMax=${PhysicalWindowPolicy.FULL_MODE_MAX} " +
                "crossfadeRuntime=${CrossfadeRolloutPolicy.RUNTIME_ENABLED} sdk=${android.os.Build.VERSION.SDK_INT}",
        )
    }

    fun installRequested(operation: String, logicalSize: Int, mode: QueueMode, startIndex: Int) {
        if (!enabled) return
        sink("install_requested op=$operation mode=$mode logicalSize=$logicalSize startLogical=$startIndex")
    }

    /** The adapter issued a physical playlist operation (install or add/remove batch). Attributed through [TracedOutcome]. */
    fun physicalSubmitted(submission: PhysicalSubmission, logicalSize: Int) {
        if (!enabled) return
        val op = (submission.onOutcome as? TracedOutcome)?.operation ?: "unlabeled"
        val counts = if (submission.kind == "adjust") " removes=${submission.removes} inserts=${submission.inserts}" else ""
        sink("window_submitted op=$op kind=${submission.kind}$counts logicalSize=$logicalSize")
    }

    /**
     * A mutation's baseline observation was requested. NOTHING was changed on the player yet; classification may still end in a
     * logical-only rebase, a bounded edit, a re-anchor or a failure. Only `baseline` is an observation (`offline`, `empty_queue`
     * and `reanchor_unverified_window` are not, the last being reported as the install it issues).
     */
    fun baselineObservation(operation: String, logicalSize: Int, result: MutationSubmission) {
        if (result is MutationSubmission.Submitted && result.path == "baseline") baselineObserved(operation, logicalSize)
    }

    /** A maintenance request with `reanchor=false` is a baseline observation; `reanchor=true` is an install (reported by the adapter). */
    fun baselineObservation(operation: String, logicalSize: Int, result: MaintenanceResult) {
        if (result is MaintenanceResult.Submitted && !result.reanchor) baselineObserved(operation, logicalSize)
    }

    private fun baselineObserved(operation: String, logicalSize: Int) {
        if (!enabled) return
        sink("baseline_observation op=$operation logicalSize=$logicalSize")
    }

    fun windowTerminal(operation: String, startedNanos: Long, outcome: BoundedInstallOutcome, logicalSize: Int, physicalCount: Int?) {
        if (!enabled) return
        val ms = (clockNanos() - startedNanos) / 1_000_000L
        val detail = when (outcome) {
            is BoundedInstallOutcome.Active ->
                "window_verified op=$operation elapsedMs=$ms logicalSize=$logicalSize installedCount=${outcome.installed.installedCount} " +
                    "logicalCurrent=${outcome.installed.logicalCurrent} physicalCount=${physicalCount ?: -1} " +
                    "gen=${outcome.installed.token.logicalGeneration}/${outcome.installed.token.windowGeneration}"
            is BoundedInstallOutcome.Stale -> "window_not_verified op=$operation elapsedMs=$ms result=stale reason=${outcome.reason}"
            is BoundedInstallOutcome.Incomplete ->
                "window_not_verified op=$operation elapsedMs=$ms result=incomplete expected=${outcome.expectedCount} observed=${outcome.observedCount}"
            is BoundedInstallOutcome.Rejected -> "window_not_verified op=$operation elapsedMs=$ms result=rejected reason=${outcome.reason}"
            is BoundedInstallOutcome.Failed ->
                "window_not_verified op=$operation elapsedMs=$ms result=failed cause=${outcome.cause::class.java.simpleName}"
            is BoundedInstallOutcome.Submitted -> return // not terminal; submission is reported only by the *Submitted helpers
        }
        sink(detail)
    }
}

/** An outcome callback that remembers which controller operation it belongs to, so adapter-level submissions can be attributed. */
internal class TracedOutcome(val operation: String, private val handler: (BoundedInstallOutcome) -> Unit) : (BoundedInstallOutcome) -> Unit {
    override fun invoke(outcome: BoundedInstallOutcome) = handler(outcome)
}
