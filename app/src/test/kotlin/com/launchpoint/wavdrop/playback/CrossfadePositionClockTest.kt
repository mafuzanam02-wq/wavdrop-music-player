package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2L4: the pure bounded monotonic projected position clock for one continuously playing stream. */
class CrossfadePositionClockTest {

    private val duration = 180_000L
    private val clock = NaturalHandoffPositionClock()

    private fun observe(raw: Long, now: Long) = clock.observe(raw, now, duration)

    /** Anchors at (raw 1000 @ t0) and calibrates with a first change (raw 1100 @ t0+100): a trusted, moving clock. */
    private fun calibrated(t0: Long = 0L) {
        assertEquals(PositionSampleDecision.Anchored, observe(1_000L, t0))
        assertEquals(PositionSampleDecision.Calibrated, observe(1_100L, t0 + 100L))
    }

    @Test fun anInitialObservationCreatesAValidButProvisionalAnchor() {
        assertFalse(clock.hasAnchor)
        assertNull(clock.projectedMs(0L))
        assertEquals(PositionSampleDecision.Anchored, observe(1_000L, 0L))
        assertTrue(clock.hasAnchor)
        assertEquals(1_000L, clock.projectedMs(0L))
        assertFalse(clock.isConfident(0L)) // its raw may already be stale: never trusted until a raw change was seen
    }

    @Test fun aPinnedStreamThatNeverChangesIsNeverTrusted() {
        observe(1_000L, 0L)
        repeat(40) { assertEquals(PositionSampleDecision.Stale, observe(1_000L, it * 16L)) }
        assertFalse(clock.isConfident(640L))
    }

    @Test fun anUnchangedRawSampleDoesNotResetAMovingAnchor() {
        calibrated()
        val before = clock.projectedMs(100L)
        // raw 1100 repeated at 116, 132, 148 ...: the projection keeps moving from the ORIGINAL anchor
        assertEquals(PositionSampleDecision.Stale, observe(1_100L, 116L))
        assertEquals(PositionSampleDecision.Stale, observe(1_100L, 132L))
        assertEquals(PositionSampleDecision.Stale, observe(1_100L, 148L))
        assertEquals(1_100L, before)
        assertEquals(1_148L, clock.projectedMs(148L))
    }

    @Test fun theOldCollapsingPatternDoesNotCollapse() {
        // raw 1000, 1000, 1000, 1250 at t = 0, 16, 32, 250 (a coarse update): projection stays on the timeline throughout
        observe(1_000L, 0L)
        observe(1_000L, 16L)
        observe(1_000L, 32L)
        assertEquals(1_032L, clock.projectedMs(32L)) // never collapses back to 1000
        assertEquals(PositionSampleDecision.Calibrated, observe(1_250L, 250L))
        assertEquals(1_250L, clock.projectedMs(250L))
    }

    @Test fun projectionAdvancesOneMsPerMonotonicMs() {
        calibrated()
        assertEquals(1_100L, clock.projectedMs(100L))
        assertEquals(1_150L, clock.projectedMs(150L))
        assertEquals(1_600L, clock.projectedMs(600L))
        assertEquals(clock.projectedMs(300L)!! + 40L, clock.projectedMs(340L))
    }

    @Test fun aModestlyStaleRawNeverRegressesTheProjection() {
        calibrated()
        assertEquals(1_200L, clock.projectedMs(200L))
        // a new raw that is merely behind the projection (1180 vs 1200): kept, never rewound
        assertEquals(PositionSampleDecision.Refreshed, observe(1_180L, 200L))
        assertEquals(1_200L, clock.projectedMs(200L))
        assertEquals(1_210L, clock.projectedMs(210L))
        // an old coarse read of exactly the previous value: still no rewind
        assertEquals(PositionSampleDecision.Stale, observe(1_180L, 220L))
        assertEquals(1_220L, clock.projectedMs(220L))
    }

    @Test fun aPlausibleRawRefreshReanchorsWithoutRewindingTheTimeline() {
        calibrated()
        val before = clock.projectedMs(200L)!!
        assertEquals(PositionSampleDecision.Refreshed, observe(1_260L, 200L)) // 60 ms ahead: within the agreement window
        assertTrue(clock.projectedMs(200L)!! >= before)
        assertEquals(1_260L, clock.projectedMs(200L))
        assertEquals(1_300L, clock.projectedMs(240L))
    }

    @Test fun aKnownDurationCapsTheProjection() {
        val c = NaturalHandoffPositionClock()
        c.observe(179_900L, 0L, 180_000L)
        c.observe(179_950L, 50L, 180_000L)
        assertEquals(180_000L, c.projectedMs(10_000L)) // never past the duration
        assertEquals(PositionSampleDecision.Invalid, c.observe(180_001L, 60L, 180_000L))
        assertFalse(c.hasAnchor)
    }

    @Test fun anInvalidRawSampleFailsClosed() {
        calibrated()
        assertEquals(PositionSampleDecision.Invalid, observe(-1L, 200L))
        assertFalse(clock.hasAnchor)
        assertFalse(clock.isConfident(200L))
        assertNull(clock.projectedMs(200L))
    }

    @Test fun aBackwardMonotonicClockCannotCreateNegativeAdvancement() {
        calibrated(t0 = 1_000L)
        val at = clock.projectedMs(1_100L)!!
        assertEquals(at, clock.projectedMs(900L)) // the clock went backwards: no advancement, never below the anchor
        assertTrue(clock.projectedMs(0L)!! >= 1_100L - 100L)
        assertEquals(PositionSampleDecision.Stale, observe(1_100L, 800L))
        assertEquals(0L, clock.ageMs(500L)) // age is clamped, never negative
    }

    @Test fun theProjectionExpiresAfterTheMaximumAgeWithoutACredibleRefresh() {
        calibrated()
        assertTrue(clock.isConfident(100L + NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS))
        assertFalse(clock.isExpired(100L + NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS))
        assertFalse(clock.isConfident(101L + NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS))
        assertTrue(clock.isExpired(101L + NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS))
        // an unchanged raw is not a confirmation: staleness cannot refresh it
        observe(1_100L, 400L)
        assertTrue(clock.isExpired(700L))
    }

    @Test fun aCredibleRefreshRestartsTheAge() {
        calibrated()
        observe(1_500L, 400L)
        assertTrue(clock.isConfident(400L + NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS))
        assertEquals(0L, clock.ageMs(400L))
    }

    @Test fun aLargeForwardRawJumpIsReportedNotSmoothed() {
        calibrated()
        assertEquals(PositionSampleDecision.ForwardDiscontinuity, observe(1_100L + 100L + NATURAL_HANDOFF_RAW_AGREEMENT_MS + 1L, 200L))
        assertEquals(1_301L, clock.projectedMs(200L)) // re-anchored on the real position, not hidden as drift
        assertFalse(clock.isConfident(200L)) // and untrusted until it proves itself again
    }

    @Test fun aLargeBackwardRawJumpIsReportedNotSmoothed() {
        calibrated()
        assertEquals(PositionSampleDecision.BackwardDiscontinuity, observe(1_200L - NATURAL_HANDOFF_RAW_AGREEMENT_MS - 1L, 200L))
        assertEquals(1_099L, clock.projectedMs(200L))
        assertFalse(clock.isConfident(200L))
        assertTrue(PositionSampleDecision.BackwardDiscontinuity.isDiscontinuity && PositionSampleDecision.ForwardDiscontinuity.isDiscontinuity)
    }

    @Test fun anUnconfirmedAnchorTreatsOnlyAGrossJumpAsADiscontinuity() {
        observe(1_000L, 0L)
        // a first coarse step of 250 ms is just the stale anchor catching up (granularity), not a seek
        assertEquals(PositionSampleDecision.Calibrated, observe(1_250L, 20L))
        assertTrue(clock.isConfident(20L))
        val other = NaturalHandoffPositionClock()
        other.observe(1_000L, 0L, duration)
        assertEquals(PositionSampleDecision.ForwardDiscontinuity, other.observe(1_000L + NATURAL_TAKEOVER_MAX_LAG_MS + 30L, 20L, duration))
    }

    @Test fun invalidateDropsEveryPieceOfState() {
        calibrated()
        clock.invalidate()
        assertFalse(clock.hasAnchor)
        assertNull(clock.projectedMs(500L))
        assertNull(clock.ageMs(500L))
        assertFalse(clock.isConfident(500L))
        assertFalse(clock.isExpired(500L))
        assertEquals(PositionSampleDecision.Anchored, observe(5_000L, 600L)) // a fresh anchor, nothing inherited
    }

    @Test fun theProjectionConstantsAreTheDocumentedBoundedValues() {
        assertEquals(500L, NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS)
        assertEquals(100L, NATURAL_HANDOFF_RAW_AGREEMENT_MS)
        // the continuity standards are unchanged by CF-2L4
        assertEquals(80L, NATURAL_TRANSFER_ENTRY_TOLERANCE_MS)
        assertEquals(200L, NATURAL_TRANSFER_ABORT_TOLERANCE_MS)
        assertEquals(150L, NATURAL_TAKEOVER_TRANSFER_DURATION_MS)
        assertEquals(350L, NATURAL_TAKEOVER_MAX_LAG_MS)
        assertTrue(NATURAL_HANDOFF_RAW_AGREEMENT_MS < NATURAL_TAKEOVER_MAX_LAG_MS)
        assertTrue(NATURAL_TAKEOVER_MAX_LAG_MS < NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS)
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
