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
}
