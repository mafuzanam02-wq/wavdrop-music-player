package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.launchpoint.wavdrop.ui.theme.AmberGoldPrimary
import com.launchpoint.wavdrop.ui.theme.BackgroundDark
import com.launchpoint.wavdrop.ui.theme.BackgroundLight
import com.launchpoint.wavdrop.ui.theme.CleanPurplePrimary
import com.launchpoint.wavdrop.ui.theme.CrimsonRedPrimary
import com.launchpoint.wavdrop.ui.theme.DeepTealPrimary
import com.launchpoint.wavdrop.ui.theme.EmeraldGreenPrimary
import com.launchpoint.wavdrop.ui.theme.OceanBluePrimary
import com.launchpoint.wavdrop.ui.theme.OnSurfaceDark
import com.launchpoint.wavdrop.ui.theme.OnSurfaceLight
import com.launchpoint.wavdrop.ui.theme.PrimaryViolet
import com.launchpoint.wavdrop.ui.theme.RosePinkPrimary
import com.launchpoint.wavdrop.ui.theme.SlateGrayPrimary
import com.launchpoint.wavdrop.ui.theme.SurfaceDark
import com.launchpoint.wavdrop.ui.theme.SurfaceLight
import com.launchpoint.wavdrop.ui.theme.SurfaceVariantDark
import com.launchpoint.wavdrop.ui.theme.SurfaceVariantLight
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-WR-UX-01B2: WCAG 2.x contrast of Wrapped supporting text, computed on alpha-composited colours from the app's
 * real theme constants and the real Material 3 default container roles (the same scheme Theme.kt builds, which does
 * not override surfaceContainerHighest). Backdrops mirror InsightCard's three treatments plus the plain scaffold.
 * This verifies the colour maths, not the rendered pixels: artwork-backed text and on-device rendering are not
 * covered (image dependent).
 */
class WrappedTextContrastTest {

    private class Mode(val name: String, val scheme: ColorScheme, val onSurface: Color)

    private val modes = listOf(
        Mode(
            "dark",
            darkColorScheme(
                background = BackgroundDark, surface = SurfaceDark,
                surfaceVariant = SurfaceVariantDark, onSurface = OnSurfaceDark,
            ),
            OnSurfaceDark,
        ),
        Mode(
            "light",
            lightColorScheme(
                background = BackgroundLight, surface = SurfaceLight,
                surfaceVariant = SurfaceVariantLight, onSurface = OnSurfaceLight,
            ),
            OnSurfaceLight,
        ),
    )

    private val accents = listOf(
        PrimaryViolet, CleanPurplePrimary, DeepTealPrimary, OceanBluePrimary, EmeraldGreenPrimary,
        AmberGoldPrimary, CrimsonRedPrimary, RosePinkPrimary, SlateGrayPrimary,
    )

    private fun over(fg: Color, alpha: Float, bg: Color) = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
    )

    private fun lin(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** Every non-artwork backdrop Wrapped text sits on, per accent, composited as InsightCard paints them. */
    private fun backdrops(mode: Mode): List<Pair<String, Color>> {
        val s = mode.scheme
        val glass = toGlass(s)
        val list = mutableListOf(
            "scaffold" to s.background,
            "glass" to glass,
            "editorial" to s.surfaceContainerHighest,
            "immersiveBottom" to over(s.surface, 0.32f, s.background),
        )
        for (p in accents) {
            list += "glassTop($p)" to over(p, 0.12f, glass)
            list += "immersiveTop($p)" to over(p, 0.24f, s.background)
            list += "decorativeBold($p)" to over(p, 0.13f, s.background)
        }
        return list
    }

    private fun toGlass(s: ColorScheme) = over(s.surfaceVariant, 0.14f, s.background)

    // Alphas requested at the Wrapped call sites (Studio/Glass/Night Pulse share these text paths).
    private val requestedAlphas = listOf(0.5f, 0.52f, 0.55f, 0.6f, 0.7f)

    @Test fun supportingTextMeetsAaOnEveryNonArtworkBackdropInBothThemes() {
        for (mode in modes) for ((name, bg) in backdrops(mode)) for (requested in requestedAlphas) {
            val fg = over(mode.onSurface, wrappedSupportingTextAlpha(requested), bg)
            val ratio = contrast(fg, bg)
            assertTrue("${mode.name}/$name alpha=$requested ratio=$ratio", ratio >= 4.5)
        }
    }

    @Test fun originalLowAlphasDidNotMeetAaSoTheFloorIsNeeded() {
        val light = modes.first { it.name == "light" }
        val dark = modes.first { it.name == "dark" }
        // Evidence for the finding: the pre-fix alphas fail on these real combinations.
        assertTrue(contrast(over(light.onSurface, 0.5f, light.scheme.background), light.scheme.background) < 4.5)
        assertTrue(contrast(over(light.onSurface, 0.55f, light.scheme.background), light.scheme.background) < 4.5)
        assertTrue(contrast(over(light.onSurface, 0.6f, light.scheme.background), light.scheme.background) < 4.5)
        assertTrue(
            contrast(
                over(dark.onSurface, 0.5f, dark.scheme.background), dark.scheme.background,
            ) < 4.5,
        )
        assertTrue(
            contrast(
                over(dark.onSurface, 0.55f, dark.scheme.surfaceContainerHighest),
                dark.scheme.surfaceContainerHighest,
            ) < 4.5,
        )
    }

    @Test fun floorOnlyLiftsAlphasBelowItAndPreservesHigherTiers() {
        assertEquals(WRAPPED_SUPPORTING_TEXT_MIN_ALPHA, wrappedSupportingTextAlpha(0.5f), 0f)
        assertEquals(WRAPPED_SUPPORTING_TEXT_MIN_ALPHA, wrappedSupportingTextAlpha(0.6f), 0f)
        assertEquals(0.7f, wrappedSupportingTextAlpha(0.7f), 0f)
        assertEquals(1f, wrappedSupportingTextAlpha(1f), 0f)
        assertTrue(WRAPPED_SUPPORTING_TEXT_MIN_ALPHA < 0.7f) // still a distinct, lower tier than 0.7
    }
}
