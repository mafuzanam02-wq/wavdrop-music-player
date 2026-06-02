package com.launchpoint.wavdrop.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTextCleanerTest {

    @Test
    fun `blank sidecar text returns null`() {
        assertNull(LyricsTextCleaner.clean("   \n\t  "))
    }

    @Test
    fun `clean normalizes line endings and trims whitespace`() {
        assertEquals(
            "Line one\nLine two",
            LyricsTextCleaner.clean("  Line one\r\nLine two\r\n  "),
        )
    }

    @Test
    fun `clean strips leading lrc timestamps`() {
        assertEquals(
            "First line\nSecond line",
            LyricsTextCleaner.clean("[00:12.34]First line\n[01:05.00]Second line"),
        )
    }

    @Test
    fun `clean strips repeated leading lrc timestamps`() {
        assertEquals(
            "Shared line",
            LyricsTextCleaner.clean("[00:12.34][00:20.00]Shared line"),
        )
    }
}
