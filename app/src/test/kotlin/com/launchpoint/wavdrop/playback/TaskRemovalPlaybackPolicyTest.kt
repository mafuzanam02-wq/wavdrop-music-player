package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRemovalPlaybackPolicyTest {
    @Test
    fun `paused with current media item keeps session`() =
        assertTrue(TaskRemovalPlaybackPolicy.shouldKeepSession(mediaItemCount = 1, hasCurrentMediaItem = true))

    @Test
    fun `paused with non-empty queue keeps session`() =
        assertTrue(TaskRemovalPlaybackPolicy.shouldKeepSession(mediaItemCount = 4, hasCurrentMediaItem = false))

    @Test
    fun `playing with media item keeps session`() =
        assertTrue(TaskRemovalPlaybackPolicy.shouldKeepSession(mediaItemCount = 4, hasCurrentMediaItem = true))

    @Test
    fun `empty player uses default stop`() =
        assertFalse(TaskRemovalPlaybackPolicy.shouldKeepSession(mediaItemCount = 0, hasCurrentMediaItem = false))

    @Test
    fun `stale logical queue over empty player is not kept because only the player is consulted`() {
        // A stale PlayerController queue is not an input; an empty Media3 player still stops.
        assertFalse(TaskRemovalPlaybackPolicy.shouldKeepSession(mediaItemCount = 0, hasCurrentMediaItem = false))
    }
}
