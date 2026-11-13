package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
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
import com.launchpoint.wavdrop.ui.theme.PrimaryVioletContainer
import com.launchpoint.wavdrop.ui.theme.RosePinkPrimary
import com.launchpoint.wavdrop.ui.theme.SecondaryAqua
import com.launchpoint.wavdrop.ui.theme.SlateGrayPrimary
import com.launchpoint.wavdrop.ui.theme.SurfaceDark
import com.launchpoint.wavdrop.ui.theme.SurfaceLight
import com.launchpoint.wavdrop.ui.theme.SurfaceVariantDark
import com.launchpoint.wavdrop.ui.theme.SurfaceVariantLight
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-WR-UX-01B3: accent-coloured text reaches WCAG AA (4.5:1) on every modelled Wrapped backdrop.
 *
 * DISCLOSURE: colour schemes here are reconstructed from the app's public theme constants (the same arguments
 * Theme.kt passes to darkColorScheme/lightColorScheme; surfaceContainerHighest is the real Material 3 default), and
 * the six fallback-theme hex values are mirrored from WrappedScreen.kt's private palette. The composition test at
 * the bottom resolves colours through a real MaterialTheme, but no device rendering, screenshot or on-device
 * accessibility audit was performed; artwork-backed text is out of scope.
 */
class WrappedAccentTextContrastTest {

    private class Mode(val name: String, val scheme: (Color) -> ColorScheme, val onSurface: Color)

    private val dark = Mode(
        "dark",
        { p ->
            darkColorScheme(
                primary = p, secondary = SecondaryAqua, background = BackgroundDark, surface = SurfaceDark,
                surfaceVariant = SurfaceVariantDark, onSurface = OnSurfaceDark, onBackground = OnSurfaceDark,
            )
        },
        OnSurfaceDark,
    )
    private val light = Mode(
        "light",
        { p ->
            lightColorScheme(
                primary = p, secondary = SecondaryAqua, background = BackgroundLight, surface = SurfaceLight,
                surfaceVariant = SurfaceVariantLight, onSurface = OnSurfaceLight, onBackground = OnSurfaceLight,
            )
        },
        OnSurfaceLight,
    )
    private val modes = listOf(dark, light)

    private val accents = listOf(
        PrimaryViolet, CleanPurplePrimary, DeepTealPrimary, OceanBluePrimary, EmeraldGreenPrimary,
        AmberGoldPrimary, CrimsonRedPrimary, RosePinkPrimary, SlateGrayPrimary,
    )

    // Mirrors WrappedFallbackTheme palette (container, secondary) in WrappedScreen.kt.
    private val fallbackPalettes = listOf(
        Color(0xFF3A3A42) to Color(0xFF8F8F99),
        Color(0xFF0B6F92) to Color(0xFF14B8D4),
        Color(0xFF08746A) to Color(0xFF2CC8A7),
        Color(0xFF6D4DBA) to Color(0xFFB08CFF),
        Color(0xFFC66B2E) to Color(0xFFE9A85B),
        Color(0xFF6D5BD0) to Color(0xFF8EA1FF),
    )
    private val intensityAlphas = listOf(0.055f, 0.08f, 0.13f) // SUBTLE, MEDIUM, BOLD

    private val styles = WrappedVisualStyle.values().map { it to it.toStyleTokens() }

    /** Every (label, backdrops) pairing a card or screen text can sit on, for the given mode and accent. */
    private fun scenarios(mode: Mode, accent: Color): List<Pair<String, List<Color>>> {
        val s = mode.scheme(accent)
        val list = mutableListOf<Pair<String, List<Color>>>()
        list += "scaffold" to wrappedScaffoldBackdrops(s)
        for ((style, tokens) in styles) list += "insight/$style" to wrappedInsightBackdrops(tokens, s)
        for ((container, secondary) in fallbackPalettes) for (a in intensityAlphas) {
            list += "decorative/fallback#${container.value}/$a" to wrappedContainerBackdrops(
                container.copy(alpha = a), listOf(container, secondary), s,
            )
        }
        for (a in intensityAlphas) { // AUTO fallback: accent / aqua secondary
            list += "decorative/auto/$a" to wrappedContainerBackdrops(accent.copy(alpha = a), listOf(accent, s.secondary), s)
            list += "decorative/autoSecondary/$a" to wrappedContainerBackdrops(s.secondary.copy(alpha = a), listOf(s.secondary, accent), s)
        }
        // Artwork card while the image is absent/failed: opaque primaryContainer base (Midnight Violet dark shown;
        // the container role differs per accent, covered via the scheme's own primaryContainer).
        list += "artworkCardNoArtwork/primaryContainer" to wrappedContainerBackdrops(
            s.primaryContainer, listOf(accent, s.secondary), s,
        )
        return list
    }

    @Test fun accentTextReachesAaOnEveryModelledBackdropForAllAccentsAndThemes() {
        var checked = 0
        for (mode in modes) for (accent in accents) {
            val s = mode.scheme(accent)
            for ((name, backdrops) in scenarios(mode, accent)) {
                val color = wrappedAccentTextColor(accent, mode.onSurface, backdrops)
                val ratio = wrappedMinContrast(color, backdrops)
                assertTrue("${mode.name} accent=$accent $name ratio=$ratio", ratio >= 4.5)
                checked++
            }
            assertTrue(s.primary == accent)
        }
        assertEquals(18 * 29, checked)
    }

    @Test fun compliantAccentIsRetainedAndNonCompliantIsAdjustedMinimally() {
        var retained = 0
        var adjusted = 0
        for (mode in modes) for (accent in accents) for ((name, backdrops) in scenarios(mode, accent)) {
            val before = wrappedMinContrast(accent, backdrops)
            val t = wrappedAccentTextBlend(accent, mode.onSurface, backdrops)
            val color = wrappedAccentTextColor(accent, mode.onSurface, backdrops)
            if (before >= 4.5) {
                assertEquals("${mode.name} $accent $name", 0f, t, 0f)
                assertEquals(accent, color)
                retained++
            } else {
                assertTrue("blend must be > 0 when the accent fails ($name)", t > 0f)
                // Minimality within the declared tolerance: one step less would still miss the target.
                val lessT = t - WRAPPED_ACCENT_BLEND_STEP
                val less = Color(
                    accent.red + (mode.onSurface.red - accent.red) * lessT,
                    accent.green + (mode.onSurface.green - accent.green) * lessT,
                    accent.blue + (mode.onSurface.blue - accent.blue) * lessT,
                )
                assertTrue("${mode.name} $accent $name not minimal", wrappedMinContrast(less, backdrops) < 4.5)
                adjusted++
            }
        }
        assertTrue("some accents are already compliant and must stay untouched", retained > 0)
        assertTrue("some accents genuinely need adjustment", adjusted > 0)
    }

    /** Finer-grained, independently written sampling of the actual gradient paint order (101 positions). */
    @Test fun glassAndNightPulseGradientInteriorsStayAtAaForTheChosenColour() {
        fun over(fg: Color, bg: Color) = fg.compositeOver(bg)
        for (mode in modes) for (accent in accents) {
            val s = mode.scheme(accent)
            val glass = WrappedVisualStyle.values().map { it.toStyleTokens() }.first { it.cardTreatment == WrappedCardTreatment.GLASS }
            val night = WrappedVisualStyle.values().map { it.toStyleTokens() }.first { it.cardTreatment == WrappedCardTreatment.IMMERSIVE }
            val glassColor = wrappedAccentTextColor(accent, mode.onSurface, wrappedInsightBackdrops(glass, s))
            val nightColor = wrappedAccentTextColor(accent, mode.onSurface, wrappedInsightBackdrops(night, s))
            val glassBase = over(s.surfaceVariant.copy(alpha = glass.containerAlpha), s.background)
            for (i in 0..100) {
                val pos = i / 100f
                for (premul in listOf(true, false)) {
                    // Glass: accent@0.12 -> Transparent over [0, 0.55]
                    val u = (pos / WRAPPED_GLASS_HAZE_END_STOP).coerceAtMost(1f)
                    val a = WRAPPED_GLASS_HAZE_ALPHA * (1 - u)
                    val rgbScale = if (premul) 1f else (1 - u) // unpremultiplied lerp toward black transparent
                    val haze = Color(accent.red * rgbScale, accent.green * rgbScale, accent.blue * rgbScale, a)
                    val glassBg = over(haze, glassBase)
                    assertTrue("glass ${mode.name} $accent @${pos}", wrappedContrastRatio(glassColor, glassBg) >= 4.5)
                    // Night Pulse: accent@alpha -> surface@0.32
                    val a2 = night.containerAlpha + (WRAPPED_IMMERSIVE_SURFACE_ALPHA - night.containerAlpha) * pos
                    val nightLayer = if (premul) {
                        val pr = accent.red * night.containerAlpha * (1 - pos) + s.surface.red * WRAPPED_IMMERSIVE_SURFACE_ALPHA * pos
                        val pg = accent.green * night.containerAlpha * (1 - pos) + s.surface.green * WRAPPED_IMMERSIVE_SURFACE_ALPHA * pos
                        val pb = accent.blue * night.containerAlpha * (1 - pos) + s.surface.blue * WRAPPED_IMMERSIVE_SURFACE_ALPHA * pos
                        Color(pr / a2, pg / a2, pb / a2, a2)
                    } else {
                        Color(
                            accent.red + (s.surface.red - accent.red) * pos,
                            accent.green + (s.surface.green - accent.green) * pos,
                            accent.blue + (s.surface.blue - accent.blue) * pos,
                            a2,
                        )
                    }
                    val nightBg = over(nightLayer, s.background)
                    assertTrue("night ${mode.name} $accent @${pos}", wrappedContrastRatio(nightColor, nightBg) >= 4.5)
                }
            }
        }
    }

    @Test fun supportingTextFloorIsUnchangedByTheAccentPolicy() {
        assertEquals(0.68f, WRAPPED_SUPPORTING_TEXT_MIN_ALPHA, 0f)
        assertEquals(0.68f, wrappedSupportingTextAlpha(0.5f), 0f)
        assertEquals(0.7f, wrappedSupportingTextAlpha(0.7f), 0f)
    }

    /** Prints representative before/after numbers into the test report (captured in the XML system-out). */
    @Test fun reportRepresentativeBeforeAfter() {
        for (mode in modes) for (accent in accents) {
            val groups = scenarios(mode, accent).groupBy {
                val n = it.first
                if (n.startsWith("decorative")) "decorative" else n
            }
            for ((group, rows) in groups) {
                val before = rows.minOf { wrappedMinContrast(accent, it.second) }
                val after = rows.minOf { (_, b) -> wrappedMinContrast(wrappedAccentTextColor(accent, mode.onSurface, b), b) }
                val blend = rows.maxOf { wrappedAccentTextBlend(accent, mode.onSurface, it.second) }
                println("ACCENT-CONTRAST ${mode.name} #${java.lang.Long.toHexString((accent.value shr 32).toLong())} $group before=${"%.2f".format(before)} after=${"%.2f".format(after)} blend=${"%.2f".format(blend)}")
            }
        }
    }

    // ── Real MaterialTheme resolution through the production composables ─────────────────────────────────────

    private class NoopApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private fun compose(content: @Composable () -> Unit) = runBlocking {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(0L)
        }
        val recomposer = Recomposer(coroutineContext + clock)
        val loop = launch(clock, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoopApplier(), recomposer)
        try {
            composition.setContent(content)
            Snapshot.sendApplyNotifications()
            recomposer.awaitIdle()
        } finally {
            composition.dispose()
            recomposer.cancel()
            loop.cancel()
        }
    }

    @Test fun productionComposablesResolveThemeColoursAndProvideTheCardColourToContent() {
        for (mode in modes) for (accent in accents) {
            val scheme = mode.scheme(accent)
            var scaffoldText = Color.Unspecified
            val insight = mutableMapOf<WrappedCardTreatment, Color>()
            var container = Color.Unspecified
            var providedToChild = Color.Unspecified
            var fallbackOutsideCard = Color.Unspecified
            val containerColor = fallbackPalettes[1].first.copy(alpha = 0.08f)
            val motif = listOf(fallbackPalettes[1].first, fallbackPalettes[1].second)
            compose {
                MaterialTheme(colorScheme = scheme) {
                    scaffoldText = rememberWrappedScaffoldAccentText()
                    for ((_, tokens) in styles) insight[tokens.cardTreatment] = rememberWrappedInsightAccentText(tokens)
                    container = rememberWrappedContainerAccentText(containerColor, motif)
                    fallbackOutsideCard = currentWrappedAccentText()
                    CompositionLocalProvider(LocalWrappedAccentText provides container) {
                        providedToChild = currentWrappedAccentText()
                    }
                }
            }
            assertEquals(wrappedAccentTextColor(accent, mode.onSurface, wrappedScaffoldBackdrops(scheme)), scaffoldText)
            for ((_, tokens) in styles) {
                assertEquals(
                    wrappedAccentTextColor(accent, mode.onSurface, wrappedInsightBackdrops(tokens, scheme)),
                    insight[tokens.cardTreatment],
                )
            }
            assertEquals(
                wrappedAccentTextColor(accent, mode.onSurface, wrappedContainerBackdrops(containerColor, motif, scheme)),
                container,
            )
            assertEquals("rows read the colour of the card hosting them", container, providedToChild)
            assertEquals("outside a Wrapped card the raw theme accent applies", accent, fallbackOutsideCard)
            assertNotEquals(Color.Unspecified, scaffoldText)
        }
    }
}
