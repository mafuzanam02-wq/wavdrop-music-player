package com.launchpoint.wavdrop.ui.components.motion

import com.launchpoint.wavdrop.ui.scan.LibraryScanUiState
import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure (Compose-free) model behind [PullToRefreshContainer] (P5-F11), so the gesture maths and the scan-state mapping are
 * unit-testable. It owns no scan state: the scan authority stays `LibraryScanCoordinator`; this only maps its states.
 */

/**
 * Pull physics. The visual displacement follows the finger 1:1 at first and gets increasingly resistant, approaching
 * [maxPullPx] asymptotically (never reaching it). The refresh trigger is [thresholdPx], deliberately well below the cap.
 */
class PullRefreshPhysics(val maxPullPx: Float, thresholdPx: Float) {
    init { require(maxPullPx > 0f) }

    /** Never more than [MAX_THRESHOLD_FRACTION] of the cap, so the threshold stays reachable on small viewports. */
    val thresholdPx: Float = thresholdPx.coerceIn(1f, maxPullPx * MAX_THRESHOLD_FRACTION)

    /** Displayed offset for a raw finger travel. d(0) = 0, slope 1 at 0, monotonic, bounded by [maxPullPx]. */
    fun displayed(rawPx: Float): Float =
        if (rawPx <= 0f) 0f else maxPullPx * (1f - exp(-rawPx / maxPullPx))

    /** Inverse of [displayed]; used to continue a pull from a settled position. */
    fun rawFor(displayedPx: Float): Float {
        val fraction = (displayedPx / maxPullPx).coerceIn(0f, MAX_INVERSE_FRACTION)
        return -maxPullPx * ln(1f - fraction)
    }

    fun progress(displayedPx: Float): Float = (displayedPx / thresholdPx).coerceIn(0f, 1f)

    companion object {
        /** The visual pull is capped at this share of the content viewport height (spec: ~35-40%). */
        const val VIEWPORT_CAP_FRACTION = 0.38f
        const val MAX_THRESHOLD_FRACTION = 0.7f
        private const val MAX_INVERSE_FRACTION = 0.999f
    }
}

/** One step of a pull: how much of the delta was consumed, and whether this step crossed the release threshold upward. */
data class PullStep(val consumedRawPx: Float, val crossedThresholdUp: Boolean)

/**
 * One drag gesture. Pulling down grows [rawPx]; reversing shrinks it (cancel by dragging back). Releasing at or past the
 * threshold requests exactly one refresh and ends the gesture, so a single completed gesture can never refresh twice.
 */
class PullGesture(private val physics: PullRefreshPhysics) {
    var rawPx: Float = 0f
        private set
    private var thresholdHapticSpent = false

    val isPulling: Boolean get() = rawPx > 0f
    val displayedPx: Float get() = physics.displayed(rawPx)

    /** Continues a gesture from an already displaced position (e.g. grabbing the compact refreshing indicator). */
    fun begin(fromDisplayedPx: Float) {
        rawPx = physics.rawFor(fromDisplayedPx)
        // Starting past the threshold must not buzz; only a fresh crossing does.
        thresholdHapticSpent = fromDisplayedPx >= physics.thresholdPx
    }

    fun pull(deltaPx: Float): PullStep {
        val before = rawPx
        val wasBelow = physics.displayed(before) < physics.thresholdPx
        rawPx = (before + deltaPx).coerceAtLeast(0f)
        val crossed = wasBelow && physics.displayed(rawPx) >= physics.thresholdPx && !thresholdHapticSpent
        if (crossed) thresholdHapticSpent = true
        return PullStep(consumedRawPx = rawPx - before, crossedThresholdUp = crossed)
    }

    /** Ends the gesture. True iff it should request a refresh (threshold reached and not already refreshing). */
    fun release(alreadyRefreshing: Boolean): Boolean {
        val fire = !alreadyRefreshing && physics.displayed(rawPx) >= physics.thresholdPx
        rawPx = 0f
        thresholdHapticSpent = false
        return fire
    }
}

/**
 * Phase of a USER-triggered refresh, derived only from the authoritative [LibraryScanUiState]. [Succeeded] is reachable
 * solely through an observed Scanning -> Complete transition after a request, so a stale Complete, a Warning/Error or an
 * interrupted scan (Scanning -> Idle) can never read as success. An automatic scan never enters this machine.
 */
enum class ManualRefreshPhase { None, Requested, Scanning, Succeeded }

/** Result of a pull release: the next phase and whether the caller must invoke the refresh action. */
data class RefreshRequest(val phase: ManualRefreshPhase, val invokeRefresh: Boolean)

fun requestManualRefresh(phase: ManualRefreshPhase, scan: LibraryScanUiState): RefreshRequest = when {
    // Already refreshing for this user: never start a second scan.
    phase == ManualRefreshPhase.Requested || phase == ManualRefreshPhase.Scanning -> RefreshRequest(phase, false)
    // An automatic scan is in flight: adopt it instead of starting a duplicate.
    scan == LibraryScanUiState.Scanning -> RefreshRequest(ManualRefreshPhase.Scanning, false)
    else -> RefreshRequest(ManualRefreshPhase.Requested, true)
}

fun reduceManualRefresh(phase: ManualRefreshPhase, scan: LibraryScanUiState): ManualRefreshPhase = when (phase) {
    ManualRefreshPhase.None, ManualRefreshPhase.Succeeded -> phase
    // Stale pre-request states are ignored until the scan is observed running.
    ManualRefreshPhase.Requested ->
        if (scan == LibraryScanUiState.Scanning) ManualRefreshPhase.Scanning else phase
    ManualRefreshPhase.Scanning -> when (scan) {
        LibraryScanUiState.Scanning -> phase
        LibraryScanUiState.Complete -> ManualRefreshPhase.Succeeded
        // Warning / Error / Idle (interrupted): no success; the existing status card reports warnings and errors.
        else -> ManualRefreshPhase.None
    }
}

/** What the refresh affordance shows. */
/** [Starting] = refresh requested but the scan is not yet confirmed running (static, never a spinner). */
enum class RefreshIndicatorMode { Hidden, Pulling, Starting, Scanning, Success }

fun indicatorMode(phase: ManualRefreshPhase, isPulling: Boolean): RefreshIndicatorMode = when (phase) {
    ManualRefreshPhase.Requested -> RefreshIndicatorMode.Starting
    ManualRefreshPhase.Scanning -> RefreshIndicatorMode.Scanning
    ManualRefreshPhase.Succeeded -> RefreshIndicatorMode.Success
    ManualRefreshPhase.None -> if (isPulling) RefreshIndicatorMode.Pulling else RefreshIndicatorMode.Hidden
}

/** Whether the compact indicator holds the content displaced (refreshing / confirming) rather than at rest. */
fun holdsCompactPosition(mode: RefreshIndicatorMode): Boolean =
    mode == RefreshIndicatorMode.Starting || mode == RefreshIndicatorMode.Scanning || mode == RefreshIndicatorMode.Success

object PullRefreshCopy {
    const val PULL_DESCRIPTION = "Pull down to refresh library"
    const val STARTING = "Starting library scan"
    const val SCANNING = "Scanning library"
    const val SUCCESS = "Library refreshed"
    const val ACTION = "Refresh library"
    const val SUCCESS_VISIBLE_MS = 1_400L

    /** A request the scan never acknowledged is dropped after this long (not a scan-duration guess). */
    const val UNACKNOWLEDGED_REQUEST_MS = 2_000L
}
