package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle

/** Headline-stat emphasis level derived from the active [WrappedVisualStyle]. */
enum class WrappedStatEmphasis { STANDARD, STRONG }

/**
 * Structural card look. This is what makes the three styles read as *different surfaces* rather than
 * the same card with slightly different numbers:
 * - [GLASS] — translucent, tonal border/highlight, soft top-haze gradient, ambience visible behind.
 * - [EDITORIAL] — opaque solid panel, flat, no accent bar, a divider under the label (magazine feel).
 * - [IMMERSIVE] — gradient-filled with an accent halo, dominant artwork, larger emphasis.
 */
enum class WrappedCardTreatment { GLASS, EDITORIAL, IMMERSIVE }

/**
 * Presentation tokens derived from a [WrappedVisualStyle] (WU-07). Presentation ONLY — data, order,
 * calculations and navigation are identical across styles. Values are pure (no hard-coded colours;
 * surfaces apply these over MaterialTheme roles), so the mapping is unit-testable.
 */
data class WrappedStyleTokens(
    /** Structural card rendering path. */
    val cardTreatment: WrappedCardTreatment,
    /** Corner radius for Wrapped cards. */
    val cornerRadius: Dp,
    /** Container fill alpha (GLASS: translucent; EDITORIAL: opaque; IMMERSIVE: gradient base). */
    val containerAlpha: Float,
    /** Tonal border alpha (GLASS shows a soft highlight border; others 0). */
    val borderAlpha: Float,
    /** Whether the left accent bar is drawn. */
    val showAccentBar: Boolean,
    /** Left accent bar width. */
    val accentBarWidth: Dp,
    /** Left accent bar alpha. */
    val accentBarAlpha: Float,
    /** Headline stat emphasis. */
    val statEmphasis: WrappedStatEmphasis,
    /** Added to the artwork scrim alphas for deeper contrast / subordinate ambience (0f = unchanged). */
    val artworkScrimBoost: Float,
    /** Artwork crossfade duration — the per-style motion character (soft vs crisp vs stronger). */
    val artworkCrossfadeMs: Int,
    /** One-shot content entrance fade duration for cards. */
    val contentRevealMs: Int,
    /** Extra vertical rise (dp) on card entrance — IMMERSIVE only; 0 elsewhere. */
    val contentRevealRise: Dp,
)

fun WrappedVisualStyle.toStyleTokens(): WrappedStyleTokens = when (this) {
    // Glass Flow — layered, translucent, ambience visible, soft settle.
    WrappedVisualStyle.GLASS_FLOW -> WrappedStyleTokens(
        cardTreatment = WrappedCardTreatment.GLASS,
        cornerRadius = 24.dp,
        containerAlpha = 0.14f,
        borderAlpha = 0.22f,
        showAccentBar = true,
        accentBarWidth = 3.dp,
        accentBarAlpha = 0.45f,
        statEmphasis = WrappedStatEmphasis.STANDARD,
        artworkScrimBoost = 0f,
        artworkCrossfadeMs = 400,
        contentRevealMs = 320,
        contentRevealRise = 0.dp,
    )
    // Studio Cards — opaque editorial panels, flat, crisp, divider structure, artwork subordinate.
    WrappedVisualStyle.STUDIO_CARDS -> WrappedStyleTokens(
        cardTreatment = WrappedCardTreatment.EDITORIAL,
        cornerRadius = 8.dp,
        containerAlpha = 1f,
        borderAlpha = 0f,
        showAccentBar = false,
        accentBarWidth = 0.dp,
        accentBarAlpha = 0f,
        statEmphasis = WrappedStatEmphasis.STANDARD,
        artworkScrimBoost = 0.10f,
        artworkCrossfadeMs = 180,
        contentRevealMs = 160,
        contentRevealRise = 0.dp,
    )
    // Night Pulse — immersive gradient + accent halo, dominant artwork, stronger reveal.
    WrappedVisualStyle.NIGHT_PULSE -> WrappedStyleTokens(
        cardTreatment = WrappedCardTreatment.IMMERSIVE,
        cornerRadius = 20.dp,
        containerAlpha = 0.24f,
        borderAlpha = 0f,
        showAccentBar = true,
        accentBarWidth = 5.dp,
        accentBarAlpha = 1f,
        statEmphasis = WrappedStatEmphasis.STRONG,
        artworkScrimBoost = 0.16f,
        artworkCrossfadeMs = 500,
        contentRevealMs = 380,
        contentRevealRise = 8.dp,
    )
}

/** Broadcasts the active style tokens to Wrapped surfaces without threading them through signatures. */
val LocalWrappedStyleTokens = compositionLocalOf { WrappedVisualStyle.DEFAULT.toStyleTokens() }
