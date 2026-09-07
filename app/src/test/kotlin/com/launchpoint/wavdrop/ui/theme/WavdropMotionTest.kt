package com.launchpoint.wavdrop.ui.theme

import com.launchpoint.wavdrop.ui.components.motion.DEFAULT_PRESSED_SCALE
import com.launchpoint.wavdrop.ui.components.motion.DEFAULT_TRANSIENT_PEAK_ALPHA
import com.launchpoint.wavdrop.ui.components.motion.MIN_PRESSED_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure guards for the WU-02 motion foundation: reduced-motion policy and the numeric ranges the
 * brief constrains (subtle press compression, quiet transient peak, ordered durations).
 */
class WavdropMotionTest {

    // ── Reduced-motion policy ────────────────────────────────────────────────
    @Test
    fun `reduced motion true only below threshold`() {
        assertTrue(WavdropMotion.isReducedMotion(0.0f))
        assertTrue(WavdropMotion.isReducedMotion(0.05f))
        assertFalse(WavdropMotion.isReducedMotion(WavdropMotion.REDUCED_MOTION_SCALE_THRESHOLD))
        assertFalse(WavdropMotion.isReducedMotion(0.1f))
        assertFalse(WavdropMotion.isReducedMotion(1.0f))
    }

    @Test
    fun `threshold matches the established project convention`() {
        assertEquals(0.1f, WavdropMotion.REDUCED_MOTION_SCALE_THRESHOLD)
    }

    // ── Duration tokens ──────────────────────────────────────────────────────
    @Test
    fun `durations are ordered from fastest to most emphasized`() {
        val d = WavdropMotion.Durations
        assertTrue(d.PressResponse < d.FastStateChange)
        assertTrue(d.FastStateChange < d.StandardStateChange)
        assertTrue(d.StandardStateChange < d.EmphasizedTransition)
        assertTrue(d.EmphasizedTransition <= d.SheetTransition)
    }

    @Test
    fun `durations sit in restrained bands`() {
        val d = WavdropMotion.Durations
        assertTrue(d.PressResponse in 80..100)
        assertTrue(d.FastStateChange in 110..130)
        assertTrue(d.StandardStateChange in 160..190)
        assertTrue(d.EmphasizedTransition in 220..260)
    }

    // ── Interaction primitive ranges ─────────────────────────────────────────
    @Test
    fun `press compression is subtle`() {
        assertTrue(DEFAULT_PRESSED_SCALE in MIN_PRESSED_SCALE..1.0f)
        assertTrue(MIN_PRESSED_SCALE >= 0.985f)
    }

    @Test
    fun `transient peak alpha is quiet`() {
        assertTrue(DEFAULT_TRANSIENT_PEAK_ALPHA in 0.08f..0.14f)
    }
}
