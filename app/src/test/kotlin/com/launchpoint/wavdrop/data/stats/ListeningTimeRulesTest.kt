package com.launchpoint.wavdrop.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningTimeRulesTest {

    @Test
    fun `estimate is used when estimate is greater than actual listening time`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = 323,
            durationMs = 196_000L,
            totalListeningTimeMs = 660_000L,
        )

        assertEquals(63_308_000L, result)
    }

    @Test
    fun `actual listening time is used when actual is greater than estimate`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = 1,
            durationMs = 180_000L,
            totalListeningTimeMs = 450_000L,
        )

        assertEquals(450_000L, result)
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
    fun `zero play count still allows actual listening time to show`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = 0,
            durationMs = 200_000L,
            totalListeningTimeMs = 90_000L,
        )

        assertEquals(90_000L, result)
    }

    @Test
    fun `zero duration still allows actual listening time to show`() {
        val result = ListeningTimeRules.effectiveListeningTimeMs(
            playCount = 5,
            durationMs = 0L,
            totalListeningTimeMs = 75_000L,
        )

        assertEquals(75_000L, result)
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
