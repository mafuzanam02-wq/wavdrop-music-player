package com.launchpoint.wavdrop.playback

/**
 * CF-2L4: longest a projected position may run without a credible raw refresh. A device log showed raw Player positions that stay
 * frozen between sparse updates (secondary jumps of roughly 250 ms); 500 ms allows one such interval plus margin. Beyond it the
 * projection EXPIRES: it can no longer authorise anything (no transfer, no completion) and a pinned/stalled stream is never
 * invented forward. Internal engineering constant, not a setting.
 */
internal const val NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS = 500L

/**
 * CF-2L4: how far a refreshed raw sample may differ from the projection of a CONFIRMED stream and still count as the same
 * continuous timeline (sampling jitter). More than this is a real discontinuity, never smoothed away.
 */
internal const val NATURAL_HANDOFF_RAW_AGREEMENT_MS = 100L

/** What one raw position observation meant for a [NaturalHandoffPositionClock]. */
internal enum class PositionSampleDecision {
    /** The first valid sample: a provisional anchor (its staleness is unknown, so it is not yet trusted). */
    Anchored,

    /** The raw value did not change: the anchor and projection are kept; this is NOT a confirmation. */
    Stale,

    /** A confirmed stream's raw value moved plausibly: re-anchored forward-only (the projection never rewinds). */
    Refreshed,

    /** An unconfirmed anchor saw its first raw change within the coarse-step bound: calibrated and now trusted. */
    Calibrated,

    /** The raw value is materially ahead of the timeline (a real seek or jump); re-anchored provisionally, reported. */
    ForwardDiscontinuity,

    /** The raw value is materially behind the timeline (a real seek or rewind); re-anchored provisionally, reported. */
    BackwardDiscontinuity,

    /** The sample was not a valid media position (negative or beyond a known duration); the clock was invalidated. */
    Invalid,
}

internal val PositionSampleDecision.isDiscontinuity: Boolean
    get() = this == PositionSampleDecision.ForwardDiscontinuity ||
        this == PositionSampleDecision.BackwardDiscontinuity ||
        this == PositionSampleDecision.Invalid

/**
 * CF-2L4: a bounded, monotonic projection of ONE continuously playing stream's position from coarse raw `Player.currentPosition`
 * samples and the single shared monotonic clock (never wall-clock, never a timer). Pure arithmetic and a few fields: nothing is
 * allocated per observation.
 *
 * Model: a raw position that is frozen between sparse updates is exact at the instant it changes, so the stream's position is
 * `anchorRaw + (now - anchorAt)` re-anchored at each change. Rules:
 *  - an unchanged raw sample never resets the anchor (the projection keeps moving, ~1 ms per monotonic ms);
 *  - the projection never moves backward because a sample is coarse (re-anchoring takes max(raw, projection));
 *  - the FIRST anchor is provisional (its raw may already be stale); the stream is trusted only after its raw has been seen to
 *    change (a pinned stream that never advances is therefore never trusted);
 *  - a raw change materially off the timeline is a discontinuity: it is reported (and re-anchored provisionally), never absorbed;
 *  - a stream without a credible refresh within [NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS] is expired;
 *  - the projection never exceeds a known positive duration.
 */
internal class NaturalHandoffPositionClock {
    private var anchored = false
    private var confirmed = false
    private var anchorRawMs = 0L
    private var anchorAtMs = 0L
    private var lastRawMs = 0L
    private var lastConfirmAtMs = 0L
    private var durationMs = 0L

    /** True while this clock holds any anchor (it must be cleared when the owning transition ends). */
    val hasAnchor: Boolean get() = anchored

    /** Drops the anchor: used for a real seek of the stream and whenever natural-handoff ownership ends. */
    fun invalidate() {
        anchored = false
        confirmed = false
        anchorRawMs = 0L
        anchorAtMs = 0L
        lastRawMs = 0L
        lastConfirmAtMs = 0L
        durationMs = 0L
    }

    /** Feeds one raw sample taken at the shared monotonic [nowMs]. [mediaDurationMs] <= 0 means unknown. */
    fun observe(rawMs: Long, nowMs: Long, mediaDurationMs: Long): PositionSampleDecision {
        if (rawMs < 0L || (mediaDurationMs > 0L && rawMs > mediaDurationMs)) {
            invalidate()
            return PositionSampleDecision.Invalid
        }
        durationMs = mediaDurationMs
        if (!anchored) {
            anchor(rawMs, nowMs, confirmedNow = false)
            return PositionSampleDecision.Anchored
        }
        if (rawMs == lastRawMs) return PositionSampleDecision.Stale
        val expected = projectedUnchecked(nowMs)
        val delta = rawMs - expected
        if (confirmed) {
            return when {
                delta > NATURAL_HANDOFF_RAW_AGREEMENT_MS -> reanchorProvisional(rawMs, nowMs, PositionSampleDecision.ForwardDiscontinuity)
                delta < -NATURAL_HANDOFF_RAW_AGREEMENT_MS -> reanchorProvisional(rawMs, nowMs, PositionSampleDecision.BackwardDiscontinuity)
                else -> {
                    // forward-only: a slightly stale raw keeps the (later) projection, a plausible refresh moves it up to the raw
                    anchor(maxOf(expected, rawMs), nowMs, confirmedNow = true)
                    lastRawMs = rawMs
                    PositionSampleDecision.Refreshed
                }
            }
        }
        // Unconfirmed: the first raw CHANGE calibrates the (possibly stale) provisional anchor; only a gross jump is a discontinuity.
        return when {
            delta > NATURAL_TAKEOVER_MAX_LAG_MS -> reanchorProvisional(rawMs, nowMs, PositionSampleDecision.ForwardDiscontinuity)
            delta < -NATURAL_TAKEOVER_MAX_LAG_MS -> reanchorProvisional(rawMs, nowMs, PositionSampleDecision.BackwardDiscontinuity)
            else -> {
                anchor(rawMs, nowMs, confirmedNow = true)
                PositionSampleDecision.Calibrated
            }
        }
    }

    /** The projected position at [nowMs], or null without an anchor. Monotonic in [nowMs]; capped at a known duration. */
    fun projectedMs(nowMs: Long): Long? = if (anchored) projectedUnchecked(nowMs) else null

    /** Milliseconds since the last credible raw confirmation (null without an anchor). */
    fun ageMs(nowMs: Long): Long? = if (anchored) (nowMs - lastConfirmAtMs).coerceAtLeast(0L) else null

    /** True only for an anchored, calibrated stream whose last credible refresh is within the maximum projection age. */
    fun isConfident(nowMs: Long): Boolean =
        anchored && confirmed && (nowMs - lastConfirmAtMs).coerceAtLeast(0L) <= NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS

    /** True for a calibrated stream whose projection outlived the maximum age without a credible raw refresh. */
    fun isExpired(nowMs: Long): Boolean =
        anchored && confirmed && (nowMs - lastConfirmAtMs).coerceAtLeast(0L) > NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS

    private fun projectedUnchecked(nowMs: Long): Long {
        val elapsed = (nowMs - anchorAtMs).coerceAtLeast(0L) // a backward monotonic reading can never create negative advancement
        val projected = anchorRawMs + elapsed
        return if (durationMs > 0L) minOf(projected, durationMs) else projected
    }

    private fun anchor(positionMs: Long, nowMs: Long, confirmedNow: Boolean) {
        anchored = true
        confirmed = confirmedNow
        anchorRawMs = positionMs
        anchorAtMs = nowMs
        lastRawMs = positionMs
        lastConfirmAtMs = nowMs
    }

    private fun reanchorProvisional(rawMs: Long, nowMs: Long, decision: PositionSampleDecision): PositionSampleDecision {
        anchor(rawMs, nowMs, confirmedNow = false)
        return decision
    }
}
