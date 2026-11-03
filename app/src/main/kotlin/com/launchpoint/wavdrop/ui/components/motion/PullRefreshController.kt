package com.launchpoint.wavdrop.ui.components.motion

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.launchpoint.wavdrop.ui.theme.WavdropMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Drives one value from `from` to `to`, reporting each step. Injectable so the lifecycle is testable without a frame clock. */
typealias OffsetAnimator = suspend (from: Float, to: Float, onValue: (Float) -> Unit) -> Unit

val DefaultOffsetAnimator: OffsetAnimator = { from, to, onValue ->
    animate(
        initialValue = from,
        targetValue = to,
        animationSpec = tween(WavdropMotion.Durations.EmphasizedTransition, easing = WavdropMotion.Easings.Emphasized),
    ) { value, _ -> onValue(value) }
}

/**
 * The gesture + settle lifecycle behind [PullToRefreshContainer] (P5-F11A). Offsets are Compose state read only in graphics
 * layers, so lists never recompose per frame.
 *
 * Invariants:
 * - Every gesture ends through [finishGesture], which always settles to the correct resting position itself; settlement never
 *   depends on a resting-target change elsewhere.
 * - [finishGesture] is idempotent, so it may be reached from several signals (pointer release, fling, disposal) safely.
 * - A settle can never overwrite newer finger movement: every pull/settle bumps a generation and stale animation steps are dropped.
 */
@Stable
class PullRefreshController(
    private val scope: CoroutineScope,
    private val animator: OffsetAnimator = DefaultOffsetAnimator,
) {
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var isDragging by mutableStateOf(false)
        private set

    private var physics: PullRefreshPhysics? = null
    private var gesture: PullGesture? = null
    private var settleJob: Job? = null
    private var settlingTarget: Float? = null
    private var generation = 0
    private var flingOwed = false

    val progress: Float get() = physics?.progress(offsetPx) ?: 0f

    fun configure(viewportPx: Float, thresholdPx: Float) {
        if (viewportPx <= 0f) return
        val cap = viewportPx * PullRefreshPhysics.VIEWPORT_CAP_FRACTION
        val current = physics
        val wanted = thresholdPx.coerceIn(1f, cap * PullRefreshPhysics.MAX_THRESHOLD_FRACTION)
        if (current == null || current.maxPullPx != cap || current.thresholdPx != wanted) {
            physics = PullRefreshPhysics(cap, thresholdPx)
        }
    }

    /** Applies a downward (+) or reversing (-) drag delta. Returns the consumed delta. */
    fun pull(deltaPx: Float, onThresholdCrossed: () -> Unit): Float {
        val physics = physics ?: return 0f
        if (!isDragging) {
            // A new finger takes over from wherever the content currently is, cancelling any settle in flight.
            cancelSettle()
            gesture = PullGesture(physics).also { it.begin(offsetPx) }
            isDragging = true
            flingOwed = false
        }
        val g = gesture ?: return 0f
        val step = g.pull(deltaPx)
        offsetPx = g.displayedPx
        if (step.crossedThresholdUp) onThresholdCrossed()
        return step.consumedRawPx
    }

    /**
     * Ends the gesture (if one is active), requests a refresh when due, and settles to [target]. Returns whether a refresh
     * was requested. [target] is evaluated after [onFire], so it sees the phase the request produced.
     */
    fun finishGesture(
        alreadyRefreshing: Boolean,
        reducedMotion: Boolean,
        onFire: () -> Unit,
        target: () -> Float,
    ): Boolean {
        if (!isDragging) return false
        val g = gesture
        isDragging = false
        gesture = null
        flingOwed = g?.isPulling == true
        val fire = g?.release(alreadyRefreshing) ?: false
        if (fire) onFire()
        settleTo(target(), reducedMotion)
        return fire
    }

    /** True once after a pulled gesture ends, so its trailing fling is swallowed instead of also moving the list. */
    fun consumeFling(): Boolean = flingOwed.also { flingOwed = false }

    /** A new finger touching down clears any stale fling debt. */
    fun onPointerDown() { flingOwed = false }

    /** Moves to [targetPx] (snaps under reduced motion). No-op while a finger is down or already settling there. */
    fun settleTo(targetPx: Float, reducedMotion: Boolean) {
        if (isDragging) return
        if (!reducedMotion && settlingTarget == targetPx && settleJob?.isActive == true) return
        cancelSettle()
        if (reducedMotion || offsetPx == targetPx) {
            offsetPx = targetPx
            return
        }
        val gen = generation
        settlingTarget = targetPx
        settleJob = scope.launch {
            animator(offsetPx, targetPx) { value -> if (gen == generation && !isDragging) offsetPx = value }
            if (gen == generation && !isDragging) {
                offsetPx = targetPx
                settlingTarget = null
            }
        }
    }

    /** Disposal / navigation away: stop everything; nothing may touch the offset afterwards. */
    fun cancel() {
        cancelSettle()
        gesture = null
        isDragging = false
        flingOwed = false
    }

    private fun cancelSettle() {
        generation++
        settleJob?.cancel()
        settleJob = null
        settlingTarget = null
    }
}
