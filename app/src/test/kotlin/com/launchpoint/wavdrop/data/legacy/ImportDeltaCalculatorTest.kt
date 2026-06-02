package com.launchpoint.wavdrop.data.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDeltaCalculatorTest {

    @Test
    fun `no baseline imports full incoming counts`() {
        val delta = calculate(previousPlay = 0, previousSkip = 0, incomingPlay = 10, incomingSkip = 3)

        assertEquals(10, delta.playDelta)
        assertEquals(3, delta.skipDelta)
        assertTrue(delta.hasNewStats)
    }

    @Test
    fun `same file again imports zero counts`() {
        val delta = calculate(previousPlay = 10, previousSkip = 3, incomingPlay = 10, incomingSkip = 3)

        assertEquals(0, delta.playDelta)
        assertEquals(0, delta.skipDelta)
        assertFalse(delta.hasNewStats)
    }

    @Test
    fun `higher incoming play count imports only difference`() {
        val delta = calculate(previousPlay = 510, previousSkip = 2, incomingPlay = 530, incomingSkip = 2)

        assertEquals(20, delta.playDelta)
        assertEquals(0, delta.skipDelta)
        assertTrue(delta.hasNewStats)
    }

    @Test
    fun `lower incoming play count imports zero plays`() {
        val delta = calculate(previousPlay = 510, previousSkip = 2, incomingPlay = 500, incomingSkip = 2)

        assertEquals(0, delta.playDelta)
        assertEquals(0, delta.skipDelta)
        assertFalse(delta.hasNewStats)
    }

    @Test
    fun `higher incoming skip count imports only difference`() {
        val delta = calculate(previousPlay = 4, previousSkip = 8, incomingPlay = 4, incomingSkip = 11)

        assertEquals(0, delta.playDelta)
        assertEquals(3, delta.skipDelta)
        assertTrue(delta.hasNewStats)
    }

    @Test
    fun `play higher but skip same imports only play delta`() {
        val delta = calculate(previousPlay = 7, previousSkip = 5, incomingPlay = 9, incomingSkip = 5)

        assertEquals(2, delta.playDelta)
        assertEquals(0, delta.skipDelta)
        assertTrue(delta.hasNewStats)
    }

    @Test
    fun `baseline updates to incoming counts after import`() {
        val delta = calculate(previousPlay = 510, previousSkip = 7, incomingPlay = 500, incomingSkip = 9)

        assertEquals(0, delta.playDelta)
        assertEquals(2, delta.skipDelta)
        assertEquals(500, delta.nextBaselinePlayCount)
        assertEquals(9, delta.nextBaselineSkipCount)
    }

    private fun calculate(
        previousPlay: Int,
        previousSkip: Int,
        incomingPlay: Int,
        incomingSkip: Int,
    ): ImportDelta = ImportDeltaCalculator.calculate(
        previousPlayCount = previousPlay,
        previousSkipCount = previousSkip,
        incomingPlayCount = incomingPlay,
        incomingSkipCount = incomingSkip,
    )
}
