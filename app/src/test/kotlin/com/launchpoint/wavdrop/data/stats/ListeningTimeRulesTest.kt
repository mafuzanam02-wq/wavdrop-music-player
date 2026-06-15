package com.launchpoint.wavdrop.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTimeRulesTest {

    @Test
    fun `actual listening time wins over estimate`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = 10,
            durationMs = 180_000L,
            totalListeningTimeMs = 45_000L,
        )

        assertEquals(45_000L, result)
    }

    @Test
    fun `estimate is used when actual listening time is zero`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = 3,
            durationMs = 200_000L,
            totalListeningTimeMs = 0L,
        )

        assertEquals(600_000L, result)
    }

    @Test
    fun `zero play count gives zero`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = 0,
            durationMs = 200_000L,
            totalListeningTimeMs = 0L,
        )

        assertEquals(0L, result)
    }

    @Test
    fun `zero duration gives zero`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = 5,
            durationMs = 0L,
            totalListeningTimeMs = 0L,
        )

        assertEquals(0L, result)
    }

    @Test
    fun `estimated listening time saturates instead of overflowing`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = Int.MAX_VALUE,
            durationMs = Long.MAX_VALUE,
            totalListeningTimeMs = 0L,
        )

        assertEquals(Long.MAX_VALUE, result)
    }
}
