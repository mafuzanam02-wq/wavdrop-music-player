package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedScope
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrappedSlideRulesTest {

    private val monthlyCopy = WrappedPeriod.month(
        month = MonthYear(2026, 6),
        zone = ZoneOffset.UTC,
    ).toWrappedPeriodCopy()

    private val yearlyCopy = WrappedPeriod.year(
        year = 2026,
        zone = ZoneOffset.UTC,
    ).toWrappedPeriodCopy()

    @Test
    fun `top list keeps ranking order and limits to three`() {
        assertEquals(listOf(1, 2, 3), wrappedTopThree(listOf(1, 2, 3, 4, 5)))
        assertEquals(listOf(1, 2), wrappedTopThree(listOf(1, 2)))
        assertEquals(emptyList<Int>(), wrappedTopThree(emptyList<Int>()))
    }

    @Test
    fun `ranked list notes cover empty one two and three plus items`() {
        assertEquals(
            "No played tracks ranked this month yet.",
            wrappedRankedListNote(WrappedRankedKind.TRACK, 0, monthlyCopy),
        )
        assertEquals(
            "Only one artist ranked this month.",
            wrappedRankedListNote(WrappedRankedKind.ARTIST, 1, monthlyCopy),
        )
        assertEquals(
            "Only two albums ranked this month.",
            wrappedRankedListNote(WrappedRankedKind.ALBUM, 2, monthlyCopy),
        )
        assertNull(wrappedRankedListNote(WrappedRankedKind.TRACK, 3, monthlyCopy))
        assertNull(wrappedRankedListNote(WrappedRankedKind.TRACK, 10, monthlyCopy))
    }

    @Test
    fun `ranked list notes use yearly copy when yearly is selected`() {
        assertEquals(
            "Only one track ranked this year.",
            wrappedRankedListNote(WrappedRankedKind.TRACK, 1, yearlyCopy),
        )
    }

    @Test
    fun `skip rate handles zero plays only skips only and mixed activity`() {
        assertEquals(0, wrappedSkipRatePercent(totalPlays = 0, totalSkips = 0))
        assertEquals(0, wrappedSkipRatePercent(totalPlays = 10, totalSkips = 0))
        assertEquals(100, wrappedSkipRatePercent(totalPlays = 0, totalSkips = 4))
        assertEquals(25, wrappedSkipRatePercent(totalPlays = 3, totalSkips = 1))
        assertEquals(33, wrappedSkipRatePercent(totalPlays = 2, totalSkips = 1))
    }

    @Test
    fun `period selector helper text shown for zero or one item and hidden for multiple`() {
        assertEquals(
            "More months will appear here as you keep listening in Wavdrop.",
            wrappedPeriodSelectorHelperText(0, "months"),
        )
        assertEquals(
            "More months will appear here as you keep listening in Wavdrop.",
            wrappedPeriodSelectorHelperText(1, "months"),
        )
        assertNull(wrappedPeriodSelectorHelperText(2, "months"))
        assertNull(wrappedPeriodSelectorHelperText(5, "months"))

        assertEquals(
            "More years will appear here as you keep listening in Wavdrop.",
            wrappedPeriodSelectorHelperText(1, "years"),
        )
        assertNull(wrappedPeriodSelectorHelperText(2, "years"))
    }

    @Test
    fun `page count remains nine monthly and nine or ten yearly`() {
        assertEquals(
            9,
            wrappedPageCount(
                scope = WrappedScope.MONTHLY,
                milestonePreferenceEnabled = true,
                hasMilestones = true,
            ),
        )
        assertEquals(
            9,
            wrappedPageCount(
                scope = WrappedScope.YEARLY,
                milestonePreferenceEnabled = false,
                hasMilestones = true,
            ),
        )
        assertEquals(
            9,
            wrappedPageCount(
                scope = WrappedScope.YEARLY,
                milestonePreferenceEnabled = true,
                hasMilestones = false,
            ),
        )
        assertEquals(
            10,
            wrappedPageCount(
                scope = WrappedScope.YEARLY,
                milestonePreferenceEnabled = true,
                hasMilestones = true,
            ),
        )
    }

    @Test
    fun `all time uses seven aggregate-safe pages regardless of milestone settings`() {
        assertEquals(
            7,
            wrappedPageCount(
                scope = WrappedScope.ALL_TIME,
                milestonePreferenceEnabled = true,
                hasMilestones = true,
            ),
        )
    }

    @Test
    fun `all time copy and disclosure describe aggregate history`() {
        val copy = WrappedPeriod.AllTime.toWrappedPeriodCopy()

        assertEquals("All Time", copy.displayLabel)
        assertEquals("in your history", copy.thisPeriod)
        assertEquals("Your Story", copy.introSubtitle)
        assertEquals(
            "All Time is based on your aggregate listening totals on this device, including restored or imported stats.",
            wrappedDataSourceDisclosure(WrappedScope.ALL_TIME),
        )
        assertEquals(
            "Wrapped is based on listening activity recorded by Wavdrop on this device. Imported totals may appear in Statistics, but they are not included here.",
            wrappedDataSourceDisclosure(WrappedScope.MONTHLY),
        )
    }

    // ── Story mode slide durations ─────────────────────────────────────────────

    @Test
    fun `intro page always returns 4000ms across all scopes`() {
        assertEquals(4_000, wrappedSlideDurationMs(0, WrappedScope.ALL_TIME, false))
        assertEquals(4_000, wrappedSlideDurationMs(0, WrappedScope.YEARLY, false))
        assertEquals(4_000, wrappedSlideDurationMs(0, WrappedScope.MONTHLY, false))
    }

    @Test
    fun `ranked list pages return 7000ms for all time scope`() {
        // All Time: page 2 = Top Tracks, 3 = Top Artists, 4 = Top Albums
        assertEquals(7_000, wrappedSlideDurationMs(2, WrappedScope.ALL_TIME, false))
        assertEquals(7_000, wrappedSlideDurationMs(3, WrappedScope.ALL_TIME, false))
        assertEquals(7_000, wrappedSlideDurationMs(4, WrappedScope.ALL_TIME, false))
    }

    @Test
    fun `ranked list pages return 7000ms for yearly and monthly scopes`() {
        // Yearly / Monthly: page 4 = Top Tracks, 5 = Top Artists, 6 = Top Albums
        assertEquals(7_000, wrappedSlideDurationMs(4, WrappedScope.YEARLY, false))
        assertEquals(7_000, wrappedSlideDurationMs(5, WrappedScope.YEARLY, false))
        assertEquals(7_000, wrappedSlideDurationMs(6, WrappedScope.YEARLY, false))
        assertEquals(7_000, wrappedSlideDurationMs(4, WrappedScope.MONTHLY, false))
        assertEquals(7_000, wrappedSlideDurationMs(5, WrappedScope.MONTHLY, false))
        assertEquals(7_000, wrappedSlideDurationMs(6, WrappedScope.MONTHLY, false))
    }

    @Test
    fun `standard pages return 5000ms for all time scope`() {
        // All Time: Overview (1), Skip Habits (5), Recent Plays (6)
        assertEquals(5_000, wrappedSlideDurationMs(1, WrappedScope.ALL_TIME, false))
        assertEquals(5_000, wrappedSlideDurationMs(5, WrappedScope.ALL_TIME, false))
        assertEquals(5_000, wrappedSlideDurationMs(6, WrappedScope.ALL_TIME, false))
    }

    @Test
    fun `standard pages return 5000ms for yearly and monthly scopes`() {
        // Yearly / Monthly: Overview (1), Streaks (2), Patterns (3), Skip Habits (7), Recent Plays (8)
        assertEquals(5_000, wrappedSlideDurationMs(1, WrappedScope.YEARLY, false))
        assertEquals(5_000, wrappedSlideDurationMs(2, WrappedScope.YEARLY, false))
        assertEquals(5_000, wrappedSlideDurationMs(3, WrappedScope.YEARLY, false))
        assertEquals(5_000, wrappedSlideDurationMs(7, WrappedScope.YEARLY, false))
        assertEquals(5_000, wrappedSlideDurationMs(8, WrappedScope.YEARLY, false))
        assertEquals(5_000, wrappedSlideDurationMs(1, WrappedScope.MONTHLY, false))
        assertEquals(5_000, wrappedSlideDurationMs(7, WrappedScope.MONTHLY, false))
        assertEquals(5_000, wrappedSlideDurationMs(8, WrappedScope.MONTHLY, false))
    }

    @Test
    fun `milestone and out-of-range pages fall back to 5000ms`() {
        // Page 9 = Milestone page (yearly with milestones) or Recent Plays fallback
        assertEquals(5_000, wrappedSlideDurationMs(9, WrappedScope.YEARLY, true))
        assertEquals(5_000, wrappedSlideDurationMs(9, WrappedScope.YEARLY, false))
        assertEquals(5_000, wrappedSlideDurationMs(9, WrappedScope.MONTHLY, false))
    }
}
