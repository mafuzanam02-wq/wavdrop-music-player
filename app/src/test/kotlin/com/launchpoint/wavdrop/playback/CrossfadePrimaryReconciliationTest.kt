package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2D2: pure planner + executor for the primary handoff reconciliation primitive. */
class CrossfadePrimaryReconciliationTest {

    private val key = CrossfadeTransitionKey(5L, 1, 2)
    private val snap = SecondaryHandoffSnapshot(6_000L, 180_000L)

    private fun plan(
        key: CrossfadeTransitionKey = this.key,
        snapshot: SecondaryHandoffSnapshot = snap,
        generation: Long = 5L,
        size: Int = 4,
        logical: Int? = 1,
        physical: Int? = 1,
        repeat: RepeatMode = RepeatMode.OFF,
        dirty: Boolean = false,
        available: Boolean = true,
    ) = planCrossfadePrimaryReconciliation(key, snapshot, generation, size, logical, physical, repeat, dirty, available)

    private fun reject(reason: CrossfadePrimaryReconciliationRejection) =
        CrossfadePrimaryReconciliationPlan.Reject(reason)

    @Test fun validRequestPlansTheExactSeek() {
        assertEquals(CrossfadePrimaryReconciliationPlan.Seek(2, 6_000L), plan())
    }

    @Test fun generationMismatchRejects() =
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.QueueGenerationChanged), plan(generation = 6L))

    @Test fun dirtyPhysicalQueueRejects() =
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty), plan(dirty = true))

    @Test fun unavailableControllerRejects() =
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.ControllerUnavailable), plan(available = false))

    @Test fun logicalSourceMismatchRejects() {
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch), plan(logical = 2))
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch), plan(logical = null))
    }

    @Test fun physicalSourceMismatchRejects() {
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch), plan(physical = 0))
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch), plan(physical = null))
    }

    @Test fun sourceAlreadyAdvancedIsNotTreatedAsHandoffDone() {
        // Media3 already moved to the target before the call: both indices read 2, key says from = 1.
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch), plan(logical = 2, physical = 2))
    }

    @Test fun targetOutOfBoundsRejects() {
        assertEquals(
            reject(CrossfadePrimaryReconciliationRejection.TargetOutOfBounds),
            plan(key = CrossfadeTransitionKey(5L, 1, -1)),
        )
        assertEquals(
            reject(CrossfadePrimaryReconciliationRejection.TargetOutOfBounds),
            plan(key = CrossfadeTransitionKey(5L, 1, 4)),
        )
    }

    @Test fun sameSourceAndTargetRejects() =
        assertEquals(
            reject(CrossfadePrimaryReconciliationRejection.TargetMismatch),
            plan(key = CrossfadeTransitionKey(5L, 1, 1)),
        )

    @Test fun automaticTargetDriftRejects() {
        assertEquals(reject(CrossfadePrimaryReconciliationRejection.TargetMismatch), plan(repeat = RepeatMode.ONE))
        // last -> first only exists under Repeat ALL.
        val wrap = CrossfadeTransitionKey(5L, 3, 0)
        assertEquals(CrossfadePrimaryReconciliationPlan.Seek(0, 6_000L), plan(key = wrap, logical = 3, physical = 3, repeat = RepeatMode.ALL))
        assertEquals(
            reject(CrossfadePrimaryReconciliationRejection.TargetMismatch),
            plan(key = wrap, logical = 3, physical = 3, repeat = RepeatMode.OFF),
        )
    }

    @Test fun invalidSnapshotsRejectWithoutClamping() {
        for (s in listOf(
            SecondaryHandoffSnapshot(-1L, 180_000L),
            SecondaryHandoffSnapshot(0L, 0L),
            SecondaryHandoffSnapshot(0L, -5L),
            SecondaryHandoffSnapshot(180_001L, 180_000L),
        )) {
            assertEquals("$s", reject(CrossfadePrimaryReconciliationRejection.InvalidSnapshot), plan(snapshot = s))
        }
        assertEquals(CrossfadePrimaryReconciliationPlan.Seek(2, 180_000L), plan(snapshot = SecondaryHandoffSnapshot(180_000L, 180_000L)))
    }

    @Test fun queuedMetadataDurationIsNeverComparedSoMismatchIsTolerated() {
        // The planner takes no Song/duration input at all: a 179_500 ms metadata duration cannot influence it.
        assertEquals(CrossfadePrimaryReconciliationPlan.Seek(2, 6_000L), plan(snapshot = SecondaryHandoffSnapshot(6_000L, 180_000L)))
    }

    @Test fun duplicateSongQueueSeeksTheExactPositionalTarget() {
        // Queue ids [10, 20, 10, 10]: the planner only sees positions, so from 2 -> to 3 must target 3, never 0.
        val dupKey = CrossfadeTransitionKey(5L, 2, 3)
        assertEquals(CrossfadePrimaryReconciliationPlan.Seek(3, 6_000L), plan(key = dupKey, logical = 2, physical = 2))
        // Another occurrence of the same song id is not the source.
        assertEquals(
            reject(CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch),
            plan(key = dupKey, logical = 0, physical = 2),
        )
    }

    // -- Executor ---------------------------------------------------------------------

    @Test fun seekPlanIssuesExactlyOneSeekWithTheExactArguments() {
        val calls = mutableListOf<Pair<Int, Long>>()
        val result = executeCrossfadePrimaryReconciliation(
            plan(snapshot = SecondaryHandoffSnapshot(6_125L, 180_000L)),
        ) { i, p -> calls += i to p }
        assertEquals(CrossfadePrimaryReconciliationResult.Succeeded, result)
        assertEquals(listOf(2 to 6_125L), calls)
    }

    @Test fun rejectedPlanNeverSeeks() {
        var calls = 0
        val result = executeCrossfadePrimaryReconciliation(plan(dirty = true)) { _, _ -> calls++ }
        assertEquals(
            CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty),
            result,
        )
        assertEquals(0, calls)
    }

    @Test fun seekExceptionIsReportedAsSeekFailed() {
        val result = executeCrossfadePrimaryReconciliation(plan()) { _, _ -> throw IllegalStateException("boom") }
        assertEquals(
            CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.SeekFailed),
            result,
        )
        assertTrue(result is CrossfadePrimaryReconciliationResult.Rejected)
    }

    // -- Deterministic post-seek local state ------------------------------------------------

    private fun song(id: Long, tag: Long) = com.launchpoint.wavdrop.data.model.Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 179_500L,
        uri = "content://media/$id/$tag", dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1, 0), song(2, 1), song(3, 2), song(4, 3))
    private val before = NowPlayingState(
        song = queue[1], isPlaying = true, queue = queue, currentIndex = 1,
        positionMs = 190_000L, durationMs = 200_000L, bufferedPositionMs = 200_000L, isSeekable = true,
    )

    @Test fun reconciledStateInstallsTheExactTargetOccurrenceAndPhysicalFacts() {
        val s = reconcileCrossfadeNowPlayingState(
            before, queue, 2, SecondaryHandoffSnapshot(6_125L, 180_000L), shuffleEnabled = false, repeatMode = RepeatMode.OFF,
        )
        assertEquals(2, s.currentIndex)
        assertEquals(queue[2], s.song)
        assertEquals(6_125L, s.positionMs)
        assertEquals(180_000L, s.durationMs)
        assertEquals(queue, s.queue)
        assertEquals(true, s.isPlaying) // intent preserved
        assertEquals(true, s.isSeekable)
        assertEquals(6_125L, s.bufferedPositionMs) // conservative, not the old item's buffer
    }

    @Test fun duplicateSongQueueInstallsTheExactPositionalTarget() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3))
        val s = reconcileCrossfadeNowPlayingState(
            before.copy(queue = dup, currentIndex = 2, song = dup[2]),
            dup, 3, SecondaryHandoffSnapshot(6_000L, 180_000L), false, RepeatMode.OFF,
        )
        assertEquals(3, s.currentIndex)
        assertTrue(s.song === dup[3])
    }

    @Test fun staleSourceStateDoesNotMatterAndQueueFlagsAreCarriedThrough() {
        // The pre-seek state still names the source occurrence; the target is installed regardless.
        val s = reconcileCrossfadeNowPlayingState(
            before, queue, 2, SecondaryHandoffSnapshot(1L, 10L), shuffleEnabled = true, repeatMode = RepeatMode.ALL,
        )
        assertEquals(2, s.currentIndex)
        assertEquals(true, s.shuffleEnabled)
        assertEquals(RepeatMode.ALL, s.repeatMode)
        assertEquals(queue, s.queue)
    }

    @Test fun outOfRangeTargetLeavesStateUnchanged() {
        assertEquals(
            before,
            reconcileCrossfadeNowPlayingState(before, queue, 9, SecondaryHandoffSnapshot(1L, 10L), false, RepeatMode.OFF),
        )
    }

    // -- Ordering: local state / persistence only after a successful seek ----------------------

    @Test fun successCallbackRunsAfterSeekWithTheTargetIndex() {
        val order = mutableListOf<String>()
        val result = executeCrossfadePrimaryReconciliation(
            plan(), { i -> order += "after($i)" }, { i, p -> order += "seek($i,$p)" },
        )
        assertEquals(CrossfadePrimaryReconciliationResult.Succeeded, result)
        assertEquals(listOf("seek(2,6000)", "after(2)"), order)
    }

    @Test fun seekFailureAndRejectionNeverRunTheSuccessCallback() {
        var after = 0
        executeCrossfadePrimaryReconciliation(plan(), { after++ }) { _, _ -> throw IllegalStateException("x") }
        executeCrossfadePrimaryReconciliation(plan(dirty = true), { after++ }) { _, _ -> }
        assertEquals(0, after)
    }
}
