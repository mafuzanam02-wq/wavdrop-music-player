package com.launchpoint.wavdrop.ui.screen.nowplaying

import com.launchpoint.wavdrop.data.lyrics.SyncedLyricsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingSyncedLyricsTest {

    private val lines = listOf(
        SyncedLyricsLine(10_000L, "one"),
        SyncedLyricsLine(20_000L, "two"),
        SyncedLyricsLine(30_000L, "three"),
    )

    @Test
    fun `before the first timestamp nothing is active`() {
        assertNull(activeSyncedLyricsIndex(lines, 0L))
        assertNull(activeSyncedLyricsIndex(lines, 9_999L))
    }

    @Test
    fun `exact first timestamp activates the first line`() {
        assertEquals(0, activeSyncedLyricsIndex(lines, 10_000L))
    }

    @Test
    fun `between timestamps the most recent line stays active`() {
        assertEquals(0, activeSyncedLyricsIndex(lines, 19_999L))
        assertEquals(1, activeSyncedLyricsIndex(lines, 25_000L))
    }

    @Test
    fun `exact second timestamp activates the second line`() {
        assertEquals(1, activeSyncedLyricsIndex(lines, 20_000L))
    }

    @Test
    fun `after the final timestamp the final line stays active`() {
        assertEquals(2, activeSyncedLyricsIndex(lines, 30_000L))
        assertEquals(2, activeSyncedLyricsIndex(lines, 9_999_999L))
    }

    @Test
    fun `seeking backward and forward follows the position`() {
        assertEquals(2, activeSyncedLyricsIndex(lines, 80_000L)) // playing at 1:20
        assertEquals(1, activeSyncedLyricsIndex(lines, 22_000L)) // seek back
        assertEquals(2, activeSyncedLyricsIndex(lines, 130_000L)) // seek forward
        assertNull(activeSyncedLyricsIndex(lines, 2_000L)) // seek before the first line
    }

    @Test
    fun `equal timestamps resolve to the last line at that time`() {
        val tied = listOf(
            SyncedLyricsLine(5_000L, "a"),
            SyncedLyricsLine(10_000L, "b"),
            SyncedLyricsLine(10_000L, "c"),
            SyncedLyricsLine(15_000L, "d"),
        )
        assertEquals(2, activeSyncedLyricsIndex(tied, 10_000L))
        assertEquals(2, activeSyncedLyricsIndex(tied, 12_000L))
    }

    @Test
    fun `identical text on different lines is resolved by position`() {
        val repeated = listOf(
            SyncedLyricsLine(10_000L, "Chorus"),
            SyncedLyricsLine(20_000L, "Verse"),
            SyncedLyricsLine(30_000L, "Chorus"),
        )
        assertEquals(0, activeSyncedLyricsIndex(repeated, 12_000L))
        assertEquals(2, activeSyncedLyricsIndex(repeated, 31_000L))
    }

    @Test
    fun `empty timed line participates in the timeline`() {
        val withGap = listOf(
            SyncedLyricsLine(10_000L, "sung"),
            SyncedLyricsLine(14_000L, ""),
            SyncedLyricsLine(20_000L, "back"),
        )
        assertEquals(1, activeSyncedLyricsIndex(withGap, 15_000L))
        assertEquals(2, activeSyncedLyricsIndex(withGap, 20_000L))
    }

    @Test
    fun `empty line list never has an active index`() {
        assertNull(activeSyncedLyricsIndex(emptyList(), 5_000L))
    }

    @Test
    fun `scroll offset is the negative half of the free viewport space`() {
        assertEquals(-450, syncedLyricsScrollOffset(viewportHeightPx = 1000, itemHeightPx = 100))
        assertEquals(-400, syncedLyricsScrollOffset(viewportHeightPx = 1000, itemHeightPx = 200))
        assertEquals(-270, syncedLyricsScrollOffset(viewportHeightPx = 600, itemHeightPx = 60))
    }

    @Test
    fun `centering delta is zero when the item is already centered`() {
        // Item coords: viewport -500..500 (center 0); item at -50 with height 100 is centered.
        assertEquals(
            0f,
            syncedLyricsCenteringDelta(itemOffsetPx = -50, itemSizePx = 100, viewportStartOffsetPx = -500, viewportEndOffsetPx = 500),
            0.001f,
        )
    }

    @Test
    fun `centering delta is positive when the item is below center and negative above`() {
        assertEquals(
            300f,
            syncedLyricsCenteringDelta(itemOffsetPx = 250, itemSizePx = 100, viewportStartOffsetPx = -500, viewportEndOffsetPx = 500),
            0.001f,
        )
        assertEquals(
            -250f,
            syncedLyricsCenteringDelta(itemOffsetPx = -300, itemSizePx = 100, viewportStartOffsetPx = -500, viewportEndOffsetPx = 500),
            0.001f,
        )
    }

    @Test
    fun `centering delta accounts for content padding in the viewport offsets`() {
        // 1000px container with 500px top padding: viewport spans -500..500 in item coordinates,
        // so an item whose center is at offset 0 sits at the container middle (y = 500).
        assertEquals(
            0f,
            syncedLyricsCenteringDelta(itemOffsetPx = -30, itemSizePx = 60, viewportStartOffsetPx = -500, viewportEndOffsetPx = 500),
            0.001f,
        )
    }
}
