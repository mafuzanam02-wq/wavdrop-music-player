package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionRulesTest {

    @Test
    fun `auto transition is classified as a natural advance`() {
        val kind = classifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals(MediaItemTransitionKind.Auto, kind)
        assertTrue(kind.isNaturalAdvance)
    }

    @Test
    fun `seek transition is not a natural advance`() {
        val kind = classifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertEquals(MediaItemTransitionKind.Seek, kind)
        assertFalse(kind.isNaturalAdvance)
    }

    @Test
    fun `repeat transition is distinct from auto`() {
        val kind = classifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)

        assertEquals(MediaItemTransitionKind.Repeat, kind)
        assertFalse(kind.isNaturalAdvance)
    }

    @Test
    fun `playlist changed transition is distinct`() {
        val kind = classifyMediaItemTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)

        assertEquals(MediaItemTransitionKind.PlaylistChanged, kind)
        assertFalse(kind.isNaturalAdvance)
    }

    @Test
    fun `unknown reasons fail safely as not natural`() {
        listOf(-1, 4, 99, Int.MAX_VALUE).forEach { reason ->
            val kind = classifyMediaItemTransition(reason)
            assertEquals(MediaItemTransitionKind.Unknown, kind)
            assertFalse(kind.isNaturalAdvance)
        }
    }

    @Test
    fun `synced queue at a natural boundary needs no app intervention`() {
        assertFalse(
            naturalTransitionRequiresQueueResync(MediaItemTransitionKind.Auto, playerQueueNeedsSync = false),
        )
    }

    @Test
    fun `only a stale player queue triggers resync at an auto boundary`() {
        assertTrue(
            naturalTransitionRequiresQueueResync(MediaItemTransitionKind.Auto, playerQueueNeedsSync = true),
        )
        MediaItemTransitionKind.values()
            .filter { it != MediaItemTransitionKind.Auto }
            .forEach { kind ->
                assertFalse(naturalTransitionRequiresQueueResync(kind, playerQueueNeedsSync = true))
                assertFalse(naturalTransitionRequiresQueueResync(kind, playerQueueNeedsSync = false))
            }
    }
}
