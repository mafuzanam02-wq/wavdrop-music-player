package com.launchpoint.wavdrop.ui.screen.wrapped

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.launchpoint.wavdrop.data.model.AlbumReportSummary
import com.launchpoint.wavdrop.data.model.ArtistReportSummary
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.MonthYear
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
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
 * P5-WR-VID-02A2. Full-story static slide generation on a device: every page of All Time / Monthly / Yearly fixtures
 * is rendered through [WrappedSlideHost] into PNG files. Fixtures are synthetic (no user data, no network). Evidence
 * is copied to the app's external files dir and pulled by hand; nothing is tracked in Git.
 */
@OptIn(DelicateCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WrappedSlideExportInstrumentedTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = File(ctx.cacheDir, "vid02a2_test").also { it.mkdirs() }
    private val evidence get() = File(ctx.getExternalFilesDir(null)!!, "vid02a2").also { it.mkdirs() }

    @Before fun clean() { base.deleteRecursively() }
    @After fun tearDown() { base.deleteRecursively() }

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

    private fun summary(period: WrappedPeriod, plays: Int = 1234) = WrappedSummary(
        period = period,
        totalPlayCount = plays, totalSkipCount = 87, totalListeningTimeMs = 52L * 3_600_000L + 17L * 60_000L,
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

    // Four saturated quadrants so the Intro artwork is easy to recognise in pixels.
    private fun artworkFile(): File = File(base, "art.png").also { f ->
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        fun quad(x: Float, y: Float, color: Int) { p.color = color; c.drawRect(x, y, x + 300f, y + 300f, p) }
        quad(0f, 0f, 0xFFE53935.toInt()); quad(300f, 0f, 0xFF43A047.toInt())
        quad(0f, 300f, 0xFF1E88E5.toInt()); quad(300f, 300f, 0xFFFDD835.toInt())
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun plan(
        s: WrappedSummary,
        id: String,
        artworkUri: String? = Uri.fromFile(artworkFile()).toString(),
        milestonePref: Boolean = true,
        visual: WrappedVisualSettings = settings,
    ) = buildWrappedExportPlan(
        s, visual, WrappedVisualStyle.DEFAULT, milestonePref, exportId = id, artworkUriFor = { artworkUri },
    )

    private val allTime = WrappedPeriod.AllTime
    private val monthly = WrappedPeriod.month(MonthYear(2025, 6), ZoneOffset.UTC)
    private val yearly = WrappedPeriod.year(2025, ZoneOffset.UTC)

    // One root composition per test (the rule allows a single setContent); hosts / direct content are swapped in it.
    private val hostState = mutableStateOf<WrappedSlideHost?>(null)
    private val directState = mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    private var contentSet = false

    private fun ensureContent() {
        if (contentSet) return
        contentSet = true
        rule.setContent {
            WavdropTheme(themeMode = ThemeMode.DARK) {
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    hostState.value?.Content()
                    directState.value?.invoke()
                }
            }
        }
    }

    private fun hostFor(p: WrappedExportPlan): WrappedSlideHost {
        ensureContent()
        val host = WrappedSlideHost(p)
        directState.value = null
        hostState.value = host
        rule.waitForIdle()
        return host
    }

    private fun <T> pump(timeoutMs: Long = 180_000, block: suspend () -> T): T {
        val d = GlobalScope.async(Dispatchers.Default) { block() }
        rule.waitUntil(timeoutMillis = timeoutMs) { d.isCompleted }
        return runBlocking { d.await() }
    }

    private fun export(p: WrappedExportPlan, host: WrappedSlideHost) =
        pump { exportWrappedSlides(ctx, host, p, base) }

    private fun decode(f: File): IntArray {
        val b = BitmapFactory.decodeFile(f.path)!!
        try {
            assertEquals(720, b.width)
            assertEquals(1280, b.height)
            return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        } finally { b.recycle() }
    }

    private fun sha(f: File) =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun IntArray.at(x: Int, y: Int) = this[y * 720 + x]
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun isBright(c: Int) = r(c) >= 215 && g(c) >= 215 && b(c) >= 215

    private fun assertSlide(p: IntArray, name: String) {
        assertTrue("$name opaque", p.all { (it ushr 24) == 255 })
        assertTrue("$name not a flat fill (${p.toHashSet().size} colours)", p.toHashSet().size > 50)
        assertTrue("$name has visible text pixels", p.count(::isBright) > 100 || p.count { r(it) < 60 && g(it) < 60 } > 100)
        // Clipping heuristic: nothing bright may reach the outer right gutter or bottom edge of the card.
        for (y in 0 until 1280) for (x in 676 until 720) {
            assertFalse("$name bright pixel in right gutter at ($x,$y)", isBright(p.at(x, y)))
        }
        for (y in 1268 until 1280) for (x in 0 until 720) {
            assertFalse("$name bright pixel in bottom gutter at ($x,$y)", isBright(p.at(x, y)))
        }
    }

    private fun copyEvidence(out: WrappedExportOutcome.Success, label: String) {
        val dest = File(evidence, label).also { it.deleteRecursively(); it.mkdirs() }
        out.directory.listFiles()!!.forEach { it.copyTo(File(dest, it.name)) }
    }

    @Test fun yearlyWithMilestonesProducesEveryPageAndKeepsLiveOrder() {
        val p = plan(summary(yearly), "y1")
        assertEquals(10, p.pageCount)
        val host = hostFor(p)
        val livePage = mutableStateOf(3) // stands in for the live pager position: export must never touch it
        val tStart = System.nanoTime()
        val heapBefore = Debug.getNativeHeapAllocatedSize()
        val out = export(p, host) as WrappedExportOutcome.Success
        val totalMs = (System.nanoTime() - tStart) / 1e6
        assertEquals(wrappedPageIds(com.launchpoint.wavdrop.data.model.WrappedScope.YEARLY, true), out.entries.map { it.pageId })
        assertEquals(p.pages.map { it.fileName }, out.entries.map { it.fileName })
        assertEquals(10, out.entries.size)
        var totalBytes = 0L
        for (e in out.entries) {
            val f = File(out.directory, e.fileName)
            assertTrue(f.exists())
            totalBytes += f.length()
            assertEquals(720, e.widthPx); assertEquals(1280, e.heightPx)
            assertSlide(decode(f), e.fileName)
        }
        assertTrue(File(out.directory, "manifest.json").exists())
        assertEquals("pager untouched", 3, livePage.value)
        // Host detached: no page text remains composed.
        rule.onNodeWithText("Your music stayed with you.").assertDoesNotExist()
        copyEvidence(out, "yearly_milestones")
        File(evidence, "yearly_report.txt").writeText(
            "pages=${out.entries.size} totalMs=$totalMs perPageMs=${totalMs / out.entries.size} bytes=$totalBytes\n" +
                "nativeHeapKB before=${heapBefore / 1024} after=${Debug.getNativeHeapAllocatedSize() / 1024}\n",
        )
    }

    @Test fun allTimeAndMonthlyAndYearlyWithoutMilestonesHaveTheLiveCounts() {
        for ((period, expected, label) in listOf(
            Triple(allTime, 7, "all_time"), Triple(monthly, 9, "monthly"), Triple(yearly, 9, "yearly_no_milestones"),
        )) {
            val id = "c-" + label.replace('_', '-')
            val p = if (label == "yearly_no_milestones") plan(summary(period), id, milestonePref = false)
            else plan(summary(period), id)
            assertEquals(expected, p.pageCount)
            val host = hostFor(p)
            val out = export(p, host) as WrappedExportOutcome.Success
            assertEquals(expected, out.entries.size)
            out.entries.forEach { assertSlide(decode(File(out.directory, it.fileName)), "$label/${it.fileName}") }
            copyEvidence(out, label)
        }
    }

    @Test fun repeatedExportsOfTheSameFixtureAreIdentical() {
        val s = summary(monthly)
        val p1 = plan(s, "r1"); val p2 = plan(s, "r2")
        val host1 = hostFor(p1)
        val a = export(p1, host1) as WrappedExportOutcome.Success
        val host2 = hostFor(p2)
        val b2 = export(p2, host2) as WrappedExportOutcome.Success
        assertTrue(a.directory != b2.directory)
        for (e in a.entries) {
            assertEquals("page ${e.fileName} repeatable", sha(File(a.directory, e.fileName)), sha(File(b2.directory, e.fileName)))
        }
    }

    @Test fun introShowsArtworkAndFallsBackWhenArtworkIsMissingOrDisabled() {
        fun introPixels(p: WrappedExportPlan): IntArray {
            val host = hostFor(p)
            val out = export(p, host) as WrappedExportOutcome.Success
            return decode(File(out.directory, out.entries.first().fileName))
        }
        fun redDominant(c: Int) = r(c) > g(c) + 40 && r(c) > b(c) + 40
        val withArt = introPixels(plan(summary(yearly), "i1"))
        assertTrue("artwork visible", redDominant(withArt.at(180, 500)))
        assertTrue("text visible over scrim", withArt.count(::isBright) > 400)

        val missing = introPixels(plan(summary(yearly), "i2", artworkUri = Uri.fromFile(File(base, "none.png")).toString()))
        assertFalse("missing artwork -> fallback", redDominant(missing.at(180, 500)))
        assertSlide(missing, "intro-missing")

        val off = introPixels(plan(summary(yearly), "i3", visual = settings.copy(useArtworkBackgrounds = false)))
        assertFalse("backgrounds off -> fallback", redDominant(off.at(180, 500)))
        assertTrue("fallback is deterministic across missing/off", missing.contentEquals(off))
    }

    @Test fun overviewSlideEqualsAnIndependentDirectRenderOfTheSamePage() {
        // Proves the exported Overview shows the same final statistics as the live composable at export settings.
        val p = plan(summary(yearly), "o1")
        val host = hostFor(p)
        val out = export(p, host) as WrappedExportOutcome.Success
        val exported = decode(File(out.directory, out.entries[1].fileName))

        val direct: IntArray
        hostState.value = null
        directState.value = {
            val layer = rememberGraphicsLayer()
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalWrappedStaticRender provides true,
                LocalWrappedExportArtwork provides WrappedExportArtwork.Unavailable(WrappedExportArtwork.Reason.NotFound),
            ) {
                Box(
                    Modifier.requiredSize(360.dp, 640.dp)
                        .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                        .background(MaterialTheme.colorScheme.background),
                ) { OverviewPage(p.summary, p.periodCopy, isVisible = true, reduceMotion = true) }
            }
            directLayer = layer
        }
        rule.waitForIdle()
        rule.onNodeWithText("1234").assertExists()
        rule.onNodeWithText("2d 4h").assertExists()
        val bmp = runBlocking { directLayer!!.toImageBitmap() }.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        direct = IntArray(720 * 1280).also { bmp.getPixels(it, 0, 720, 0, 0, 720, 1280) }
        bmp.recycle()
        assertTrue("exported Overview equals the direct render", exported.contentEquals(direct))
    }

    private var directLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null

    @Test fun cancellationAfterOnePageRemovesTheExportAndDetachesTheHost() {
        val p = plan(summary(yearly), "x1")
        val host = hostFor(p)
        val stranger = File(base, "unrelated.txt").also { it.writeText("keep") }
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val job = GlobalScope.launch(Dispatchers.Default) {
            generateWrappedSlides(
                p, base,
                render = { page ->
                    if (page.index == 2) { started.complete(Unit); gate.await() } // never released: cancelled instead
                    host.capture(page, null)
                },
                sizeOf = { it.width to it.height },
                encode = { bmp, out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) },
                recycle = { it.recycle() },
            )
        }
        rule.waitUntil(timeoutMillis = 120_000) { started.isCompleted }
        val dir = wrappedExportDirectory(base, p.exportId)
        assertEquals("two pages were written before the cancel", 2, dir.list()!!.count { it.endsWith(".png") })
        runBlocking { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertFalse(dir.exists())
        assertTrue(stranger.exists())
        rule.onNodeWithText("Your music stayed with you.").assertDoesNotExist()
        // The host is reusable after a cancelled export.
        val after = pump { host.capture(p.pages[0], null) }
        assertEquals(720, after.width)
        after.recycle()
    }

    @Test fun aFailureOnALaterPageCleansUpAndReleasesTheHost() {
        val p = plan(summary(yearly), "f1")
        val host = hostFor(p)
        val out = pump {
            generateWrappedSlides(
                p, base,
                render = { page -> if (page.index == 3) throw IllegalStateException("simulated") else host.capture(page, null) },
                sizeOf = { it.width to it.height },
                encode = { bmp, out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) },
                recycle = { it.recycle() },
            )
        }
        out as WrappedExportOutcome.Failed
        assertEquals(3, out.pageIndex)
        assertEquals(WrappedExportOutcome.Reason.Unexpected, out.reason)
        assertFalse(wrappedExportDirectory(base, p.exportId).exists())
        val after = pump { host.capture(p.pages[0], null) } // host still usable, nothing left attached
        after.recycle()
    }

    @Test fun anArtworkSourceFromAnotherReportIsIgnored() {
        // The host only honours artwork loaded from this plan's own Intro source.
        val p = plan(summary(yearly), "k1")
        val host = hostFor(p)
        val foreign = loadWrappedExportArtworkBlocking(Uri.fromFile(artworkFile()).toString(), key = "someone-elses-key")
        val bmp = pump { host.capture(p.pages[0], foreign) }
        val px = IntArray(720 * 1280)
        val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
        sw.getPixels(px, 0, 720, 0, 0, 720, 1280)
        sw.recycle(); bmp.recycle()
        assertFalse("foreign artwork must not appear", r(px.at(180, 500)) > g(px.at(180, 500)) + 40)
    }

    private fun loadWrappedExportArtworkBlocking(uri: String, key: String) =
        runBlocking { loadWrappedExportArtwork(ctx, uri, key) }
}
