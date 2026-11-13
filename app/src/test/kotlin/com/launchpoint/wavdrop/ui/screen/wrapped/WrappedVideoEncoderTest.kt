package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** P5-WR-VID-02B: lifecycle, ownership, cancellation and cleanup of the encoder wrapper, with a fake engine. */
class WrappedVideoEncoderTest {

    private lateinit var root: File
    private lateinit var out: File

    @Before fun setUp() {
        root = Files.createTempDirectory("wrapped-video-encoder").toFile()
        out = File(root, "videos")
    }

    @After fun tearDown() { root.deleteRecursively() }

    // ---- fixtures -------------------------------------------------------------------------------------------

    private fun pngBytes(): ByteArray {
        val o = ByteArrayOutputStream()
        fun int(v: Int) { o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v) }
        o.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        int(13); o.write("IHDR".toByteArray()); int(720); int(1280); o.write(byteArrayOf(8, 6, 0, 0, 0)); int(0)
        int(4); o.write("IDAT".toByteArray()); o.write(byteArrayOf(1, 2, 3, 4)); int(0)
        int(0); o.write("IEND".toByteArray()); o.write(byteArrayOf(0xAE.toByte(), 0x42, 0x60, 0x82.toByte()))
        return o.toByteArray()
    }

    private val slideNames = (1..4).map { "slide_%02d_page.png".format(it) }

    private fun export(dirName: String = WRAPPED_EXPORT_DIR_PREFIX + "enc"): WrappedExportOutcome.Success {
        val dir = File(root, dirName).also { it.mkdirs() }
        slideNames.forEach { File(dir, it).writeBytes(pngBytes()) }
        File(dir, "manifest.json").writeText(
            "{\"pageCount\":${slideNames.size},\"slides\":[" + slideNames.joinToString(",") { "{\"file\":\"$it\"}" } + "]}",
        )
        val entries = slideNames.mapIndexed { i, n ->
            WrappedExportEntry(i, WrappedPageId.values()[i], n, 720, 1280, 3_000)
        }
        return WrappedExportOutcome.Success(dir, entries)
    }

    private val goodInfo = WrappedVideoInfo("video/avc", 1, 0, 720, 1280, 0, 12_000)

    private class FakeEngine(
        private val behaviour: (FakeEngine, WrappedVideoRequest, String, WrappedVideoEngine.Listener) -> Unit,
        private val progress: () -> Int? = { null },
    ) : WrappedVideoEngine {
        val started = CountDownLatch(1)
        val cancelCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        @Volatile var request: WrappedVideoRequest? = null
        @Volatile var outputPath: String? = null
        @Volatile var listener: WrappedVideoEngine.Listener? = null

        override fun start(request: WrappedVideoRequest, outputPath: String, listener: WrappedVideoEngine.Listener) {
            this.request = request; this.outputPath = outputPath; this.listener = listener
            started.countDown()
            behaviour(this, request, outputPath, listener)
        }
        override suspend fun cancel() { cancelCount.incrementAndGet() }
        override suspend fun progressPercent(): Int? = progress()
        override fun release() { releaseCount.incrementAndGet() }
    }

    private fun succeeds(bytes: Int = 100) = { _: FakeEngine, _: WrappedVideoRequest, path: String, l: WrappedVideoEngine.Listener ->
        File(path).writeBytes(ByteArray(bytes) { 1 })
        l.onCompleted()
    }

    private val hangs = { _: FakeEngine, _: WrappedVideoRequest, path: String, _: WrappedVideoEngine.Listener ->
        File(path).writeBytes(ByteArray(10) { 1 }) // a partially written file exists while encoding
    }

    private fun encoder(
        engine: FakeEngine,
        info: WrappedVideoInfo? = goodInfo,
        factory: () -> WrappedVideoEngine = { engine },
        progressIntervalMs: Long = 250L,
    ) = WrappedVideoEncoder(factory, inspect = { info }, progressIntervalMs = progressIntervalMs)

    private fun states() = Collections.synchronizedList(ArrayList<WrappedVideoState>())

    private fun partFiles() = out.list().orEmpty().filter { it.endsWith(".part") }

    // ---- tests ----------------------------------------------------------------------------------------------

    @Test fun `the engine receives the ordered slides with durations and the target frame rate`() {
        val e = export()
        val engine = FakeEngine(succeeds())
        runBlocking { encoder(engine).encode(e, out, "wrapped.mp4") }
        val req = engine.request!!
        assertEquals(slideNames, req.slides.map { it.fileName })
        assertEquals(listOf(0, 1, 2, 3), req.slides.map { it.index })
        assertTrue(req.slides.all { it.durationMs == 3_000 })
        assertEquals(30, req.frameRate)
        assertTrue("output goes to an owned temp file, not the final name", engine.outputPath!!.endsWith(".part"))
    }

    @Test fun `success finalises the video, keeps the sources and reports metadata`() {
        val e = export()
        val stranger = File(root, "unrelated.txt").also { it.writeText("keep") }
        out.mkdirs()
        val previous = File(out, "earlier.mp4").also { it.writeText("previous export") }
        val st = states()
        val engine = FakeEngine(succeeds(250))
        val result = runBlocking { encoder(engine).encode(e, out, "wrapped.mp4", st::add) } as WrappedVideoOutcome.Completed
        assertEquals(File(out, "wrapped.mp4"), result.file)
        assertEquals(250L, result.sizeBytes)
        assertEquals(goodInfo, result.info)
        assertTrue(partFiles().isEmpty())
        assertEquals(listOf(WrappedVideoState.Preparing, WrappedVideoState.Encoding(null), WrappedVideoState.Completed), st.toList())
        assertEquals("sources untouched", slideNames.toSet() + "manifest.json", e.directory.list()!!.toSet())
        assertEquals("previous video untouched", "previous export", previous.readText())
        assertEquals("keep", stranger.readText())
        assertEquals(1, engine.releaseCount.get())
    }

    @Test fun `progress is reported when available and never invented when not`() {
        val polled = AtomicInteger()
        val values = listOf(10, 55, 90)
        val gate = CountDownLatch(3)
        val withProgress = FakeEngine(
            behaviour = { eng, _, path, l ->
                Thread {
                    gate.await() // completes only after three progress polls: no timing involved
                    File(path).writeBytes(ByteArray(10)); l.onCompleted()
                }.start()
            },
            progress = { val i = polled.getAndIncrement(); gate.countDown(); values.getOrNull(i) ?: 100 },
        )
        val st = states()
        runBlocking { encoder(withProgress, progressIntervalMs = 1).encode(export(), out, "p.mp4", st::add) }
        val seen = st.filterIsInstance<WrappedVideoState.Encoding>().mapNotNull { it.percent }
        assertTrue("reported $seen", seen.take(3) == values)

        val noProgress = FakeEngine(succeeds(), progress = { null })
        val st2 = states()
        runBlocking { encoder(noProgress, progressIntervalMs = 1).encode(export("${WRAPPED_EXPORT_DIR_PREFIX}b"), out, "q.mp4", st2::add) }
        assertTrue(st2.filterIsInstance<WrappedVideoState.Encoding>().all { it.percent == null })
    }

    @Test fun `an engine failure removes the part file and reports the engine's reason`() {
        val e = export()
        val st = states()
        val engine = FakeEngine({ _, _, path, l ->
            File(path).writeBytes(ByteArray(20)); l.onError(WrappedVideoEngineError("ERROR_CODE_ENCODER_INIT_FAILED", "no encoder"))
        })
        val r = runBlocking { encoder(engine).encode(e, out, "f.mp4", st::add) } as WrappedVideoOutcome.Failed
        assertEquals(WrappedVideoFailure.EngineFailed, r.failure)
        assertTrue(r.detail.contains("ERROR_CODE_ENCODER_INIT_FAILED"))
        assertFalse(File(out, "f.mp4").exists())
        assertTrue(partFiles().isEmpty())
        assertEquals(WrappedVideoState.Failed(WrappedVideoFailure.EngineFailed), st.last())
        assertEquals(1, engine.releaseCount.get())
        assertEquals("sources untouched", 5, e.directory.list()!!.size)
    }

    @Test fun `cancelling during encoding cancels the real engine and removes the part file`() {
        val e = export()
        val stranger = File(root, "unrelated.txt").also { it.writeText("keep") }
        val st = states()
        val engine = FakeEngine(hangs)
        val enc = encoder(engine)
        runBlocking {
            val job = launch(Dispatchers.Default) { enc.encode(e, out, "c.mp4", st::add) }
            withContext(Dispatchers.IO) { engine.started.await() }
            assertEquals("a part file exists while encoding", 1, partFiles().size)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals("Transformer-level cancel was invoked", 1, engine.cancelCount.get())
        assertEquals(1, engine.releaseCount.get())
        assertTrue(partFiles().isEmpty())
        assertFalse(File(out, "c.mp4").exists())
        assertEquals(WrappedVideoState.Cancelled, st.last())
        assertEquals("sources untouched", 5, e.directory.list()!!.size)
        assertEquals("keep", stranger.readText())
    }

    @Test fun `callbacks after cancellation are ignored and cannot affect the next encode`() {
        val e = export()
        val engine = FakeEngine(hangs)
        var current: FakeEngine = engine
        val enc = encoder(engine, factory = { current })
        runBlocking {
            val job = launch(Dispatchers.Default) { enc.encode(e, out, "late.mp4") }
            withContext(Dispatchers.IO) { engine.started.await() }
            job.cancel(); job.join()
        }
        val late = engine.listener!!
        late.onCompleted()
        late.onError(WrappedVideoEngineError("X", "late"))
        assertEquals("both late callbacks ignored", 2, enc.ignoredLateCallbacks.get())
        assertFalse(File(out, "late.mp4").exists())
        assertTrue(partFiles().isEmpty())
        // The same encoder runs a fresh encode normally afterwards (busy flag released, stale listener inert).
        current = FakeEngine(succeeds())
        val r = runBlocking { enc.encode(export("${WRAPPED_EXPORT_DIR_PREFIX}next"), out, "next.mp4") }
        assertTrue("the cancelled encoder is reusable and the stale listener is inert", r is WrappedVideoOutcome.Completed)
        assertEquals("the stale callbacks stay ignored", 2, enc.ignoredLateCallbacks.get())
        assertTrue(File(out, "next.mp4").exists())
    }

    @Test fun `a duplicate completion delivers exactly one result`() {
        val engine = FakeEngine({ _, _, path, l ->
            File(path).writeBytes(ByteArray(30)); l.onCompleted(); l.onCompleted(); l.onError(WrappedVideoEngineError("X", "after"))
        })
        val enc = encoder(engine)
        val st = states()
        val r = runBlocking { enc.encode(export(), out, "d.mp4", st::add) }
        assertTrue(r is WrappedVideoOutcome.Completed)
        assertEquals(1, st.count { it == WrappedVideoState.Completed })
        assertEquals(2, enc.ignoredLateCallbacks.get())
        assertEquals(1, out.list()!!.count { it == "d.mp4" })
    }

    @Test fun `a second encode while one is running is refused`() {
        val engine = FakeEngine(hangs)
        val enc = encoder(engine)
        runBlocking {
            val first = async(Dispatchers.Default) { enc.encode(export(), out, "one.mp4") }
            withContext(Dispatchers.IO) { engine.started.await() }
            val second = enc.encode(export("${WRAPPED_EXPORT_DIR_PREFIX}two"), out, "two.mp4") as WrappedVideoOutcome.Failed
            assertEquals(WrappedVideoFailure.Busy, second.failure)
            first.cancel(); first.join()
        }
        assertFalse(File(out, "two.mp4").exists())
    }

    @Test fun `an existing completed video is never overwritten and the engine is never created`() {
        out.mkdirs()
        val existing = File(out, "wrapped.mp4").also { it.writeText("finished earlier") }
        var created = false
        val r = runBlocking {
            WrappedVideoEncoder({ created = true; FakeEngine(succeeds()) }, inspect = { goodInfo }).encode(export(), out, "wrapped.mp4")
        } as WrappedVideoOutcome.Failed
        assertEquals(WrappedVideoFailure.OutputExists, r.failure)
        assertFalse(created)
        assertEquals("finished earlier", existing.readText())
    }

    @Test fun `a video that appears during encoding is not replaced`() {
        out.mkdirs()
        val engine = FakeEngine({ _, _, path, l ->
            File(path).writeBytes(ByteArray(30))
            File(out, "race.mp4").writeText("someone else")
            l.onCompleted()
        })
        val r = runBlocking { encoder(engine).encode(export(), out, "race.mp4") } as WrappedVideoOutcome.Failed
        assertEquals(WrappedVideoFailure.FinalizeFailed, r.failure)
        assertEquals("someone else", File(out, "race.mp4").readText())
        assertTrue(partFiles().isEmpty())
    }

    @Test fun `an output that fails verification is not published`() {
        val bad = listOf(
            goodInfo.copy(videoMime = "video/hevc"),
            goodInfo.copy(audioTrackCount = 1),
            goodInfo.copy(codedWidth = 1280, codedHeight = 720),
            goodInfo.copy(durationMs = 20_000),
        )
        for ((i, info) in bad.withIndex()) {
            val r = runBlocking {
                encoder(FakeEngine(succeeds()), info = info).encode(export("${WRAPPED_EXPORT_DIR_PREFIX}v$i"), out, "bad$i.mp4")
            } as WrappedVideoOutcome.Failed
            assertEquals(WrappedVideoFailure.OutputInvalid, r.failure)
            assertFalse(File(out, "bad$i.mp4").exists())
        }
        assertTrue(partFiles().isEmpty())
        val unreadable = runBlocking { encoder(FakeEngine(succeeds()), info = null).encode(export(), out, "x.mp4") }
        assertTrue(unreadable is WrappedVideoOutcome.Failed)
    }

    @Test fun `invalid input fails before any engine exists and writes nothing`() {
        val e = export()
        File(e.directory, slideNames[2]).delete()
        var created = false
        val r = runBlocking {
            WrappedVideoEncoder({ created = true; FakeEngine(succeeds()) }, inspect = { goodInfo }).encode(e, out, "i.mp4")
        } as WrappedVideoOutcome.Failed
        assertEquals(WrappedVideoFailure.InvalidInput, r.failure)
        assertFalse(created)
        assertFalse(out.exists() && out.list()!!.isNotEmpty())
    }

    @Test fun `unsafe output names are rejected`() {
        for (name in listOf("../x.mp4", "a/b.mp4", "a\\b.mp4", "x.txt", ".mp4", ".hidden.mp4", "")) {
            val r = runBlocking { encoder(FakeEngine(succeeds())).encode(export(), out, name) } as WrappedVideoOutcome.Failed
            assertEquals("'$name'", WrappedVideoFailure.InvalidOutputName, r.failure)
        }
    }

    @Test fun `an unexpected exception is reported and the part file is removed`() {
        val engine = FakeEngine(succeeds())
        val enc = WrappedVideoEncoder({ engine }, inspect = { throw IllegalStateException("boom") })
        val r = runBlocking { enc.encode(export(), out, "u.mp4") } as WrappedVideoOutcome.Failed
        assertEquals(WrappedVideoFailure.Unexpected, r.failure)
        assertTrue(partFiles().isEmpty())
        assertFalse(File(out, "u.mp4").exists())
        assertEquals(1, engine.releaseCount.get())
    }

    @Test fun `an engine that throws while starting is a failure not a hang`() {
        val engine = FakeEngine({ _, _, _, _ -> throw IllegalStateException("no codec") })
        val r = runBlocking { encoder(engine).encode(export(), out, "s.mp4") } as WrappedVideoOutcome.Failed
        assertEquals(WrappedVideoFailure.EngineFailed, r.failure)
        assertTrue(r.detail.contains("START_FAILED"))
        assertTrue(partFiles().isEmpty())
    }

    // ---- output verification (pure) -------------------------------------------------------------------------

    // ---- ownership and publication across dispatcher handoffs (P5-WR-VID-02B-R1) -------------------------------

    /**
     * Two genuinely different single-thread dispatchers: the caller runs on one, the encoder's IO work on the other.
     * The IO dispatch numbered [cancelAfterIoDispatch] (1 = input validation, 2 = part-file creation, 3 = publication
     * move) is run to completion on the IO thread; only then is the caller's job cancelled and the caller released.
     * The caller dispatcher holds every task from just before that dispatch until the cancel has happened, so the
     * caller is always cancelled AFTER the IO work finished and strictly BEFORE it can receive the result. Latch-ordered:
     * no sleeps and no races.
     */
    private inner class Handoff(private val cancelAfterIoDispatch: Int) {
        private val callerExec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-caller") }
        private val ioExec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "test-io") }
        private val callerDelegate = callerExec.asCoroutineDispatcher()
        private val ioDelegate = ioExec.asCoroutineDispatcher()
        private val holdCaller = java.util.concurrent.atomic.AtomicBoolean(false)
        private val cancelDone = CountDownLatch(1)
        private val ioCount = AtomicInteger()
        @Volatile var job: kotlinx.coroutines.Job? = null
        /** Part files that existed in the output directory at the moment the caller was cancelled. */
        @Volatile var partsAtCancel: List<String> = emptyList()
        @Volatile var finalExistedAtCancel = false
        @Volatile var cancelFired = false

        val caller = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                callerDelegate.dispatch(context, Runnable {
                    if (holdCaller.get()) cancelDone.await()
                    block.run()
                })
            }
        }
        val io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                val n = ioCount.incrementAndGet()
                val fire = n == cancelAfterIoDispatch
                if (fire) holdCaller.set(true)
                ioDelegate.dispatch(context, Runnable {
                    block.run()
                    if (fire) {
                        partsAtCancel = out.list().orEmpty().filter { it.endsWith(".part") }
                        finalExistedAtCancel = File(out, "wrapped.mp4").exists()
                        cancelFired = true
                        job?.cancel()
                        cancelDone.countDown()
                    }
                })
            }
        }

        fun <T> launchEncode(block: suspend () -> T): kotlinx.coroutines.Deferred<T> =
            kotlinx.coroutines.CoroutineScope(caller).async { block() }.also { job = it }

        fun close() { callerExec.shutdownNow(); ioExec.shutdownNow() }
    }

    private fun handoffEncoder(h: Handoff, engine: () -> WrappedVideoEngine, info: WrappedVideoInfo? = goodInfo) =
        WrappedVideoEncoder(engine, inspect = { info }, ioDispatcher = h.io)

    private fun Handoff.awaitDone(d: kotlinx.coroutines.Deferred<*>) = runBlocking { d.join() }

    @Test fun `cancelled after the part file was created but before the result is delivered it is still removed`() {
        val h = Handoff(cancelAfterIoDispatch = 2)
        try {
            val e = export()
            out.mkdirs()
            val stranger = File(root, "unrelated.txt").also { it.writeText("keep") }
            val strayPart = File(out, "other.mp4.stray.part").also { it.writeText("not ours") }
            val earlier = File(out, "earlier.mp4").also { it.writeText("previous video") }
            var started = false
            val st = states()
            val enc = handoffEncoder(h, { started = true; FakeEngine(succeeds()) })
            val d = h.launchEncode { enc.encode(e, out, "wrapped.mp4", st::add) }
            h.awaitDone(d)
            assertTrue("the cancel really happened after the file existed", h.cancelFired)
            assertEquals("a part file of ours existed at cancel time", 2, h.partsAtCancel.size) // ours + the stray one
            assertTrue("the caller was cancelled", d.isCancelled)
            assertFalse("the encoder must not start after a cancelled setup", started)
            assertEquals("only the stray part file (not ours) remains", listOf("other.mp4.stray.part"), partFiles())
            assertEquals("not ours", strayPart.readText())
            assertFalse(File(out, "wrapped.mp4").exists())
            assertEquals("previous video", earlier.readText())
            assertEquals("keep", stranger.readText())
            assertEquals("sources untouched", 5, e.directory.list()!!.size)
            assertEquals(WrappedVideoState.Cancelled, st.last())
            assertEquals(1, st.count { it == WrappedVideoState.Cancelled })
        } finally { h.close() }
    }

    @Test fun `a pre-existing target is never touched when the caller is cancelled during setup`() {
        val h = Handoff(cancelAfterIoDispatch = 2)
        try {
            val e = export()
            out.mkdirs()
            val existing = File(out, "wrapped.mp4").also { it.writeText("finished earlier") }
            var started = false
            val enc = handoffEncoder(h, { started = true; FakeEngine(succeeds()) })
            val d = h.launchEncode { enc.encode(e, out, "wrapped.mp4") }
            h.awaitDone(d)
            assertTrue(d.isCancelled)
            assertFalse(started)
            assertEquals("finished earlier", existing.readText())
            assertTrue(partFiles().isEmpty())
        } finally { h.close() }
    }

    @Test fun `cancelled during input validation leaves nothing behind and never starts the encoder`() {
        val h = Handoff(cancelAfterIoDispatch = 1)
        try {
            var started = false
            val enc = handoffEncoder(h, { started = true; FakeEngine(succeeds()) })
            val d = h.launchEncode { enc.encode(export(), out, "wrapped.mp4") }
            h.awaitDone(d)
            assertTrue(d.isCancelled)
            assertFalse(started)
            assertTrue(out.list().orEmpty().isEmpty())
        } finally { h.close() }
    }

    @Test fun `cancelled as the move completes the published video is kept and reported as completed`() {
        val h = Handoff(cancelAfterIoDispatch = 3)
        try {
            val e = export()
            val stranger = File(root, "unrelated.txt").also { it.writeText("keep") }
            val st = states()
            val engine = FakeEngine(succeeds(321))
            val enc = handoffEncoder(h, { engine })
            // A Deferred whose job was cancelled reports the cancellation even if its body returned normally, so the
            // value the encoder actually returned is captured inside the coroutine.
            var returned: WrappedVideoOutcome? = null
            val d = h.launchEncode { enc.encode(e, out, "wrapped.mp4", st::add).also { returned = it } }
            h.awaitDone(d)
            assertTrue("the cancel really arrived after the move", h.cancelFired && h.finalExistedAtCancel)
            val result = returned as WrappedVideoOutcome.Completed
            assertEquals(File(out, "wrapped.mp4"), result.file)
            assertEquals(321L, result.sizeBytes)
            assertTrue("published file is kept, not deleted as an unfinished part", File(out, "wrapped.mp4").exists())
            assertEquals(321L, File(out, "wrapped.mp4").length())
            assertTrue(partFiles().isEmpty())
            assertEquals("exactly one terminal state, and it is Completed", WrappedVideoState.Completed, st.last())
            assertEquals(0, st.count { it == WrappedVideoState.Cancelled })
            assertEquals(1, st.count { it == WrappedVideoState.Completed })
            assertEquals(1, engine.releaseCount.get())
            assertEquals("sources untouched", 5, e.directory.list()!!.size)
            assertEquals("keep", stranger.readText())
        } finally { h.close() }
    }

    @Test fun `cancelled after verification but before the move wins and nothing is published`() {
        val e = export()
        val st = states()
        val engine = FakeEngine(succeeds())
        lateinit var job: kotlinx.coroutines.Job
        val enc = WrappedVideoEncoder({ engine }, inspect = { job.cancel(); goodInfo })
        val result = runBlocking {
            val d = async(Dispatchers.Default) { enc.encode(e, out, "wrapped.mp4", st::add) }
            job = d
            d.join()
            d
        }
        assertTrue(result.isCancelled)
        assertFalse("nothing published", File(out, "wrapped.mp4").exists())
        assertTrue(partFiles().isEmpty())
        assertEquals(WrappedVideoState.Cancelled, st.last())
        assertEquals(1, engine.releaseCount.get())
        assertEquals("sources untouched", 5, e.directory.list()!!.size)
    }

    @Test fun `a successful encode through separate caller and io dispatchers is unchanged`() {
        val h = Handoff(cancelAfterIoDispatch = Int.MAX_VALUE)
        try {
            val e = export()
            val st = states()
            val enc = handoffEncoder(h, { FakeEngine(succeeds(77)) })
            val d = h.launchEncode { enc.encode(e, out, "ok.mp4", st::add) }
            h.awaitDone(d)
            val r = d.getCompleted() as WrappedVideoOutcome.Completed
            assertEquals(File(out, "ok.mp4"), r.file)
            assertEquals(77L, r.sizeBytes)
            assertEquals(goodInfo, r.info)
            assertEquals(listOf(WrappedVideoState.Preparing, WrappedVideoState.Encoding(null), WrappedVideoState.Completed), st.toList())
            assertEquals(listOf("ok.mp4"), out.list()!!.toList())
        } finally { h.close() }
    }

    @Test fun `the encoder is reusable after a cancelled setup and after a failure`() {
        val h = Handoff(cancelAfterIoDispatch = 2)
        try {
            var current: FakeEngine = FakeEngine(succeeds())
            val enc = handoffEncoder(h, { current })
            val cancelled = h.launchEncode { enc.encode(export(), out, "a.mp4") }
            h.awaitDone(cancelled)
            assertTrue(cancelled.isCancelled)

            current = FakeEngine({ _, _, path, l -> File(path).writeBytes(ByteArray(5)); l.onError(WrappedVideoEngineError("E", "boom")) })
            val failed = kotlinx.coroutines.CoroutineScope(h.caller).async { enc.encode(export("${WRAPPED_EXPORT_DIR_PREFIX}f"), out, "b.mp4") }
            runBlocking { failed.join() }
            assertEquals(WrappedVideoFailure.EngineFailed, (failed.getCompleted() as WrappedVideoOutcome.Failed).failure)

            current = FakeEngine(succeeds(40))
            val ok = kotlinx.coroutines.CoroutineScope(h.caller).async { enc.encode(export("${WRAPPED_EXPORT_DIR_PREFIX}g"), out, "c.mp4") }
            runBlocking { ok.join() }
            assertTrue("busy gate released on every path", ok.getCompleted() is WrappedVideoOutcome.Completed)
            assertEquals(listOf("c.mp4"), out.list()!!.toList())
        } finally { h.close() }
    }

    @Test fun `verification accepts portrait avc without audio within two frames`() {
        assertTrue(verifyWrappedVideoInfo(goodInfo, 12_000).isEmpty())
        assertTrue(verifyWrappedVideoInfo(goodInfo.copy(durationMs = 12_000 + WRAPPED_VIDEO_DURATION_TOLERANCE_MS), 12_000).isEmpty())
        assertFalse(verifyWrappedVideoInfo(goodInfo.copy(durationMs = 12_000 + WRAPPED_VIDEO_DURATION_TOLERANCE_MS + 1), 12_000).isEmpty())
    }

    @Test fun `verification applies rotation so a landscape file with a rotation transform is portrait`() {
        val rotated = goodInfo.copy(codedWidth = 1280, codedHeight = 720, rotationDegrees = 90)
        assertEquals(720 to 1280, rotated.displayWidth to rotated.displayHeight)
        assertTrue(verifyWrappedVideoInfo(rotated, 12_000).isEmpty())
        // The same coded size WITHOUT a rotation is a 1280x720 video and must fail.
        val landscape = goodInfo.copy(codedWidth = 1280, codedHeight = 720, rotationDegrees = 0)
        assertTrue(verifyWrappedVideoInfo(landscape, 12_000).any { it.contains("expected 720x1280") })
    }

    @Test fun `verification names every problem`() {
        val p = verifyWrappedVideoInfo(WrappedVideoInfo("video/hevc", 2, 1, 100, 100, 0, 1), 12_000)
        assertTrue(p.size >= 5)
    }
}
