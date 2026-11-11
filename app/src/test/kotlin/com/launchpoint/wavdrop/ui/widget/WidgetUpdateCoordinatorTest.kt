package com.launchpoint.wavdrop.ui.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** P5-UI-15B — overlapping widget-update requests are serialised, coalesced and always end on the latest state. */
class WidgetUpdateCoordinatorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() { scope.cancel() }

    private suspend fun awaitUntil(message: String, condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }

    @Test fun aSingleRequestRunsOnceWithItsTarget() = runBlocking {
        val seen = CopyOnWriteArrayList<Set<Int>?>()
        val c = WidgetUpdateCoordinator(scope) { seen += it }
        c.request(listOf(5))
        awaitUntil("ran") { seen.size == 1 }
        assertEquals(setOf(5), seen.single())
        c.request(null)
        awaitUntil("ran all") { seen.size == 2 }
        assertNull(seen[1])
    }

    @Test fun passesNeverOverlapEvenUnderAHeavyBurstOfRequests() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val runs = AtomicInteger()
        val c = WidgetUpdateCoordinator(scope) {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { m -> maxOf(m, now) }
            delay(10)
            active.decrementAndGet()
            runs.incrementAndGet()
        }
        repeat(60) { i ->
            scope.launch { c.request(if (i % 2 == 0) null else listOf(i)) }
        }
        awaitUntil("settled") { runs.get() >= 1 && active.get() == 0 }
        delay(150)
        assertEquals("never more than one pass at a time", 1, maxActive.get())
        assertTrue("bursts coalesce instead of running 60 times (ran ${runs.get()})", runs.get() < 60)
    }

    @Test fun requestsDuringARunMergeIntoOneFollowUpPass() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Set<Int>?>()
        val started = CompletableDeferred<Unit>()
        val c = WidgetUpdateCoordinator(scope) { target ->
            seen += target
            if (seen.size == 1) { started.complete(Unit); gate.await() }
        }
        c.request(listOf(1))
        started.await()
        c.request(listOf(2)); c.request(listOf(3)); c.request(listOf(2))
        gate.complete(Unit)
        awaitUntil("follow-up") { seen.size == 2 }
        delay(100)
        assertEquals("exactly one follow-up pass", 2, seen.size)
        assertEquals(setOf(1), seen[0])
        assertEquals(setOf(2, 3), seen[1])
    }

    @Test fun anAllWidgetsRequestAbsorbsPendingIdRequests() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Set<Int>?>()
        val c = WidgetUpdateCoordinator(scope) { target ->
            seen += target
            if (seen.size == 1) { started.complete(Unit); gate.await() }
        }
        c.request(listOf(1))
        started.await()
        c.request(listOf(2))
        c.request(null)
        c.request(listOf(3))
        gate.complete(Unit)
        awaitUntil("follow-up") { seen.size == 2 }
        delay(100)
        assertEquals(2, seen.size)
        assertNull("the follow-up refreshes every widget", seen[1])
    }

    @Test fun anAppearanceSaveAndAPlaybackUpdateRaceEndsWithTheLatestStateRendered() = runBlocking {
        // Each pass reads "state" when it RUNS (as the real updater reads the snapshot + appearances), so the last pass wins.
        val state = java.util.concurrent.atomic.AtomicReference("old")
        val rendered = CopyOnWriteArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val c = WidgetUpdateCoordinator(scope) {
            val read = state.get()
            if (rendered.isEmpty()) { started.complete(Unit); gate.await() }
            rendered += read
        }
        c.request(null)           // playback update starts rendering "old"...
        started.await()
        state.set("new")        // ...the appearance save lands meanwhile...
        c.request(listOf(1))      // ...and requests its refresh
        gate.complete(Unit)
        awaitUntil("both passes") { rendered.size == 2 }
        assertEquals("old", rendered[0])
        assertEquals("the pass that ran last read the newest state", "new", rendered.last())
    }

    @Test fun aFailingPassIsReportedAndDoesNotStopLaterRequests() = runBlocking {
        val errors = CopyOnWriteArrayList<Throwable>()
        val runs = AtomicInteger()
        val c = WidgetUpdateCoordinator(scope, onError = { errors += it }) {
            if (runs.incrementAndGet() == 1) error("boom")
        }
        c.request(null)
        awaitUntil("first failed") { errors.size == 1 }
        c.request(null)
        awaitUntil("second ran") { runs.get() == 2 }
        assertEquals("boom", errors.single().message)
    }

    // ── P5-UI-15B2: awaited requests ─────────────────────────────────────────────────────────────────────────────────

    @Test fun requestAndAwaitReturnsTrueAfterThePassThatCoversItAndFalseWhenThatPassFails() = runBlocking {
        val fail = java.util.concurrent.atomic.AtomicBoolean(false)
        val c = WidgetUpdateCoordinator(scope) { if (fail.get()) error("render failed") }
        assertTrue(c.requestAndAwait(listOf(1)))
        fail.set(true)
        assertTrue(!c.requestAndAwait(listOf(1)))
        fail.set(false)
        assertTrue("a failed pass never stops later requests", c.requestAndAwait(listOf(1)))
        assertTrue("no ids means nothing can have been rendered", !c.requestAndAwait(emptyList()))
    }

    @Test fun theAwaitedPassStartsAfterTheRequestSoItSeesStateChangedBeforeTheCall() = runBlocking {
        val state = java.util.concurrent.atomic.AtomicReference("old")
        val read = CopyOnWriteArrayList<String>()
        val c = WidgetUpdateCoordinator(scope) { read += state.get() }
        state.set("new") // e.g. the appearance was persisted
        assertTrue(c.requestAndAwait(listOf(7)))
        assertEquals(listOf("new"), read.toList())
    }

    @Test fun anAwaitedRequestAbsorbedIntoAnAllWidgetsPassStillRendersAndChecksItsOwnIds() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<Set<Int>?>()
        val c = WidgetUpdateCoordinator(scope) { target ->
            seen += target
            if (seen.size == 1) { started.complete(Unit); gate.await() }
        }
        c.request(null)           // pass 1 (all) is running
        started.await()
        c.request(null)           // pass 2 will be "all"...
        val awaited = scope.async { c.requestAndAwait(listOf(9)) } // ...and absorbs this awaited request
        delay(50)
        gate.complete(Unit)
        assertTrue(withTimeout(5_000) { awaited.await() })
        awaitUntil("all passes") { seen.size >= 3 }
        assertEquals("first the awaited ids explicitly, then everything", setOf(9), seen[1])
        assertNull(seen[2])
    }

    @Test fun aFailureOfTheAwaitedIdIsNotHiddenByAnAllWidgetsPass() = runBlocking {
        val c = WidgetUpdateCoordinator(scope) { target -> if (target != null && 9 in target) error("widget 9 is gone") }
        c.request(null)
        assertTrue(!c.requestAndAwait(listOf(9)))
        assertTrue("other widgets still update afterwards", c.requestAndAwait(listOf(1)))
    }

    @Test fun cancellingTheCoordinatorsScopeReleasesAnAwaitingCallerWithFalse() = runBlocking {
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val c = WidgetUpdateCoordinator(localScope) { started.complete(Unit); CompletableDeferred<Unit>().await() }
        val awaited = scope.async { c.requestAndAwait(listOf(3)) }
        started.await()
        localScope.cancel()
        assertTrue(!withTimeout(5_000) { awaited.await() })
    }
}
