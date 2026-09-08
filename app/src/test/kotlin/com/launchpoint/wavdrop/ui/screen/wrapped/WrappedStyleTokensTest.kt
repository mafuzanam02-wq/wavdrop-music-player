package com.launchpoint.wavdrop.ui.screen.wrapped

import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedStyleTokensTest {

    @Test
    fun `every style maps to valid tokens`() {
        WrappedVisualStyle.entries.forEach { style ->
            val t = style.toStyleTokens()
            assertTrue(t.containerAlpha in 0f..1f)
            assertTrue(t.borderAlpha in 0f..1f)
            assertTrue(t.accentBarAlpha in 0f..1f)
            assertTrue(t.artworkScrimBoost >= 0f)
            assertTrue(t.artworkCrossfadeMs > 0)
            assertTrue(t.contentRevealMs > 0)
        }
    }

    @Test
    fun `each style uses a distinct structural card treatment`() {
        val treatments = WrappedVisualStyle.entries.map { it.toStyleTokens().cardTreatment }
        // The core of the fix: the three styles must not share a card treatment.
        assertEquals(treatments.size, treatments.toSet().size)
        assertEquals(WrappedCardTreatment.GLASS, WrappedVisualStyle.GLASS_FLOW.toStyleTokens().cardTreatment)
        assertEquals(WrappedCardTreatment.EDITORIAL, WrappedVisualStyle.STUDIO_CARDS.toStyleTokens().cardTreatment)
        assertEquals(WrappedCardTreatment.IMMERSIVE, WrappedVisualStyle.NIGHT_PULSE.toStyleTokens().cardTreatment)
    }

    @Test
    fun `glass flow is translucent bordered with soft settle`() {
        val t = WrappedVisualStyle.GLASS_FLOW.toStyleTokens()
        assertEquals(24.dp, t.cornerRadius)
        assertTrue("glass is translucent", t.containerAlpha < 1f)
        assertTrue("glass shows a tonal border", t.borderAlpha > 0f)
        assertTrue(t.showAccentBar)
        assertEquals(WrappedStatEmphasis.STANDARD, t.statEmphasis)
    }

    @Test
    fun `studio cards are opaque editorial with no accent bar or border`() {
        val t = WrappedVisualStyle.STUDIO_CARDS.toStyleTokens()
        assertEquals(1f, t.containerAlpha, 0.0001f)
        assertEquals(0f, t.borderAlpha, 0.0001f)
        assertEquals(false, t.showAccentBar)
        assertEquals(8.dp, t.cornerRadius)
        // Crisp: shortest reveal + fastest artwork crossfade.
        assertTrue(t.contentRevealMs < WrappedVisualStyle.GLASS_FLOW.toStyleTokens().contentRevealMs)
        assertTrue(t.artworkCrossfadeMs < WrappedVisualStyle.GLASS_FLOW.toStyleTokens().artworkCrossfadeMs)
    }

    @Test
    fun `night pulse is the most immersive and emphatic`() {
        val glass = WrappedVisualStyle.GLASS_FLOW.toStyleTokens()
        val night = WrappedVisualStyle.NIGHT_PULSE.toStyleTokens()
        assertEquals(WrappedStatEmphasis.STRONG, night.statEmphasis)
        assertTrue(night.artworkScrimBoost > glass.artworkScrimBoost)
        assertTrue(night.accentBarAlpha >= glass.accentBarAlpha)
        assertTrue("night has the strongest reveal", night.contentRevealRise.value > glass.contentRevealRise.value)
        // Night has the strongest artwork scrim boost of all styles.
        val maxBoost = WrappedVisualStyle.entries.maxOf { it.toStyleTokens().artworkScrimBoost }
        assertEquals(maxBoost, night.artworkScrimBoost, 0.0001f)
    }

    @Test
    fun `no two styles produce an identical token set`() {
        val tokenSets = WrappedVisualStyle.entries.map { it.toStyleTokens() }
        assertEquals(tokenSets.size, tokenSets.toSet().size)
        // Spot-check pairwise inequality of the whole token bundle.
        assertNotEquals(
            WrappedVisualStyle.GLASS_FLOW.toStyleTokens(),
            WrappedVisualStyle.STUDIO_CARDS.toStyleTokens(),
        )
        assertNotEquals(
            WrappedVisualStyle.STUDIO_CARDS.toStyleTokens(),
            WrappedVisualStyle.NIGHT_PULSE.toStyleTokens(),
        )
    }
}
