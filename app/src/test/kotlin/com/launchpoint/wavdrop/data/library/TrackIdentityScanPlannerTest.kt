package com.launchpoint.wavdrop.data.library

import com.launchpoint.wavdrop.data.library.TrackIdentityScanPlanner.ExistingIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackIdentityScanPlannerTest {

    @Test
    fun `creates identity for live song with no identity`() {
        val plan = TrackIdentityScanPlanner.plan(
            liveSongIds = setOf(1L, 2L),
            existingIdentities = emptyList(),
        )

        assertEquals(setOf(1L, 2L), plan.songIdsNeedingIdentity.toSet())
        assertTrue(plan.identityUuidsToClear.isEmpty())
    }

    @Test
    fun `does not create duplicate when identity already exists`() {
        val plan = TrackIdentityScanPlanner.plan(
            liveSongIds = setOf(1L),
            existingIdentities = listOf(ExistingIdentity("uuid-1", currentSongId = 1L)),
        )

        assertTrue(plan.songIdsNeedingIdentity.isEmpty())
        assertTrue(plan.identityUuidsToClear.isEmpty())
        assertFalse(plan.hasWork)
    }

    @Test
    fun `clears identity when currentSongId missing from live set`() {
        val plan = TrackIdentityScanPlanner.plan(
            liveSongIds = setOf(2L),
            existingIdentities = listOf(ExistingIdentity("uuid-1", currentSongId = 1L)),
        )

        // Song 1 vanished → clear its identity; song 2 is new → needs an identity.
        assertEquals(listOf("uuid-1"), plan.identityUuidsToClear)
        assertEquals(listOf(2L), plan.songIdsNeedingIdentity)
    }

    @Test
    fun `does not clear null currentSongId`() {
        val plan = TrackIdentityScanPlanner.plan(
            liveSongIds = emptySet(),
            existingIdentities = listOf(ExistingIdentity("uuid-1", currentSongId = null)),
        )

        assertTrue(plan.identityUuidsToClear.isEmpty())
        assertTrue(plan.songIdsNeedingIdentity.isEmpty())
        assertFalse(plan.hasWork)
    }

    @Test
    fun `mixed create and clear case`() {
        val plan = TrackIdentityScanPlanner.plan(
            liveSongIds = setOf(2L, 3L),
            existingIdentities = listOf(
                ExistingIdentity("uuid-1", currentSongId = 1L),   // 1 vanished → clear
                ExistingIdentity("uuid-2", currentSongId = 2L),   // 2 still live → leave
                ExistingIdentity("uuid-3", currentSongId = null), // already unresolved → leave
            ),
        )

        assertEquals(listOf("uuid-1"), plan.identityUuidsToClear)
        assertEquals(listOf(3L), plan.songIdsNeedingIdentity) // only 3 lacks an identity
    }

    @Test
    fun `idempotency - second run after applying first plan produces no work`() {
        val liveSongIds = setOf(2L, 3L)
        val initial = listOf(
            ExistingIdentity("uuid-1", currentSongId = 1L),
            ExistingIdentity("uuid-2", currentSongId = 2L),
        )

        val first = TrackIdentityScanPlanner.plan(liveSongIds, initial)

        // Apply the first plan to the identity set: clear uuid-1, mint one for song 3.
        val afterApply = buildList {
            initial.forEach { id ->
                if (id.identityUuid in first.identityUuidsToClear) {
                    add(id.copy(currentSongId = null))
                } else {
                    add(id)
                }
            }
            first.songIdsNeedingIdentity.forEachIndexed { index, songId ->
                add(ExistingIdentity("new-uuid-$index", currentSongId = songId))
            }
        }

        val second = TrackIdentityScanPlanner.plan(liveSongIds, afterApply)

        assertFalse(second.hasWork)
        assertTrue(second.songIdsNeedingIdentity.isEmpty())
        assertTrue(second.identityUuidsToClear.isEmpty())
    }
}
