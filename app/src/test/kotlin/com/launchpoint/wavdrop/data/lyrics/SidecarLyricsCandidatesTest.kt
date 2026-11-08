package com.launchpoint.wavdrop.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarLyricsCandidatesTest {

    @Test
    fun `candidates include lrc before txt`() {
        assertEquals(
            listOf("Song.lrc", "Song.txt"),
            SidecarLyricsCandidates.displayNames(
                audioDisplayName = "Song.mp3",
                title = "Song",
            ),
        )
    }

    @Test
    fun `candidates include title fallback when display base differs`() {
        assertEquals(
            listOf("01 Track.lrc", "Track Title.lrc", "01 Track.txt", "Track Title.txt"),
            SidecarLyricsCandidates.displayNames(
                audioDisplayName = "01 Track.flac",
                title = "Track Title",
            ),
        )
    }

    @Test
    fun `candidates use title when display name is unavailable`() {
        assertEquals(
            listOf("Track Title.lrc", "Track Title.txt"),
            SidecarLyricsCandidates.displayNames(
                audioDisplayName = null,
                title = " Track Title ",
            ),
        )
    }

    @Test
    fun `relaxed names strip one terminal duplicate number and keep lrc before txt`() {
        assertEquals(
            listOf("Alexander William - Cuffley (Official Video).lrc", "Alexander William - Cuffley (Official Video).txt"),
            SidecarLyricsCandidates.relaxedDisplayNames("Alexander William - Cuffley (Official Video) (1).mp3"),
        )
    }

    @Test
    fun `relaxed names are empty without a terminal duplicate number`() {
        assertEquals(emptyList<String>(), SidecarLyricsCandidates.relaxedDisplayNames("Song.mp3"))
        assertEquals(emptyList<String>(), SidecarLyricsCandidates.relaxedDisplayNames("Song (live).mp3"))
        assertEquals(emptyList<String>(), SidecarLyricsCandidates.relaxedDisplayNames("Song (1) remix.mp3"))
        assertEquals(emptyList<String>(), SidecarLyricsCandidates.relaxedDisplayNames("(1).mp3"))
        assertEquals(emptyList<String>(), SidecarLyricsCandidates.relaxedDisplayNames(null))
    }

    @Test
    fun `only the last duplicate number is stripped`() {
        assertEquals("Song (1)", SidecarLyricsCandidates.duplicateSuffixStrippedBase("Song (1) (2).mp3"))
    }

    @Test
    fun `folder parsing distinguishes root from unknown`() {
        assertEquals(SidecarFolder.Root, SidecarFolder.parse("/"))
        assertEquals(null, SidecarFolder.parse(null))
        assertEquals(null, SidecarFolder.parse(""))
        assertEquals(null, SidecarFolder.parse("  "))
        assertEquals(false, SidecarFolder.Root.accepts(SidecarFolder.parse("Music/")))
        assertEquals(false, SidecarFolder.Root.accepts(null))
        assertEquals(false, SidecarFolder.parse("Music/")!!.accepts(SidecarFolder.Root))
    }
}
