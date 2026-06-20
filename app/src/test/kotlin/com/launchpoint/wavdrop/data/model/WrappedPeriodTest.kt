package com.launchpoint.wavdrop.data.model

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class WrappedPeriodTest {

    @Test
    fun `monthly period exposes display share and accessibility labels`() {
        val period = WrappedPeriod.month(MonthYear(2026, 6), ZoneOffset.UTC)

        assertEquals(WrappedScope.MONTHLY, period.scope)
        assertEquals("Jun 2026", period.shortLabel)
        assertEquals("June 2026", period.displayLabel)
        assertEquals("2026-06", period.shareFilenameLabel)
        assertEquals("Monthly Wrapped for June 2026", period.accessibilityLabel)
    }

    @Test
    fun `monthly filename zero pads single digit month`() {
        val period = WrappedPeriod.month(MonthYear(2026, 2), ZoneOffset.UTC)

        assertEquals("2026-02", period.shareFilenameLabel)
    }

    @Test
    fun `yearly period exposes year labels`() {
        val period = WrappedPeriod.year(2026, ZoneOffset.UTC)

        assertEquals(WrappedScope.YEARLY, period.scope)
        assertEquals("2026", period.shortLabel)
        assertEquals("2026", period.displayLabel)
        assertEquals("2026", period.shareFilenameLabel)
        assertEquals("Yearly Wrapped for 2026", period.accessibilityLabel)
    }

    @Test
    fun `monthly and yearly factories create expected inclusive ranges`() {
        val monthly = WrappedPeriod.month(MonthYear(2026, 6), ZoneOffset.UTC)
        val yearly = WrappedPeriod.year(2026, ZoneOffset.UTC)

        assertEquals(
            ListeningPeriodRange.month(2026, 6, ZoneOffset.UTC),
            monthly.range,
        )
        assertEquals(
            ListeningPeriodRange.year(2026, ZoneOffset.UTC),
            yearly.range,
        )
    }
}
