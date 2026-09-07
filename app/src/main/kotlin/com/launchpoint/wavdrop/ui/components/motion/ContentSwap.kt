package com.launchpoint.wavdrop.ui.components.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.launchpoint.wavdrop.ui.theme.WavdropMotion

/**
 * One small, reusable transition for lightweight content changes (WU-02): a text/value swap, an
 * icon/state replacement, a small count update, or an empty→content flip.
 *
 * It is a restrained fade-through — cross-fade only, no large slides. Under reduced motion it swaps
 * immediately (no [AnimatedContent] wrapper at all), which also keeps it cheap. Deliberately narrow
 * so callers don't reach for [AnimatedContent] with bespoke specs everywhere; not for large lists.
 */
@Composable
fun <T> WavdropFadeThrough(
    targetState: T,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = rememberReducedMotion(),
    content: @Composable (T) -> Unit,
) {
    if (reducedMotion) {
        content(targetState)
        return
    }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            val duration = WavdropMotion.Durations.StandardStateChange
            fadeIn(tween(durationMillis = duration, easing = WavdropMotion.Easings.Standard))
                .togetherWith(fadeOut(tween(durationMillis = duration, easing = WavdropMotion.Easings.Standard)))
        },
        label = "wavdropFadeThrough",
        content = { content(it) },
    )
}
