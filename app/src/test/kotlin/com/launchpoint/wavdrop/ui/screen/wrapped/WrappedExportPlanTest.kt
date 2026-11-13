package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedScope
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P5-WR-VID-02A2: page order/count and plan behaviour. Pure JVM: no Android graphics, no Compose rendering. */
class WrappedExportPlanTest {

    private fun summary(period: WrappedPeriod, plays: Int = 0, listeningMs: Long = 0L, songs: Int = 0) = WrappedSummary(
        period = period,
        totalPlayCount = plays, totalSkipCount = 0, totalListeningTimeMs = listeningMs,
        uniqueSongsPlayedCount = songs, uniqueArtistsPlayedCount = 0, uniqueAlbumsPlayedCount = 0,
        listeningDaysCount = 0, busiestDay = null, busiestDayPlayCount = 0, averagePlaysPerActiveDay = 0.0,
        topSongs = emptyList(), topArtists = emptyList(), topAlbums = emptyList(),
        mostPlayedSong = null, mostPlayedArtist = null, mostPlayedAlbum = null, mostSkippedTrack = null,
        recentlyPlayed = emptyList(),
        emptyState = ListeningAnalyticsEmptyState(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, true, true),
        longestStreak = 0, currentStreak = 0, mostActiveDayOfWeek = null, mostActiveHour = null,
        averageListeningTimePerActiveDayMs = 0L, mostReplayedTrack = null,
    )

    private val settings = WrappedVisualSettings(true, WrappedBackgroundIntensity.MEDIUM, WrappedFallbackTheme.AUTO)
    private val allTime = WrappedPeriod.AllTime
    private val monthly = WrappedPeriod.month(MonthYear(2025, 6), ZoneOffset.UTC)
    private val yearly = WrappedPeriod.year(2025, ZoneOffset.UTC)

    private fun plan(
        s: WrappedSummary,
        milestonePref: Boolean = true,
        id: String = "test-export",
        style: WrappedVisualStyle = WrappedVisualStyle.DEFAULT,
    ) = buildWrappedExportPlan(s, settings, style, milestonePref, exportId = id, artworkUriFor = { null })

    private val allTimeOrder = listOf(
        WrappedPageId.INTRO, WrappedPageId.OVERVIEW, WrappedPageId.TOP_TRACKS, WrappedPageId.TOP_ARTISTS,
        WrappedPageId.TOP_ALBUMS, WrappedPageId.SKIP_HABITS, WrappedPageId.RECENT_PLAYS,
    )
    private val periodOrder = listOf(
        WrappedPageId.INTRO, WrappedPageId.OVERVIEW, WrappedPageId.STREAKS, WrappedPageId.PATTERNS,
        WrappedPageId.TOP_TRACKS, WrappedPageId.TOP_ARTISTS, WrappedPageId.TOP_ALBUMS,
        WrappedPageId.SKIP_HABITS, WrappedPageId.RECENT_PLAYS,
    )

    @Test fun `all time has seven pages in live order without per-day pages`() {
        val p = plan(summary(allTime, plays = 500))
        assertEquals(allTimeOrder, p.pages.map { it.id })
        assertEquals(7, p.pageCount)
        assertFalse(p.includesMilestonePage)
        assertTrue(p.milestones.isEmpty())
    }

    @Test fun `monthly has nine pages and never a milestone page`() {
        val p = plan(summary(monthly, plays = 5_000, listeningMs = 600L * 3_600_000L, songs = 500))
        assertEquals(periodOrder, p.pages.map { it.id })
        assertFalse(p.includesMilestonePage)
    }

    @Test fun `yearly without any milestone has nine pages`() {
        val p = plan(summary(yearly))
        assertEquals(periodOrder, p.pages.map { it.id })
        assertFalse(p.includesMilestonePage)
    }

    @Test fun `yearly with milestones and preference on appends the milestone page last`() {
        val p = plan(summary(yearly, plays = 1_234), milestonePref = true)
        assertEquals(periodOrder + WrappedPageId.MILESTONES, p.pages.map { it.id })
        assertEquals(10, p.pageCount)
        assertTrue(p.includesMilestonePage)
        assertTrue(p.milestones.isNotEmpty())
    }

    @Test fun `yearly with milestones but preference off has nine pages`() {
        val p = plan(summary(yearly, plays = 1_234), milestonePref = false)
        assertEquals(9, p.pageCount)
        assertFalse(p.includesMilestonePage)
    }

    @Test fun `page count always equals the live wrappedPageCount`() {
        for (period in listOf(allTime, monthly, yearly)) for (plays in listOf(0, 150)) for (pref in listOf(true, false)) {
            val s = summary(period, plays = plays)
            val hasMilestones = period.scope == WrappedScope.YEARLY && computeMilestones(s).isNotEmpty()
            assertEquals(wrappedPageCount(period.scope, pref, hasMilestones), plan(s, pref).pageCount)
        }
    }

    @Test fun `empty and low data reports still plan every page`() {
        assertEquals(9, plan(summary(yearly, plays = 0)).pageCount)
        assertEquals(7, plan(summary(allTime, plays = 1)).pageCount)
    }

    @Test fun `slides have sequential unique file names and the provisional duration`() {
        val p = plan(summary(yearly, plays = 1_234))
        assertEquals(p.pages.indices.toList(), p.pages.map { it.index })
        assertEquals(p.pageCount, p.pages.map { it.fileName }.toSet().size)
        assertEquals("slide_01_intro.png", p.pages.first().fileName)
        assertEquals("slide_10_milestones.png", p.pages.last().fileName)
        assertEquals(p.pages.map { it.fileName }, p.pages.map { it.fileName }.sorted())
        assertTrue(p.pages.all { it.durationMs == WRAPPED_EXPORT_SLIDE_DURATION_MS })
        assertEquals(3_000 * 10, p.totalDurationMs)
    }

    @Test fun `export duration is independent of live story durations`() {
        assertNotEquals(
            wrappedSlideDurationMs(0, WrappedScope.ALL_TIME, false),
            plan(summary(allTime)).pages.first().durationMs,
        )
    }

    @Test fun `plan captures report identity scope period and style at build time`() {
        val p = plan(summary(monthly), style = WrappedVisualStyle.STUDIO_CARDS)
        assertEquals("MONTHLY:2025-06", p.reportKey)
        assertEquals(WrappedScope.MONTHLY, p.scope)
        assertEquals(monthly.displayLabel, p.periodLabel)
        assertEquals(WrappedVisualStyle.STUDIO_CARDS, p.visualStyle)
        assertEquals(settings, p.visualSettings)
        // Changing a copy of the live settings afterwards leaves the plan untouched (value semantics).
        val changed = settings.copy(useArtworkBackgrounds = false)
        assertTrue(p.visualSettings.useArtworkBackgrounds)
        assertFalse(changed.useArtworkBackgrounds)
    }

    @Test fun `intro artwork source follows the artwork background setting`() {
        val on = buildWrappedExportPlan(
            summary(yearly), settings, WrappedVisualStyle.DEFAULT, true, "a", artworkUriFor = { "content://art/1" },
        )
        val off = buildWrappedExportPlan(
            summary(yearly), settings.copy(useArtworkBackgrounds = false), WrappedVisualStyle.DEFAULT, true, "b",
            artworkUriFor = { "content://art/1" },
        )
        assertEquals("content://art/1", on.introArtworkUri)
        assertNull(off.introArtworkUri)
    }

    @Test fun `default export ids are unique and unsafe ids are rejected`() {
        val ids = (1..50).map {
            buildWrappedExportPlan(summary(allTime), settings, WrappedVisualStyle.DEFAULT, true).exportId
        }
        assertEquals(50, ids.toSet().size)
        for (bad in listOf("", "../x", "a/b", "a b", "a\\b", "x".repeat(65))) {
            try {
                plan(summary(allTime), id = bad)
                fail("accepted unsafe id '$bad'")
            } catch (expected: IllegalArgumentException) {
                // rejected, as required
            }
        }
    }

    @Test fun `live page order and plan order share one source`() {
        assertEquals(allTimeOrder, wrappedPageIds(WrappedScope.ALL_TIME, showMilestonePage = true))
        assertEquals(periodOrder, wrappedPageIds(WrappedScope.MONTHLY, showMilestonePage = false))
        assertEquals(periodOrder + WrappedPageId.MILESTONES, wrappedPageIds(WrappedScope.YEARLY, true))
    }
}
