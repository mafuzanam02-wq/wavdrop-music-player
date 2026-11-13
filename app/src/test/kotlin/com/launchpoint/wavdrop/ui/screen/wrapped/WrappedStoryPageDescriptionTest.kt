package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.model.WrappedScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P5-WR-UX-01B1: label formatting only. This does NOT prove the Compose semantics tree exposes the label on the
 * page indicator (the project has no Compose UI test dependency for JVM tests); that is device QA.
 */
class WrappedStoryPageDescriptionTest {

    @Test fun firstAndLastPagesAreOneBased() {
        assertEquals("Story page 1 of 9", wrappedStoryPageDescription(0, 9))
        assertEquals("Story page 9 of 9", wrappedStoryPageDescription(8, 9))
    }

    @Test fun middlePageUsesActualCount() {
        assertEquals("Story page 3 of 9", wrappedStoryPageDescription(2, 9))
        assertEquals("Story page 2 of 7", wrappedStoryPageDescription(1, 7))
    }

    @Test fun invalidOrTransitionalPositionsProduceNoDescription() {
        assertNull(wrappedStoryPageDescription(-1, 9))
        assertNull(wrappedStoryPageDescription(9, 9))
        assertNull(wrappedStoryPageDescription(12, 9))
        assertNull(wrappedStoryPageDescription(0, 0))
        assertNull(wrappedStoryPageDescription(0, -3))
    }

    @Test fun descriptionTracksRealPageCountsPerScopeAndMilestones() {
        val allTime = wrappedPageCount(WrappedScope.ALL_TIME, milestonePreferenceEnabled = true, hasMilestones = true)
        val monthly = wrappedPageCount(WrappedScope.MONTHLY, milestonePreferenceEnabled = true, hasMilestones = true)
        val yearlyNoMilestones = wrappedPageCount(WrappedScope.YEARLY, milestonePreferenceEnabled = false, hasMilestones = true)
        val yearlyMilestones = wrappedPageCount(WrappedScope.YEARLY, milestonePreferenceEnabled = true, hasMilestones = true)

        assertEquals("Story page ${allTime} of $allTime", wrappedStoryPageDescription(allTime - 1, allTime))
        assertEquals("Story page 1 of $monthly", wrappedStoryPageDescription(0, monthly))
        assertEquals("Story page 1 of $yearlyNoMilestones", wrappedStoryPageDescription(0, yearlyNoMilestones))
        assertEquals("Story page $yearlyMilestones of $yearlyMilestones", wrappedStoryPageDescription(yearlyMilestones - 1, yearlyMilestones))
        // Only yearly gains a page from milestones, and the description follows it.
        assertEquals(yearlyNoMilestones + 1, yearlyMilestones)
        assertEquals(monthly, yearlyNoMilestones)
    }
}
