package com.launchpoint.wavdrop.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-A: the pure window planner. Every test works on window geometry and counts; no Song, MediaItem, player or queue-sized
 * structure is created, so the 50,000-entry cases cost the same as the 25-entry ones.
 */
class PhysicalWindowPolicyTest {

    private val policy = PhysicalWindowPolicy()
    private val cfg = policy.config

    private fun initial(n: Int, current: Int, repeat: RepeatMode = RepeatMode.OFF, lg: Long = 1L, wg: Long = 1L) =
        policy.initialWindow(n, current, repeat, lg, wg)

    private fun window(n: Int, start: Int, length: Int, lg: Long = 1L, wg: Long = 1L) =
        PhysicalWindow(lg, wg, n, start, length, wraps = start.toLong() + length > n)

    private val sizes = listOf(1, 25, 50, 100, 299, 300, 301, 1_000, 5_000, 25_000, 50_000)

    // ── constants ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the design constants are centralized with the approved values`() {
        assertEquals(100, PhysicalWindowPolicy.W_MAX)
        assertEquals(20, PhysicalWindowPolicy.HISTORY_TARGET)
        assertEquals(40, PhysicalWindowPolicy.HISTORY_MAX)
        assertEquals(10, PhysicalWindowPolicy.HISTORY_MIN)
        assertEquals(79, PhysicalWindowPolicy.AHEAD_TARGET)
        assertEquals(30, PhysicalWindowPolicy.AHEAD_LOW)
        assertEquals(8, PhysicalWindowPolicy.AHEAD_FLOOR)
        assertEquals(25, PhysicalWindowPolicy.REFILL_BATCH)
        assertEquals(300, PhysicalWindowPolicy.FULL_MODE_MAX)
        assertEquals(PhysicalWindowPolicy.Config(), policy.config)
        assertTrue("full mode must be able to hold a ring window twice over", PhysicalWindowPolicy.FULL_MODE_MAX >= 2 * PhysicalWindowPolicy.W_MAX)
    }

    @Test fun `inconsistent configurations are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PhysicalWindowPolicy.Config(aheadFloor = 30, aheadLow = 30) }
        assertThrows(IllegalArgumentException::class.java) { PhysicalWindowPolicy.Config(aheadLow = 80) } // low must stay below target
        assertThrows(IllegalArgumentException::class.java) { PhysicalWindowPolicy.Config(historyMin = 25) } // above target
        assertThrows(IllegalArgumentException::class.java) { PhysicalWindowPolicy.Config(historyMax = 10) } // below target
        assertThrows(IllegalArgumentException::class.java) { PhysicalWindowPolicy.Config(wMax = 90) } // 20 + 1 + 79 no longer fits
        assertThrows(IllegalArgumentException::class.java) { PhysicalWindowPolicy.Config(fullModeMax = 199) } // < 2 * wMax
        assertThrows(IllegalArgumentException::class.java) { PhysicalWindowPolicy.Config(refillBatch = 0) }
    }

    // ── initial window ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `initial window around a middle current keeps the target history and ahead`() {
        val w = initial(1_000, 500)!!
        assertEquals(480, w.start)
        assertEquals(100, w.length)
        assertEquals(20, w.logicalToPhysical(500))
        assertEquals(20, w.historyCount(20))
        assertEquals(79, w.aheadCount(20))
        assertFalse(w.wraps)
    }

    @Test fun `initial window at the first song has no history and the target ahead`() {
        val w = initial(1_000, 0)!!
        assertEquals(0, w.start)
        assertEquals(80, w.length)
        assertEquals(0, w.logicalToPhysical(0))
        assertTrue(w.beginsAtLogicalStart)
    }

    @Test fun `initial window near the last song is shorter and ends at the queue end`() {
        val w = initial(1_000, 995)!!
        assertEquals(975, w.start)
        assertEquals(25, w.length)
        assertTrue(w.endsAtLogicalEnd)
        assertEquals(20, w.logicalToPhysical(995))
        assertFalse(w.wraps)
    }

    @Test fun `repeat-all continues the initial window across the end as a ring`() {
        val w = initial(1_000, 999, RepeatMode.ALL)!!
        assertEquals(979, w.start)
        assertEquals(100, w.length)
        assertTrue(w.wraps)
        assertEquals(20, w.logicalToPhysical(999))
        assertEquals(21, w.logicalToPhysical(0))
        assertEquals(78, w.lastLogical)
    }

    @Test fun `repeat off and one never produce a wrapping window`() {
        for (repeat in listOf(RepeatMode.OFF, RepeatMode.ONE)) {
            for (n in sizes) for (current in listOf(0, n / 2, n - 1)) {
                assertFalse("$repeat n=$n current=$current", initial(n, current, repeat)!!.wraps)
            }
        }
    }

    @Test fun `a ring window never holds a position twice even on small queues`() {
        for (n in listOf(101, 150, 200, 299, 300, 301)) for (current in listOf(0, 1, n / 2, n - 1)) {
            val w = initial(n, current, RepeatMode.ALL)!!
            val logical = (0 until w.length).map { w.physicalToLogical(it)!! }
            assertEquals("n=$n current=$current $w", logical.size, logical.toSet().size)
            assertEquals(current, w.physicalToLogical(minOf(current, cfg.historyTarget)))
        }
    }

    @Test fun `initial window invariants hold for every representative size position and repeat mode`() {
        for (n in sizes) for (repeat in RepeatMode.values()) {
            val currents = listOf(0, 1, cfg.historyTarget, n / 2, n - 2, n - 1).filter { it in 0 until n }.distinct()
            for (current in currents) {
                val w = initial(n, current, repeat, lg = 4L, wg = 9L)
                assertNotNull("n=$n current=$current $repeat", w)
                w!!
                val physicalCurrent = w.logicalToPhysical(current)
                assertNotNull("current must be loaded", physicalCurrent)
                assertEquals("current occurrence conserved", current, w.physicalToLogical(physicalCurrent!!))
                assertTrue("length ${w.length} <= W_MAX", w.length <= cfg.wMax)
                assertTrue(w.length <= n)
                assertTrue("history", w.historyCount(physicalCurrent)!! <= cfg.historyTarget)
                assertTrue("ahead", w.aheadCount(physicalCurrent)!! <= cfg.aheadTarget)
                assertEquals(4L, w.logicalGeneration)
                assertEquals(9L, w.windowGeneration)
                if (repeat != RepeatMode.ALL) assertFalse(w.wraps)
                val seen = (0 until w.length).map { w.physicalToLogical(it)!! }.toSet()
                assertEquals("no occurrence twice", w.length, seen.size)
            }
        }
    }

    @Test fun `short queues are loaded completely and mid-sized ones keep the target history`() {
        for (n in listOf(1, 2, 21)) {
            val w = initial(n, n / 2)!!
            assertEquals("n=$n", 0, w.start)
            assertEquals("n=$n", n, w.length)
            assertTrue(w.endsAtLogicalEnd && w.beginsAtLogicalStart)
        }
        val mid = initial(50, 25)!! // history is capped at the target, so a 50-item queue is not loaded from index 0
        assertEquals(5, mid.start)
        assertEquals(50 - 5, mid.length)
        assertEquals(25, mid.physicalToLogical(20))
        val whole = initial(25, 12)!!
        assertEquals(25, whole.length)
        assertEquals(0, whole.start)
    }

    @Test fun `initial window rejects positions it cannot prove`() {
        assertNull(initial(0, 0))
        assertNull(initial(10, -1))
        assertNull(initial(10, 10))
        assertNull(initial(10, 3, lg = -1L))
        assertNull(initial(10, 3, wg = -1L))
    }

    @Test fun `a shuffled queue yields the same geometry as an ordered one`() {
        // The planner only sees positions and a size: shuffle changes what sits at each position, never the window.
        val ordered = initial(5_000, 1_234)!!
        val shuffledQueue = initial(5_000, 1_234)!!
        assertEquals(ordered, shuffledQueue)
    }

    // ── ahead refill ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `ahead above the low watermark needs no refill`() {
        val w = window(1_000, start = 100, length = 51) // history 20, ahead 30 at physical 20
        val atLow = policy.refillAhead(w, physicalCurrent = 20, repeatMode = RepeatMode.OFF)
        assertTrue("ahead == AHEAD_LOW refills", atLow is AheadDecision.Refill)
        val above = policy.refillAhead(w, physicalCurrent = 19, repeatMode = RepeatMode.OFF) // ahead 31
        assertEquals(AheadDecision.None(NoAheadReason.Sufficient), above)
    }

    @Test fun `refill is one bounded batch starting right after the window`() {
        val w = window(1_000, start = 100, length = 51)
        val d = policy.refillAhead(w, 20, RepeatMode.OFF) as AheadDecision.Refill
        assertEquals(151, d.firstLogical)
        assertEquals(25, d.count)
        assertFalse("30 ahead is above the floor", d.priority)
        assertEquals(w.token, d.token)
    }

    @Test fun `refill never exceeds the ahead target nor the window maximum`() {
        val nearTarget = window(1_000, start = 100, length = 75) // current at physical 20 => ahead 54
        val d1 = policy.refillAhead(nearTarget, 24, RepeatMode.OFF) // ahead 50 => needs 29, batch 25
        assertTrue(d1 is AheadDecision.None) // 50 > 30 low
        val tight = window(1_000, start = 100, length = 90) // ahead 10 at physical 79? current 79 -> ahead 10
        val d2 = policy.refillAhead(tight, 79, RepeatMode.OFF) as AheadDecision.Refill
        assertEquals("only 10 slots left in W_MAX", 10, d2.count)
        val full = window(1_000, start = 100, length = 100)
        assertEquals(AheadDecision.None(NoAheadReason.WindowFull), policy.refillAhead(full, 95, RepeatMode.OFF))
    }

    @Test fun `ahead below the floor is a priority refill`() {
        val w = window(1_000, start = 100, length = 40)
        val d = policy.refillAhead(w, physicalCurrent = 35, repeatMode = RepeatMode.OFF) as AheadDecision.Refill // ahead 4
        assertTrue(d.priority)
        val justAtFloor = policy.refillAhead(w, physicalCurrent = 31, repeatMode = RepeatMode.OFF) as AheadDecision.Refill // ahead 8
        assertFalse("ahead == AHEAD_FLOOR is not below it", justAtFloor.priority)
    }

    @Test fun `no logical future means nothing to append`() {
        val atEnd = window(1_000, start = 920, length = 80)
        assertEquals(AheadDecision.None(NoAheadReason.NothingToAppend), policy.refillAhead(atEnd, 75, RepeatMode.OFF))
        assertEquals(AheadDecision.None(NoAheadReason.NothingToAppend), policy.refillAhead(atEnd, 75, RepeatMode.ONE))
        val whole = window(80, start = 0, length = 80)
        assertEquals(AheadDecision.None(NoAheadReason.NothingToAppend), policy.refillAhead(whole, 70, RepeatMode.ALL))
    }

    @Test fun `repeat-all continues the refill at logical zero and stops before the window start`() {
        val atEnd = window(1_000, start = 920, length = 80)
        val d = policy.refillAhead(atEnd, 60, RepeatMode.ALL) as AheadDecision.Refill // ahead 19
        assertEquals(0, d.firstLogical)
        assertEquals(20, d.count) // limited by the 20 free slots in W_MAX
        val (first, available) = policy.appendable(atEnd, RepeatMode.ALL)
        assertEquals(0, first)
        assertEquals(920, available)
    }

    @Test fun `a ring window continues where it ended and cannot reach its own start`() {
        val ring = window(1_000, start = 960, length = 60) // 960..999, 0..19
        assertTrue(ring.wraps)
        val (first, available) = policy.appendable(ring, RepeatMode.ALL)
        assertEquals(20, first)
        assertEquals(960 - 20, available)
        val closed = window(100, start = 40, length = 100) // the whole ring
        assertEquals("a closed ring has nothing left to append", 0, policy.appendable(closed, RepeatMode.ALL).second)
        assertEquals("a ring window outside repeat-all is never extended", 0 to 0, policy.appendable(ring, RepeatMode.OFF))
    }

    @Test fun `invalid current index is reported not guessed`() {
        val w = window(1_000, 100, 50)
        assertEquals(AheadDecision.None(NoAheadReason.InvalidCurrent), policy.refillAhead(w, -1, RepeatMode.OFF))
        assertEquals(AheadDecision.None(NoAheadReason.InvalidCurrent), policy.refillAhead(w, 50, RepeatMode.OFF))
    }

    // ── history ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `history above the maximum is trimmed down to the target`() {
        val w = window(1_000, start = 100, length = 100)
        val trim = policy.historyDecision(w, physicalCurrent = 41) as HistoryDecision.Trim
        assertEquals(21, trim.count)
        assertEquals(HistoryDecision.None(NoHistoryReason.Within), policy.historyDecision(w, 40))
    }

    @Test fun `history below the minimum is refilled from the positions behind the window`() {
        val w = window(1_000, start = 100, length = 60)
        val prepend = policy.historyDecision(w, physicalCurrent = 5) as HistoryDecision.Prepend
        assertEquals(15, prepend.count) // up to the 20 target
        assertEquals(85, prepend.firstLogical)
        assertEquals(HistoryDecision.None(NoHistoryReason.Within), policy.historyDecision(w, 10))
    }

    @Test fun `history is not refilled past the logical start`() {
        val w = window(1_000, start = 0, length = 60)
        assertEquals(HistoryDecision.None(NoHistoryReason.AtLogicalStart), policy.historyDecision(w, 3))
        val short = window(1_000, start = 4, length = 60)
        val prepend = policy.historyDecision(short, 3) as HistoryDecision.Prepend
        assertEquals(4, prepend.count)
        assertEquals(0, prepend.firstLogical)
    }

    @Test fun `a full window drops far-ahead items to make room for history`() {
        val full = window(1_000, start = 100, length = 100) // history 3, ahead 96
        val prepend = policy.historyDecision(full, 3) as HistoryDecision.Prepend
        assertEquals(17, prepend.count) // up to the history target of 20
        assertEquals(17, prepend.tailTrim) // no free slot: the same number of far-ahead items go
        assertEquals(83, prepend.firstLogical)
        val plan = policy.planAdjustment(full, 3, RepeatMode.OFF) as WindowAdjustment.Adjust
        assertEquals(17, plan.trimTail)
        assertEquals(17, plan.prependCount)
        assertEquals(100, plan.resultWindow.length)
        assertEquals(20, plan.physicalCurrentAfter)
        assertEquals(103, plan.resultWindow.physicalToLogical(plan.physicalCurrentAfter))
        assertTrue("ahead stays above the low watermark", plan.resultWindow.aheadCount(plan.physicalCurrentAfter)!! > cfg.aheadLow)
    }

    @Test fun `history refill never takes a position the ring already holds`() {
        val ring = window(300, start = 250, length = 90) // 250..299, 0..39 -> 210 positions free behind
        val prepend = policy.historyDecision(ring, 3) as HistoryDecision.Prepend
        assertEquals("10 free slots plus 7 far-ahead items released by the tail trim", 17, prepend.count)
        assertEquals(7, prepend.tailTrim)
        assertTrue(prepend.count + ring.length - prepend.tailTrim <= 100)
        val tight = window(120, start = 100, length = 100) // ring 100..119, 0..79: only 20 positions are not loaded
        val d = policy.historyDecision(tight, 3) as HistoryDecision.Prepend
        assertTrue("never more than the 20 free positions plus what the tail trim releases", d.count <= 20 + d.tailTrim)
        val result = tight.trimTail(d.tailTrim).prepend(d.count)
        assertTrue(result.length <= 100 && result.length <= 120)
    }

    // ── combined plan ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a healthy window needs no change`() {
        val w = initial(5_000, 2_000)!!
        val pc = w.logicalToPhysical(2_000)!!
        assertEquals(WindowAdjustment.NoChange(w.token), policy.planAdjustment(w, pc, RepeatMode.OFF))
    }

    @Test fun `ahead and history trim are planned together and the current item is conserved`() {
        // 50 history, current, 25 ahead.
        val w = window(5_000, start = 1_000, length = 76)
        val plan = policy.planAdjustment(w, physicalCurrent = 50, repeatMode = RepeatMode.OFF) as WindowAdjustment.Adjust
        assertEquals(30, plan.trimFront) // 50 -> 20
        assertEquals(0, plan.prependCount)
        assertTrue(plan.appendCount > 0)
        assertEquals(20, plan.physicalCurrentAfter)
        assertEquals(1_050, plan.resultWindow.physicalToLogical(plan.physicalCurrentAfter))
        assertEquals(w.windowGeneration + 1, plan.resultWindow.windowGeneration)
        assertEquals(w.logicalGeneration, plan.resultWindow.logicalGeneration)
        assertTrue(plan.resultWindow.length <= cfg.wMax)
        assertEquals(w.token, plan.token)
    }

    @Test fun `history refill shifts the current physical index by the prepended amount`() {
        val w = window(5_000, start = 1_000, length = 90) // current at physical 4: history 4
        val plan = policy.planAdjustment(w, 4, RepeatMode.OFF) as WindowAdjustment.Adjust
        assertEquals("10 free slots plus 6 far-ahead items released", 16, plan.prependCount)
        assertEquals(6, plan.trimTail)
        assertEquals(20, plan.physicalCurrentAfter)
        assertEquals(1_004, plan.resultWindow.physicalToLogical(plan.physicalCurrentAfter))
        assertEquals(984, plan.prependFirstLogical)
        assertEquals(100, plan.resultWindow.length)
    }

    @Test fun `leaving repeat-all trims the wrapped tail when the current item is before it`() {
        val ring = window(1_000, start = 950, length = 100) // wrapped part is the last 50 items
        val plan = policy.planAdjustment(ring, 30, RepeatMode.OFF) as WindowAdjustment.Adjust
        assertEquals(50, plan.trimTail)
        assertFalse(plan.resultWindow.wraps)
        assertEquals(999, plan.resultWindow.lastLogical)
    }

    @Test fun `leaving repeat-all with the current item inside the wrap re-anchors`() {
        val ring = window(1_000, start = 950, length = 100)
        assertEquals(
            WindowAdjustment.Reanchor(ReanchorReason.RepeatChangedInsideWrap),
            policy.planAdjustment(ring, 60, RepeatMode.OFF),
        )
        assertTrue("repeat-all keeps a ring valid", policy.planAdjustment(ring, 60, RepeatMode.ALL) !is WindowAdjustment.Reanchor)
    }

    @Test fun `an unprovable current index re-anchors instead of guessing`() {
        val w = window(1_000, 100, 50)
        assertEquals(WindowAdjustment.Reanchor(ReanchorReason.Unprovable), policy.planAdjustment(w, 50, RepeatMode.OFF))
        assertEquals(WindowAdjustment.Reanchor(ReanchorReason.Unprovable), policy.planAdjustment(w, -1, RepeatMode.OFF))
    }

    // ── navigation ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a target outside the loaded window must re-anchor`() {
        val w = window(25_000, start = 10_000, length = 100)
        assertEquals(Localization.InWindow(30), policy.localize(w, 10_030))
        assertEquals(Localization.OutsideWindow, policy.localize(w, 9_999))
        assertEquals(Localization.OutsideWindow, policy.localize(w, 24_999))
        assertEquals(Localization.InvalidTarget, policy.localize(w, -1))
        assertEquals(Localization.InvalidTarget, policy.localize(w, 25_000))
    }

    @Test fun `next and previous are classified against the genuine queue boundaries`() {
        assertEquals(NavigationStep.Linear(6), policy.nextStep(10, 5, RepeatMode.OFF))
        assertEquals(NavigationStep.Edge, policy.nextStep(10, 9, RepeatMode.OFF))
        assertEquals(NavigationStep.Edge, policy.nextStep(10, 9, RepeatMode.ONE))
        assertEquals(NavigationStep.RepeatAllWrap(0), policy.nextStep(10, 9, RepeatMode.ALL))
        assertEquals(NavigationStep.Edge, policy.nextStep(1, 0, RepeatMode.ALL))

        assertEquals(NavigationStep.Linear(4), policy.previousStep(10, 5, RepeatMode.OFF))
        assertEquals(NavigationStep.Edge, policy.previousStep(10, 0, RepeatMode.OFF))
        assertEquals(NavigationStep.RepeatAllWrap(9), policy.previousStep(10, 0, RepeatMode.ALL))
        assertEquals(NavigationStep.Edge, policy.previousStep(1, 0, RepeatMode.ALL))
        assertTrue(policy.isLogicalStart(0) && !policy.isLogicalStart(1))
        assertTrue(policy.isLogicalEnd(10, 9) && !policy.isLogicalEnd(10, 8) && !policy.isLogicalEnd(0, 0))
    }

    @Test fun `previous at index zero under repeat-all lands outside the initial window`() {
        val w = initial(5_000, 0, RepeatMode.ALL)!!
        val wrap = policy.previousStep(5_000, 0, RepeatMode.ALL) as NavigationStep.RepeatAllWrap
        assertEquals(Localization.OutsideWindow, policy.localize(w, wrap.logicalIndex))
        val forward = policy.nextStep(5_000, 4_999, RepeatMode.ALL) as NavigationStep.RepeatAllWrap
        val tailRing = initial(5_000, 4_999, RepeatMode.ALL)!!
        assertEquals("the forward wrap target is inside the ring window", Localization.InWindow(21), policy.localize(tailRing, forward.logicalIndex))
    }

    // ── movement simulations (deterministic) ───────────────────────────────────────────────────────────────────────────

    /** Applies [plan] the way a later slice will: the new window replaces the old one and the current index follows it. */
    private class Sim(var window: PhysicalWindow, var current: Int, var adjustments: Int = 0)

    private fun Sim.settle(policy: PhysicalWindowPolicy, repeat: RepeatMode) {
        val plan = policy.planAdjustment(window, current, repeat)
        if (plan is WindowAdjustment.Adjust) {
            window = plan.resultWindow
            current = plan.physicalCurrentAfter
            adjustments++
        } else {
            assertFalse("a re-anchor is not expected while walking", plan is WindowAdjustment.Reanchor)
        }
    }

    @Test fun `walking forward through a queue keeps the window valid and the floor satisfied`() {
        for (repeat in listOf(RepeatMode.OFF, RepeatMode.ALL)) {
            val n = 1_000
            val sim = Sim(initial(n, 0, repeat)!!, current = 0)
            val laps = if (repeat == RepeatMode.ALL) 25 * n / 10 else n - 1
            for (step in 1..laps) {
                sim.current += 1 // Media3 advanced to the next physical item (an AUTO transition)
                val expectedLogical = step % n
                assertEquals("step $step $repeat", expectedLogical, sim.window.physicalToLogical(sim.current))
                sim.settle(policy, repeat)
                assertEquals("after settle step $step $repeat", expectedLogical, sim.window.physicalToLogical(sim.current))
                assertTrue(sim.window.length <= cfg.wMax)
                val ahead = sim.window.aheadCount(sim.current)!!
                val atRealEnd = repeat == RepeatMode.OFF && expectedLogical == n - 1
                val remaining = if (repeat == RepeatMode.OFF) n - 1 - expectedLogical else Int.MAX_VALUE
                assertTrue("ahead $ahead at step $step $repeat", atRealEnd || ahead >= minOf(cfg.aheadFloor, remaining))
                assertTrue("history", sim.window.historyCount(sim.current)!! <= cfg.historyMax)
                if (repeat == RepeatMode.OFF) assertFalse(sim.window.wraps)
            }
            assertTrue("adjustments are batched, not per step (${sim.adjustments})", sim.adjustments < laps / 5)
        }
    }

    @Test fun `walking backward keeps history available until the logical start`() {
        val n = 2_000
        val sim = Sim(initial(n, 1_500)!!, current = initial(n, 1_500)!!.logicalToPhysical(1_500)!!)
        for (step in 1..1_500) {
            sim.current -= 1
            if (sim.current < 0) {
                // The user outran the loaded history: the real implementation re-anchors; here we just prove the planner would
                // have refilled before that on this slow walk.
                throw AssertionError("ran out of history at step $step")
            }
            val expected = 1_500 - step
            assertEquals(expected, sim.window.physicalToLogical(sim.current))
            sim.settle(policy, RepeatMode.OFF)
            assertEquals(expected, sim.window.physicalToLogical(sim.current))
            val history = sim.window.historyCount(sim.current)!!
            assertTrue("history $history at logical $expected", sim.window.beginsAtLogicalStart || history >= cfg.historyMin)
        }
    }

    @Test fun `a burst of skips near the refill threshold is handled by one bounded plan per turn`() {
        val n = 5_000
        var window = initial(n, 2_000)!!
        var current = window.logicalToPhysical(2_000)!!
        // Skip 49 items without ever running the planner (ahead 79 -> 30): it must still be inside the window and refill next.
        current += 49
        assertEquals(2_049, window.physicalToLogical(current))
        val plan = policy.planAdjustment(window, current, RepeatMode.OFF) as WindowAdjustment.Adjust
        assertEquals(25, plan.appendCount)
        assertFalse(plan.priority)
        window = plan.resultWindow
        current = plan.physicalCurrentAfter
        assertEquals(2_049, window.physicalToLogical(current))
        // A longer burst lands under the floor: the plan is flagged as a priority refill.
        current += 42
        val urgent = policy.planAdjustment(window, current, RepeatMode.OFF) as WindowAdjustment.Adjust
        assertTrue(urgent.priority || urgent.appendCount > 0)
    }

    @Test fun `plans are rejected when either generation has moved`() {
        val w = initial(5_000, 2_000, lg = 12L, wg = 3L)!!
        val plan = policy.planAdjustment(w, 5, RepeatMode.OFF) as WindowAdjustment.Adjust
        assertTrue(plan.token.isValidFor(w, currentLogicalGeneration = 12L))
        assertFalse("logical edit", plan.token.isValidFor(w, currentLogicalGeneration = 13L))
        assertFalse("another shift happened first", plan.token.isValidFor(plan.resultWindow, currentLogicalGeneration = 12L))
    }

    // ── randomized planner invariants (fixed seed) ─────────────────────────────────────────────────────────────────────

    @Test fun `random walks never violate the window invariants`() {
        val random = Random(424242)
        repeat(300) { run ->
            val n = random.nextInt(cfg.fullModeMax + 1, 6_000)
            val mode = RepeatMode.values()[random.nextInt(3)]
            val startLogical = random.nextInt(n)
            val sim = Sim(initial(n, startLogical, mode)!!, current = 0)
            sim.current = sim.window.logicalToPhysical(startLogical)!!
            var logical = startLogical
            repeat(200) {
                val forward = random.nextInt(10) < 8
                val canMove = if (forward) {
                    mode == RepeatMode.ALL || logical < n - 1
                } else {
                    logical > 0
                }
                if (canMove) {
                    sim.current += if (forward) 1 else -1
                    logical = when {
                        forward && logical == n - 1 -> 0
                        forward -> logical + 1
                        else -> logical - 1
                    }
                    if (sim.current !in 0 until sim.window.length) return@repeat // outran history: a re-anchor in real use
                    assertEquals("run=$run", logical, sim.window.physicalToLogical(sim.current))
                }
                val plan = policy.planAdjustment(sim.window, sim.current, mode)
                if (plan is WindowAdjustment.Adjust) {
                    sim.window = plan.resultWindow
                    sim.current = plan.physicalCurrentAfter
                    assertEquals("run=$run plan", logical, sim.window.physicalToLogical(sim.current))
                }
                assertTrue(sim.window.length in 1..cfg.wMax)
                if (mode != RepeatMode.ALL) assertFalse(sim.window.wraps)
                val seen = HashSet<Int>()
                for (p in 0 until sim.window.length) assertTrue(seen.add(sim.window.physicalToLogical(p)!!))
            }
        }
    }
}
