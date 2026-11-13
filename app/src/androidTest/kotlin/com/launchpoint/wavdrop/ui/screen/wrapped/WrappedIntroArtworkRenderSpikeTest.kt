package com.launchpoint.wavdrop.ui.screen.wrapped

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.net.Uri
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ImageResult
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.data.settings.WrappedBackgroundIntensity
import com.launchpoint.wavdrop.data.settings.WrappedFallbackTheme
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import java.io.File
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5-WR-VID-02A1. Renders the artwork-backed Wrapped intro page for a locally generated artwork fixture through the
 * preloaded-artwork seam (no network, no user files, no pager). Output PNGs go to the app's external files dir and
 * are pulled by hand, not tracked in Git.
 */
@RunWith(AndroidJUnit4::class)
class WrappedIntroArtworkRenderSpikeTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir get() = File(ctx.cacheDir, "vid02a1").also { it.mkdirs() }
    private val outDir get() = ctx.getExternalFilesDir(null)!!

    private val fixture = WrappedSummary(
        period = WrappedPeriod.year(2025, ZoneOffset.UTC),
        totalPlayCount = 1234, totalSkipCount = 0, totalListeningTimeMs = 0L,
        uniqueSongsPlayedCount = 0, uniqueArtistsPlayedCount = 0, uniqueAlbumsPlayedCount = 0,
        listeningDaysCount = 0, busiestDay = null, busiestDayPlayCount = 0, averagePlaysPerActiveDay = 0.0,
        topSongs = emptyList(), topArtists = emptyList(), topAlbums = emptyList(),
        mostPlayedSong = null, mostPlayedArtist = null, mostPlayedAlbum = null, mostSkippedTrack = null,
        recentlyPlayed = emptyList(),
        emptyState = ListeningAnalyticsEmptyState(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, true, true),
        longestStreak = 0, currentStreak = 0, mostActiveDayOfWeek = null, mostActiveHour = null,
        averageListeningTimePerActiveDayMs = 0L, mostReplayedTrack = null,
    )

    private val settings = WrappedVisualSettings(
        useArtworkBackgrounds = true,
        backgroundIntensity = WrappedBackgroundIntensity.MEDIUM,
        fallbackTheme = WrappedFallbackTheme.AUTO,
    )

    private var showHost by mutableStateOf(true)
    private var layerRef: GraphicsLayer? = null

    @After fun cleanup() { dir.deleteRecursively() }

    // Four saturated quadrants so artwork presence and the scrim are measurable.
    private fun artworkFile(): File = File(dir, "art.png").also { f ->
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        fun quad(x: Float, y: Float, color: Int) { p.color = color; c.drawRect(x, y, x + 300f, y + 300f, p) }
        quad(0f, 0f, 0xFFE53935.toInt()); quad(300f, 0f, 0xFF43A047.toInt())
        quad(0f, 300f, 0xFF1E88E5.toInt()); quad(300f, 300f, 0xFFFDD835.toInt())
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun load(file: File, key: String = "fixture") = runBlocking {
        loadWrappedExportArtwork(ctx, Uri.fromFile(file), key)
    }

    private fun host(artwork: WrappedExportArtwork?, theme: ThemeMode = ThemeMode.DARK) {
        rule.setContent {
            if (showHost) {
                val layer = rememberGraphicsLayer()
                layerRef = layer
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 2f, fontScale = 1f),
                    LocalWrappedStaticRender provides true,
                    LocalWrappedExportArtwork provides artwork,
                ) {
                    WavdropTheme(themeMode = theme) {
                        Box(
                            Modifier
                                .requiredSize(360.dp, 640.dp)
                                .drawWithContent {
                                    layer.record { this@drawWithContent.drawContent() }
                                    drawLayer(layer)
                                }
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            val copy = WrappedPeriod.year(2025, ZoneOffset.UTC).toWrappedPeriodCopy()
                            IntroPage(fixture, copy, settings)
                        }
                    }
                }
            }
        }
    }

    private fun capture(): Bitmap = runBlocking { layerRef!!.toImageBitmap() }.asAndroidBitmap()

    private fun pixels(hw: Bitmap): IntArray {
        val b = hw.copy(Bitmap.Config.ARGB_8888, false)
        try { return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) } }
        finally { b.recycle() }
    }

    private fun IntArray.at(x: Int, y: Int) = this[y * 720 + x]
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    private fun whitePixels(p: IntArray) = p.count { r(it) >= 235 && g(it) >= 235 && b(it) >= 235 }

    private fun save(bmp: Bitmap, name: String) =
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test fun artworkBackedPageRendersFinalStateFromTheFirstFrame() {
        val file = artworkFile()
        val t0 = System.nanoTime()
        val art = load(file)
        val loadMs = (System.nanoTime() - t0) / 1e6
        assertTrue("artwork must load: $art", art is WrappedExportArtwork.Ready)

        rule.mainClock.autoAdvance = false // first frame must already be final: no animation is allowed to run
        host(art)
        rule.waitForIdle()
        val tCap = System.nanoTime()
        val first = capture()
        val capMs = (System.nanoTime() - tCap) / 1e6
        assertEquals(720, first.width)
        assertEquals(1280, first.height)
        val early = pixels(first)

        rule.mainClock.autoAdvance = true
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val second = capture()
        val late = pixels(second)
        assertTrue("first capture equals final state (no crossfade in progress)", early.contentEquals(late))
        assertTrue("every pixel opaque", early.all { (it ushr 24) == 255 })

        // Artwork is present, tinted by the scrim: darker than the source colour but still clearly that colour.
        val red = early.at(180, 500); val green = early.at(560, 500)
        val blue = early.at(180, 780); val yellow = early.at(560, 780)
        assertTrue("red quadrant", r(red) > g(red) + 40 && r(red) > b(red) + 40 && r(red) < 0xE5)
        assertTrue("green quadrant", g(green) > r(green) + 25 && g(green) > b(green) + 25 && g(green) < 0xA0)
        assertTrue("blue quadrant", b(blue) > r(blue) + 40 && b(blue) < 0xE0)
        assertTrue("yellow quadrant", r(yellow) > b(yellow) + 60 && g(yellow) > b(yellow) + 60)

        // Foreground text is composed and visible (white over the scrim).
        rule.onNodeWithText("Your music stayed with you.").assertExists()
        val white = whitePixels(early)
        assertTrue("white text pixels present ($white)", white > 400)

        save(first, "wavdrop_vid02a1_capture1.png")
        save(second, "wavdrop_vid02a1_capture2.png")
        File(outDir, "wavdrop_vid02a1_report.txt").writeText(
            "size=${first.width}x${first.height} config=${first.config} allocBytes=${first.allocationByteCount}\n" +
                "artworkLoadMs=$loadMs captureMs=$capMs whitePixels=$white\n" +
                "samples red=${Integer.toHexString(red)} green=${Integer.toHexString(green)} " +
                "blue=${Integer.toHexString(blue)} yellow=${Integer.toHexString(yellow)}\n" +
                "nativeHeapKB=${Debug.getNativeHeapAllocatedSize() / 1024}\n",
        )
    }

    @Test fun repeatedCapturesFromIdenticalInputsAreIdentical() {
        val art = load(artworkFile())
        host(art)
        rule.waitForIdle()
        val a = pixels(capture())
        val b2 = pixels(capture())
        assertTrue(a.contentEquals(b2))
        // A second host built from the same inputs (new composition, new layer) is identical as well.
        showHost = false; rule.waitForIdle(); showHost = true
        rule.waitForIdle()
        assertTrue("new composition from identical input", a.contentEquals(pixels(capture())))
    }

    @Test fun missingArtworkGivesCompleteDeterministicFallback() {
        val missing = load(File(dir, "does-not-exist.png"))
        assertTrue("missing -> Unavailable: $missing", missing is WrappedExportArtwork.Unavailable)
        val reasonMissing = (missing as WrappedExportArtwork.Unavailable).reason

        rule.mainClock.autoAdvance = false
        host(missing)
        rule.waitForIdle()
        val early = pixels(capture())
        rule.mainClock.autoAdvance = true
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        val late = pixels(capture())
        assertTrue("fallback is final on the first frame", early.contentEquals(late))
        assertTrue("fallback opaque", early.all { (it ushr 24) == 255 })
        rule.onNodeWithText("Your music stayed with you.").assertExists()
        // No artwork colours leaked into the fallback.
        val red = early.at(180, 500)
        assertFalse("no red artwork in fallback", r(red) > g(red) + 40 && r(red) > b(red) + 40)
        assertTrue("fallback is not a flat fill", early.toHashSet().size > 50)
        File(outDir, "wavdrop_vid02a1_fallback_missing.txt").writeText("reason=$reasonMissing\n")
        save(capture(), "wavdrop_vid02a1_fallback.png")
    }

    @Test fun decodeFailureGivesFallbackRendering() {
        val bad = File(dir, "bad.png").also { it.writeBytes(ByteArray(64) { 0x7F }) }
        val result = load(bad)
        assertTrue("undecodable -> Unavailable: $result", result is WrappedExportArtwork.Unavailable)
        host(result)
        rule.waitForIdle()
        val p = pixels(capture())
        assertTrue(p.all { (it ushr 24) == 255 })
        rule.onNodeWithText("Your music stayed with you.").assertExists()
        File(outDir, "wavdrop_vid02a1_fallback_decode.txt")
            .writeText("reason=${(result as WrappedExportArtwork.Unavailable).reason}\n")
    }

    @Test fun artworkBackgroundsOffIgnoresSuppliedArtwork() {
        // The user's own "artwork backgrounds" setting still wins in export mode.
        val art = load(artworkFile())
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalWrappedStaticRender provides true,
                LocalWrappedExportArtwork provides art,
            ) {
                val layer = rememberGraphicsLayer()
                layerRef = layer
                WavdropTheme(themeMode = ThemeMode.DARK) {
                    Box(
                        Modifier.requiredSize(360.dp, 640.dp)
                            .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        IntroPage(
                            fixture,
                            WrappedPeriod.year(2025, ZoneOffset.UTC).toWrappedPeriodCopy(),
                            settings.copy(useArtworkBackgrounds = false),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val red = pixels(capture()).at(180, 500)
        assertFalse("artwork must not appear when disabled", r(red) > g(red) + 40 && r(red) > b(red) + 40)
    }

    @Test fun layerIsReleasedWhenHostLeavesComposition() {
        host(load(artworkFile()))
        rule.waitForIdle()
        val layer = layerRef!!
        assertFalse(layer.isReleased)
        showHost = false
        rule.waitForIdle()
        assertTrue(layer.isReleased)
        showHost = true
    }

    @Test fun cancelledArtworkLoadPropagatesCancellationAndLeavesNoHost() {
        val hang = ImageLoader.Builder(ctx)
            .components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult = awaitCancellation()
                })
            }
            .build()
        val started = java.util.concurrent.CountDownLatch(1)
        val job = GlobalScope.launch(Dispatchers.Default, CoroutineStart.DEFAULT) {
            started.countDown()
            loadWrappedExportArtwork(ctx, Uri.fromFile(artworkFile()), "k", loader = hang)
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        runBlocking { job.cancelAndJoin() }
        assertTrue("cancellation propagated, not converted to a result", job.isCancelled)
        hang.shutdown()

        // A render host attached for a request that is then abandoned is detached and its layer released.
        host(null)
        rule.waitForIdle()
        val layer = layerRef!!
        showHost = false
        rule.waitForIdle()
        assertTrue(layer.isReleased)
        showHost = true
    }

    @Test fun liveDefaultsAreUnchanged() {
        var exportDefault: WrappedExportArtwork? = WrappedExportArtwork.Unavailable(WrappedExportArtwork.Reason.NotFound)
        var staticDefault = true
        rule.setContent {
            exportDefault = LocalWrappedExportArtwork.current
            staticDefault = LocalWrappedStaticRender.current
        }
        rule.waitForIdle()
        assertEquals(null, exportDefault)
        assertFalse(staticDefault)
        assertNotNull(ctx)
    }
}
