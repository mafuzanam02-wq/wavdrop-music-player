package com.launchpoint.wavdrop.ui.components.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.launchpoint.wavdrop.ui.theme.WavdropMotion

/**
 * Restrained press-feedback for tappable cards / rows / buttons (WU-02).
 *
 * Applies a slight scale compression while pressed and settles cleanly on release — enough to make
 * a surface feel responsive to touch without becoming flashy (no bounce, no shadow, no glow). It is
 * meant to COMPLEMENT Material ripple, not replace it: pass the same [interactionSource] you give to
 * `clickable(...)`, and ripple keeps working underneath.
 *
 * Under reduced motion the scale is disabled entirely; the caller's ripple remains as the touch
 * affordance, so there is no accessibility regression.
 *
 * @param pressedScale peak compression, clamped to a subtle 0.985–1.0 range.
 */
fun Modifier.wavdropPressFeedback(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = DEFAULT_PRESSED_SCALE,
): Modifier = composed {
    if (!enabled) return@composed this

    val reducedMotion = rememberReducedMotion()
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (pressed && !reducedMotion) pressedScale.coerceIn(MIN_PRESSED_SCALE, 1f) else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = WavdropMotion.Durations.PressResponse,
            easing = WavdropMotion.Easings.Press,
        ),
        label = "wavdropPressScale",
    )

    // Block form of graphicsLayer keeps the read in the draw phase and avoids per-frame allocation.
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Subtle default compression — perceptible under the finger, invisible otherwise. */
const val DEFAULT_PRESSED_SCALE = 0.99f

/** Floor so no caller can request an exaggerated shrink. */
const val MIN_PRESSED_SCALE = 0.985f
