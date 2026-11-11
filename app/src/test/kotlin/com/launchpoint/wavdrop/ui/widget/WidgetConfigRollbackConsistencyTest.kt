package com.launchpoint.wavdrop.ui.widget

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P5-UI-15B3 — after a failed / timed-out / cancelled Save, what the launcher DISPLAYS must end up equal to what is PERSISTED.
 *
 * The real store and the real [WidgetUpdateCoordinator] are used. The renderer is a model of `performUpdate`: it reads the
 * persisted appearance when its pass starts, can be held (gate) between reading and pushing, and "pushes" to a fake launcher.
 * That ordering — read first, push later — is exactly what lets a late pass show the new look after a rollback.
 * Scheduling is controlled by gates, not sleeps. Nothing here is Samsung-device verification.
 */
class WidgetConfigRollbackConsistencyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WidgetAppearanceStore

    private val old = WidgetAppearance(WidgetBackgroundStyle.ARTWORK, WidgetArtworkIntensity.LIGHT)
    private val new = WidgetAppearance(WidgetBackgroundStyle.SOLID, WidgetArtworkIntensity.DARK)
    private val known = setOf(1, 2, 99)

    private val started = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val gates = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val pushes = CopyOnWriteArrayList<Pair<Int, WidgetAppearance>>()
    private val passTargets = CopyOnWriteArrayList<Set<Int>?>()
    @Volatile private var failAfterPushing: Int? = null
    private val reconciles = CopyOnWriteArrayList<Int>()
    private lateinit var coordinator: WidgetUpdateCoordinator

    @Before fun setUp() {
        val file = File(tmp.newFolder(), "appearance.preferences_pb")
        store = WidgetAppearanceStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        coordinator = WidgetUpdateCoordinator(scope) { target ->
            passTargets += target
            for (id in target ?: known) {
                val read = store.get(id)                       // state is read when the pass starts ...
                started.getOrPut(id) { CompletableDeferred() }.complete(Unit)
                gates[id]?.await()                             // ... a held pass keeps the value it read ...
                pushes += id to read                           // ... and pushes it later.
                if (failAfterPushing == id) throw WidgetRenderException("failed after partial progress")
            }
        }
    }

    @After fun tearDown() { scope.cancel() }

    private fun saver(timeoutMs: Long = 5_000, withReconcile: Boolean = true) = WidgetConfigSaver(
        store,
        renderFirstUpdate = { id -> coordinator.requestAndAwait(listOf(id)) },
        timeoutMs = timeoutMs,
        reconcile = { id -> if (withReconcile) { reconciles += id; coordinator.request(listOf(id)) } },
    )

    private fun hold(id: Int) { gates[id] = CompletableDeferred(); started[id] = CompletableDeferred() }
    private fun release(id: Int) { gates[id]!!.complete(Unit) }
    private suspend fun awaitStarted(id: Int) = withTimeout(5_000) { started.getOrPut(id) { CompletableDeferred() }.await() }
    private suspend fun settle(minPushes: Int) = withTimeout(5_000) { while (pushes.size < minPushes) delay(5) }
    private fun displayed(id: Int) = pushes.lastOrNull { it.first == id }?.second

    private suspend fun givenConfigured(id: Int, a: WidgetAppearance = old) { assertTrue(store.save(id, a)) }

    // -- render completes before the timeout -----------------------------------------------------------------------

    @Test fun aSaveWhoseRenderFinishesInTimeShowsAndKeepsTheNewLookWithNoExtraRender() = runBlocking {
        givenConfigured(1)
        assertEquals(WidgetConfigSaver.Result.Saved, saver().commit(1, new, old))
        assertEquals(listOf(1 to new), pushes.toList())
        assertEquals(new, store.get(1))
        delay(50)
        assertEquals("no reconcile after a successful save", emptyList<Int>(), reconciles.toList())
        assertEquals(1, pushes.size)
    }

    // -- timeout BEFORE the render starts (queued behind another pass) ---------------------------------------------

    @Test fun aRenderStillQueuedWhenSaveTimesOutNeverShowsTheNewLook() = runBlocking {
        givenConfigured(2)
        hold(1)
        coordinator.request(listOf(1))            // another widget's pass is executing and blocked
        awaitStarted(1)
        assertEquals(WidgetConfigSaver.Result.Failed, saver(timeoutMs = 150).commit(2, new, old))
        assertEquals("rolled back in the store", old, store.get(2))
        release(1)
        withTimeout(5_000) { while (displayed(2) == null) delay(5) }
        assertEquals("the queued pass read the rolled-back value", old, displayed(2))
        assertTrue("the new look was never pushed for widget 2", pushes.none { it.first == 2 && it.second == new })
    }

    // -- timeout WHILE the render is executing, then the late render completes -------------------------------------

    @Test fun aRenderExecutingWhenSaveTimesOutIsFollowedByAReconcilingRenderOfTheRolledBackLook() = runBlocking {
        givenConfigured(1)
        hold(1)
        val result = scope.async { saver(timeoutMs = 200).commit(1, new, old) }
        awaitStarted(1)                            // the pass has already READ the new appearance
        assertEquals(WidgetConfigSaver.Result.Failed, result.await())
        assertEquals(old, store.get(1))
        assertTrue("no render has pushed yet", pushes.isEmpty())
        release(1)                                 // the late pass now pushes what it read: the NEW look
        settle(2)
        assertEquals("late stale push first, then the reconciling push", listOf(1 to new, 1 to old), pushes.toList())
        assertEquals("displayed == persisted", store.get(1), displayed(1))
    }

    @Test fun withoutTheReconcilingRenderTheLateRenderWouldLeaveTheLauncherOutOfSyncWithTheStore() = runBlocking {
        // Documents the original scenario: this is the behaviour the reconcile step exists to prevent.
        givenConfigured(1)
        hold(1)
        val result = scope.async { saver(timeoutMs = 200, withReconcile = false).commit(1, new, old) }
        awaitStarted(1)
        assertEquals(WidgetConfigSaver.Result.Failed, result.await())
        release(1)
        settle(1)
        delay(100)
        assertEquals("stale: launcher shows the new look", new, displayed(1))
        assertEquals("while the store holds the old one", old, store.get(1))
    }

    // -- render fails after partial progress -----------------------------------------------------------------------

    @Test fun aRenderThatPushedThenFailedIsReconciledBackToThePersistedLook() = runBlocking {
        givenConfigured(1)
        failAfterPushing = 1
        // first pass pushes the new look and then throws; the reconciling pass must not fail the same way
        val s = WidgetConfigSaver(store, { id -> coordinator.requestAndAwait(listOf(id)) }, 5_000) { id ->
            reconciles += id
            failAfterPushing = null
            coordinator.request(listOf(id))
        }
        assertEquals(WidgetConfigSaver.Result.Failed, s.commit(1, new, old))
        settle(2)
        assertEquals(old, store.get(1))
        assertEquals(listOf(1 to new, 1 to old), pushes.toList())
        assertEquals(old, displayed(1))
    }

    // -- cancellation during Save ----------------------------------------------------------------------------------

    @Test fun closingTheScreenWhileTheRenderIsHeldRollsBackAndReconciles() = runBlocking {
        givenConfigured(1)
        hold(1)
        val job = scope.launch { saver().commit(1, new, old) }
        awaitStarted(1)
        job.cancelAndJoin()
        assertEquals(old, store.get(1))
        assertEquals(listOf(1), reconciles.toList())
        release(1)
        settle(2)
        assertEquals(old, displayed(1))
    }

    @Test fun cancelOnTheScreenNeverSavesOrReconcilesAnything() = runBlocking {
        givenConfigured(1)
        // The Cancel button never touches the saver at all (source guard in WidgetConfigSaverTest); nothing was requested.
        delay(30)
        assertEquals(old, store.get(1)); assertTrue(reconciles.isEmpty()); assertTrue(pushes.isEmpty())
    }

    // -- playback-driven update racing with / after a failed save --------------------------------------------------

    @Test fun aPlaybackDrivenAllWidgetsUpdateDuringAHeldPassStillEndsOnThePersistedLook() = runBlocking {
        givenConfigured(1); givenConfigured(99)
        hold(1)
        val result = scope.async { saver(timeoutMs = 200).commit(1, new, old) }
        awaitStarted(1)
        coordinator.request(null)                  // playback event arrives while the config pass is still held
        assertEquals(WidgetConfigSaver.Result.Failed, result.await())
        release(1)
        withTimeout(5_000) { while (pushes.count { it.first == 99 } < 1) delay(5) }
        delay(100)
        assertEquals(old, displayed(1))
        assertEquals(old, displayed(99))
    }

    @Test fun aLaterPlaybackUpdateRendersThePersistedLookNotTheFailedOne() = runBlocking {
        givenConfigured(1)
        hold(1)
        assertEquals(WidgetConfigSaver.Result.Failed, saver(timeoutMs = 100).commit(1, new, old))
        release(1)
        coordinator.request(null)
        withTimeout(5_000) { while (pushes.count { it.first == 99 } < 1) delay(5) }
        delay(100)
        assertEquals(old, displayed(1))
    }

    // -- retry, isolation ------------------------------------------------------------------------------------------

    @Test fun aFailedSaveCanBeRetriedAndTheRetrySucceedsWithTheNewLook() = runBlocking {
        givenConfigured(1)
        hold(1)
        val s = saver(timeoutMs = 150)
        assertEquals(WidgetConfigSaver.Result.Failed, s.commit(1, new, old))
        release(1)
        settle(1)
        delay(100)
        assertEquals(WidgetConfigSaver.Result.Saved, s.commit(1, new, old))
        assertEquals(new, store.get(1))
        assertEquals(new, displayed(1))
    }

    @Test fun reconcilingTouchesOnlyTheWidgetBeingConfigured() = runBlocking {
        givenConfigured(1); givenConfigured(99, new)
        hold(1)
        assertEquals(WidgetConfigSaver.Result.Failed, saver(timeoutMs = 150).commit(1, new, old))
        release(1)
        settle(2)
        delay(100)
        assertEquals(listOf(1), reconciles.toList())
        assertTrue("no pass ever targeted widget 99", passTargets.none { it == null || 99 in it })
        assertEquals(new, store.get(99))
    }

    @Test fun anInvalidIdIsRefusedAndRequestsNoReconcile() = runBlocking {
        assertEquals(WidgetConfigSaver.Result.Failed, saver().commit(0, new, old))   // refused by the store: nothing persisted
        delay(30)
        assertTrue(reconciles.isEmpty())
        assertTrue(pushes.isEmpty())
    }

    // -- wiring ----------------------------------------------------------------------------------------------------

    @Test fun theActivityReconcilesThroughTheExistingUpdaterWithNoSecondRenderingPipeline() {
        val src = File("src/main/kotlin/com/launchpoint/wavdrop/ui/widget/WidgetConfigActivity.kt").readText()
        assertTrue(src.contains("reconcile = { id -> WavdropWidgetUpdater.requestUpdate(applicationContext, listOf(id)) }"))
        assertTrue("the activity renders nothing itself", !src.contains("updateAppWidget"))
    }
}
