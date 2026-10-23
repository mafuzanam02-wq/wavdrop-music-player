package com.launchpoint.wavdrop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure positional transformations that let a deferred start follow its exact entry across a structural edit. */
class OccurrenceMapTest {

    private fun List<Int>.mapAll(f: (Int) -> Int?) = map(f)

    @Test fun identityKeepsEveryPosition() = assertEquals(listOf(0, 1, 5), listOf(0, 1, 5).mapAll(OccurrenceMap.identity()))

    @Test fun insertShiftsOnlyEntriesAtOrAfterTheInsertionPoint() {
        assertEquals(listOf(0, 1, 4, 5), listOf(0, 1, 2, 3).mapAll(OccurrenceMap.insert(at = 2, count = 2)))
    }

    @Test fun removeDropsTheEntryAndClosesTheGap() {
        val map = OccurrenceMap.remove(2)
        assertEquals(listOf(0, 1, null, 2, 3), listOf(0, 1, 2, 3, 4).map(map))
    }

    @Test fun removeAllHandlesAnyOrderAndMultiplePositions() {
        val map = OccurrenceMap.removeAll(listOf(5, 1, 3))
        assertEquals(listOf(0, null, 1, null, 2, null, 3), (0..6).map(map))
    }

    @Test fun moveForwardShiftsTheSpanBackByOne() {
        val map = OccurrenceMap.move(from = 1, to = 3)
        assertEquals(listOf(0, 3, 1, 2, 4), (0..4).map(map))
    }

    @Test fun moveBackwardShiftsTheSpanForwardByOne() {
        val map = OccurrenceMap.move(from = 3, to = 1)
        assertEquals(listOf(0, 2, 3, 1, 4), (0..4).map(map))
    }

    @Test fun swapExchangesTwoPositions() = assertEquals(listOf(0, 2, 1, 3), (0..3).map(OccurrenceMap.swap(1, 2)))

    @Test fun clearEarlierRemovesEverythingBeforeTheCurrentEntryAndRebasesIt() {
        val map = OccurrenceMap.clearEarlier(current = 3)
        assertEquals(listOf(null, null, null, 0, 1, 2), (0..5).map(map))
    }

    @Test fun clearUpNextRemovesEverythingAfterTheCurrentEntry() {
        val map = OccurrenceMap.clearUpNext(current = 3)
        assertEquals(listOf(0, 1, 2, 3, null, null), (0..5).map(map))
    }

    @Test fun aDuplicateSongIsTrackedByPositionNotById() {
        // queue [A, B, A]; remove the first A: the SECOND A (index 2) is now index 1, and the first A's old slot is gone.
        val map = OccurrenceMap.remove(0)
        assertEquals(1, map(2))
        assertNull(map(0))
    }
}
