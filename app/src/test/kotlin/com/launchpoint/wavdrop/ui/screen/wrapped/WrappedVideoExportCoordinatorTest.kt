package com.launchpoint.wavdrop.ui.screen.wrapped

import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.MonthYear
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P5-WR-VID-02C coordinator state machine. Pure JVM: slide generation and encoding are fakes that suspend on explicit
 * gates, so every ordering below is decided by the test, never by a sleep.
 */
class WrappedVideoExportCoordinatorTest {

    private val settings = WrappedVisualSettings(true, WrappedBackgroundIntensity.MEDIUM, WrappedFallbackTheme.AUTO)
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private lateinit var guard: WrappedVideoExportGuard

    @Before fun setUp() {
        root = Files.createTempDirectory("wrapped_video_export_test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        guard = WrappedVideoExportGuard()
    }

    @After fun tearDown() {
        // Let any still-unwinding run finish its (NonCancellable) cleanup BEFORE the temp directory goes away.
        val job = scope.coroutineContext[kotlinx.coroutines.Job]!!
        scope.cancel()
        runBlocking { withTimeout(15_000) { job.join() } }
        root.deleteRecursively()
    }

    private fun summary(period: WrappedPeriod, empty: Boolean = false) = WrappedSummary(
        period = period,
        totalPlayCount = if (empty) 0 else 10, totalSkipCount = 0, totalListeningTimeMs = 0L,
        uniqueSongsPlayedCount = 0, uniqueArtistsPlayedCount = 0, uniqueAlbumsPlayedCount = 0,
        listeningDaysCount = 0, busiestDay = null, busiestDayPlayCount = 0, averagePlaysPerActiveDay = 0.0,
        topSongs = emptyList(), topArtists = emptyList(), topAlbums = emptyList(),
        mostPlayedSong = null, mostPlayedArtist = null, mostPlayedAlbum = null, mostSkippedTrack = null,
        recentlyPlayed = emptyList(),
        emptyState = ListeningAnalyticsEmptyState(
            if (empty) ListeningAnalyticsEmptyReason.NO_EVENTS_IN_RANGE else ListeningAnalyticsEmptyReason.HAS_ACTIVITY,
            !empty, !empty,
        ),
        longestStreak = 0, currentStreak = 0, mostActiveDayOfWeek = null, mostActiveHour = null,
        averageListeningTimePerActiveDayMs = 0L, mostReplayedTrack = null,
    )

    private fun plan(
        period: WrappedPeriod = WrappedPeriod.AllTime,
        id: String = "exp-1",
    ) = buildWrappedExportPlan(summary(period), settings, WrappedVisualStyle.DEFAULT, true, exportId = id, artworkUriFor = { null })

    private fun slidesDir(id: String) = File(root, WRAPPED_EXPORT_DIR_PREFIX + id).also { it.mkdirs() }
    private fun success(id: String) = WrappedExportOutcome.Success(slidesDir(id), emptyList())
    private fun video(id: String) = File(root, "wavdrop_wrapped_$id.mp4").also { it.writeBytes(ByteArray(64) { 1 }) }
    private val info = WrappedVideoInfo("video/avc", 1, 0, 720, 1280, 0, 1000)
    private fun completed(f: File) = WrappedVideoOutcome.Completed(f, info, f.length(), 5)


    private fun coordinator(
        generate: suspend (WrappedExportPlan) -> WrappedExportOutcome = { success(it.exportId) },
        encode: suspend (WrappedExportOutcome.Success, WrappedExportPlan, (WrappedVideoState) -> Unit) -> WrappedVideoOutcome =
            { _, p, _ -> completed(video(p.exportId)) },
        deleteSlides: (File) -> Boolean = { it.deleteRecursively() },
        onCleanupProblem: (String) -> Unit = {},
        sweep: () -> Int = { 0 },
        g: WrappedVideoExportGuard = guard,
    ) = WrappedVideoExportCoordinator(
        scope = scope, generateSlides = generate, encode = encode, sweepOrphans = sweep,
        deleteSlides = deleteSlides, onCleanupProblem = onCleanupProblem, guard = g, ioDispatcher = Dispatchers.Default,
    )

    private suspend fun WrappedVideoExportCoordinator.await(what: String, p: (WrappedVideoExportState) -> Boolean): WrappedVideoExportState =
        withTimeout(15_000) { state.first(p) }.also { assertNotNull(what, it) }

    private fun blocking(block: suspend () -> Any?) { runBlocking { withTimeout(30_000) { block() } } }

    // ---- eligibility and snapshot ------------------------------------------------------------------------

    @Test fun `export is offered only for a report that has content`() {
        assertTrue(isWrappedVideoExportEligible(summary(WrappedPeriod.AllTime)))
        assertFalse(isWrappedVideoExportEligible(summary(WrappedPeriod.AllTime, empty = true)))
    }

    @Test fun `the export uses exactly the plan it was started with and reports that period`() = blocking {
        val monthly = plan(WrappedPeriod.month(MonthYear(2025, 6), ZoneOffset.UTC), "exp-m")
        val other = plan(WrappedPeriod.year(2024, ZoneOffset.UTC), "exp-y") // "the user changed the report meanwhile"
        val seen = ArrayList<WrappedExportPlan>()
        val gate = CompletableDeferred<Unit>()
        val c = coordinator(
            generate = { p -> seen += p; gate.await(); success(p.exportId) },
            encode = { _, p, _ -> seen += p; completed(video(p.exportId)) },
        )
        assertTrue(c.start(monthly))
        val preparing = c.state.value as WrappedVideoExportState.Preparing
        assertEquals(monthly.periodLabel, preparing.periodLabel)
        assertEquals(monthly.pageCount, preparing.pageCount)
        gate.complete(Unit)
        val done = c.await("completed") { it is WrappedVideoExportState.Completed } as WrappedVideoExportState.Completed
        assertEquals(monthly.reportKey, done.reportKey)
        assertEquals(monthly.periodLabel, done.periodLabel)
        assertTrue("both stages saw the same immutable snapshot", seen.all { it === monthly })
        assertFalse(seen.contains(other))
    }

    // ---- duplicate taps / overlap ------------------------------------------------------------------------

    @Test fun `a second tap while active starts nothing and a second coordinator cannot overlap either`() = blocking {
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val c = coordinator(generate = { p -> started.incrementAndGet(); gate.await(); success(p.exportId) })
        assertTrue(c.start(plan(id = "a")))
        assertFalse(c.start(plan(id = "b")))
        val c2 = coordinator()
        assertFalse("process-wide guard", c2.start(plan(id = "c")))
        gate.complete(Unit)
        c.await("done") { it is WrappedVideoExportState.Completed }
        c.joinCurrent()
        assertEquals(1, started.get())
        assertFalse(guard.isHeld)
    }

    // ---- progress -----------------------------------------------------------------------------------------

    @Test fun `preparation shows the page count with no percentage then encoding starts with unknown progress then reports percent`() = blocking {
        val p = plan(id = "prog")
        val genGate = CompletableDeferred<Unit>()
        val encStarted = CompletableDeferred<Unit>()
        val reported = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val c = coordinator(
            generate = { genGate.await(); success(it.exportId) },
            encode = { _, pl, onState ->
                encStarted.complete(Unit)
                onState(WrappedVideoState.Preparing) // encoder-internal states must not leak into the UI
                finish.await()
                onState(WrappedVideoState.Encoding(42))
                reported.complete(Unit)
                finish.await()
                completed(video(pl.exportId))
            },
        )
        c.start(p)
        val prep = c.state.value
        assertTrue(prep is WrappedVideoExportState.Preparing)
        assertFalse("no invented percentage while preparing", wrappedVideoExportStatusText(prep)!!.contains("%"))
        assertTrue(wrappedVideoExportStatusText(prep)!!.contains("${p.pageCount} slides"))
        genGate.complete(Unit)
        encStarted.await()
        val unknown = c.state.value as WrappedVideoExportState.Encoding
        assertNull("unknown progress stays unknown", unknown.percent)
        assertFalse(wrappedVideoExportStatusText(unknown)!!.contains("%"))
        finish.complete(Unit)
        reported.await()
        assertEquals(42, (c.state.value as WrappedVideoExportState.Encoding).percent)
        assertTrue(wrappedVideoExportStatusText(c.state.value)!!.contains("42%"))
        c.await("done") { it is WrappedVideoExportState.Completed }
    }

    // ---- cancellation -------------------------------------------------------------------------------------

    @Test fun `cancel during slide preparation cancels generation, never starts the encoder and releases everything`() = blocking {
        val genStarted = CompletableDeferred<Unit>()
        val genCancelled = CompletableDeferred<Unit>()
        val encodeCalls = AtomicInteger()
        val c = coordinator(
            generate = {
                genStarted.complete(Unit)
                try { awaitCancellation() } finally { genCancelled.complete(Unit) }
            },
            encode = { _, p, _ -> encodeCalls.incrementAndGet(); completed(video(p.exportId)) },
        )
        c.start(plan(id = "cp"))
        genStarted.await()
        c.cancel()
        assertTrue(c.state.value is WrappedVideoExportState.Cancelling)
        genCancelled.await()
        assertTrue(c.await("cancelled") { it is WrappedVideoExportState.Cancelled } is WrappedVideoExportState.Cancelled)
        c.joinCurrent()
        assertEquals(0, encodeCalls.get())
        assertFalse(guard.isHeld)
    }

    @Test fun `cancel during encoding cancels the encoder and removes this export's slide directory`() = blocking {
        val dirs = ArrayList<File>()
        val encStarted = CompletableDeferred<Unit>()
        val encCancelled = CompletableDeferred<Unit>()
        val c = coordinator(
            generate = { success(it.exportId).also { s -> dirs += s.directory } },
            encode = { _, _, _ ->
                encStarted.complete(Unit)
                try { awaitCancellation() } finally { encCancelled.complete(Unit) }
            },
        )
        c.start(plan(id = "ce"))
        encStarted.await()
        c.cancel()
        encCancelled.await()
        c.await("cancelled") { it is WrappedVideoExportState.Cancelled }
        c.joinCurrent()
        assertFalse("slide dir removed", dirs.single().exists())
        assertFalse(guard.isHeld)
    }

    @Test fun `cancelling immediately after start still cleans up and releases the guard`() = blocking {
        val c = coordinator(generate = { awaitCancellation() })
        c.start(plan(id = "imm"))
        c.cancel()
        c.joinCurrent()
        assertTrue(c.state.value is WrappedVideoExportState.Cancelled)
        assertFalse(guard.isHeld)
        assertTrue("a new export may start afterwards", coordinator().start(plan(id = "after")))
    }

    @Test fun `leaving the screen cancels the export and removes its intermediates but keeps no active state`() = blocking {
        val dirs = ArrayList<File>()
        val encStarted = CompletableDeferred<Unit>()
        val c = coordinator(
            generate = { success(it.exportId).also { s -> dirs += s.directory } },
            encode = { _, _, _ -> encStarted.complete(Unit); awaitCancellation() },
        )
        c.start(plan(id = "leave"))
        encStarted.await()
        c.release()
        c.joinCurrent()
        assertFalse(dirs.single().exists())
        assertFalse(c.state.value.isActive)
        assertFalse(guard.isHeld)
    }

    @Test fun `cancel is a no-op for Idle and for a finished export`() = blocking {
        val c = coordinator()
        c.cancel()
        assertEquals(WrappedVideoExportState.Idle, c.state.value)
        c.start(plan(id = "fin"))
        val done = c.await("done") { it is WrappedVideoExportState.Completed } as WrappedVideoExportState.Completed
        c.joinCurrent()
        c.cancel()
        assertSame("state untouched", done, c.state.value)
        assertTrue(done.file.exists())
    }

    // ---- publication policy -------------------------------------------------------------------------------

    @Test fun `a cancel that arrives at the commit point keeps the published MP4 and reports Completed`() = blocking {
        lateinit var c: WrappedVideoExportCoordinator
        val dirs = ArrayList<File>()
        c = coordinator(
            generate = { success(it.exportId).also { s -> dirs += s.directory } },
            encode = { _, p, _ ->
                val f = video(p.exportId) // the encoder has already published the file ...
                c.cancel() // ... when the cancel lands
                completed(f)
            },
        )
        c.start(plan(id = "commit"))
        val done = c.await("completed") { it is WrappedVideoExportState.Completed || it is WrappedVideoExportState.Cancelled }
        assertTrue("published video is not relabelled as cancelled", done is WrappedVideoExportState.Completed)
        c.joinCurrent()
        assertTrue((done as WrappedVideoExportState.Completed).file.exists())
        assertFalse("slides still cleaned", dirs.single().exists())
        assertTrue("final state stays Completed", c.state.value is WrappedVideoExportState.Completed)
    }

    @Test fun `the published MP4 survives success, slide cleanup and a later dismiss`() = blocking {
        val c = coordinator()
        c.start(plan(id = "keep"))
        val done = c.await("done") { it is WrappedVideoExportState.Completed } as WrappedVideoExportState.Completed
        c.joinCurrent()
        c.dismiss()
        assertTrue(done.file.exists())
        assertEquals(64L, done.file.length())
    }

    // ---- failure / retry ----------------------------------------------------------------------------------

    @Test fun `a slide failure is reported and the encoder is never called`() = blocking {
        val encodeCalls = AtomicInteger()
        val c = coordinator(
            generate = { WrappedExportOutcome.Failed(2, WrappedExportOutcome.Reason.RenderFailed) },
            encode = { _, p, _ -> encodeCalls.incrementAndGet(); completed(video(p.exportId)) },
        )
        c.start(plan(id = "sf"))
        val f = c.await("failed") { it is WrappedVideoExportState.Failed } as WrappedVideoExportState.Failed
        assertEquals(WrappedVideoExportFailure.SlidesFailed, f.reason)
        c.joinCurrent()
        assertEquals(0, encodeCalls.get())
        assertTrue(wrappedVideoExportStatusText(f)!!.contains("slides"))
    }

    @Test fun `retry after an encoding failure completes and the failed attempt left no slides behind`() = blocking {
        val attempt = AtomicInteger()
        val dirs = ArrayList<File>()
        val c = coordinator(
            generate = { p -> success(p.exportId).also { dirs += it.directory } },
            encode = { _, p, _ ->
                if (attempt.incrementAndGet() == 1) WrappedVideoOutcome.Failed(WrappedVideoFailure.EngineFailed, "boom")
                else completed(video(p.exportId))
            },
        )
        c.start(plan(id = "r1"))
        val f = c.await("failed") { it is WrappedVideoExportState.Failed } as WrappedVideoExportState.Failed
        assertEquals(WrappedVideoExportFailure.EncodingFailed, f.reason)
        c.joinCurrent()
        assertFalse(dirs[0].exists())
        assertTrue("retry accepted", c.start(plan(id = "r2")))
        c.await("completed") { it is WrappedVideoExportState.Completed }
        c.joinCurrent()
        assertFalse(dirs[1].exists())
    }

    @Test fun `an unexpected exception becomes a failure and the guard is released`() = blocking {
        val c = coordinator(generate = { throw IllegalStateException("x") })
        c.start(plan(id = "ux"))
        val f = c.await("failed") { it is WrappedVideoExportState.Failed } as WrappedVideoExportState.Failed
        assertEquals(WrappedVideoExportFailure.Unexpected, f.reason)
        c.joinCurrent()
        assertFalse(guard.isHeld)
    }

    // ---- stale callbacks ----------------------------------------------------------------------------------

    @Test fun `callbacks from an old export can never change a newer export`() = blocking {
        var oldCallback: ((WrappedVideoState) -> Unit)? = null
        val firstEncStarted = CompletableDeferred<Unit>()
        val secondProgress = CompletableDeferred<Unit>()
        val secondFinish = CompletableDeferred<Unit>()
        val n = AtomicInteger()
        val c = coordinator(
            encode = { _, p, onState ->
                if (n.incrementAndGet() == 1) {
                    oldCallback = onState
                    firstEncStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    onState(WrappedVideoState.Encoding(7))
                    secondProgress.complete(Unit)
                    secondFinish.await()
                    completed(video(p.exportId))
                }
            },
        )
        c.start(plan(id = "old"))
        firstEncStarted.await()
        c.cancel()
        c.await("cancelled") { it is WrappedVideoExportState.Cancelled }
        c.joinCurrent()
        c.start(plan(id = "new"))
        secondProgress.await()
        oldCallback!!(WrappedVideoState.Encoding(99)) // late callback of the first export
        oldCallback!!(WrappedVideoState.Completed)
        oldCallback!!(WrappedVideoState.Cancelled)
        val s = c.state.value as WrappedVideoExportState.Encoding
        assertEquals(7, s.percent)
        assertEquals(plan(id = "new").periodLabel, s.periodLabel)
        secondFinish.complete(Unit)
        c.await("completed") { it is WrappedVideoExportState.Completed }
    }

    @Test fun `progress arriving after cancel was requested does not revive the Encoding state`() = blocking {
        var cb: ((WrappedVideoState) -> Unit)? = null
        val encStarted = CompletableDeferred<Unit>()
        val c = coordinator(encode = { _, _, onState -> cb = onState; encStarted.complete(Unit); awaitCancellation() })
        c.start(plan(id = "late"))
        encStarted.await()
        c.cancel()
        cb!!(WrappedVideoState.Encoding(50))
        assertTrue(c.state.value is WrappedVideoExportState.Cancelling || c.state.value is WrappedVideoExportState.Cancelled)
        c.await("cancelled") { it is WrappedVideoExportState.Cancelled }
        cb!!(WrappedVideoState.Encoding(60))
        assertTrue(c.state.value is WrappedVideoExportState.Cancelled)
    }

    // ---- cleanup observability ----------------------------------------------------------------------------

    @Test fun `a slide cleanup failure is observable and does not turn a published video into a failure`() = blocking {
        val problems = ArrayList<String>()
        val c = coordinator(deleteSlides = { false }, onCleanupProblem = { problems += it })
        c.start(plan(id = "cl1"))
        val done = c.await("done") { it is WrappedVideoExportState.Completed }
        c.joinCurrent()
        assertTrue(done is WrappedVideoExportState.Completed)
        assertTrue(c.state.value is WrappedVideoExportState.Completed)
        assertEquals(1, problems.size)
        assertFalse(guard.isHeld)
    }

    @Test fun `a throwing slide cleanup is also only reported`() = blocking {
        val problems = ArrayList<String>()
        val c = coordinator(deleteSlides = { throw java.io.IOException("disk") }, onCleanupProblem = { problems += it })
        c.start(plan(id = "cl2"))
        c.await("done") { it is WrappedVideoExportState.Completed }
        c.joinCurrent()
        assertTrue(c.state.value is WrappedVideoExportState.Completed)
        assertEquals(1, problems.size)
        assertFalse(guard.isHeld)
    }

    @Test fun `orphan sweep removes only stale part files and slide directories`() {
        val slideBase = File(root, "wrapped_slide_export").also { it.mkdirs() }
        val videoDir = File(root, WRAPPED_VIDEO_DIR).also { it.mkdirs() }
        val orphanDir = File(slideBase, WRAPPED_EXPORT_DIR_PREFIX + "dead").also { it.mkdirs() }
        File(orphanDir, "slide_01_intro.png").writeBytes(byteArrayOf(1))
        val unrelatedDir = File(slideBase, "something_else").also { it.mkdirs() }
        val part = File(videoDir, "wavdrop_wrapped_x.mp4.1234.part").also { it.writeBytes(byteArrayOf(1)) }
        val finished = File(videoDir, "wavdrop_wrapped_y.mp4").also { it.writeBytes(byteArrayOf(1)) }
        val foreignPart = File(videoDir, "other.part").also { it.writeBytes(byteArrayOf(1)) }
        val otherFile = File(videoDir, "notes.txt").also { it.writeBytes(byteArrayOf(1)) }
        assertEquals(2, sweepWrappedVideoOrphans(slideBase, videoDir))
        assertFalse(orphanDir.exists()); assertFalse(part.exists())
        assertTrue(unrelatedDir.exists()); assertTrue("completed MP4 untouched", finished.exists())
        assertTrue(foreignPart.exists()); assertTrue(otherFile.exists())
    }

    // ---- P5-WR-VID-04: seven-day completed-MP4 retention ----------------------------------------------------

    private val NOW = 2_000_000_000_000L
    private val WEEK = WRAPPED_VIDEO_RETENTION_MILLIS
    private val uuidA = "11111111-1111-4111-8111-111111111111"
    private val uuidB = "22222222-2222-4222-8222-222222222222"
    private val uuidC = "33333333-3333-4333-8333-333333333333"

    private fun dirs(): Pair<File, File> =
        File(root, "wrapped_slide_export").also { it.mkdirs() } to File(root, WRAPPED_VIDEO_DIR).also { it.mkdirs() }

    private fun mp4(dir: File, name: String, modified: Long) =
        File(dir, name).also { it.writeBytes(byteArrayOf(1, 2, 3)); assertTrue(it.setLastModified(modified)) }

    private fun retentionSweep(slides: File, videos: File, problems: MutableList<String>? = null,
                               delete: (File) -> Boolean = { it.delete() }) =
        sweepWrappedVideoOrphans(slides, videos, NOW, delete) { problems?.add(it) }

    @Test fun `retention constant is exactly seven days`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, WRAPPED_VIDEO_RETENTION_MILLIS)
    }

    @Test fun `an mp4 newer than seven days is preserved`() {
        val (s, v) = dirs()
        val f = mp4(v, "wavdrop_wrapped_$uuidA.mp4", NOW - WEEK + 60_000)
        assertEquals(0, retentionSweep(s, v)); assertTrue(f.exists())
    }

    @Test fun `an mp4 exactly seven days old is preserved and one millisecond more is deleted`() {
        val (s, v) = dirs()
        val exact = mp4(v, "wavdrop_wrapped_$uuidA.mp4", NOW - WEEK)
        val over = mp4(v, "wavdrop_wrapped_$uuidB.mp4", NOW - WEEK - 1)
        assertEquals(1, retentionSweep(s, v))
        assertTrue("exactly seven days is not older than seven days", exact.exists())
        assertFalse(over.exists())
    }

    @Test fun `several mp4s older than seven days are all deleted`() {
        val (s, v) = dirs()
        val a = mp4(v, "wavdrop_wrapped_$uuidA.mp4", NOW - WEEK - 86_400_000L)
        val b = mp4(v, "wavdrop_wrapped_$uuidB.mp4", NOW - 30 * 86_400_000L)
        val fresh = mp4(v, "wavdrop_wrapped_$uuidC.mp4", NOW - 1_000)
        assertEquals(2, retentionSweep(s, v))
        assertFalse(a.exists()); assertFalse(b.exists()); assertTrue(fresh.exists())
    }

    @Test fun `a future-dated mp4 is preserved`() {
        val (s, v) = dirs()
        val f = mp4(v, "wavdrop_wrapped_$uuidA.mp4", NOW + 10 * WEEK)
        assertEquals(0, retentionSweep(s, v)); assertTrue(f.exists())
    }

    @Test fun `malformed export names and unrelated mp4s are preserved even when very old`() {
        val (s, v) = dirs()
        val old = NOW - 100 * 86_400_000L
        val keep = listOf(
            "wavdrop_wrapped_notauuid.mp4", "wavdrop_wrapped_$uuidA.mp4.bak", "wavdrop_wrapped_$uuidA.MP4",
            "wavdrop_wrapped_$uuidA .mp4", "wavdrop_wrapped_.mp4", "holiday.mp4", "xwavdrop_wrapped_$uuidA.mp4",
            "wavdrop_wrapped_$uuidA.txt",
        ).map { mp4(v, it, old) }
        assertEquals(0, retentionSweep(s, v))
        keep.forEach { assertTrue(it.name, it.exists()) }
    }

    @Test fun `a directory that looks like an expired export and a matching-prefix sibling directory are preserved`() {
        val (s, v) = dirs()
        val old = NOW - 100 * 86_400_000L
        val lookalike = File(v, "wavdrop_wrapped_$uuidA.mp4").also { it.mkdirs(); it.setLastModified(old) }
        val sibling = File(root, WRAPPED_VIDEO_DIR + "_old").also { it.mkdirs() }
        val inSibling = mp4(sibling, "wavdrop_wrapped_$uuidB.mp4", old)
        assertEquals(0, retentionSweep(s, v))
        assertTrue(lookalike.isDirectory); assertTrue(inSibling.exists())
    }

    @Test fun `a symlink that escapes the video directory is preserved and its target is untouched`() {
        val (s, v) = dirs()
        val old = NOW - 100 * 86_400_000L
        val outside = mp4(File(root, "elsewhere").also { it.mkdirs() }, "wavdrop_wrapped_$uuidB.mp4", old)
        val link = File(v, "wavdrop_wrapped_$uuidA.mp4")
        val created = try { Files.createSymbolicLink(link.toPath(), outside.toPath()); true } catch (e: Exception) { false }
        org.junit.Assume.assumeTrue("symlinks unavailable on this machine", created)
        assertEquals(0, retentionSweep(s, v))
        assertTrue(Files.isSymbolicLink(link.toPath())); assertTrue(outside.exists())
    }

    @Test fun `a symlink to another file inside the directory is not followed either`() {
        val (s, v) = dirs()
        val target = mp4(v, "wavdrop_wrapped_$uuidB.mp4", NOW - 1_000)
        val link = File(v, "wavdrop_wrapped_$uuidA.mp4")
        val created = try { Files.createSymbolicLink(link.toPath(), target.toPath()); true } catch (e: Exception) { false }
        org.junit.Assume.assumeTrue("symlinks unavailable on this machine", created)
        assertEquals(0, retentionSweep(s, v))
        assertTrue(target.exists())
    }

    @Test fun `a path that only reaches the video directory through dot-dot or lives elsewhere is never expired`() {
        val (_, v) = dirs()
        val old = NOW - 100 * 86_400_000L
        val outside = mp4(File(root, "elsewhere").also { it.mkdirs() }, "wavdrop_wrapped_$uuidA.mp4", old)
        val viaDotDot = File(v, "../elsewhere/wavdrop_wrapped_$uuidA.mp4")
        assertTrue(viaDotDot.exists())
        assertFalse(isExpiredWrappedVideo(viaDotDot, v, NOW))
        assertFalse(isExpiredWrappedVideo(outside, v, NOW))
        val inside = mp4(v, "wavdrop_wrapped_$uuidB.mp4", old)
        assertTrue("control: the same age inside the directory is expired", isExpiredWrappedVideo(inside, v, NOW))
    }

    @Test fun `the image share cache and other app files are never touched`() {
        val (s, v) = dirs()
        val old = NOW - 100 * 86_400_000L
        val share = File(root, "wrapped_share").also { it.mkdirs() }
        val png = File(share, "wavdrop_wrapped_1794569066673_$uuidA.png").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(old) }
        val shareMp4 = mp4(share, "wavdrop_wrapped_$uuidB.mp4", old)
        val db = File(root, "wavdrop.db").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(old) }
        assertEquals(0, retentionSweep(s, v))
        assertTrue(png.exists()); assertTrue(shareMp4.exists()); assertTrue(db.exists())
    }

    @Test fun `part files and slide directories are still cleaned alongside retention`() {
        val (s, v) = dirs()
        val orphanDir = File(s, WRAPPED_EXPORT_DIR_PREFIX + "dead").also { it.mkdirs() }
        File(orphanDir, "a.png").writeBytes(byteArrayOf(1))
        val part = File(v, "wavdrop_wrapped_$uuidA.mp4.99.part").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(NOW) }
        val expired = mp4(v, "wavdrop_wrapped_$uuidB.mp4", NOW - WEEK - 5)
        assertEquals(3, retentionSweep(s, v))
        assertFalse(orphanDir.exists()); assertFalse(part.exists()); assertFalse(expired.exists())
    }

    @Test fun `a video older than seven days is pruned even if it was the last one published by an earlier export`() = blocking {
        val (s, v) = dirs()
        val first = coordinator(
            sweep = { sweepWrappedVideoOrphans(s, v, NOW) { } },
            encode = { _, _, _ -> completed(mp4(v, "wavdrop_wrapped_$uuidA.mp4", NOW - 1_000)) },
        )
        first.start(plan(id = "last1"))
        val done = first.await("done") { it is WrappedVideoExportState.Completed } as WrappedVideoExportState.Completed
        first.joinCurrent()
        assertTrue(done.file.exists())
        // Eight days later the next export starts; the earlier (last published) video has outlived the grace window.
        val later = NOW + 8 * 86_400_000L
        val second = coordinator(
            sweep = { sweepWrappedVideoOrphans(s, v, later) { } },
            encode = { _, _, _ -> completed(mp4(v, "wavdrop_wrapped_$uuidB.mp4", later - 1_000)) },
        )
        second.start(plan(id = "last2"))
        val done2 = second.await("done") { it is WrappedVideoExportState.Completed } as WrappedVideoExportState.Completed
        second.joinCurrent()
        assertFalse("older last-published video is pruned", done.file.exists())
        assertTrue("the new export is intact", done2.file.exists())
    }

    @Test fun `a failed deletion is reported, skipped and does not stop the rest of the sweep`() {
        val (s, v) = dirs()
        val a = mp4(v, "wavdrop_wrapped_$uuidA.mp4", NOW - WEEK - 1)
        val b = mp4(v, "wavdrop_wrapped_$uuidB.mp4", NOW - WEEK - 1)
        val problems = ArrayList<String>()
        val removed = retentionSweep(s, v, problems = problems, delete = { f -> if (f.name.contains(uuidA)) false else f.delete() })
        assertEquals(1, removed)
        assertEquals(1, problems.size); assertTrue(problems[0].contains(a.name))
        assertTrue(a.exists()); assertFalse(b.exists())
        val thrown = retentionSweep(s, v, problems = problems, delete = { throw SecurityException("nope") })
        assertEquals(0, thrown); assertTrue(a.exists())
    }

    @Test fun `a sweep that throws is reported and the new export still completes`() = blocking {
        val problems = ArrayList<String>()
        val c = coordinator(sweep = { throw SecurityException("denied") }, onCleanupProblem = { problems += it })
        c.start(plan(id = "swf"))
        c.await("done") { it is WrappedVideoExportState.Completed }
        c.joinCurrent()
        assertTrue(c.state.value is WrappedVideoExportState.Completed)
        assertEquals(1, problems.size)
        assertFalse(guard.isHeld)
    }

    @Test fun `cleanup runs before the new export generates its slides`() = blocking {
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val c = coordinator(
            sweep = { events += "sweep"; 0 },
            generate = { events += "generate"; success(it.exportId) },
        )
        c.start(plan(id = "ord"))
        c.await("done") { it is WrappedVideoExportState.Completed }
        c.joinCurrent()
        assertEquals(listOf("sweep", "generate"), events.toList())
    }

    @Test fun `a second export cannot start or sweep while one holds the guard`() = blocking {
        val sweeps = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val first = coordinator(generate = { gate.await(); success(it.exportId) }, sweep = { sweeps.incrementAndGet() })
        val second = coordinator(sweep = { sweeps.incrementAndGet() })
        assertTrue(first.start(plan(id = "g1")))
        assertFalse(second.start(plan(id = "g2")))
        assertEquals(WrappedVideoExportState.Idle, second.state.value)
        gate.complete(Unit)
        first.await("done") { it is WrappedVideoExportState.Completed }
        first.joinCurrent()
        assertEquals(1, sweeps.get())
    }

    @Test fun `a successful new export is never removed by its own startup sweep`() = blocking {
        val (s, v) = dirs()
        val oldExport = mp4(v, "wavdrop_wrapped_$uuidA.mp4", NOW - 100 * 86_400_000L)
        val fakeNow = { NOW }
        val c = coordinator(
            sweep = { sweepWrappedVideoOrphans(s, v, fakeNow()) { } },
            encode = { _, p, _ -> completed(mp4(v, "wavdrop_wrapped_${uuidC}.mp4", NOW - 10)) },
        )
        c.start(plan(id = "own"))
        val done = c.await("done") { it is WrappedVideoExportState.Completed } as WrappedVideoExportState.Completed
        c.joinCurrent()
        assertFalse("expired older export was pruned at start", oldExport.exists())
        assertTrue(done.file.exists())
        // Within the grace window a later sweep keeps it too.
        assertEquals(0, sweepWrappedVideoOrphans(s, v, NOW + 3 * 86_400_000L) { })
        assertTrue(done.file.exists())
    }

    @Test fun `the sweep runs only inside a started export, after the guard is held`() = blocking {
        val sweeps = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val c = coordinator(generate = { gate.await(); success(it.exportId) }, sweep = { sweeps.incrementAndGet() })
        assertEquals(0, sweeps.get())
        c.start(plan(id = "sw"))
        assertTrue(guard.isHeld)
        gate.complete(Unit)
        c.await("done") { it is WrappedVideoExportState.Completed }
        c.joinCurrent()
        assertEquals(1, sweeps.get())
    }

    // ---- UI text --------------------------------------------------------------------------------------------

    @Test fun `every state has meaningful text and nothing claims the video was shared`() {
        val f = File("x.mp4")
        val states = listOf(
            WrappedVideoExportState.Preparing("All Time", 7),
            WrappedVideoExportState.Encoding("All Time", null),
            WrappedVideoExportState.Encoding("All Time", 30),
            WrappedVideoExportState.Cancelling("All Time"),
            WrappedVideoExportState.Completed("All Time", "ALL_TIME", f),
            WrappedVideoExportState.Cancelled("All Time"),
        ) + WrappedVideoExportFailure.entries.map { WrappedVideoExportState.Failed("All Time", it) }
        for (s in states) {
            val t = wrappedVideoExportStatusText(s)!!
            assertTrue(t, t.isNotBlank())
            assertFalse(t, t.contains("shared", ignoreCase = true))
        }
        assertNull(wrappedVideoExportStatusText(WrappedVideoExportState.Idle))
    }

    @Test fun `share results never claim success and only the chooser failure modes have text`() {
        assertNull("opening the chooser is not a success message", wrappedVideoShareMessage(WrappedVideoShareResult.Opened))
        for (r in WrappedVideoShareResult.entries.filter { it != WrappedVideoShareResult.Opened }) {
            val m = wrappedVideoShareMessage(r)!!
            assertFalse(m, m.contains("success", ignoreCase = true))
            assertFalse(m, m.contains("shared successfully", ignoreCase = true))
        }
    }

    // ---- share eligibility (FileProvider's mapping is the second line of defence) --------------------------

    @Test fun `only a finished mp4 directly inside the video directory is shareable`() {
        val videoDir = File(root, WRAPPED_VIDEO_DIR).also { it.mkdirs() }
        val ok = File(videoDir, "wavdrop_wrapped_a.mp4").also { it.writeBytes(byteArrayOf(1, 2)) }
        assertTrue(isWrappedVideoShareable(ok, videoDir))

        val empty = File(videoDir, "wavdrop_wrapped_empty.mp4").also { it.createNewFile() }
        val part = File(videoDir, "wavdrop_wrapped_p.mp4.1.part").also { it.writeBytes(byteArrayOf(1)) }
        val wrongName = File(videoDir, "secrets.mp4").also { it.writeBytes(byteArrayOf(1)) }
        val slideDir = File(root, "wrapped_slide_export").also { it.mkdirs() }
        val slide = File(slideDir, "wavdrop_wrapped_a.mp4").also { it.writeBytes(byteArrayOf(1)) }
        val sibling = File(root, WRAPPED_VIDEO_DIR + "_evil").also { it.mkdirs() }
        val siblingFile = File(sibling, "wavdrop_wrapped_a.mp4").also { it.writeBytes(byteArrayOf(1)) }
        val traversal = File(File(videoDir, "sub").also { it.mkdirs() }, "../../wrapped_slide_export/wavdrop_wrapped_a.mp4")
        val nested = File(File(videoDir, "sub"), "wavdrop_wrapped_n.mp4").also { it.writeBytes(byteArrayOf(1)) }
        for (f in listOf(empty, part, wrongName, slide, siblingFile, traversal, nested, File(videoDir, "missing.mp4"), videoDir)) {
            assertFalse(f.path, isWrappedVideoShareable(f, videoDir))
        }
    }
}
