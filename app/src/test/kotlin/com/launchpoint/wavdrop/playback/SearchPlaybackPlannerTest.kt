package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SearchPlaybackPlannerTest {

    private fun song(id: Long, label: String) = Song(
        id = id,
        title = label,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        duration = 180_000L,
        uri = "content://media/$id",
        dateAdded = 0L,
        trackNumber = 0,
        year = 2020,
    )

    private val a = song(1, "A")
    private val b = song(2, "B")
    private val c = song(3, "C")
    private val m = song(13, "M")
    private val n = song(14, "N")
    private val o = song(15, "O")
    private val d = song(4, "D")
    private val f = song(6, "F")
    private val e = song(5, "E")
    private val y = song(25, "Y")
    private val z = song(26, "Z")
    private val x = song(99, "X")

    @Test
    fun `preserve queue inserts search result after current effective item`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c, m, n, o),
            currentPlaybackIndex = 3,
            song = x,
        )!!

        assertEquals(listOf(a, b, c, m, x, n, o), plan.queue)
        assertEquals(4, plan.currentIndex)
        assertEquals(m, plan.queue[plan.currentIndex - 1])
        assertEquals(n, plan.queue[plan.currentIndex + 1])
    }

    @Test
    fun `preserve queue is deterministic when repeated from same setup`() {
        val expected = listOf(a, b, c, m, x, n, o)

        repeat(10) {
            val plan = SearchPlaybackPlanner.preserveQueue(
                playbackQueue = listOf(a, b, c, m, n, o),
                currentPlaybackIndex = 3,
                song = x,
            )!!

            assertEquals(expected, plan.queue)
            assertEquals(4, plan.currentIndex)
        }
    }

    @Test
    fun `preserve queue uses shuffled effective order without reshuffling`() {
        val shuffledEffectiveOrder = listOf(c, o, a, m, b, n)

        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = shuffledEffectiveOrder,
            currentPlaybackIndex = 3,
            song = x,
        )!!

        assertEquals(listOf(c, o, a, m, x, b, n), plan.queue)
        assertEquals(4, plan.currentIndex)
        assertEquals(m, plan.queue[plan.currentIndex - 1])
        assertEquals(b, plan.queue[plan.currentIndex + 1])
    }

    @Test
    fun `preserve queue moves later duplicate search result after current`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c, m, x, n),
            currentPlaybackIndex = 3,
            song = x,
        )!!

        assertEquals(listOf(a, b, c, m, x, n), plan.queue)
        assertEquals(4, plan.currentIndex)
        assertEquals(1, plan.queue.count { it.id == x.id })
        assertEquals(m, plan.queue[plan.currentIndex - 1])
        assertEquals(n, plan.queue[plan.currentIndex + 1])
    }

    @Test
    fun `replace queue uses search context and selected song index`() {
        val plan = SearchPlaybackPlanner.replaceQueue(
            searchContext = listOf(c, x, o),
            song = x,
        )

        assertEquals(listOf(c, x, o), plan.queue)
        assertEquals(1, plan.currentIndex)
    }

    @Test
    fun `preserve queue ignores search context and uses active queue only`() {
        val searchContext = listOf(e, z, y)
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c, d, f),
            currentPlaybackIndex = 1,
            song = e,
        )!!

        assertEquals(listOf(e, z, y), searchContext)
        assertEquals(listOf(a, b, e, c, d, f), plan.queue)
        assertEquals(2, plan.currentIndex)
    }

    @Test
    fun `preserve sync keeps plan while Media3 still reports previous song`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c, m, n, o),
            currentPlaybackIndex = 3,
            song = x,
        )!!

        val decision = SearchPlaybackPlanner.preserveSyncDecision(
            plan = plan,
            activeQueue = plan.queue,
            mediaSongId = m.id,
            mediaIndex = 3,
            fromTransition = false,
        )

        assertEquals(PreserveSearchSyncAction.UsePlan, decision.action)
        assertSame(plan, decision.plan)
    }

    @Test
    fun `preserve sync clears plan once Media3 confirms searched song`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c, m, n, o),
            currentPlaybackIndex = 3,
            song = x,
        )!!

        val decision = SearchPlaybackPlanner.preserveSyncDecision(
            plan = plan,
            activeQueue = plan.queue,
            mediaSongId = x.id,
            mediaIndex = 4,
            fromTransition = false,
        )

        assertEquals(PreserveSearchSyncAction.ClearPlan, decision.action)
        assertNull(decision.plan)
    }

    @Test
    fun `preserve sync clears stale plan on later transition away from searched song`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c, m, n, o),
            currentPlaybackIndex = 3,
            song = x,
        )!!

        val decision = SearchPlaybackPlanner.preserveSyncDecision(
            plan = plan,
            activeQueue = plan.queue,
            mediaSongId = n.id,
            mediaIndex = 5,
            fromTransition = true,
        )

        assertEquals(PreserveSearchSyncAction.ClearPlan, decision.action)
        assertNull(decision.plan)
    }

    @Test
    fun `preserve queue rejects invalid current index when active queue exists`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = listOf(a, b, c),
            currentPlaybackIndex = null,
            song = x,
        )

        assertNull(plan)
    }

    @Test
    fun `preserve with shuffled order makes current previous and keeps shuffled future`() {
        // Visible shuffled order; current item is `a` at playback index 2. Tapping `x`.
        val shuffledVisibleOrder = listOf(c, o, a, m, b, n)

        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = shuffledVisibleOrder,
            currentPlaybackIndex = 2,
            song = x,
        )!!

        // History (c, o, a) preserved; x becomes current; shuffled future (m, b, n) preserved.
        assertEquals(listOf(c, o, a, x, m, b, n), plan.queue)
        assertEquals(3, plan.currentIndex)
        // Previously-current song `a` is now the item immediately before X (the "previous").
        assertEquals(a, plan.queue[plan.currentIndex - 1])
        // The up-next is the shuffled future, not the sorted/library order.
        assertEquals(listOf(m, b, n), plan.queue.drop(plan.currentIndex + 1))
    }

    @Test
    fun `preserve does not reorder to library order under shuffle`() {
        // Same set of songs but in a shuffled visible order; the result must follow the shuffled
        // order verbatim, never the ascending library/id order.
        val shuffledVisibleOrder = listOf(c, o, a, m, b, n)

        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = shuffledVisibleOrder,
            currentPlaybackIndex = 2,
            song = x,
        )!!

        val libraryLikeSortedIds = plan.queue.map { it.id }.sorted()
        assert(plan.queue.map { it.id } != libraryLikeSortedIds) {
            "preserved queue must retain shuffled order, not collapse to sorted library order"
        }
        // The relative order of the pre-existing songs is unchanged by the insertion of X.
        assertEquals(
            shuffledVisibleOrder,
            plan.queue.filterNot { it.id == x.id },
        )
    }

    @Test
    fun `preserve queue fallback keeps existing queue as up-next after tapped song`() {
        // Simulates the unresolved-current-index case: nothing is destroyed, the whole active
        // queue is preserved as up-next and the tapped song becomes current.
        val fallback = SearchPlaybackPlanner.preserveQueueFallback(
            playbackQueue = listOf(a, b, c),
            song = x,
        )

        assertEquals(listOf(x, a, b, c), fallback.queue)
        assertEquals(0, fallback.currentIndex)
        assertEquals(x, fallback.queue[fallback.currentIndex])
    }

    @Test
    fun `preserve queue fallback removes duplicate of tapped song from continuation`() {
        val fallback = SearchPlaybackPlanner.preserveQueueFallback(
            playbackQueue = listOf(a, x, b),
            song = x,
        )

        assertEquals(listOf(x, a, b), fallback.queue)
        assertEquals(0, fallback.currentIndex)
        assertEquals(1, fallback.queue.count { it.id == x.id })
    }

    @Test
    fun `preserve queue fallback yields single-song queue when active queue empty`() {
        val fallback = SearchPlaybackPlanner.preserveQueueFallback(
            playbackQueue = emptyList(),
            song = x,
        )

        assertEquals(listOf(x), fallback.queue)
        assertEquals(0, fallback.currentIndex)
    }

    @Test
    fun `preserve queue allows one-song fallback only when active queue is empty`() {
        val plan = SearchPlaybackPlanner.preserveQueue(
            playbackQueue = emptyList(),
            currentPlaybackIndex = null,
            song = x,
        )!!

        assertEquals(listOf(x), plan.queue)
        assertEquals(0, plan.currentIndex)
    }
}
