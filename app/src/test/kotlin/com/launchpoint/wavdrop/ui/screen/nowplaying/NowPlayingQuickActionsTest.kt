package com.launchpoint.wavdrop.ui.screen.nowplaying

import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingQuickActionCorner.BottomLeft
import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingQuickActionCorner.BottomRight
import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingQuickActionCorner.TopLeft
import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingQuickActionCorner.TopRight
import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingQuickActionType.Favorite
import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingQuickActionType.Playlist
import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingQuickActionType.Share
import com.launchpoint.wavdrop.ui.screen.nowplaying.NowPlayingQuickActionType.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingQuickActionsTest {

    private fun actions(
        hasSong: Boolean = true,
        external: Boolean = false,
        favorite: Boolean = false,
        timer: Boolean = false,
    ) = nowPlayingQuickActions(hasSong, external, favorite, timer)

    @Test
    fun `local song offers favorite playlist timer share in order`() {
        assertEquals(listOf(Favorite, Playlist, Timer, Share), actions().map { it.type })
    }

    @Test
    fun `external audio offers only timer and share`() {
        val types = actions(external = true).map { it.type }
        assertEquals(listOf(Timer, Share), types)
        assertFalse(Favorite in types)
        assertFalse(Playlist in types)
    }

    @Test
    fun `favorite action distinguishes selected state`() {
        val off = actions(favorite = false).first { it.type == Favorite }
        val on = actions(favorite = true).first { it.type == Favorite }
        assertEquals("Add to favorites", off.contentDescription)
        assertFalse(off.selected)
        assertEquals("Remove from favorites", on.contentDescription)
        assertTrue(on.selected)
    }

    @Test
    fun `timer action reflects active state`() {
        val idle = actions(timer = false).first { it.type == Timer }
        val active = actions(timer = true).first { it.type == Timer }
        assertFalse(idle.selected)
        assertTrue(active.selected)
        assertEquals("Sleep timer", idle.contentDescription)
        assertEquals("Sleep timer, active", active.contentDescription)
    }

    @Test
    fun `playlist and share have fixed descriptions and are never selected`() {
        val list = actions(favorite = true, timer = true)
        val playlist = list.first { it.type == Playlist }
        val share = list.first { it.type == Share }
        assertEquals("Add to playlist", playlist.contentDescription)
        assertEquals("Share track", share.contentDescription)
        assertFalse(playlist.selected)
        assertFalse(share.selected)
    }

    @Test
    fun `no current song produces no quick actions`() {
        assertTrue(actions(hasSong = false).isEmpty())
        assertTrue(actions(hasSong = false, external = true, favorite = true, timer = true).isEmpty())
    }

    @Test
    fun `local song actions map to the four artwork corners`() {
        val corners = actions().associate { it.type to it.type.corner() }
        assertEquals(TopLeft, corners[Favorite])
        assertEquals(TopRight, corners[Playlist])
        assertEquals(BottomLeft, corners[Timer])
        assertEquals(BottomRight, corners[Share])
    }

    @Test
    fun `external audio keeps timer and share in their bottom corners`() {
        val corners = actions(external = true).map { it.type.corner() }
        assertEquals(listOf(BottomLeft, BottomRight), corners)
        assertFalse(TopLeft in corners)
        assertFalse(TopRight in corners)
    }
}
