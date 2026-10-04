package com.launchpoint.wavdrop.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2L1: the pure post-AUTO reconciliation planner, the takeover decision and the AUTO classifier. */
class CrossfadePostAutoReconciliationPlannerTest {

    private val key = CrossfadeTransitionKey(5L, 1, 2)
    private val snapshot = SecondaryHandoffSnapshot(7_250L, 180_000L)

    private fun plan(
        key: CrossfadeTransitionKey = this.key,
        snapshot: SecondaryHandoffSnapshot = this.snapshot,
        generation: Long = 5L,
        size: Int = 4,
        logical: Int? = 2,
        physical: Int? = 2,
        repeat: RepeatMode = RepeatMode.OFF,
        dirty: Boolean = false,
        controller: Boolean = true,
    ) = planCrossfadePostAutoReconciliation(key, snapshot, generation, size, logical, physical, repeat, dirty, controller)

    private fun rejected(reason: CrossfadePrimaryReconciliationRejection) = CrossfadePrimaryReconciliationPlan.Reject(reason)

    @Test fun expectedTargetCurrentIsPlannedAsSeekOnTheTarget() {
        assertEquals(CrossfadePrimaryReconciliationPlan.Seek(2, 7_250L), plan())
    }

    @Test fun physicalStillAtSourceIsRejected() {
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch), plan(physical = 1))
    }

    @Test fun physicalWrongTargetIsRejected() {
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch), plan(physical = 3))
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch), plan(physical = null))
    }

    @Test fun logicalNotOnTargetIsRejected() {
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch), plan(logical = 1))
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.CurrentOccurrenceMismatch), plan(logical = null))
    }

    @Test fun generationMismatchIsRejected() {
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.QueueGenerationChanged), plan(generation = 6L))
    }

    @Test fun dirtyQueueIsRejected() {
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty), plan(dirty = true))
    }

    @Test fun controllerUnavailableIsRejected() {
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.ControllerUnavailable), plan(controller = false))
    }

    @Test fun invalidSecondarySnapshotIsRejected() {
        val bad = listOf(
            SecondaryHandoffSnapshot(-1L, 180_000L),
            SecondaryHandoffSnapshot(10L, 0L),
            SecondaryHandoffSnapshot(200_000L, 180_000L),
        )
        bad.forEach { assertEquals(rejected(CrossfadePrimaryReconciliationRejection.InvalidSnapshot), plan(snapshot = it)) }
    }

    @Test fun outOfBoundsAndSameOccurrenceAreRejected() {
        assertEquals(
            rejected(CrossfadePrimaryReconciliationRejection.TargetOutOfBounds),
            plan(key = CrossfadeTransitionKey(5L, 1, 9), logical = 9, physical = 9),
        )
        assertEquals(
            rejected(CrossfadePrimaryReconciliationRejection.TargetMismatch),
            plan(key = CrossfadeTransitionKey(5L, 2, 2)),
        )
    }

    @Test fun targetMustStillBeTheAutomaticNextOfTheSource() {
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.TargetMismatch), plan(repeat = RepeatMode.ONE))
        assertEquals(
            rejected(CrossfadePrimaryReconciliationRejection.TargetMismatch),
            plan(key = CrossfadeTransitionKey(5L, 1, 3), logical = 3, physical = 3),
        )
    }

    @Test fun duplicateSongsAreTargetedByExactPosition() {
        // Positions only: the plan names index 2 even if other indices hold the same song id.
        val dup = CrossfadeTransitionKey(5L, 1, 2)
        assertEquals(CrossfadePrimaryReconciliationPlan.Seek(2, 7_250L), plan(key = dup, logical = 2, physical = 2))
        assertEquals(rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch), plan(key = dup, physical = 0))
    }

    @Test fun repeatAllWrapTargetsTheFirstOccurrence() {
        val wrap = CrossfadeTransitionKey(5L, 3, 0)
        assertEquals(
            CrossfadePrimaryReconciliationPlan.Seek(0, 7_250L),
            plan(key = wrap, logical = 0, physical = 0, repeat = RepeatMode.ALL),
        )
        assertEquals(
            rejected(CrossfadePrimaryReconciliationRejection.TargetMismatch),
            plan(key = wrap, logical = 0, physical = 0, repeat = RepeatMode.OFF),
        )
    }

    // ── takeover decision ───────────────────────────────────────────────────────

    private fun facts(position: Long) = PrimaryTakeoverFacts(physicalIndex = 2, isReady = true, positionMs = position, isAdvancing = true)

    @Test fun farBehindThePrimarySeeksToTheFreshSecondaryPosition() {
        assertEquals(
            NaturalTakeoverDecision.SeekPrimary(7_250L, 0L),
            decideNaturalTakeover(facts(100L), 7_250L, seekTargetMs = null, seekLeadMs = 0L),
        )
    }

    @Test fun closeEnoughTakesOverWithoutASeek() {
        assertEquals(NaturalTakeoverDecision.TakeOver, decideNaturalTakeover(facts(7_170L), 7_250L, null, 0L)) // 80 ms behind
        assertEquals(NaturalTakeoverDecision.TakeOver, decideNaturalTakeover(facts(7_330L), 7_250L, null, 0L)) // 80 ms ahead
        // CF-2L2: the old 350 ms bound is no longer permission to begin the ownership transfer
        assertTrue(decideNaturalTakeover(facts(7_169L), 7_250L, null, 0L) is NaturalTakeoverDecision.SeekPrimary)
        assertTrue(decideNaturalTakeover(facts(6_900L), 7_250L, null, 0L) is NaturalTakeoverDecision.SeekPrimary)
    }

    @Test fun seekInFlightWaitsUntilThePrimaryLands() {
        assertEquals(NaturalTakeoverDecision.AwaitSeekLanding, decideNaturalTakeover(facts(150L), 7_300L, 7_250L, 0L))
        assertEquals(NaturalTakeoverDecision.TakeOver, decideNaturalTakeover(facts(7_250L), 7_300L, 7_250L, 0L))
    }

    @Test fun outsideToleranceAfterLandingNeverTakesOverAndRepositionsAheadByTheObservedLag() {
        // fresh secondary 9000 ms, primary landed at 7250 ms: a takeover here would audibly rewind B by 1.75 s.
        val decision = decideNaturalTakeover(facts(7_250L), 9_000L, seekTargetMs = 7_250L, seekLeadMs = 0L)
        assertTrue(decision != NaturalTakeoverDecision.TakeOver)
        assertEquals(NaturalTakeoverDecision.SeekPrimary(10_750L, 1_750L), decision)
    }

    @Test fun noRetryHistoryCanAuthoriseAStaleTakeover() {
        // Whatever was tried before (any target, any accumulated lead) a position outside tolerance is never a TakeOver.
        listOf(0L, 500L, 1_750L, NATURAL_TAKEOVER_MAX_LEAD_MS).forEach { lead ->
            val behind = decideNaturalTakeover(facts(7_250L), 9_000L, 7_250L, lead)
            val ahead = decideNaturalTakeover(facts(12_000L), 9_000L, 12_000L, lead)
            assertTrue("behind lead=$lead", behind is NaturalTakeoverDecision.SeekPrimary)
            assertTrue("ahead lead=$lead", ahead is NaturalTakeoverDecision.SeekPrimary)
        }
    }

    @Test fun leadConvergesAndIsCapped() {
        assertEquals(NaturalTakeoverDecision.TakeOver, decideNaturalTakeover(facts(10_950L), 11_000L, 10_750L, 1_750L))
        val capped = decideNaturalTakeover(facts(0L + 1_000L), 90_000L, 1_000L, 0L) as NaturalTakeoverDecision.SeekPrimary
        assertEquals(NATURAL_TAKEOVER_MAX_LEAD_MS, capped.leadMs)
    }

    // ── AUTO classification ─────────────────────────────────────────────────────

    @Test fun onlyTheGenuineAutoReasonQualifies() {
        assertTrue(isNaturalAutoTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertFalse(isNaturalAutoTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
        assertFalse(isNaturalAutoTransition(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
        assertFalse(isNaturalAutoTransition(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
        assertFalse(isNaturalAutoTransition(-1))
    }

    @Test fun postAutoExecutionIssuesExactlyOneSeekOnTheCurrentTarget() {
        val seeks = mutableListOf<Pair<Int, Long>>()
        val result = executeCrossfadePrimaryReconciliation(plan = plan(), seekTo = { index, position -> seeks += index to position })
        assertEquals(CrossfadePrimaryReconciliationResult.Succeeded, result)
        assertEquals(listOf(2 to 7_250L), seeks)
    }
}
