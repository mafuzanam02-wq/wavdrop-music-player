package com.launchpoint.wavdrop.data.settings

import com.launchpoint.wavdrop.data.model.SmartCollectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic contract for the Home Smart Collections preference (WU-01). Covers the
 * test matrix in the brief: default backfill, order preservation, dedup, unknown-id
 * removal, forward-incompatible ids, exactly-three sizing, and uniqueness.
 */
class HomeSmartCollectionSelectionTest {

    private val af = SmartCollectionType.ALWAYS_FINISH
    private val fg = SmartCollectionType.FORGOTTEN_GEMS
    private val ua = SmartCollectionType.USUALLY_ABANDON
    private val fav = SmartCollectionType.FAVORITES
    private val mp = SmartCollectionType.MOST_PLAYED
    private val np = SmartCollectionType.NEVER_PLAYED

    // A. no stored selection → default three
    @Test
    fun `no stored selection yields default three`() {
        assertEquals(HomeSmartCollectionSelection.DEFAULT, HomeSmartCollectionSelection.sanitizeIds(emptyList()))
        assertEquals(listOf(af, fg, ua), HomeSmartCollectionSelection.DEFAULT)
    }

    // B. valid custom three → preserved in exact order
    @Test
    fun `valid custom three preserved in order`() {
        val stored = listOf(mp, fav, np)
        assertEquals(stored, HomeSmartCollectionSelection.sanitize(stored))
    }

    // C. duplicate IDs → deduplicated and backfilled
    @Test
    fun `duplicate ids are deduped and backfilled`() {
        val result = HomeSmartCollectionSelection.sanitize(listOf(fav, fav, fav))
        assertEquals(3, result.size)
        assertEquals(fav, result.first())
        assertEquals(result.size, result.toSet().size)
    }

    // D. unknown ID → removed and backfilled
    @Test
    fun `unknown id is removed and backfilled to three`() {
        val result = HomeSmartCollectionSelection.sanitizeIds(listOf("NOT_A_REAL_COLLECTION", fav.name))
        assertEquals(3, result.size)
        assertEquals(fav, result.first())
        assertTrue(result.all { it in SmartCollectionType.entries })
    }

    // E. only one valid persisted ID → retain it + fill two
    @Test
    fun `single valid id retained then filled`() {
        val result = HomeSmartCollectionSelection.sanitize(listOf(np))
        assertEquals(3, result.size)
        assertEquals(np, result.first())
    }

    // F. reordered three → order preserved
    @Test
    fun `reordered three preserves order`() {
        val reordered = listOf(ua, af, fg)
        assertEquals(reordered, HomeSmartCollectionSelection.sanitize(reordered))
    }

    // G. selection cannot persist more than three
    @Test
    fun `more than three is clamped to three`() {
        val result = HomeSmartCollectionSelection.sanitize(listOf(af, fg, ua, fav, mp))
        assertEquals(listOf(af, fg, ua), result)
    }

    // H. selection cannot persist fewer than three
    @Test
    fun `fewer than three is filled to three`() {
        assertEquals(3, HomeSmartCollectionSelection.sanitize(listOf(fav, mp)).size)
        assertEquals(3, HomeSmartCollectionSelection.sanitize(emptyList()).size)
    }

    // J. available-list order changing does not overwrite user order
    @Test
    fun `available order does not override stored order`() {
        val stored = listOf(np, fav, mp)
        val shuffledAvailable = listOf(mp, af, fav, ua, np, fg)
        assertEquals(stored, HomeSmartCollectionSelection.sanitize(stored, available = shuffledAvailable))
    }

    // K. default/stored id missing in a future build → deterministic valid fallback
    @Test
    fun `id missing from available is dropped and backfilled deterministically`() {
        // ALWAYS_FINISH is stored but no longer available in this hypothetical build.
        val available = SmartCollectionType.entries.filterNot { it == af }
        val result = HomeSmartCollectionSelection.sanitize(
            stored = listOf(af, fg, ua),
            available = available,
            fallback = HomeSmartCollectionSelection.DEFAULT,
        )
        assertEquals(3, result.size)
        assertTrue(af !in result)
        assertTrue(result.all { it in available })
        // Deterministic: fg, ua retained in order, one backfill from fallback/available order.
        assertEquals(fg, result[0])
        assertEquals(ua, result[1])
    }

    // L. all resulting IDs unique (fuzz across representative inputs)
    @Test
    fun `results are always unique`() {
        val inputs = listOf(
            emptyList(),
            listOf(fav),
            listOf(fav, fav),
            listOf(af, fg, ua, fav, mp, np),
            listOf(np, np, fg, fg),
        )
        for (input in inputs) {
            val result = HomeSmartCollectionSelection.sanitize(input)
            assertEquals("unique for $input", result.size, result.toSet().size)
        }
    }

    @Test
    fun `parse drops blanks and unknowns preserving order`() {
        val parsed = HomeSmartCollectionSelection.parse(listOf(" FAVORITES ", "", "nope", "MOST_PLAYED"))
        assertEquals(listOf(fav, mp), parsed)
    }

    @Test
    fun `serialize round-trips through parse`() {
        val types = listOf(af, np, mp)
        assertEquals(types, HomeSmartCollectionSelection.parse(HomeSmartCollectionSelection.serialize(types)))
    }

    @Test
    fun `fewer than three available returns what exists`() {
        val available = listOf(fav, mp)
        val result = HomeSmartCollectionSelection.sanitize(listOf(fav), available = available)
        assertEquals(listOf(fav, mp), result)
    }
}
