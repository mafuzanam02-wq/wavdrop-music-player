package com.launchpoint.wavdrop.data.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8FormatterTest {
    private fun e(path: String, ms: Long = 178_776, artist: String = "Artist", title: String = "Title") =
        M3u8Entry(path, ms, artist, title)

    /** Independent minimal M3U reader: non-comment, non-blank lines are paths. */
    private fun parsePaths(text: String) = text.split("\n").filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test fun `valid extended m3u with header extinf and path per entry`() {
        val text = M3u8Formatter.format(listOf(e("/storage/emulated/0/Music/a.mp3", 178_776, "Alexander William", "Cuffley")))
        assertEquals(
            "#EXTM3U\n#EXTINF:179,Alexander William - Cuffley\n/storage/emulated/0/Music/a.mp3\n",
            text,
        )
    }

    @Test fun `order and duplicate occurrences are preserved`() {
        val a = e("/storage/emulated/0/Music/a.mp3"); val b = e("/storage/emulated/0/Music/b.mp3")
        val text = M3u8Formatter.format(listOf(a, b, a, a))
        assertEquals(listOf(a.path, b.path, a.path, a.path), parsePaths(text))
    }

    @Test fun `duration conversion rounds to seconds and uses minus one when unknown`() {
        fun extinf(ms: Long) = M3u8Formatter.format(listOf(e("/a.mp3", ms))).lines()[1].substringBefore(',')
        assertEquals("#EXTINF:179", extinf(178_776))
        assertEquals("#EXTINF:1", extinf(1_499))
        assertEquals("#EXTINF:2", extinf(1_500))
        assertEquals("#EXTINF:-1", extinf(0))
        assertEquals("#EXTINF:-1", extinf(-5))
    }

    @Test fun `unicode is written as utf8 without a bom`() {
        val entry = e("/storage/emulated/0/Music/Plàsi - 14 Days 日本語.mp3", artist = "Plàsi", title = "Ünï 🎵")
        val bytes = M3u8Formatter.toBytes(listOf(entry))
        assertFalse(bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte())
        assertArrayEquals("#EXTM3U".toByteArray(Charsets.UTF_8), bytes.copyOfRange(0, 7))
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("Plàsi - Ünï 🎵"))
        assertEquals(listOf(entry.path), parsePaths(text))
    }

    @Test fun `metadata cannot inject directives or extra lines`() {
        val evil = e("/a.mp3", artist = "Ar\r\n#EXTINF:1,x", title = "Ti\n/etc/passwd\u0000\u0007 tle")
        val text = M3u8Formatter.format(listOf(evil))
        val lines = text.split("\n").filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertEquals("#EXTM3U", lines[0])
        assertTrue(lines[1].startsWith("#EXTINF:179,"))
        assertEquals(listOf("/a.mp3"), parsePaths(text))
        assertFalse(text.contains('\r'))
        assertTrue(text.none { it.code < 0x20 && it != '\n' })
    }

    @Test fun `all newlines are plain lf`() {
        val text = M3u8Formatter.format(listOf(e("/a.mp3"), e("/b.mp3")))
        assertFalse(text.contains('\r')); assertTrue(text.endsWith("\n"))
    }

    @Test fun `path characters are not rewritten`() {
        val p = "/storage/emulated/0/Music/Crywolf_-_12.25.16_[untitled_Christmas_song](256k) #1 %20 é.mp3"
        assertEquals(listOf(p), parsePaths(M3u8Formatter.format(listOf(e(p)))))
    }

    @Test fun `unsafe paths are rejected`() {
        assertTrue(M3u8Formatter.isSerializablePath("/storage/emulated/0/a.mp3"))
        assertTrue(M3u8Formatter.isSerializablePath("/storage/1A2B-3C4D/Music/a.mp3"))
        assertFalse(M3u8Formatter.isSerializablePath(""))
        assertFalse(M3u8Formatter.isSerializablePath("   "))
        assertFalse(M3u8Formatter.isSerializablePath("content://media/external/audio/media/5"))
        assertFalse(M3u8Formatter.isSerializablePath("Music/relative.mp3"))
        assertFalse(M3u8Formatter.isSerializablePath("/a\nb.mp3"))
        assertFalse(M3u8Formatter.isSerializablePath("/a\r.mp3"))
        assertFalse(M3u8Formatter.isSerializablePath("/a\u0000.mp3"))
        assertFalse(M3u8Formatter.isSerializablePath("/a .mp3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `format refuses an unsafe path instead of emitting it`() {
        M3u8Formatter.format(listOf(e("/a\n#EXTINF:1,evil\n/b.mp3")))
    }

    @Test fun `unknown artist is omitted from the display text`() {
        assertEquals("#EXTINF:179,T", M3u8Formatter.format(listOf(e("/a.mp3", artist = "<unknown>", title = "T"))).lines()[1])
        assertEquals("#EXTINF:179,T", M3u8Formatter.format(listOf(e("/a.mp3", artist = "Unknown Artist", title = "T"))).lines()[1])
    }
}
