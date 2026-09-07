package com.launchpoint.wavdrop.ui.components.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import com.launchpoint.wavdrop.ui.theme.WavdropMotion
import kotlinx.coroutines.delay

/**
 * Reusable, restrained "tonal flash" for confirming a selection or acknowledging a state change
 * (WU-02) — generalised from the proven QueueSheet drop-flash idiom (peak alpha ~0.12, ~220ms fade)
 * without touching the queue files.
 *
 * Use it for success / selection confirmation and brief emphasis. It is intentionally quiet: a low
 * peak alpha over a single MaterialTheme colour, no glow and no movement. Under reduced motion it
 * becomes a short static hold instead of a fade, so the acknowledgement still reads.
 */
@Stable
class TransientHighlightState internal constructor(
    private val peakAlpha: Float,
    private val durationMillis: Int,
    private val reducedMotion: Boolean,
) {
    private val animatable = Animatable(0f)

    /** Current overlay alpha, read in the draw phase by [transientHighlight]. */
    val alpha: Float get() = animatable.value

    /** Snap to peak, then either fade out (normal) or hold briefly and clear (reduced motion). */
    suspend fun flash() {
        animatable.snapTo(peakAlpha)
        if (reducedMotion) {
            delay(durationMillis.toLong())
            animatable.snapTo(0f)
        } else {
            animatable.animateTo(0f, animationSpec = tween(durationMillis = durationMillis))
        }
    }
}

/**
 * Remembers a [TransientHighlightState]. Call [TransientHighlightState.flash] from a coroutine /
 * LaunchedEffect when the acknowledged change happens.
 */
@Composable
fun rememberTransientHighlight(
    peakAlpha: Float = DEFAULT_TRANSIENT_PEAK_ALPHA,
    durationMillis: Int = WavdropMotion.Durations.EmphasizedTransition,
): TransientHighlightState {
    val reducedMotion = rememberReducedMotion()
    return remember(peakAlpha, durationMillis, reducedMotion) {
        TransientHighlightState(peakAlpha, durationMillis, reducedMotion)
    }
}

/**
 * Draws the transient tonal overlay ON TOP of content. Reads [TransientHighlightState.alpha] only in
 * the draw phase, so a flash never triggers recomposition or relayout of the row.
 *
 * @param enabled lets a single hoisted state drive just the affected row in a list.
 */
fun Modifier.transientHighlight(
    state: TransientHighlightState,
    color: Color,
    enabled: Boolean = true,
): Modifier = this.drawWithContent {
    drawContent()
    val a = if (enabled) state.alpha else 0f
    if (a > 0f) drawRect(color = color.copy(alpha = a))
}

/** Default peak alpha — quiet by design (brief spec: ~0.08–0.14). */
const val DEFAULT_TRANSIENT_PEAK_ALPHA = 0.12f
