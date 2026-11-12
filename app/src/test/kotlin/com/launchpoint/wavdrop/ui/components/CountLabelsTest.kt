package com.launchpoint.wavdrop.ui.components

import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** P5-UX-03A. Pure formatter tests: exactly 1 is singular; 0 and every other count is plural. */
class CountLabelsTest {

    @Test fun `zero uses the plural for every supported noun`() {
        assertEquals("0 songs", CountLabels.songs(0))
        assertEquals("0 albums", CountLabels.albums(0))
        assertEquals("0 artists", CountLabels.artists(0))
        assertEquals("0 plays", CountLabels.plays(0))
        assertEquals("0 skips", CountLabels.skips(0))
    }

    @Test fun `one uses the singular for every supported noun`() {
        assertEquals("1 song", CountLabels.songs(1))
        assertEquals("1 album", CountLabels.albums(1))
        assertEquals("1 artist", CountLabels.artists(1))
        assertEquals("1 play", CountLabels.plays(1))
        assertEquals("1 skip", CountLabels.skips(1))
    }

    @Test fun `two and representative larger counts use the plural`() {
        assertEquals("2 songs", CountLabels.songs(2))
        assertEquals("2 plays", CountLabels.plays(2))
        assertEquals("2 albums", CountLabels.albums(2))
        assertEquals("2 artists", CountLabels.artists(2))
        assertEquals("2 skips", CountLabels.skips(2))
        assertEquals("11 songs", CountLabels.songs(11))
        assertEquals("101 plays", CountLabels.plays(101))
        assertEquals("12345 songs", CountLabels.songs(12345))
        assertEquals(
            "${Int.MAX_VALUE} songs", CountLabels.songs(Int.MAX_VALUE),
        )
    }

    @Test fun `Insights Never Played and Smart Collections counts read correctly`() {
        // These screens now call CountLabels.songs(count) directly.
        assertEquals("1 song", CountLabels.songs(1))
        assertEquals("0 songs", CountLabels.songs(0))
        assertEquals("57 songs", CountLabels.songs(57))
    }

    @Test fun `Library keeps its grouped-number formatting`() {
        val nf = NumberFormat.getIntegerInstance(Locale.US)
        assertEquals("1 song", CountLabels.format(1, "song", "songs", nf.format(1)))
        assertEquals("1,234 songs", CountLabels.format(1234, "song", "songs", nf.format(1234)))
        assertEquals("0 artists", CountLabels.format(0, "artist", "artists", nf.format(0)))
    }

    @Test fun `generic format defaults to adding s and the plain number`() {
        assertEquals("1 file", CountLabels.format(1, "file"))
        assertEquals("3 files", CountLabels.format(3, "file"))
        assertEquals("2 entries", CountLabels.format(2, "entry", "entries"))
    }

    @Test fun `negative values are not singular and are not given special wording`() {
        assertEquals("-1 songs", CountLabels.songs(-1))
    }

    @Test fun `suffix and prefix compositions used by screens`() {
        assertEquals("1 play this month", "${CountLabels.plays(1)} this month")
        assertEquals("3 skips this month", "${CountLabels.skips(3)} this month")
        assertEquals("Add 1 song", "Add ${CountLabels.songs(1)}")
        assertEquals("1 song · 3 min", "${CountLabels.songs(1)} · 3 min")
    }
}
