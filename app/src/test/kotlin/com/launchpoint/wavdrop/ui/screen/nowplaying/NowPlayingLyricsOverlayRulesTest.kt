package com.launchpoint.wavdrop.ui.screen.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricsOverlayRulesTest {

    @Test
    fun `no lyrics help text keeps embedded and sidecar guidance`() {
        assertEquals(
            "Long-press to add custom lyrics, or add embedded/sidecar lyrics locally.",
            NowPlayingLyricsOverlayRules.NO_LYRICS_HELP_TEXT,
        )
    }

    @Test
    fun `no lyrics search action is exposed when callback is available`() {
        assertTrue(
            NowPlayingLyricsOverlayRules.showSearchOnlineAction {},
        )
    }

    @Test
    fun `no lyrics search action can be hidden when callback is unavailable`() {
        assertFalse(
            NowPlayingLyricsOverlayRules.showSearchOnlineAction(null),
        )
    }
}
