package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.takeOrElse
import kotlin.math.pow

/**
 * P5-WR-UX-01B3: accent-coloured *text* in Wrapped keeps the theme accent when it already reads at WCAG AA
 * (4.5:1, ordinary text) over the card it sits on, and otherwise moves toward `onSurface` by the smallest blend
 * that does. Decorative accent uses (bars, halos, borders, dots, motifs, progress, icons) are not text and keep the
 * raw accent. This is deliberately separate from the supporting-text alpha floor in WrappedStyleTokens.kt.
 *
 * The backdrop functions model how the cards actually paint (same constants as InsightCard, same order), sampling
 * gradients across their full extent under both premultiplied and unpremultiplied interpolation, so the chosen
 * colour is a single stable value for the whole card.
 */
internal const val WRAPPED_ACCENT_TEXT_MIN_CONTRAST = 4.5

/** Blend search step; the returned blend is the smallest multiple of this that reaches the target. */
internal const val WRAPPED_ACCENT_BLEND_STEP = 0.01f

// Shared with InsightCard's painting so the model and the renderer cannot drift apart.
internal const val WRAPPED_GLASS_HAZE_ALPHA = 0.12f
internal const val WRAPPED_GLASS_HAZE_END_STOP = 0.55f
internal const val WRAPPED_IMMERSIVE_SURFACE_ALPHA = 0.32f

/** Upper bound of the alpha any decorative motif stroke/dot draws with (WrappedDecorativeBackground coerceAtMost). */
internal const val WRAPPED_MOTIF_MAX_ALPHA = 0.18f

private const val GRADIENT_SAMPLES = 20

internal val LocalWrappedAccentText = compositionLocalOf { Color.Unspecified }

private fun channel(c: Float): Double =
    if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

private fun luminance(c: Color): Double =
    0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

/** WCAG 2.x contrast ratio of two opaque colours. */
internal fun wrappedContrastRatio(a: Color, b: Color): Double {
    val x = luminance(a)
    val y = luminance(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}

internal fun wrappedMinContrast(foreground: Color, backdrops: List<Color>): Double =
    backdrops.minOf { wrappedContrastRatio(foreground, it) }

/** Smallest blend (multiple of [WRAPPED_ACCENT_BLEND_STEP]) of [accent] toward [onSurface] reaching [minRatio]. */
internal fun wrappedAccentTextBlend(
    accent: Color,
    onSurface: Color,
    backdrops: List<Color>,
    minRatio: Double = WRAPPED_ACCENT_TEXT_MIN_CONTRAST,
): Float {
    val steps = Math.round(1f / WRAPPED_ACCENT_BLEND_STEP)
    for (i in 0..steps) {
        val t = i * WRAPPED_ACCENT_BLEND_STEP
        if (wrappedMinContrast(blend(accent, onSurface, t), backdrops) >= minRatio) return t
    }
    return 1f
}

internal fun wrappedAccentTextColor(
    accent: Color,
    onSurface: Color,
    backdrops: List<Color>,
    minRatio: Double = WRAPPED_ACCENT_TEXT_MIN_CONTRAST,
): Color = blend(accent, onSurface, wrappedAccentTextBlend(accent, onSurface, backdrops, minRatio))

private fun blend(from: Color, to: Color, t: Float): Color =
    if (t <= 0f) from else Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f,
    )

/** Gradient colour between two stops as the shader produces it, under either interpolation convention. */
private fun gradientAt(from: Color, to: Color, u: Float, premultiplied: Boolean): Color {
    val a = from.alpha + (to.alpha - from.alpha) * u
    if (a <= 0f) return Color.Transparent
    return if (premultiplied) {
        fun ch(f: Float, g: Float) = (f * from.alpha + (g * to.alpha - f * from.alpha) * u) / a
        Color(ch(from.red, to.red), ch(from.green, to.green), ch(from.blue, to.blue), a)
    } else {
        Color(
            from.red + (to.red - from.red) * u,
            from.green + (to.green - from.green) * u,
            from.blue + (to.blue - from.blue) * u,
            a,
        )
    }
}

/** Plain scaffold (Scaffold's default container is colorScheme.background). */
internal fun wrappedScaffoldBackdrops(scheme: ColorScheme): List<Color> = listOf(scheme.background)

/**
 * Backdrops under InsightCard text for the active treatment, mirroring InsightCard's fill:
 * GLASS = surfaceVariant@containerAlpha, then a primary@haze -> transparent gradient over [0, haze stop];
 * EDITORIAL = opaque surfaceContainerHighest; IMMERSIVE = primary@containerAlpha -> surface@0.32 gradient.
 * Everything is composited over the scaffold background.
 */
internal fun wrappedInsightBackdrops(tokens: WrappedStyleTokens, scheme: ColorScheme): List<Color> {
    val primary = scheme.primary
    return when (tokens.cardTreatment) {
        WrappedCardTreatment.EDITORIAL -> listOf(scheme.surfaceContainerHighest)
        WrappedCardTreatment.GLASS -> {
            val base = scheme.surfaceVariant.copy(alpha = tokens.containerAlpha).compositeOver(scheme.background)
            val haze = primary.copy(alpha = WRAPPED_GLASS_HAZE_ALPHA)
            buildList {
                for (premul in listOf(true, false)) for (i in 0..GRADIENT_SAMPLES) {
                    val pos = i / GRADIENT_SAMPLES.toFloat()
                    val layer = if (pos >= WRAPPED_GLASS_HAZE_END_STOP) {
                        Color.Transparent
                    } else {
                        gradientAt(haze, Color.Transparent, pos / WRAPPED_GLASS_HAZE_END_STOP, premul)
                    }
                    add(layer.compositeOver(base))
                }
            }
        }
        WrappedCardTreatment.IMMERSIVE -> {
            val top = primary.copy(alpha = tokens.containerAlpha)
            val bottom = scheme.surface.copy(alpha = WRAPPED_IMMERSIVE_SURFACE_ALPHA)
            buildList {
                for (premul in listOf(true, false)) for (i in 0..GRADIENT_SAMPLES) {
                    val pos = i / GRADIENT_SAMPLES.toFloat()
                    add(gradientAt(top, bottom, pos, premul).compositeOver(scheme.background))
                }
            }
        }
    }
}

/**
 * Backdrops under text on a card whose container is [container] (possibly translucent, over the scaffold), with the
 * decorative motif strokes/dots ([motif] colours at their maximum alpha) potentially behind glyphs.
 */
internal fun wrappedContainerBackdrops(
    container: Color,
    motif: List<Color>,
    scheme: ColorScheme,
): List<Color> {
    val base = container.compositeOver(scheme.background)
    return listOf(base) + motif.map { it.copy(alpha = WRAPPED_MOTIF_MAX_ALPHA).compositeOver(base) }
}

@Composable
internal fun rememberWrappedScaffoldAccentText(): Color {
    val s = MaterialTheme.colorScheme
    return remember(s.primary, s.onSurface, s.background) {
        wrappedAccentTextColor(s.primary, s.onSurface, wrappedScaffoldBackdrops(s))
    }
}

@Composable
internal fun rememberWrappedInsightAccentText(tokens: WrappedStyleTokens): Color {
    val s = MaterialTheme.colorScheme
    return remember(
        tokens, s.primary, s.onSurface, s.background, s.surface, s.surfaceVariant, s.surfaceContainerHighest,
    ) {
        wrappedAccentTextColor(s.primary, s.onSurface, wrappedInsightBackdrops(tokens, s))
    }
}

@Composable
internal fun rememberWrappedContainerAccentText(container: Color, motif: List<Color>): Color {
    val s = MaterialTheme.colorScheme
    return remember(container, motif, s.primary, s.onSurface, s.background) {
        wrappedAccentTextColor(s.primary, s.onSurface, wrappedContainerBackdrops(container, motif, s))
    }
}

/**
 * Accent-as-text colour for the card currently hosting this content; falls back to the theme accent outside a
 * Wrapped card (the cards provide [LocalWrappedAccentText]).
 */
@Composable
internal fun currentWrappedAccentText(): Color =
    LocalWrappedAccentText.current.takeOrElse { MaterialTheme.colorScheme.primary }
