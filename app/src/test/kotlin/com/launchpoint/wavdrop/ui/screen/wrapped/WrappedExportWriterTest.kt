package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** P5-WR-VID-02A2: ordering, atomic writes, ownership and cleanup of the slide generator, with a fake bitmap. */
class WrappedExportWriterTest {

    private class FakeBitmap(val w: Int = 720, val h: Int = 1280)

    private lateinit var base: File

    @Before fun setUp() { base = Files.createTempDirectory("wrapped-export-test").toFile() }
    @After fun tearDown() { base.deleteRecursively() }

    private val summary = WrappedSummary(
        period = WrappedPeriod.AllTime,
        totalPlayCount = 1, totalSkipCount = 0, totalListeningTimeMs = 0L,
        uniqueSongsPlayedCount = 0, uniqueArtistsPlayedCount = 0, uniqueAlbumsPlayedCount = 0,
        listeningDaysCount = 0, busiestDay = null, busiestDayPlayCount = 0, averagePlaysPerActiveDay = 0.0,
        topSongs = emptyList(), topArtists = emptyList(), topAlbums = emptyList(),
        mostPlayedSong = null, mostPlayedArtist = null, mostPlayedAlbum = null, mostSkippedTrack = null,
        recentlyPlayed = emptyList(),
        emptyState = ListeningAnalyticsEmptyState(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, true, true),
        longestStreak = 0, currentStreak = 0, mostActiveDayOfWeek = null, mostActiveHour = null,
        averageListeningTimePerActiveDayMs = 0L, mostReplayedTrack = null,
    )

    /** All Time: 7 pages. */
    private fun plan(id: String) = buildWrappedExportPlan(
        summary,
        WrappedVisualSettings(false, WrappedBackgroundIntensity.MEDIUM, WrappedFallbackTheme.AUTO),
        WrappedVisualStyle.DEFAULT, true, exportId = id, artworkUriFor = { null },
    )

    private class Tracker {
        var live = 0
        var maxLive = 0
        var recycled = 0
        fun create(): FakeBitmap { live++; maxLive = maxOf(maxLive, live); return FakeBitmap() }
        fun recycle() { live--; recycled++ }
    }

    private fun run(
        p: WrappedExportPlan,
        t: Tracker,
        render: suspend (WrappedExportPage) -> FakeBitmap? = { t.create() },
        encode: (FakeBitmap, OutputStream) -> Boolean = { _, out -> out.write(byteArrayOf(1, 2, 3)); true },
        onUnexpected: (String, Exception) -> Unit = { _, _ -> },
    ) = runBlocking {
        generateWrappedSlides(p, base, render, { it.w to it.h }, encode, { t.recycle() }, onUnexpected)
    }

    @Test fun `success writes ordered slides and a manifest and nothing else`() {
        val p = plan("exp-1")
        val out = run(p, Tracker()) as WrappedExportOutcome.Success
        assertEquals(wrappedExportDirectory(base, "exp-1"), out.directory)
        assertEquals(7, out.entries.size)
        assertEquals((p.pages.map { it.fileName } + "manifest.json").toSet(), out.directory.list()!!.toSet())
        assertTrue(out.entries.all { it.widthPx == 720 && it.heightPx == 1280 && it.durationMs == 3_000 })
        assertEquals(p.pages.map { it.id }, out.entries.map { it.pageId })
        assertTrue(File(out.directory, "manifest.json").readText().contains("\"pageCount\":7"))
        assertTrue("no .part files remain", out.directory.list()!!.none { it.endsWith(".part") })
    }

    @Test fun `at most one slide bitmap is resident and every one is recycled`() {
        val t = Tracker()
        run(plan("exp-2"), t)
        assertEquals(1, t.maxLive)
        assertEquals(7, t.recycled)
        assertEquals(0, t.live)
    }

    @Test fun `an existing export directory is never reused or overwritten`() {
        val existing = wrappedExportDirectory(base, "exp-3").also { it.mkdirs() }
        val keep = File(existing, "slide_01_intro.png").also { it.writeText("old") }
        val out = run(plan("exp-3"), Tracker()) as WrappedExportOutcome.Failed
        assertEquals(WrappedExportOutcome.Reason.SetupFailed, out.reason)
        assertTrue(keep.exists())
        assertEquals("old", keep.readText())
    }

    @Test fun `two exports coexist and unrelated files are untouched`() {
        val stranger = File(base, "unrelated.txt").also { it.writeText("x") }
        val otherDir = File(base, "someone_elses_dir").also { it.mkdirs() }
        val first = run(plan("exp-a"), Tracker()) as WrappedExportOutcome.Success
        val second = run(plan("exp-b"), Tracker()) as WrappedExportOutcome.Success
        assertTrue(first.directory.isDirectory && second.directory.isDirectory)
        assertTrue(stranger.exists() && otherDir.isDirectory)
    }

    @Test fun `a failed render on a later page removes the whole export and keeps neighbours`() {
        val keep = File(base, "keep.txt").also { it.writeText("k") }
        val prior = run(plan("exp-ok"), Tracker()) as WrappedExportOutcome.Success
        val t = Tracker()
        val out = run(plan("exp-fail"), t, render = { page -> if (page.index == 3) null else t.create() })
            as WrappedExportOutcome.Failed
        assertEquals(3, out.pageIndex)
        assertEquals(WrappedExportOutcome.Reason.RenderFailed, out.reason)
        assertFalse(wrappedExportDirectory(base, "exp-fail").exists())
        assertTrue(keep.exists() && prior.directory.isDirectory)
        assertEquals("pages 0-2 were produced then recycled", 3, t.recycled)
        assertEquals(0, t.live)
    }

    @Test fun `an unexpected exception is reported cleaned up and not swallowed silently`() {
        val t = Tracker()
        val seen = ArrayList<String>()
        val out = run(
            plan("exp-boom"), t,
            render = { page -> if (page.index == 2) throw IllegalStateException("boom") else t.create() },
            onUnexpected = { stage, _ -> seen += stage },
        ) as WrappedExportOutcome.Failed
        assertEquals(WrappedExportOutcome.Reason.Unexpected, out.reason)
        assertEquals(listOf("render"), seen)
        assertFalse(wrappedExportDirectory(base, "exp-boom").exists())
    }

    @Test fun `a failed encode removes the export and recycles the bitmap`() {
        val t = Tracker()
        val out = run(plan("exp-enc"), t, encode = { _, out -> out.write(1); false }) as WrappedExportOutcome.Failed
        assertEquals(WrappedExportOutcome.Reason.EncodeFailed, out.reason)
        assertEquals(0, out.pageIndex)
        assertFalse(wrappedExportDirectory(base, "exp-enc").exists())
        assertEquals(0, t.live)
    }

    @Test fun `an empty encode is rejected and leaves no partial file`() {
        val out = run(plan("exp-empty"), Tracker(), encode = { _, _ -> true }) as WrappedExportOutcome.Failed
        assertEquals(WrappedExportOutcome.Reason.EncodeFailed, out.reason)
        assertFalse(wrappedExportDirectory(base, "exp-empty").exists())
    }

    @Test fun `cancellation after pages were written stops further pages and removes the export`() {
        val t = Tracker()
        val thirdPageStarted = CompletableDeferred<Unit>()
        val rendered = ArrayList<Int>()
        runBlocking {
            val job = launch(Dispatchers.Default) {
                generateWrappedSlides(
                    plan("exp-cancel"), base,
                    render = { page ->
                        rendered += page.index
                        if (page.index == 2) {
                            thirdPageStarted.complete(Unit)
                            awaitCancellation()
                        }
                        t.create()
                    },
                    sizeOf = { it.w to it.h },
                    encode = { _, out -> out.write(9); true },
                    recycle = { t.recycle() },
                )
            }
            thirdPageStarted.await()
            // pages 0 and 1 are already on disk at this point
            assertEquals(2, wrappedExportDirectory(base, "exp-cancel").list()!!.count { it.endsWith(".png") })
            job.cancelAndJoin()
            assertTrue("cancellation is not converted into a result", job.isCancelled)
        }
        assertEquals("no page after the cancelled one was started", listOf(0, 1, 2), rendered)
        assertFalse(wrappedExportDirectory(base, "exp-cancel").exists())
        assertEquals(0, t.live)
    }

    /** Runs [block] and reports whether it ended by throwing CancellationException (it must never return a result). */
    private fun cancelledWith(block: suspend () -> WrappedExportOutcome): Boolean = runBlocking {
        try { block(); false } catch (e: kotlin.coroutines.cancellation.CancellationException) { true }
    }

    @Test fun `cancellation raised during encoding is rethrown not converted to a failure`() {
        val stranger = File(base, "unrelated.txt").also { it.writeText("keep") }
        val t = Tracker()
        val rendered = ArrayList<Int>()
        var encodeCalls = 0
        val cancelled = cancelledWith {
            generateWrappedSlides(
                plan("enc-cancel"), base,
                render = { page -> rendered += page.index; t.create() },
                sizeOf = { it.w to it.h },
                encode = { _, out ->
                    out.write(1)
                    if (++encodeCalls == 2) throw kotlin.coroutines.cancellation.CancellationException("encode cancelled")
                    true
                },
                recycle = { t.recycle() },
            )
        }
        assertTrue("cancellation must propagate", cancelled)
        assertEquals("no page after the cancelled one was rendered", listOf(0, 1), rendered)
        assertFalse("incomplete export removed", wrappedExportDirectory(base, "enc-cancel").exists())
        assertEquals("bitmaps released", 0, t.live)
        assertEquals(2, t.recycled)
        assertTrue("unrelated files preserved", stranger.exists() && stranger.readText() == "keep")
    }

    @Test fun `cancellation raised while sizing the bitmap is rethrown and cleaned up`() {
        val stranger = File(base, "unrelated.txt").also { it.writeText("keep") }
        val t = Tracker()
        val rendered = ArrayList<Int>()
        var sizeCalls = 0
        val cancelled = cancelledWith {
            generateWrappedSlides(
                plan("size-cancel"), base,
                render = { page -> rendered += page.index; t.create() },
                sizeOf = {
                    if (++sizeCalls == 3) throw kotlin.coroutines.cancellation.CancellationException("size cancelled")
                    it.w to it.h
                },
                encode = { _, out -> out.write(1); true },
                recycle = { t.recycle() },
            )
        }
        assertTrue(cancelled)
        assertEquals(listOf(0, 1, 2), rendered)
        assertFalse(wrappedExportDirectory(base, "size-cancel").exists())
        assertEquals(0, t.live)
        assertEquals(3, t.recycled)
        assertTrue(stranger.exists())
    }

    @Test fun `cancellation raised while rendering is still rethrown`() {
        val t = Tracker()
        val cancelled = cancelledWith {
            generateWrappedSlides(
                plan("render-cancel"), base,
                render = { page ->
                    if (page.index == 1) throw kotlin.coroutines.cancellation.CancellationException("render cancelled")
                    t.create()
                },
                sizeOf = { it.w to it.h }, encode = { _, out -> out.write(1); true }, recycle = { t.recycle() },
            )
        }
        assertTrue(cancelled)
        assertFalse(wrappedExportDirectory(base, "render-cancel").exists())
        assertEquals(0, t.live)
    }

    @Test fun `a bitmap is not recycled while an encode is still running when the export is cancelled`() {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val encodeStarted = java.util.concurrent.CountDownLatch(1)
        val releaseEncode = java.util.concurrent.CountDownLatch(1)
        val t = Tracker()
        val stranger = File(base, "unrelated.txt").also { it.writeText("keep") }
        runBlocking {
            val job = launch(Dispatchers.Default) {
                generateWrappedSlides(
                    plan("mid-encode"), base,
                    render = { t.create() },
                    sizeOf = { it.w to it.h },
                    encode = { _, out ->
                        out.write(1)
                        encodeStarted.countDown()
                        releaseEncode.await() // blocking native-style work that cannot observe cancellation
                        events += "encode-finished"
                        true
                    },
                    recycle = { events += "recycle"; t.recycle() },
                )
            }
            kotlinx.coroutines.withContext(Dispatchers.IO) { encodeStarted.await() }
            job.cancel() // cancelled while the encode is still active
            assertTrue("recycle must not have happened yet", events.isEmpty())
            releaseEncode.countDown()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(listOf("encode-finished", "recycle"), events.toList())
        assertEquals(0, t.live)
        assertFalse(wrappedExportDirectory(base, "mid-encode").exists())
        assertTrue(stranger.exists())
    }

    @Test fun `sizing encoding and file writes run on the IO dispatcher while rendering stays on the caller`() {
        val callerThread = Thread.currentThread().name
        val renderThreads = ArrayList<String>()
        val sizeThreads = ArrayList<String>()
        val encodeThreads = ArrayList<String>()
        val out = runBlocking {
            generateWrappedSlides(
                plan("io-thread"), base,
                render = { renderThreads += Thread.currentThread().name; FakeBitmap() },
                sizeOf = { sizeThreads += Thread.currentThread().name; it.w to it.h },
                encode = { _, o -> encodeThreads += Thread.currentThread().name; o.write(1); true },
                recycle = {},
            )
        }
        assertTrue(out is WrappedExportOutcome.Success)
        assertEquals(7, encodeThreads.size)
        // Coroutine debug mode appends " @coroutine#N" to the thread name, so compare the stable prefix.
        assertTrue("render stays on the caller's context: $renderThreads", renderThreads.all { it.startsWith(callerThread) })
        // The production default is Dispatchers.IO: its worker threads are named DefaultDispatcher-worker-N.
        assertTrue("encode off the caller thread: $encodeThreads", encodeThreads.none { it.startsWith(callerThread) })
        assertTrue("encode on IO: $encodeThreads", encodeThreads.all { it.startsWith("DefaultDispatcher-worker") })
        assertTrue("sizing on IO: $sizeThreads", sizeThreads.all { it.startsWith("DefaultDispatcher-worker") })
    }

    /**
     * Deterministic stand-in for the IO dispatcher: runs each dispatched block inline on the calling thread and, right
     * after the first one (the directory-creation step) has finished but BEFORE its result is handed back to the
     * parent coroutine, cancels [target]. This reproduces "cancelled between a successful mkdir and the dispatcher
     * return" without any sleeps or thread races: the parent's resumption is queued on the runBlocking event loop and
     * cannot run until the cancel call below has returned.
     */
    private class CancelAfterFirstDispatch : kotlinx.coroutines.CoroutineDispatcher() {
        var target: kotlinx.coroutines.Job? = null
        var armed = true
        var dispatches = 0
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
            if (armed) { armed = false; target?.cancel() }
        }
    }

    @Test fun `cancellation between a successful mkdir and the dispatcher return still removes the owned directory`() {
        val stranger = File(base, "unrelated.txt").also { it.writeText("keep") }
        val neighbour = wrappedExportDirectory(base, "other-export").also { it.mkdirs() }
        File(neighbour, "slide_01_intro.png").writeText("theirs")
        val hook = CancelAfterFirstDispatch()
        val t = Tracker()
        var rendered = 0
        var encoded = 0
        runBlocking {
            val job = launch {
                generateWrappedSlides(
                    plan("setup-cancel"), base,
                    render = { rendered++; t.create() },
                    sizeOf = { it.w to it.h },
                    encode = { _, out -> encoded++; out.write(1); true },
                    recycle = { t.recycle() },
                    ioDispatcher = hook,
                )
            }
            hook.target = job
            job.join()
            assertTrue("cancellation propagates, it is not turned into a result", job.isCancelled)
        }
        assertTrue("the creation step really ran and was then cancelled", hook.dispatches >= 2 && !hook.armed)
        assertFalse("the directory this call created is removed", wrappedExportDirectory(base, "setup-cancel").exists())
        assertEquals("nothing rendered after setup cancellation", 0, rendered)
        assertEquals("nothing encoded after setup cancellation", 0, encoded)
        assertEquals(0, t.live)
        assertTrue("shared base directory kept", base.isDirectory)
        assertTrue("unrelated file kept", stranger.readText() == "keep")
        assertTrue("another export's directory kept", File(neighbour, "slide_01_intro.png").readText() == "theirs")
    }

    @Test fun `a directory that existed before the call is never deleted even if the call is cancelled`() {
        val existing = wrappedExportDirectory(base, "pre-existing").also { it.mkdirs() }
        val keep = File(existing, "slide_01_intro.png").also { it.writeText("old") }
        val hook = CancelAfterFirstDispatch()
        var rendered = 0
        var outcome: WrappedExportOutcome? = null
        var cancelledJob = false
        runBlocking {
            val job = launch {
                outcome = generateWrappedSlides(
                    plan("pre-existing"), base,
                    render = { rendered++; FakeBitmap() },
                    sizeOf = { it.w to it.h }, encode = { _, out -> out.write(1); true }, recycle = {},
                    ioDispatcher = hook,
                )
            }
            hook.target = job
            job.join()
            cancelledJob = job.isCancelled
        }
        assertEquals(0, rendered)
        // mkdir failed, so nothing is owned. The caller was cancelled meanwhile, so it receives the cancellation
        // rather than a stale SetupFailed result; the pre-existing directory is left alone either way.
        assertTrue("cancellation propagates when setup fails for a cancelled caller", cancelledJob)
        assertEquals("no result is produced for a cancelled caller", null, outcome)
        assertTrue(existing.isDirectory)
        assertEquals("old", keep.readText())
    }

    /**
     * Two genuinely different single-thread dispatchers. The caller runs on [caller]; the writer's IO work runs on
     * [io]. On the FIRST io dispatch (directory creation) the io thread (1) tells [caller] to hold every task, (2) runs
     * the creation block, (3) records that the directory exists, (4) cancels the caller's job and only then
     * (5) releases the caller. So the caller is cancelled after a real mkdir and strictly before the result can be
     * delivered back to it, with latch-based ordering and no sleeps.
     */
    @Test fun `caller cancelled after mkdir on a different dispatcher still cleans up and renders nothing`() {
        val callerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-caller") }
        val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-io") }
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        val ioDelegate = ioExecutor.asCoroutineDispatcher()
        try {
            val stranger = File(base, "unrelated.txt").also { it.writeText("keep") }
            val neighbour = wrappedExportDirectory(base, "bystander").also { it.mkdirs() }
            File(neighbour, "slide_01_intro.png").writeText("theirs")
            val target = wrappedExportDirectory(base, "two-dispatchers")

            val holdCaller = java.util.concurrent.atomic.AtomicBoolean(false)
            val cancelDone = java.util.concurrent.CountDownLatch(1)
            val firstIo = java.util.concurrent.atomic.AtomicBoolean(true)
            lateinit var job: kotlinx.coroutines.Job
            var existedWhenCancelled = false
            var creationThread = ""

            val heldCaller = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    callerDispatcher.dispatch(context, Runnable {
                        if (holdCaller.get()) cancelDone.await()
                        block.run()
                    })
                }
            }
            val controlledIo = object : kotlinx.coroutines.CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    ioDelegate.dispatch(context, Runnable {
                        val first = firstIo.getAndSet(false)
                        if (first) holdCaller.set(true)
                        block.run()
                        if (first) {
                            creationThread = Thread.currentThread().name
                            existedWhenCancelled = target.isDirectory
                            job.cancel()
                            cancelDone.countDown()
                        }
                    })
                }
            }

            val t = Tracker()
            var rendered = 0
            var encoded = 0
            var result: WrappedExportOutcome? = null
            job = kotlinx.coroutines.CoroutineScope(heldCaller).launch {
                result = generateWrappedSlides(
                    plan("two-dispatchers"), base,
                    render = { rendered++; t.create() },
                    sizeOf = { it.w to it.h },
                    encode = { _, out -> encoded++; out.write(1); true },
                    recycle = { t.recycle() },
                    ioDispatcher = controlledIo,
                )
            }
            runBlocking { job.join() }

            assertEquals("creation ran on the io dispatcher", "test-io", creationThread)
            assertTrue("the directory was really created before the cancel", existedWhenCancelled)
            assertTrue("cancellation propagated", job.isCancelled)
            assertEquals("no result for a cancelled caller", null, result)
            assertFalse("owned directory removed by the cleanup", target.exists())
            assertEquals("no page rendering began", 0, rendered)
            assertEquals(0, encoded)
            assertEquals(0, t.live)
            assertTrue("base directory kept", base.isDirectory)
            assertTrue("unrelated file kept", stranger.readText() == "keep")
            assertEquals("unrelated directory kept", "theirs", File(neighbour, "slide_01_intro.png").readText())
        } finally {
            callerExecutor.shutdownNow()
            ioExecutor.shutdownNow()
        }
    }

    @Test fun `an uncancelled export through an inline dispatcher is unchanged`() {
        val hook = CancelAfterFirstDispatch().also { it.armed = false }
        val p = plan("inline-ok")
        val out = runBlocking {
            generateWrappedSlides(
                p, base, render = { FakeBitmap() }, sizeOf = { it.w to it.h },
                encode = { _, o -> o.write(1); true }, recycle = {}, ioDispatcher = hook,
            )
        } as WrappedExportOutcome.Success
        assertEquals(7, out.entries.size)
        assertEquals((p.pages.map { it.fileName } + "manifest.json").toSet(), out.directory.list()!!.toSet())
        assertTrue(File(out.directory, "manifest.json").readText().contains("\"pageCount\":7"))
    }

    @Test fun `manifest lists page identity dimensions duration and file in order`() {
        val p = plan("exp-man")
        val out = run(p, Tracker()) as WrappedExportOutcome.Success
        val json = wrappedExportManifestJson(p, out.entries)
        assertTrue(json.startsWith("{\"exportId\":\"exp-man\""))
        assertTrue(json.contains("\"page\":\"INTRO\",\"file\":\"slide_01_intro.png\",\"width\":720,\"height\":1280,\"durationMs\":3000"))
        assertTrue(json.indexOf("slide_01_intro") < json.indexOf("slide_07_recent_plays"))
    }
}
