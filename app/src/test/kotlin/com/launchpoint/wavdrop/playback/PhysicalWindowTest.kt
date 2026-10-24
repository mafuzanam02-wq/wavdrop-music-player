package com.launchpoint.wavdrop.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-A: exact mapping of [PhysicalWindow]. Pure arithmetic over positions: no Song, no MediaItem and no queue-sized structure is
 * ever built, so 50,000-entry queues cost the same as 5-entry ones.
 */
class PhysicalWindowTest {

    private fun window(
        queueSize: Int,
        start: Int,
        length: Int,
        wraps: Boolean = start.toLong() + length > queueSize,
        logicalGeneration: Long = 1L,
        windowGeneration: Long = 1L,
        maxLength: Int = PhysicalWindowPolicy.W_MAX,
    ) = PhysicalWindow(logicalGeneration, windowGeneration, queueSize, start, length, wraps, maxLength)

    private fun logicalPositions(w: PhysicalWindow): List<Int> = (0 until w.length).map { w.physicalToLogical(it)!! }

    // ── exact mapping ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `window at the logical start maps physical i to logical i`() {
        val w = window(queueSize = 1_000, start = 0, length = 80)
        assertEquals((0 until 80).toList(), logicalPositions(w))
        assertTrue(w.beginsAtLogicalStart)
        assertFalse(w.endsAtLogicalEnd)
        assertFalse(w.wraps)
    }

    @Test fun `window ending at the last logical position`() {
        val w = window(queueSize = 1_000, start = 900, length = 100)
        assertEquals((900..999).toList(), logicalPositions(w))
        assertEquals(999, w.lastLogical)
        assertTrue(w.endsAtLogicalEnd)
        assertFalse(w.wraps)
        assertNull("no wrap for a non-wrapping window", w.logicalToPhysical(0))
    }

    @Test fun `single item window`() {
        val w = window(queueSize = 1_000, start = 500, length = 1)
        assertEquals(500, w.physicalToLogical(0))
        assertNull(w.physicalToLogical(1))
        assertEquals(0, w.logicalToPhysical(500))
        assertNull(w.logicalToPhysical(499))
        assertNull(w.logicalToPhysical(501))
        assertEquals(500, w.firstLogical)
        assertEquals(500, w.lastLogical)
    }

    @Test fun `whole queue window`() {
        val w = window(queueSize = 40, start = 0, length = 40)
        assertEquals((0 until 40).toList(), logicalPositions(w))
        assertTrue(w.beginsAtLogicalStart)
        assertTrue(w.endsAtLogicalEnd)
        assertFalse(w.wraps)
    }

    @Test fun `arbitrary middle window`() {
        val w = window(queueSize = 25_000, start = 12_345, length = 100)
        assertEquals(12_345, w.physicalToLogical(0))
        assertEquals(12_444, w.physicalToLogical(99))
        assertEquals(50, w.logicalToPhysical(12_395))
        assertNull(w.logicalToPhysical(12_344))
        assertNull(w.logicalToPhysical(12_445))
        assertFalse(w.beginsAtLogicalStart)
        assertFalse(w.endsAtLogicalEnd)
    }

    @Test fun `repeat-all wrap crosses the end exactly once`() {
        val w = window(queueSize = 100, start = 99, length = 2)
        assertTrue(w.wraps)
        assertEquals(99, w.physicalToLogical(0))
        assertEquals(0, w.physicalToLogical(1))
        assertEquals(0, w.logicalToPhysical(99))
        assertEquals(1, w.logicalToPhysical(0))
        assertNull(w.logicalToPhysical(1))
        assertNull(w.logicalToPhysical(98))
        assertEquals(0, w.lastLogical)
    }

    @Test fun `wrapping window maps both sides of the boundary`() {
        val w = window(queueSize = 1_000, start = 950, length = 100)
        assertTrue(w.wraps)
        assertEquals((950..999).toList() + (0..49).toList(), logicalPositions(w))
        assertEquals(49, w.lastLogical)
        assertEquals(50, w.logicalToPhysical(0))
        assertEquals(99, w.logicalToPhysical(49))
        assertNull(w.logicalToPhysical(50))
        assertNull(w.logicalToPhysical(949))
        assertFalse(w.beginsAtLogicalStart)
        assertFalse(w.endsAtLogicalEnd)
    }

    @Test fun `a ring window covering the whole queue holds every position once`() {
        val w = window(queueSize = 60, start = 17, length = 60)
        assertTrue(w.wraps)
        assertEquals((0 until 60).toList(), logicalPositions(w).sorted())
        assertEquals(60, logicalPositions(w).toSet().size)
    }

    @Test fun `mapping is by position so duplicate songs at different positions stay distinct`() {
        // Songs [A, B, A, B, A] would be ids [1,2,1,2,1]; the window never sees an id, only positions.
        val w = window(queueSize = 5, start = 0, length = 5)
        val ids = longArrayOf(1, 2, 1, 2, 1)
        val physicalOfThirdPosition = w.logicalToPhysical(2)
        val physicalOfFirstPosition = w.logicalToPhysical(0)
        assertEquals(2, physicalOfThirdPosition)
        assertEquals(0, physicalOfFirstPosition)
        assertEquals("same song id", ids[0], ids[2])
        assertNotEquals("different occurrences keep different physical slots", physicalOfFirstPosition, physicalOfThirdPosition)
    }

    @Test fun `a shuffled queue is just a different order of the same positions`() {
        // A shuffled playback order is a permutation of library indices; the window indexes PLAYBACK positions, so the window
        // geometry is identical for any permutation.
        val order = (0 until 300).shuffled(Random(7))
        val w = window(queueSize = 300, start = 120, length = 50)
        (0 until 50).forEach { p ->
            val playbackPosition = w.physicalToLogical(p)!!
            assertEquals(120 + p, playbackPosition)
            assertTrue(order[playbackPosition] in 0 until 300)
        }
    }

    // ── out-of-range lookups ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `out of window and invalid indices resolve to null instead of wrapping`() {
        val w = window(queueSize = 1_000, start = 100, length = 50)
        for (bad in listOf(-1, 50, 51, 1_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertNull("physical $bad", w.physicalToLogical(bad))
        }
        for (bad in listOf(-1, 99, 150, 999, 1_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertNull("logical $bad", w.logicalToPhysical(bad))
            assertFalse(w.containsLogical(bad))
        }
        assertTrue(w.containsLogical(100))
        assertTrue(w.containsLogical(149))
    }

    @Test fun `index equal to the window length is not in the window`() {
        val w = window(queueSize = 500, start = 10, length = 30)
        assertNotNull(w.physicalToLogical(29))
        assertNull(w.physicalToLogical(30))
        assertNull(w.logicalToPhysical(40))
    }

    @Test fun `history and ahead counts follow the physical current index`() {
        val w = window(queueSize = 500, start = 10, length = 30)
        assertEquals(0, w.historyCount(0))
        assertEquals(29, w.aheadCount(0))
        assertEquals(12, w.historyCount(12))
        assertEquals(17, w.aheadCount(12))
        assertEquals(0, w.aheadCount(29))
        assertNull(w.historyCount(-1))
        assertNull(w.aheadCount(30))
    }

    // ── illegal states are rejected ────────────────────────────────────────────────────────────────────────────────────

    private fun assertRejected(message: String, block: () -> Unit) {
        assertThrows(message, IllegalArgumentException::class.java) { block() }
    }

    @Test fun `queue size zero or negative is rejected`() {
        assertRejected("size 0") { window(queueSize = 0, start = 0, length = 1, wraps = false) }
        assertRejected("size -1") { window(queueSize = -1, start = 0, length = 1, wraps = false) }
    }

    @Test fun `start beyond the queue or negative is rejected`() {
        assertRejected("start == size") { window(queueSize = 100, start = 100, length = 1, wraps = false) }
        assertRejected("start > size") { window(queueSize = 100, start = 500, length = 1, wraps = false) }
        assertRejected("start < 0") { window(queueSize = 100, start = -1, length = 1, wraps = false) }
    }

    @Test fun `length zero negative or beyond the queue is rejected`() {
        assertRejected("length 0") { window(queueSize = 100, start = 0, length = 0, wraps = false) }
        assertRejected("length -3") { window(queueSize = 100, start = 0, length = -3, wraps = false) }
        assertRejected("length > size") { window(queueSize = 50, start = 0, length = 51, wraps = false, maxLength = 1_000) }
    }

    @Test fun `length beyond the configured maximum is rejected`() {
        assertRejected("101 > W_MAX") { window(queueSize = 1_000, start = 0, length = 101, wraps = false) }
        assertRejected("custom maximum") { window(queueSize = 1_000, start = 0, length = 11, wraps = false, maxLength = 10) }
        window(queueSize = 1_000, start = 0, length = 10, wraps = false, maxLength = 10) // exactly the maximum is fine
    }

    @Test fun `an inconsistent wrapping flag is rejected in both directions`() {
        assertRejected("claims a wrap that does not happen") { window(queueSize = 100, start = 10, length = 20, wraps = true) }
        assertRejected("hides a wrap that happens") { window(queueSize = 100, start = 95, length = 10, wraps = false) }
        assertRejected("wrap flag on a window ending exactly at the end") { window(queueSize = 100, start = 90, length = 10, wraps = true) }
        window(queueSize = 100, start = 90, length = 10, wraps = false) // ends exactly at the end: no wrap
        window(queueSize = 100, start = 91, length = 10, wraps = true)
    }

    @Test fun `negative generations are rejected`() {
        assertRejected("logical") { window(queueSize = 10, start = 0, length = 1, logicalGeneration = -1L) }
        assertRejected("window") { window(queueSize = 10, start = 0, length = 1, windowGeneration = -1L) }
    }

    @Test fun `validate reports a precise reason and null for legal windows`() {
        assertNull(PhysicalWindow.validate(0, 0, 100, 0, 100, false, maxLength = 100))
        assertTrue(PhysicalWindow.validate(0, 0, 0, 0, 1, false)!!.contains("non-empty"))
        assertTrue(PhysicalWindow.validate(0, 0, 100, 100, 1, false)!!.contains("start="))
        assertTrue(PhysicalWindow.validate(0, 0, 100, 0, 101, false, maxLength = 200)!!.contains("exceeds queueSize"))
        assertTrue(PhysicalWindow.validate(0, 0, 1_000, 0, 101, false)!!.contains("maximum"))
        assertTrue(PhysicalWindow.validate(0, 0, 100, 95, 10, false)!!.contains("wraps"))
    }

    // ── integer boundaries ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `arithmetic never overflows near Int MAX_VALUE`() {
        val n = Int.MAX_VALUE
        val tail = window(queueSize = n, start = n - 50, length = 50)
        assertFalse(tail.wraps)
        assertEquals(n - 50, tail.physicalToLogical(0))
        assertEquals(n - 1, tail.physicalToLogical(49))
        assertEquals(49, tail.logicalToPhysical(n - 1))
        assertTrue(tail.endsAtLogicalEnd)

        val ring = window(queueSize = n, start = n - 10, length = 30)
        assertTrue("start + length exceeds Int.MAX_VALUE but is still detected as a wrap", ring.wraps)
        assertEquals(n - 10, ring.physicalToLogical(0))
        assertEquals(n - 1, ring.physicalToLogical(9))
        assertEquals(0, ring.physicalToLogical(10))
        assertEquals(19, ring.physicalToLogical(29))
        assertEquals(10, ring.logicalToPhysical(0))
        assertEquals(9, ring.logicalToPhysical(n - 1))
        assertNull(ring.logicalToPhysical(20))
        assertNull(ring.logicalToPhysical(n))

        val head = window(queueSize = n, start = 0, length = 100)
        assertEquals(99, head.physicalToLogical(99))
        assertTrue(head.beginsAtLogicalStart)
    }

    // ── generations and plan tokens ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a token is valid only for the exact window and logical revision`() {
        val w = window(queueSize = 1_000, start = 0, length = 50, logicalGeneration = 7L, windowGeneration = 3L)
        val token = w.token
        assertEquals(PlanToken(7L, 3L), token)
        assertTrue("matching generations accept", token.isValidFor(w, currentLogicalGeneration = 7L))
        assertFalse("changed logical generation rejects", token.isValidFor(w, currentLogicalGeneration = 8L))
        val shifted = w.withNextWindowGeneration()
        assertFalse("changed window generation rejects", token.isValidFor(shifted, currentLogicalGeneration = 7L))
        val edited = window(queueSize = 1_000, start = 0, length = 50, logicalGeneration = 8L, windowGeneration = 4L)
        assertFalse("both changed rejects", token.isValidFor(edited, currentLogicalGeneration = 8L))
        assertTrue("the successor accepts its own token", shifted.token.isValidFor(shifted, 7L))
    }

    @Test fun `tokens with invalid generations are never valid`() {
        val w = window(queueSize = 100, start = 0, length = 10, logicalGeneration = 0L, windowGeneration = 0L)
        assertFalse(PlanToken(-1L, 0L).isValidFor(w, 0L))
        assertFalse(PlanToken(0L, -1L).isValidFor(w, 0L))
        assertFalse("a negative live generation (a wrapped counter) accepts nothing", w.token.isValidFor(w, -1L))
        assertTrue(w.token.isValidFor(w, 0L))
    }

    @Test fun `the window generation never wraps silently`() {
        val last = window(queueSize = 100, start = 0, length = 10, windowGeneration = Long.MAX_VALUE)
        assertThrows(IllegalStateException::class.java) { last.nextWindowGeneration() }
        assertThrows(IllegalStateException::class.java) { last.withNextWindowGeneration() }
        val nearly = window(queueSize = 100, start = 0, length = 10, windowGeneration = Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, nearly.nextWindowGeneration())
        assertEquals(Long.MAX_VALUE, nearly.withNextWindowGeneration().windowGeneration)
    }

    @Test fun `a window shift changes the window generation but never the logical generation`() {
        val w = window(queueSize = 1_000, start = 100, length = 50, logicalGeneration = 5L, windowGeneration = 9L)
        val next = w.withNextWindowGeneration()
        assertEquals(5L, next.logicalGeneration)
        assertEquals(10L, next.windowGeneration)
        assertEquals(w.start, next.start)
        assertNotEquals(w, next)
        assertEquals(w, window(queueSize = 1_000, start = 100, length = 50, logicalGeneration = 5L, windowGeneration = 9L))
        assertEquals(w.hashCode(), window(queueSize = 1_000, start = 100, length = 50, logicalGeneration = 5L, windowGeneration = 9L).hashCode())
    }

    // ── geometry edits ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `trim prepend and append keep the mapping of the surviving positions`() {
        val w = window(queueSize = 1_000, start = 100, length = 60)
        val trimmed = w.trimFront(15)
        assertEquals(115, trimmed.start)
        assertEquals(45, trimmed.length)
        assertEquals(w.logicalToPhysical(130)!! - 15, trimmed.logicalToPhysical(130))

        val grown = w.append(25)
        assertEquals(85, grown.length)
        assertEquals(w.logicalToPhysical(130), grown.logicalToPhysical(130))
        assertEquals(159 + 25, grown.lastLogical)

        val before = w.prepend(20)
        assertEquals(80, before.start)
        assertEquals(80, before.length)
        assertEquals(w.logicalToPhysical(130)!! + 20, before.logicalToPhysical(130))

        val tail = w.trimTail(10)
        assertEquals(50, tail.length)
        assertEquals(149, tail.lastLogical)
    }

    @Test fun `append across the queue end produces a wrapping window and trimming front can unwrap it`() {
        val w = window(queueSize = 200, start = 150, length = 50) // ends exactly at the end
        val ring = w.append(10)
        assertTrue(ring.wraps)
        assertEquals(9, ring.lastLogical)
        val unwrapped = ring.trimFront(50) // physical 50.. is logical 0..9
        assertFalse(unwrapped.wraps)
        assertEquals(0, unwrapped.start)
        assertEquals(10, unwrapped.length)
    }

    @Test fun `edits that would create an illegal window are rejected`() {
        val w = window(queueSize = 1_000, start = 5, length = 50)
        assertThrows(IllegalArgumentException::class.java) { w.prepend(6) } // only 5 positions behind
        assertThrows(IllegalArgumentException::class.java) { w.trimFront(50) } // would remove the whole window
        assertThrows(IllegalArgumentException::class.java) { w.trimTail(0) }
        assertThrows(IllegalArgumentException::class.java) { w.append(51) } // 101 > W_MAX
        val small = window(queueSize = 60, start = 0, length = 60)
        assertThrows(IllegalArgumentException::class.java) { small.append(1) } // would hold a position twice
    }

    // ── deterministic property-style checks ────────────────────────────────────────────────────────────────────────────

    @Test fun `every valid window is injective round-trips and stays inside the queue`() {
        val random = Random(20260101)
        var checked = 0
        var wrapping = 0
        repeat(3_000) {
            val n = when (it % 4) {
                0 -> random.nextInt(1, 40)
                1 -> random.nextInt(1, 400)
                2 -> random.nextInt(1, 5_000)
                else -> random.nextInt(1, 50_001)
            }
            val length = random.nextInt(1, minOf(n, PhysicalWindowPolicy.W_MAX) + 1)
            val start = random.nextInt(0, n)
            val w = window(queueSize = n, start = start, length = length)
            checked++
            if (w.wraps) wrapping++

            val seen = HashSet<Int>(length * 2)
            for (p in 0 until length) {
                val logical = w.physicalToLogical(p)
                assertNotNull("n=$n start=$start length=$length p=$p", logical)
                assertTrue(logical!! in 0 until n)
                assertTrue("duplicate logical $logical in $w", seen.add(logical))
                assertEquals("round trip p=$p in $w", p, w.logicalToPhysical(logical))
                assertTrue(w.containsLogical(logical))
            }
            assertEquals(length, seen.size)
            assertNull(w.physicalToLogical(length))
            assertNull(w.physicalToLogical(-1))
            // Positions outside the image are not found: probe the neighbours and random positions (no per-queue table).
            repeat(20) {
                val l = random.nextInt(0, n)
                assertEquals(
                    "membership of $l in $w",
                    l in seen,
                    w.logicalToPhysical(l) != null,
                )
            }
            assertEquals(w.start, w.firstLogical)
            assertEquals(w.beginsAtLogicalStart, !w.wraps && w.start == 0)
        }
        assertEquals(3_000, checked)
        assertTrue("the sample must exercise wrapping windows ($wrapping)", wrapping > 100)
    }

    @Test fun `current occurrence is conserved by trim prepend and append`() {
        val random = Random(99)
        repeat(1_000) {
            val n = random.nextInt(150, 3_000)
            val length = random.nextInt(2, 90)
            val start = random.nextInt(0, n - length) // non-wrapping base so edits stay legal
            val w = window(queueSize = n, start = start, length = length)
            val current = random.nextInt(0, length)
            val logicalCurrent = w.physicalToLogical(current)!!

            val front = if (current > 0) random.nextInt(1, current + 1) else 0
            val trimmed = if (front > 0) w.trimFront(front) else w
            assertEquals(logicalCurrent, trimmed.physicalToLogical(current - front))

            val back = minOf(start, 90 - trimmed.length).coerceAtLeast(0)
            val grown = if (back > 0) trimmed.prepend(back) else trimmed
            assertEquals(logicalCurrent, grown.physicalToLogical(current - front + back))
        }
    }
}
