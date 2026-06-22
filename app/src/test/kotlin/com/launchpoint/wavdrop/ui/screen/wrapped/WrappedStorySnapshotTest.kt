package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrappedStorySnapshotTest {

    @Test
    fun `same report restores snapshot`() {
        val store = WrappedStorySnapshotStore()
        store.save("YEARLY:2026", page = 4, isPlaying = false, progress = 0.45f)

        assertEquals(
            WrappedStorySnapshot("YEARLY:2026", 4, false, 0.45f),
            store.get("YEARLY:2026", pageCount = 9),
        )
    }

    @Test
    fun `different report does not restore`() {
        val store = WrappedStorySnapshotStore()
        store.save("YEARLY:2026", page = 4, isPlaying = false, progress = 0.45f)

        assertNull(store.get("YEARLY:2025", pageCount = 9))
    }

    @Test
    fun `page and progress are clamped`() {
        val store = WrappedStorySnapshotStore()
        store.save("ALL_TIME", page = 99, isPlaying = true, progress = 2f)

        assertEquals(
            WrappedStorySnapshot("ALL_TIME", 6, true, 1f),
            store.get("ALL_TIME", pageCount = 7),
        )

        store.save("ALL_TIME", page = -3, isPlaying = false, progress = -1f)
        assertEquals(
            WrappedStorySnapshot("ALL_TIME", 0, false, 0f),
            store.get("ALL_TIME", pageCount = 7),
        )
    }

    @Test
    fun `report keys are stable and distinct`() {
        val allTime = WrappedPeriod.AllTime.toWrappedReportKey()
        val yearly = WrappedPeriod.year(2026, ZoneOffset.UTC).toWrappedReportKey()
        val monthly = WrappedPeriod.month(MonthYear(2026, 6), ZoneOffset.UTC).toWrappedReportKey()

        assertEquals("ALL_TIME", allTime)
        assertEquals("YEARLY:2026", yearly)
        assertEquals("MONTHLY:2026-06", monthly)
        assertNotEquals(allTime, yearly)
        assertNotEquals(yearly, monthly)
    }
}
