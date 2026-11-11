package com.launchpoint.wavdrop.ui.widget

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * P5-UI-15B2 — the configuration screen's Save: persist → render first update → only then success; rollback on failure; no
 * duplicate operations. Uses the real store and the real update coordinator; the render step stands in for the production
 * `performUpdate` (which needs a launcher/AppWidgetManager). Nothing here is Samsung-device verification.
 */
class WidgetConfigSaverTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: WidgetAppearanceStore

    @Before fun setUp() {
        val file = File(tmp.newFolder(), "appearance.preferences_pb")
        store = WidgetAppearanceStore(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After fun tearDown() { scope.cancel() }

    private val light = WidgetAppearance(WidgetBackgroundStyle.ARTWORK, WidgetArtworkIntensity.LIGHT)
    private val dark = WidgetAppearance(WidgetBackgroundStyle.ARTWORK, WidgetArtworkIntensity.DARK)
    private val solid = WidgetAppearance(WidgetBackgroundStyle.SOLID, WidgetArtworkIntensity.BALANCED)

    private fun saver(timeoutMs: Long = 5_000, render: suspend (Int) -> Boolean) = WidgetConfigSaver(store, render, timeoutMs)

    // ── 1. initial configuration and the first widget update ─────────────────────────────────────────────────────────

    @Test fun theFirstRenderOfANewWidgetSeesTheJustSavedAppearanceAndSaveSucceedsOnlyAfterIt() = runBlocking {
        val seenAtRender = CopyOnWriteArrayList<WidgetAppearance>()
        val s = saver { id -> seenAtRender += store.get(id); true }
        assertEquals(WidgetConfigSaver.Result.Saved, s.commit(21, solid, previous = WidgetAppearance.DEFAULT))
        assertEquals("render ran exactly once and read the new appearance", listOf(solid), seenAtRender.toList())
        assertEquals(solid, store.get(21))
    }

    @Test fun theRealCoordinatorRendersTheNewWidgetFromStoredStateBeforeSaveSucceeds() = runBlocking {
        val rendered = CopyOnWriteArrayList<Pair<Int, WidgetAppearance>>()
        val coordinator = WidgetUpdateCoordinator(scope) { target ->
            target!!.forEach { rendered += it to store.get(it) }
        }
        val s = saver { id -> coordinator.requestAndAwait(listOf(id)) }
        assertEquals(WidgetConfigSaver.Result.Saved, s.commit(30, dark, WidgetAppearance.DEFAULT))
        assertEquals(listOf(30 to dark), rendered.toList())
    }

    // ── 2. save completion ordering ──────────────────────────────────────────────────────────────────────────────────

    @Test fun successIsNotReportedWhileTheFirstRenderIsStillRunning() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Unit>()
        val s = saver { started.complete(Unit); gate.await() }
        val result = scope.async { s.commit(40, light, WidgetAppearance.DEFAULT) }
        started.await()
        delay(100)
        assertFalse("Save must still be pending while rendering", result.isCompleted)
        assertEquals("the appearance is already persisted, rendering reads it", light, store.get(40))
        gate.complete(true)
        assertEquals(WidgetConfigSaver.Result.Saved, withTimeout(5_000) { result.await() })
    }

    // ── 3. cancellation (screen closed mid-save) never leaves changes behind ─────────────────────────────────────────

    @Test fun closingTheScreenDuringASaveRollsTheAppearanceBack() = runBlocking {
        store.save(50, light)
        val started = CompletableDeferred<Unit>()
        val s = saver { started.complete(Unit); CompletableDeferred<Boolean>().await() }
        val job: Job = scope.launch { s.commit(50, dark, previous = light) }
        started.await()
        assertEquals(dark, store.get(50))
        job.cancel()
        job.join()
        assertEquals("rolled back to what the screen opened with", light, store.get(50))
    }

    @Test fun theActivityCancelPathNeverTouchesTheStoreOrTheSaver() {
        val src = activitySource()
        val cancel = src.substringAfter("private fun cancel").substringBefore("private fun save")
        assertTrue(cancel.contains("RESULT_CANCELED") && cancel.contains("finish()"))
        assertFalse(cancel.contains("saver") || cancel.contains("widgetAppearanceStore") || cancel.contains("RESULT_OK"))
    }

    // ── 4. invalid widget id ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun anInvalidIdIsRefusedWithoutRenderingOrStoringAnything() = runBlocking {
        val renders = AtomicInteger()
        val s = saver { renders.incrementAndGet(); true }
        listOf(0, -1).forEach { bad ->
            assertEquals(WidgetConfigSaver.Result.Failed, s.commit(bad, solid, WidgetAppearance.DEFAULT))
        }
        assertEquals(0, renders.get())
        assertEquals(WidgetAppearance.DEFAULT, store.get(1))
    }

    // ── 5. duplicate Save protection ─────────────────────────────────────────────────────────────────────────────────

    @Test fun aSecondSaveWhileOneIsInFlightIsRefusedAndOnlyOneRenderHappens() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Unit>()
        val renders = AtomicInteger()
        val s = saver { renders.incrementAndGet(); started.complete(Unit); gate.await() }
        val first = scope.async { s.commit(60, light, WidgetAppearance.DEFAULT) }
        started.await()
        assertEquals(WidgetConfigSaver.Result.Busy, s.commit(60, dark, WidgetAppearance.DEFAULT))
        assertEquals(WidgetConfigSaver.Result.Busy, s.commit(60, solid, WidgetAppearance.DEFAULT))
        assertEquals("the in-flight appearance was not overwritten by the duplicates", light, store.get(60))
        gate.complete(true)
        assertEquals(WidgetConfigSaver.Result.Saved, first.await())
        assertEquals(1, renders.get())
        assertEquals("after success the screen is finished: no further saves", WidgetConfigSaver.Result.Busy, s.commit(60, dark, light))
    }

    @Test fun aFailedSaveCanBeRetriedButNotConcurrently() = runBlocking {
        val attempts = AtomicInteger()
        val s = saver { attempts.incrementAndGet() > 1 } // first render fails, second succeeds
        assertEquals(WidgetConfigSaver.Result.Failed, s.commit(70, dark, WidgetAppearance.DEFAULT))
        assertEquals(WidgetAppearance.DEFAULT, store.get(70))
        assertEquals(WidgetConfigSaver.Result.Saved, s.commit(70, dark, WidgetAppearance.DEFAULT))
        assertEquals(dark, store.get(70))
    }

    // ── 6. reconfiguring an existing widget ──────────────────────────────────────────────────────────────────────────

    @Test fun reconfiguringAnExistingWidgetReplacesItsAppearanceAndLeavesOthersAlone() = runBlocking {
        store.save(80, light)
        store.save(81, solid)
        val s = saver { true }
        assertEquals(WidgetConfigSaver.Result.Saved, s.commit(80, dark, previous = light))
        assertEquals(dark, store.get(80))
        assertEquals("another widget untouched", solid, store.get(81))
    }

    @Test fun aFailedReconfigurationKeepsTheExistingWidgetsPreviousLook() = runBlocking {
        store.save(90, light)
        val s = saver { false }
        assertEquals(WidgetConfigSaver.Result.Failed, s.commit(90, dark, previous = light))
        assertEquals(light, store.get(90))
    }

    // ── 7. failure / removal / timeout during configuration ──────────────────────────────────────────────────────────

    @Test fun aRenderThatThrowsIsAFailureNotACrashAndRollsBack() = runBlocking {
        val s = saver { error("RemoteViews exploded") }
        assertEquals(WidgetConfigSaver.Result.Failed, s.commit(100, dark, WidgetAppearance.DEFAULT))
        assertEquals(WidgetAppearance.DEFAULT, store.get(100))
    }

    @Test fun aRenderThatNeverFinishesTimesOutInsteadOfSpinningForever() = runBlocking {
        val s = saver(timeoutMs = 150) { CompletableDeferred<Boolean>().await() }
        val started = System.nanoTime()
        assertEquals(WidgetConfigSaver.Result.Failed, s.commit(110, dark, WidgetAppearance.DEFAULT))
        assertTrue("returned promptly", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals(WidgetAppearance.DEFAULT, store.get(110))
    }

    @Test fun aWidgetRemovedDuringConfigurationFailsTheSaveAndLeavesNoSettings() = runBlocking {
        val live = mutableSetOf(120)
        val coordinator = WidgetUpdateCoordinator(scope) { target ->
            val missing = target!!.filter { it !in live }
            if (missing.isNotEmpty()) throw WidgetRenderException("missing $missing")
        }
        val s = saver { id -> coordinator.requestAndAwait(listOf(id)) }
        live.remove(120) // the user removed the widget while the screen was open
        assertEquals(WidgetConfigSaver.Result.Failed, s.commit(120, dark, WidgetAppearance.DEFAULT))
        assertEquals(WidgetAppearance.DEFAULT, store.get(120))
    }

    @Test fun aForeignWidgetIdIsNeverReportedAsRendered() = runBlocking {
        val ours = setOf(130)
        val coordinator = WidgetUpdateCoordinator(scope) { target ->
            if (target!!.any { it !in ours }) throw WidgetRenderException("foreign")
        }
        val s = saver { id -> coordinator.requestAndAwait(listOf(id)) }
        assertEquals(WidgetConfigSaver.Result.Failed, s.commit(999, solid, WidgetAppearance.DEFAULT))
        assertEquals(WidgetConfigSaver.Result.Saved, saver { id -> coordinator.requestAndAwait(listOf(id)) }.commit(130, solid, WidgetAppearance.DEFAULT))
    }

    // ── overlap with playback-driven all-widget updates ──────────────────────────────────────────────────────────────

    @Test fun anAllWidgetsBurstRunningAlongsideTheFirstUpdateDoesNotHideOrBreakIt() = runBlocking {
        val live = setOf(140)
        val rendered = CopyOnWriteArrayList<Pair<String, WidgetAppearance>>()
        val coordinator = WidgetUpdateCoordinator(scope) { target ->
            delay(5)
            val ids = target ?: live
            ids.forEach { rendered += (if (target == null) "all" else "targeted") to store.get(it) }
            if (target != null && target.any { it !in live }) throw WidgetRenderException("missing")
        }
        val s = saver { id -> coordinator.requestAndAwait(listOf(id)) }
        repeat(20) { coordinator.request(null) } // playback updates piling up
        assertEquals(WidgetConfigSaver.Result.Saved, s.commit(140, light, WidgetAppearance.DEFAULT))
        assertTrue("some render saw the new appearance", rendered.any { it.second == light })
        delay(100)
        assertEquals("the last render of this widget shows the final appearance", light, rendered.last().second)
    }

    // ── source guards for the activity wiring ────────────────────────────────────────────────────────────────────────

    private fun activitySource(): String =
        listOf("src/main/kotlin/com/launchpoint/wavdrop/ui/widget/WidgetConfigActivity.kt", "app/src/main/kotlin/com/launchpoint/wavdrop/ui/widget/WidgetConfigActivity.kt")
            .map(::File).first { it.exists() }.readText()

    @Test fun theActivityReportsOkOnlyFromASavedResultAndGuardsDuplicateTaps() {
        val src = activitySource()
        val save = src.substringAfter("private fun save")
        assertTrue("duplicate taps ignored up front", save.contains("if (saving) return"))
        assertEquals("RESULT_OK is set exactly once, in the Saved branch", 1, Regex("RESULT_OK").findAll(codeOnly(src.substringAfter("override fun onCreate"))).count())
        val savedBranch = save.substringAfter("Result.Saved ->").substringBefore("Result.Failed ->")
        assertTrue(savedBranch.contains("RESULT_OK") && savedBranch.contains("finish()"))
        val failedBranch = save.substringAfter("Result.Failed ->").substringBefore("Result.Busy ->")
        assertTrue("failure shows an error and re-enables Save instead of spinning", failedBranch.contains("saveFailed = true") && failedBranch.contains("saving = false") && !failedBranch.contains("RESULT_OK"))
        assertTrue("the first render is awaited through the existing coordinator", src.contains("WavdropWidgetUpdater.requestUpdateAndAwait(applicationContext, id)"))
        assertFalse("no fire-and-forget request remains in the Save path", save.contains("requestUpdate(applicationContext"))
    }

    @Test fun theManifestKeepsTheConfigScreenFromBeingRecreatedMidSave() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        val block = manifest.substringAfter("android:name=\".ui.widget.WidgetConfigActivity\"").substringBefore("</activity>")
        listOf("orientation", "screenSize", "uiMode", "fontScale").forEach { assertTrue("configChanges handles $it", block.contains(it)) }
    }

    /** Source with whole-line `//` comments removed, so a comment mentioning a result code is not counted as a call. */
    private fun codeOnly(source: String): String = source.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
}
