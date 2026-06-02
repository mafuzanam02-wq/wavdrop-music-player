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
}
