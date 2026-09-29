package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.playback.PlaybackSessionSnapshot
import com.launchpoint.wavdrop.data.settings.ResumeBehaviorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HydrationAuthorityTest {
    private fun decide(external: Boolean = false, mediaItemCount: Int, hasCurrent: Boolean) =
        HydrationAuthority.decide(
            isExternalPlayback = external,
            mediaQueuePresent = HydrationAuthority.mediaQueuePresent(mediaItemCount, hasCurrent),
        )

    @Test
    fun `logical and physical queue is already hydrated`() {
        // The logical queue is not an input at all; only the physical player matters.
        assertEquals(HydrationDecision.ALREADY_HYDRATED, decide(mediaItemCount = 4, hasCurrent = true))
    }

    @Test
    fun `physical queue alone is already hydrated`() {
        assertEquals(HydrationDecision.ALREADY_HYDRATED, decide(mediaItemCount = 2, hasCurrent = false))
        assertEquals(HydrationDecision.ALREADY_HYDRATED, decide(mediaItemCount = 0, hasCurrent = true))
    }

    @Test
    fun `stale logical only state needs hydration`() {
        // logical queue non-empty, mediaItemCount == 0, currentMediaItem == null
        val decision = decide(mediaItemCount = 0, hasCurrent = false)
        assertEquals(HydrationDecision.NEEDS_HYDRATION, decision)
        assertFalse(decision == HydrationDecision.ALREADY_HYDRATED)
    }

    @Test
    fun `completely empty state needs hydration`() {
        assertEquals(HydrationDecision.NEEDS_HYDRATION, decide(mediaItemCount = 0, hasCurrent = false))
    }

    @Test
    fun `external playback is never hydrated over`() {
        assertEquals(HydrationDecision.SKIP_EXTERNAL, decide(external = true, mediaItemCount = 0, hasCurrent = false))
        assertEquals(HydrationDecision.SKIP_EXTERNAL, decide(external = true, mediaItemCount = 3, hasCurrent = true))
        assertFalse(HydrationAuthority.mayApplySnapshot(isExternalPlayback = true, mediaQueuePresent = false))
    }

    @Test
    fun `race recheck blocks overwrite when media appears before apply`() {
        // Hydration begins against an empty player...
        assertEquals(HydrationDecision.NEEDS_HYDRATION, decide(mediaItemCount = 0, hasCurrent = false))
        // ...user starts playback while it suspends; the pre-apply recheck sees physical media.
        assertFalse(HydrationAuthority.mayApplySnapshot(isExternalPlayback = false, mediaQueuePresent = true))
        // Still empty at apply time: allowed.
        assertTrue(HydrationAuthority.mayApplySnapshot(isExternalPlayback = false, mediaQueuePresent = false))
    }

    @Test
    fun `duplicate occurrence restore still targets the saved second occurrence`() {
        val songs = (1L..4L).map(::song)
        val snapshot = PlaybackSessionSnapshot(
            queueSongIds = listOf(1, 2, 1, 3),
            currentSongId = 1,
            currentIndex = 2,
            positionMs = 5_000L,
            repeatMode = RepeatMode.OFF,
            shuffleEnabled = false,
            updatedAtMs = 0L,
        )
        val result = PlaybackResumptionMapper.map(snapshot, ResumeBehaviorSettings(), songs)
        val plan = (result as PlaybackResumptionResult.Ready).plan
        assertEquals(2, plan.currentLibraryIndex)
        assertEquals(2, plan.startPlaybackIndex)
    }

    private fun song(id: Long) = Song(
        id = id, title = "T$id", artist = "A$id", album = "Al$id", albumId = id,
        duration = 1_000L, uri = "content://media/$id", dateAdded = 0L, trackNumber = 1, year = 2020,
    )
}
