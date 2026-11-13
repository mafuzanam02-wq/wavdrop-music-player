package com.launchpoint.wavdrop.ui.screen.wrapped

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.launchpoint.wavdrop.data.model.AlbumReportSummary
import com.launchpoint.wavdrop.data.model.ArtistReportSummary
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.Song
import com.launchpoint.wavdrop.data.model.SongStatsSummary
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.data.settings.WrappedVisualStyle
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5-WR-VID-02B on a real device: synthetic Wrapped reports are rendered to PNG slides (VID-02A2), then encoded to an
 * H.264 MP4 by the real Media3 Transformer, read back with MediaExtractor/MediaMetadataRetriever, and frames near every
 * slide boundary are compared with the source slides. Fixtures are synthetic; evidence is copied to the app's external
 * files dir and pulled by hand (not tracked in Git).
 */
@OptIn(DelicateCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WrappedVideoEncodeInstrumentedTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = File(ctx.cacheDir, "vid02b_test").also { it.mkdirs() }
    private val videos get() = File(base, "videos")
    private val evidence get() = File(ctx.getExternalFilesDir(null)!!, "vid02b").also { it.mkdirs() }

    @Before fun clean() { base.deleteRecursively() }
    @After fun tearDown() { base.deleteRecursively() }

    // ---- synthetic fixtures (same shape as the slide-export test) ------------------------------------------

    private val settings = WrappedVisualSettings(true, WrappedBackgroundIntensity.MEDIUM, WrappedFallbackTheme.AUTO)

    private fun song(id: Long, title: String, artist: String, album: String, albumId: Long) =
        Song(id, title, artist, album, albumId, 200_000L, "content://x/$id", 0L, 1, 2024)

    private fun stat(s: Song, plays: Int, skips: Int = 0) =
        SongStatsSummary(s, plays, skips, 1_700_000_000_000L, plays * 200_000L)

    private val songs = listOf(
        song(1, "Neon Skyline", "The Midnight Echoes", "City Lights", 7),
        song(2, "Paper Boats", "Harbour & Hale", "Low Tide", 8),
        song(3, "A Very Long Track Title That Needs To Wrap Or Ellipsize Cleanly", "Artist With A Long Name Too", "Album", 9),
        song(4, "Quiet Rooms", "Soft Machines", "Rooms", 10),
        song(5, "Afterglow", "Lumen", "Afterglow", 11),
    )

    private fun summary(period: WrappedPeriod) = WrappedSummary(
        period = period,
        totalPlayCount = 1234, totalSkipCount = 87, totalListeningTimeMs = 52L * 3_600_000L + 17L * 60_000L,
        uniqueSongsPlayedCount = 312, uniqueArtistsPlayedCount = 96, uniqueAlbumsPlayedCount = 40,
        listeningDaysCount = 87, busiestDay = LocalDate.of(2025, 6, 14), busiestDayPlayCount = 41,
        averagePlaysPerActiveDay = 14.2,
        topSongs = songs.take(3).mapIndexed { i, s -> stat(s, 120 - i * 17) },
        topArtists = listOf(
            ArtistReportSummary("The Midnight Echoes", 12, 3, 300, 4, 60_000_000L, 1_700_000_000_000L),
            ArtistReportSummary("Harbour & Hale", 9, 2, 210, 2, 40_000_000L, 1_700_000_000_000L),
            ArtistReportSummary("Soft Machines", 7, 2, 150, 9, 30_000_000L, 1_700_000_000_000L),
        ),
        topAlbums = listOf(
            AlbumReportSummary("City Lights", "The Midnight Echoes", 10, 280, 3, 50_000_000L, 1_700_000_000_000L),
            AlbumReportSummary("Low Tide", "Harbour & Hale", 8, 190, 1, 33_000_000L, 1_700_000_000_000L),
            AlbumReportSummary("Rooms", "Soft Machines", 6, 120, 5, 22_000_000L, 1_700_000_000_000L),
        ),
        mostPlayedSong = stat(songs[0], 120), mostPlayedArtist = null, mostPlayedAlbum = null,
        mostSkippedTrack = stat(songs[3], 4, skips = 19),
        recentlyPlayed = songs.map { stat(it, 3) },
        emptyState = ListeningAnalyticsEmptyState(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, true, true),
        longestStreak = 14, currentStreak = 5, mostActiveDayOfWeek = DayOfWeek.FRIDAY, mostActiveHour = 21,
        averageListeningTimePerActiveDayMs = 36L * 60_000L, mostReplayedTrack = stat(songs[0], 120),
    )

    private fun artworkUri(): String {
        val f = File(base, "art.png")
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        fun quad(x: Float, y: Float, color: Int) { p.color = color; c.drawRect(x, y, x + 300f, y + 300f, p) }
        quad(0f, 0f, 0xFFE53935.toInt()); quad(300f, 0f, 0xFF43A047.toInt())
        quad(0f, 300f, 0xFF1E88E5.toInt()); quad(300f, 300f, 0xFFFDD835.toInt())
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return Uri.fromFile(f).toString()
    }

    private val hostState = mutableStateOf<WrappedSlideHost?>(null)
    private var contentSet = false

    private fun generate(period: WrappedPeriod, id: String): WrappedExportOutcome.Success {
        val plan = buildWrappedExportPlan(
            summary(period), settings, WrappedVisualStyle.DEFAULT, true, exportId = id, artworkUriFor = { artworkUri() },
        )
        if (!contentSet) {
            contentSet = true
            rule.setContent {
                WavdropTheme(themeMode = ThemeMode.DARK) {
                    Box(Modifier.background(MaterialTheme.colorScheme.background)) { hostState.value?.Content() }
                }
            }
        }
        val host = WrappedSlideHost(plan)
        hostState.value = host
        rule.waitForIdle()
        val d = GlobalScope.async(Dispatchers.Default) { exportWrappedSlides(ctx, host, plan, base) }
        rule.waitUntil(timeoutMillis = 180_000) { d.isCompleted }
        return runBlocking { d.await() } as WrappedExportOutcome.Success
    }

    private fun newEncoder(progressIntervalMs: Long = 250L) = WrappedVideoEncoder(
        engineFactory = { Media3WrappedVideoEngine(ctx) },
        inspect = ::inspectWrappedVideo,
        decodable = ::wrappedSlideDecodable,
        progressIntervalMs = progressIntervalMs,
    )

    // ---- measurement helpers ---------------------------------------------------------------------------------

    /** Posts a tick to the main looper and records the worst lateness while it runs: a main-thread stall meter. */
    private class MainThreadStallMeter {
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var running = false
        @Volatile var maxLateMs = 0L
        @Volatile var ticks = 0
        private var expected = 0L
        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val late = SystemClock.uptimeMillis() - expected
                if (late > maxLateMs) maxLateMs = late
                ticks++
                expected = SystemClock.uptimeMillis() + 16
                handler.postDelayed(this, 16)
            }
        }
        fun start() { running = true; expected = SystemClock.uptimeMillis() + 16; handler.postDelayed(tick, 16) }
        fun stop() { running = false; handler.removeCallbacks(tick) }
    }

    private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    private fun threadNames(): Set<String> = Thread.getAllStackTraces().keys.map { it.name }.toSet()

    /** 180x320 grid sampled at the centre of every 4x4 block of a 720x1280 image (same grid for slides and video). */
    private fun downscaled(bitmap: Bitmap): IntArray {
        val px = IntArray(180 * 320)
        for (y in 0 until 320) for (x in 0 until 180) px[y * 180 + x] = bitmap.getPixel(x * 4 + 2, y * 4 + 2)
        return px
    }

    /** Converts one decoded YUV420 frame to the same 180x320 grid (BT.601 or BT.709 per the decoder's colour standard). */
    private fun gridFromImage(image: android.media.Image, bt601: Boolean): IntArray {
        val crop = image.cropRect
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val px = IntArray(180 * 320)
        for (gy in 0 until 320) for (gx in 0 until 180) {
            val x = crop.left + gx * 4 + 2
            val y = crop.top + gy * 4 + 2
            val yy = (yP.buffer.get(y * yP.rowStride + x * yP.pixelStride).toInt() and 0xFF) - 16
            val u = (uP.buffer.get((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride).toInt() and 0xFF) - 128
            val v = (vP.buffer.get((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride).toInt() and 0xFF) - 128
            val c = 1.164f * yy
            val r = if (bt601) c + 1.596f * v else c + 1.793f * v
            val g = if (bt601) c - 0.391f * u - 0.813f * v else c - 0.213f * u - 0.533f * v
            val b = if (bt601) c + 2.018f * u else c + 2.112f * u
            fun cl(f: Float) = f.toInt().coerceIn(0, 255)
            px[gy * 180 + gx] = (0xFF shl 24) or (cl(r) shl 16) or (cl(g) shl 8) or cl(b)
        }
        return px
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) {
            sum += abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF)) +
                abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF)) + abs((a[i] and 0xFF) - (b[i] and 0xFF))
        }
        return sum / (3.0 * a.size)
    }

    private fun luminanceSpread(px: IntArray): Int {
        var lo = 255; var hi = 0
        for (c in px) { val y = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10; if (y < lo) lo = y; if (y > hi) hi = y }
        return hi - lo
    }

    /** One frame via a FRESH retriever per attempt (a retriever's decoder session can fail independently of the file). */
    private fun frameAt(mp4: File, tMs: Long, report: StringBuilder): Bitmap? {
        for (attempt in 1..3) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(mp4.path)
                val f = r.getFrameAtTime(tMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (f != null) {
                    if (attempt > 1) report.appendLine("RETRY frame@${tMs}ms succeeded on attempt $attempt")
                    return f
                }
                report.appendLine("RETRIEVER-NULL frame@${tMs}ms attempt $attempt")
            } catch (e: Exception) {
                report.appendLine("RETRIEVER-EXCEPTION frame@${tMs}ms attempt $attempt: ${e.javaClass.simpleName}")
            } finally {
                r.release()
            }
        }
        return null
    }

    private class DecodeStats(
        val frames: Int, val firstPtsUs: Long, val lastPtsUs: Long, val monotonic: Boolean, val decoderName: String,
        /** For each requested time (ms): the 180x320 grid of the first decoded frame at or after it. */
        val samples: Map<Long, IntArray>,
    )

    /**
     * Independent of MediaMetadataRetriever (whose seeking is unreliable on some hardware decoders): demux and decode
     * EVERY frame with MediaCodec, count them, and sample the frames at [targetsMs].
     */
    private fun decodeAllFrames(mp4: File, targetsMs: List<Long> = emptyList()): DecodeStats {
        val pending = targetsMs.sorted().toMutableList()
        val samples = HashMap<Long, IntArray>()
        val ex = android.media.MediaExtractor()
        ex.setDataSource(mp4.path)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = android.media.MediaCodec.BufferInfo()
        var frames = 0
        var first = -1L
        var last = -1L
        var mono = true
        var inputDone = false
        var outputDone = false
        val deadline = SystemClock.elapsedRealtime() + 120_000
        try {
            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        frames++
                        if (first < 0) first = info.presentationTimeUs
                        if (info.presentationTimeUs < last) mono = false
                        last = info.presentationTimeUs
                        while (pending.isNotEmpty() && info.presentationTimeUs >= pending.first() * 1_000 - 16_000) {
                            val t = pending.removeAt(0)
                            val image = codec.getOutputImage(o)
                            if (image != null) {
                                val std = codec.outputFormat.let { if (it.containsKey(android.media.MediaFormat.KEY_COLOR_STANDARD)) it.getInteger(android.media.MediaFormat.KEY_COLOR_STANDARD) else 1 }
                                samples[t] = gridFromImage(image, bt601 = std == 2 || std == 4)
                                image.close()
                            }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            val name = codec.name
            codec.stop(); codec.release(); ex.release()
            decoderNameHolder = name
        }
        return DecodeStats(frames, first, last, mono, decoderNameHolder, samples)
    }

    private var decoderNameHolder = ""

    /** Number of sync (key) frames and total samples, to explain seek behaviour of a given decoder. */
    private fun syncSamples(mp4: File): Pair<Int, Int> {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(mp4.path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
            ex.selectTrack(track)
            var sync = 0
            var total = 0
            while (ex.sampleTime >= 0) {
                total++
                if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) sync++
                if (!ex.advance()) break
            }
            return sync to total
        } finally { ex.release() }
    }

    /** Decodes the video with the platform decoder near every boundary and matches each frame to its source slide. */
    /** Probe times (ms) and the slide each must show: first frame, mid-slide, 100 ms either side of every cut, near the end. */
    private fun probesFor(input: WrappedExportOutcome.Success): List<Pair<Long, Int>> {
        var elapsedMs = 0L
        val probes = ArrayList<Pair<Long, Int>>()
        for ((k, e) in input.entries.withIndex()) {
            val d = e.durationMs.toLong()
            probes += (elapsedMs + d / 2) to k
            if (k > 0) probes += (elapsedMs - 100) to (k - 1) // just before the cut
            probes += (elapsedMs + 100) to k // just after the cut
            elapsedMs += d
        }
        probes += 0L to 0 // the very first frame
        probes += (elapsedMs - 100) to (input.entries.size - 1) // near the end
        return probes.sortedBy { it.first }
    }

    private fun checkFramesAgainstSlides(input: WrappedExportOutcome.Success, stats: DecodeStats, report: StringBuilder) {
        val sources = input.entries.map { e ->
            val b = BitmapFactory.decodeFile(File(input.directory, e.fileName).path)!!
            try { downscaled(b) } finally { b.recycle() }
        }
        run {
            val probes = probesFor(input)
            var worstExpected = 0.0
            var minMargin = Double.MAX_VALUE
            for ((tMs, expectedIndex) in probes) {
                val px = stats.samples[tMs]
                assertTrue("no decoded frame at $tMs ms (decoder ${stats.decoderName})", px != null)
                px!!
                assertTrue("blank frame at $tMs ms (spread ${luminanceSpread(px)})", luminanceSpread(px) > 40)
                val diffs = sources.map { meanAbsDiff(px, it) }
                val best = diffs.indices.minByOrNull { diffs[it] }!!
                val second = diffs.filterIndexed { i, _ -> i != best }.minOrNull() ?: Double.MAX_VALUE
                report.appendLine("frame@${tMs}ms expected=$expectedIndex best=$best diffExpected=%.2f nextBest=%.2f".format(diffs[expectedIndex], second))
                assertEquals("frame at $tMs ms shows the wrong slide (diffs=${diffs.map { "%.1f".format(it) }})", expectedIndex, best)
                worstExpected = maxOf(worstExpected, diffs[expectedIndex])
                minMargin = minOf(minMargin, (if (best == expectedIndex) second else diffs[expectedIndex]) - diffs[expectedIndex])
            }
            report.appendLine("frames probed=${probes.size} worstDiffToExpected=%.2f smallestMarginToNextSlide=%.2f".format(worstExpected, minMargin))
        }
    }

    private fun encodeAndVerify(label: String, export: WrappedExportOutcome.Success) {
        val report = StringBuilder("== $label ==\n")
        val states = java.util.Collections.synchronizedList(ArrayList<WrappedVideoState>())
        val progress = java.util.Collections.synchronizedList(ArrayList<Int?>())
        val psss = ArrayList<Int>()
        val meter = MainThreadStallMeter()
        val pssBefore = pssKb()
        val nativeBefore = Debug.getNativeHeapAllocatedSize() / 1024
        val enc = newEncoder()
        meter.start()
        val t0 = SystemClock.elapsedRealtime()
        val outcome = runBlocking {
            enc.encode(export, videos, "$label.mp4") { s ->
                states += s
                if (s is WrappedVideoState.Encoding) { progress += s.percent; psss += pssKb() }
            }
        }
        val wall = SystemClock.elapsedRealtime() - t0
        meter.stop()
        report.appendLine("outcome=${outcome.javaClass.simpleName}")
        if (outcome is WrappedVideoOutcome.Failed) {
            File(evidence, "$label.txt").writeText(report.toString() + "FAILED ${outcome.failure}: ${outcome.detail}\n")
        }
        outcome as? WrappedVideoOutcome.Completed ?: throw AssertionError("encode failed: ${(outcome as WrappedVideoOutcome.Failed).failure} ${outcome.detail}")

        val info = outcome.info
        val expectedMs = export.entries.sumOf { it.durationMs.toLong() }
        report.appendLine("slides=${export.entries.size} plannedMs=$expectedMs containerDurationMs=${info.durationMs}")
        report.appendLine("mime=${info.videoMime} videoTracks=${info.videoTrackCount} audioTracks=${info.audioTrackCount}")
        report.appendLine("coded=${info.codedWidth}x${info.codedHeight} rotation=${info.rotationDegrees} display=${info.displayWidth}x${info.displayHeight}")
        report.appendLine("sizeBytes=${outcome.sizeBytes} encodeMs(reported)=${outcome.encodeMs} wallMs=$wall ratioToVideo=%.2f".format(wall / expectedMs.toDouble()))
        report.appendLine("progress=${progress.toList()}")
        report.appendLine("states=${states.map { it.javaClass.simpleName }.distinct()}")
        report.appendLine("mainThreadMaxLateMs=${meter.maxLateMs} ticks=${meter.ticks}")
        report.appendLine("pssKb before=$pssBefore peak=${psss.maxOrNull()} after=${pssKb()} nativeHeapKb before=$nativeBefore after=${Debug.getNativeHeapAllocatedSize() / 1024}")

        assertTrue("verification: ${verifyWrappedVideoInfo(info, expectedMs)}", verifyWrappedVideoInfo(info, expectedMs).isEmpty())
        assertEquals(WrappedVideoState.Preparing, states.first())
        assertEquals(WrappedVideoState.Completed, states.last())
        assertTrue(File(videos, "$label.mp4").exists())
        assertTrue("no part file left", videos.list()!!.none { it.endsWith(".part") })
        assertEquals("sources preserved", export.entries.size + 1, export.directory.list()!!.size)
        assertTrue("video under 30 MB budget", outcome.sizeBytes < 30L * 1024 * 1024)

        // Persist the file and the container report BEFORE the frame checks so a decoder problem leaves evidence behind.
        File(videos, "$label.mp4").copyTo(File(evidence, "$label.mp4"), overwrite = true)
        // Full decode of every frame (and the probe frames), independent of MediaMetadataRetriever.
        val stats = decodeAllFrames(File(videos, "$label.mp4"), probesFor(export).map { it.first })
        val expectedFrames = expectedMs * WRAPPED_VIDEO_FRAME_RATE / 1000
        report.appendLine("fullDecode decoder=${stats.decoderName} frames=${stats.frames} expectedFrames=$expectedFrames firstPtsUs=${stats.firstPtsUs} lastPtsUs=${stats.lastPtsUs} monotonic=${stats.monotonic}")
        val (syncCount, sampleCount) = syncSamples(File(videos, "$label.mp4"))
        report.appendLine("syncSamples=$syncCount of $sampleCount samples")
        File(evidence, "$label.txt").writeText(report.toString())
        assertTrue("decoded ${stats.frames} frames, expected about $expectedFrames", abs(stats.frames - expectedFrames) <= 2)
        assertTrue("first frame at ${stats.firstPtsUs} us", stats.firstPtsUs in 0..40_000)
        assertTrue("last frame at ${stats.lastPtsUs} us, video is ${expectedMs} ms", stats.lastPtsUs >= (expectedMs - 70) * 1000)
        assertTrue("presentation times go backwards", stats.monotonic)
        try {
            checkFramesAgainstSlides(export, stats, report)
        } finally {
            File(evidence, "$label.txt").writeText(report.toString())
        }
        // Best effort, NOT asserted: full-size frames via MediaMetadataRetriever for visual inspection. Its seeking is
        // unreliable on some hardware decoders (observed on the Galaxy A15), so the success count is reported.
        val frameDir = File(evidence, "${label}_frames").also { it.deleteRecursively(); it.mkdirs() }
        var saved = 0
        var at = 0L
        for ((k, e) in export.entries.withIndex()) {
            frameAt(File(videos, "$label.mp4"), at + e.durationMs / 2, report)?.let { f ->
                File(frameDir, "frame_%02d_%s.png".format(k + 1, e.pageId.name.lowercase())).outputStream().use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
                f.recycle()
                saved++
            }
            at += e.durationMs
        }
        report.appendLine("retrieverFramesSaved=$saved of ${export.entries.size} (best effort, not asserted)")
        File(evidence, "$label.txt").writeText(report.toString())
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test fun allTimeReportEncodesToACompletePortraitMp4() {
        val export = generate(WrappedPeriod.AllTime, "vid-all-time")
        assertEquals(7, export.entries.size)
        encodeAndVerify("all_time", export)
    }

    @Test fun yearlyReportWithMilestonesEncodesToACompletePortraitMp4() {
        val export = generate(WrappedPeriod.year(2025, ZoneOffset.UTC), "vid-yearly")
        assertEquals(10, export.entries.size)
        encodeAndVerify("yearly_milestones", export)
    }

    @Test fun cancellingARealEncodeStopsTransformerRemovesTheOutputAndLeavesTheDeviceReusable() {
        val small = generate(WrappedPeriod.year(2025, ZoneOffset.UTC), "vid-cancel-src")
        // A long composition (120 slides, 6 minutes) so the real encode is still running when the cancel arrives.
        val bigDir = File(base, WRAPPED_EXPORT_DIR_PREFIX + "vid-cancel-big").also { it.mkdirs() }
        val source = File(small.directory, small.entries[1].fileName)
        val bigEntries = (0 until 120).map { i ->
            val name = "slide_%03d_overview.png".format(i + 1)
            Files.copy(source.toPath(), File(bigDir, name).toPath())
            WrappedExportEntry(i, WrappedPageId.OVERVIEW, name, 720, 1280, 3_000)
        }
        File(bigDir, "manifest.json").writeText(
            "{\"pageCount\":${bigEntries.size},\"slides\":[" + bigEntries.joinToString(",") { "{\"file\":\"${it.fileName}\"}" } + "]}",
        )
        val big = WrappedExportOutcome.Success(bigDir, bigEntries)

        val report = StringBuilder("== cancel ==\n")
        val threadsBefore = threadNames()
        val active = CompletableDeferred<Unit>()
        val states = java.util.Collections.synchronizedList(ArrayList<WrappedVideoState>())
        val enc = newEncoder(progressIntervalMs = 100)
        val encodeStarted = SystemClock.elapsedRealtime()
        var cancelledAfterMs = 0L
        var progressAtCancel: Int? = null
        var partBytesAtCancel = 0L
        val job = GlobalScope.launch(Dispatchers.Default) {
            enc.encode(big, videos, "never.mp4") { s ->
                states += s
                if (s is WrappedVideoState.Encoding && (s.percent ?: 0) >= 1) {
                    progressAtCancel = s.percent
                    active.complete(Unit)
                }
            }
        }
        runBlocking { active.await() } // a real, reporting encode is under way
        partBytesAtCancel = videos.listFiles()!!.filter { it.name.endsWith(".part") }.sumOf { it.length() }
        val threadsDuring = threadNames()
        cancelledAfterMs = SystemClock.elapsedRealtime() - encodeStarted
        val cancelStart = SystemClock.elapsedRealtime()
        runBlocking { job.cancelAndJoin() }
        val cancelMs = SystemClock.elapsedRealtime() - cancelStart
        assertTrue(job.isCancelled)
        report.appendLine("cancelledAfterMs=$cancelledAfterMs progressAtCancel=$progressAtCancel partBytesAtCancel=$partBytesAtCancel cancelCompletedInMs=$cancelMs")
        assertEquals(WrappedVideoState.Cancelled, states.last())
        assertTrue("part file was written before the cancel", partBytesAtCancel > 0)
        assertTrue("no part file after cancel", videos.list().orEmpty().none { it.endsWith(".part") })
        assertFalse("no video published", File(videos, "never.mp4").exists())
        assertEquals("sources preserved", bigEntries.size + 1, bigDir.list()!!.size)

        // Transformer/codec worker threads created for the encode must not outlive the cancel (bounded wait).
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lingering: Set<String>
        do {
            lingering = (threadNames() - threadsBefore).filter { n -> n.startsWith("Transformer") || n.startsWith("ExoPlayer") || n.contains("Codec") }.toSet()
            if (lingering.isEmpty()) break
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        report.appendLine("threads started during encode: ${(threadsDuring - threadsBefore).sorted()}")
        report.appendLine("encoder threads still alive after cancel: ${lingering.sorted()}")
        assertTrue("encoder threads still alive after cancel: $lingering", lingering.isEmpty())

        // The device is reusable: a fresh encode immediately afterwards succeeds and publishes a valid video.
        val after = runBlocking { newEncoder().encode(small, videos, "after_cancel.mp4") }
        report.appendLine("encode after cancel: ${after.javaClass.simpleName}")
        after as? WrappedVideoOutcome.Completed ?: throw AssertionError("post-cancel encode failed: ${(after as WrappedVideoOutcome.Failed).detail}")
        assertTrue(verifyWrappedVideoInfo(after.info, small.entries.sumOf { it.durationMs.toLong() }).isEmpty())
        File(evidence, "cancel.txt").writeText(report.toString())
    }

    @Test fun anExistingVideoIsNeverOverwrittenByARealEncode() {
        val export = generate(WrappedPeriod.AllTime, "vid-no-overwrite")
        videos.mkdirs()
        val existing = File(videos, "taken.mp4").also { it.writeText("finished earlier") }
        val r = runBlocking { newEncoder().encode(export, videos, "taken.mp4") } as WrappedVideoOutcome.Failed
        assertEquals(WrappedVideoFailure.OutputExists, r.failure)
        assertEquals("finished earlier", existing.readText())
    }
}
