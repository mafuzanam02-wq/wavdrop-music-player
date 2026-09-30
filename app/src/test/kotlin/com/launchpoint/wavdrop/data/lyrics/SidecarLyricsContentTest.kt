package com.launchpoint.wavdrop.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarLyricsContentTest {

    @Test
    fun `timed lrc resolves to synced`() {
        val result = SidecarLyricsContent.resolve("Song.lrc", "[00:12.50]First\n[00:16.20]Second")

        assertTrue(result is LyricsResult.Synced)
        result as LyricsResult.Synced
        assertEquals(listOf(12_500L, 16_200L), result.lines.map { it.timeMs })
        assertEquals("First\nSecond", result.plainText)
    }

    @Test
    fun `lrc extension is matched case insensitively`() {
        assertTrue(SidecarLyricsContent.resolve("Song.LRC", "[00:01.00]x") is LyricsResult.Synced)
    }

    @Test
    fun `untimed lrc falls back to cleaned plain text`() {
        assertEquals(
            LyricsResult.Available("Plain one\nPlain two"),
            SidecarLyricsContent.resolve("Song.lrc", "Plain one\nPlain two\n"),
        )
    }

    @Test
    fun `metadata only lrc falls back to existing cleaned behavior`() {
        assertEquals(
            LyricsResult.Available("[ti:Title]"),
            SidecarLyricsContent.resolve("Song.lrc", "[ti:Title]"),
        )
    }

    @Test
    fun `txt stays plain even when it contains timestamps`() {
        assertEquals(
            LyricsResult.Available("First\nSecond"),
            SidecarLyricsContent.resolve("Song.txt", "[00:12.50]First\n[00:16.20]Second"),
        )
    }

    @Test
    fun `blank content resolves to nothing`() {
        assertNull(SidecarLyricsContent.resolve("Song.lrc", "  \n"))
        assertNull(SidecarLyricsContent.resolve("Song.txt", ""))
    }
}
