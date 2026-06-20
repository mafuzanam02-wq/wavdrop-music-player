package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedScope
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedSelectionResolverTest {

    private val years = listOf(2026, 2025)
    private val months = listOf(MonthYear(2026, 6), MonthYear(2026, 5))

    @Test
    fun `default selection resolves yearly and latest year`() {
        val resolved = resolveWrappedSelection(
            request = WrappedSelectionRequest(WrappedScope.YEARLY, null, null),
            availableYears = years,
            availableMonths = months,
            zone = ZoneOffset.UTC,
        )

        assertEquals(WrappedScope.YEARLY, resolved?.scope)
        assertEquals(2026, resolved?.year)
        assertEquals(2026, (resolved?.period as WrappedPeriod.Yearly).year)
    }

    @Test
    fun `monthly selection defaults to latest month`() {
        val resolved = resolveWrappedSelection(
            request = WrappedSelectionRequest(WrappedScope.MONTHLY, null, null),
            availableYears = years,
            availableMonths = months,
            zone = ZoneOffset.UTC,
        )

        assertEquals(MonthYear(2026, 6), resolved?.month)
        assertEquals(
            MonthYear(2026, 6),
            (resolved?.period as WrappedPeriod.Monthly).month,
        )
    }

    @Test
    fun `scope selections retain independent requested year and month`() {
        val yearly = resolveWrappedSelection(
            request = WrappedSelectionRequest(
                scope = WrappedScope.YEARLY,
                year = 2025,
                month = MonthYear(2026, 5),
            ),
            availableYears = years,
            availableMonths = months,
            zone = ZoneOffset.UTC,
        )
        val monthly = resolveWrappedSelection(
            request = WrappedSelectionRequest(
                scope = WrappedScope.MONTHLY,
                year = yearly?.year,
                month = yearly?.month,
            ),
            availableYears = years,
            availableMonths = months,
            zone = ZoneOffset.UTC,
        )

        assertEquals(2025, yearly?.year)
        assertEquals(MonthYear(2026, 5), monthly?.month)
    }

    @Test
    fun `invalid selections fall back to latest valid periods`() {
        val resolved = resolveWrappedSelection(
            request = WrappedSelectionRequest(
                scope = WrappedScope.MONTHLY,
                year = 1999,
                month = MonthYear(1999, 1),
            ),
            availableYears = years,
            availableMonths = months,
            zone = ZoneOffset.UTC,
        )

        assertEquals(2026, resolved?.year)
        assertEquals(MonthYear(2026, 6), resolved?.month)
    }

    @Test
    fun `no event-backed periods resolves empty`() {
        assertNull(
            resolveWrappedSelection(
                request = WrappedSelectionRequest(WrappedScope.YEARLY, null, null),
                availableYears = emptyList(),
                availableMonths = emptyList(),
                zone = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun `milestone page is yearly only`() {
        assertTrue(
            shouldShowWrappedMilestonePage(
                scope = WrappedScope.YEARLY,
                preferenceEnabled = true,
                hasMilestones = true,
            ),
        )
        assertFalse(
            shouldShowWrappedMilestonePage(
                scope = WrappedScope.MONTHLY,
                preferenceEnabled = true,
                hasMilestones = true,
            ),
        )
    }

    @Test
    fun `monthly period copy does not use yearly phrasing`() {
        val copy = WrappedPeriod.month(
            month = MonthYear(2026, 6),
            zone = ZoneOffset.UTC,
        ).toWrappedPeriodCopy()

        assertEquals("June 2026 in Review", copy.inReviewTitle)
        assertEquals("this month", copy.thisPeriod)
        assertEquals("during this month", copy.duringThisPeriod)
        assertFalse(copy.thisPeriod.contains("year"))
    }
}
