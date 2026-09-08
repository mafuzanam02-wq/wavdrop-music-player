package com.launchpoint.wavdrop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkRenderDecisionTest {

    // ── Retention enabled (large Now Playing artwork) ───────────────────────────

    @Test
    fun `loading with a prior success retains the previous artwork`() {
        assertEquals(
            ArtworkRenderDecision.RETAIN_PREVIOUS,
            resolveArtworkRenderDecision(
                phase = ArtworkLoadPhase.LOADING,
                retainPreviousOnLoad = true,
                hasPreviousSuccess = true,
            ),
        )
    }

    @Test
    fun `loading with no prior success falls back to placeholder`() {
        assertEquals(
            ArtworkRenderDecision.PLACEHOLDER,
            resolveArtworkRenderDecision(
                phase = ArtworkLoadPhase.LOADING,
                retainPreviousOnLoad = true,
                hasPreviousSuccess = false,
            ),
        )
    }

    @Test
    fun `success always shows the new image even with retention`() {
        assertEquals(
            ArtworkRenderDecision.NEW_IMAGE,
            resolveArtworkRenderDecision(
                phase = ArtworkLoadPhase.SUCCESS,
                retainPreviousOnLoad = true,
                hasPreviousSuccess = true,
            ),
        )
    }

    @Test
    fun `definitive empty or error resolves truthfully to placeholder even with a prior success`() {
        assertEquals(
            ArtworkRenderDecision.PLACEHOLDER,
            resolveArtworkRenderDecision(
                phase = ArtworkLoadPhase.RESOLVED_EMPTY,
                retainPreviousOnLoad = true,
                hasPreviousSuccess = true,
            ),
        )
    }

    // ── Retention disabled (default — every list/grid/header call site) ──────────

    @Test
    fun `retention disabled preserves existing behavior`() {
        // Success → image; anything else → placeholder, regardless of prior success.
        assertEquals(
            ArtworkRenderDecision.NEW_IMAGE,
            resolveArtworkRenderDecision(ArtworkLoadPhase.SUCCESS, retainPreviousOnLoad = false, hasPreviousSuccess = true),
        )
        assertEquals(
            ArtworkRenderDecision.PLACEHOLDER,
            resolveArtworkRenderDecision(ArtworkLoadPhase.LOADING, retainPreviousOnLoad = false, hasPreviousSuccess = true),
        )
        assertEquals(
            ArtworkRenderDecision.PLACEHOLDER,
            resolveArtworkRenderDecision(ArtworkLoadPhase.RESOLVED_EMPTY, retainPreviousOnLoad = false, hasPreviousSuccess = true),
        )
    }
}
