package com.launchpoint.wavdrop.ui.screen.wrapped

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.drawscope.draw
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
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyReason
import com.launchpoint.wavdrop.data.model.ListeningAnalyticsEmptyState
import com.launchpoint.wavdrop.data.model.WrappedPeriod
import com.launchpoint.wavdrop.data.model.WrappedSummary
import com.launchpoint.wavdrop.data.settings.ThemeMode
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P5-WR-VID-02A0 spike. Renders [OverviewPage] for a fixed fixture into a 720x1280 bitmap through a GraphicsLayer
 * hosted in a Compose test activity (no pager, no ViewModel, no production screen). Real rendering behaviour is
 * verified on the emulator; output PNGs go to the app's external files dir and are pulled by hand, not tracked in Git.
 */
@RunWith(AndroidJUnit4::class)
class WrappedOverviewRenderSpikeTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fixture = WrappedSummary(
        period = WrappedPeriod.year(2025, ZoneOffset.UTC),
        totalPlayCount = 1234,
        totalSkipCount = 0,
        totalListeningTimeMs = 52L * 3_600_000L + 17L * 60_000L,
        uniqueSongsPlayedCount = 312,
        uniqueArtistsPlayedCount = 96,
        uniqueAlbumsPlayedCount = 40,
        listeningDaysCount = 87,
        busiestDay = LocalDate.of(2025, 6, 14),
        busiestDayPlayCount = 41,
        averagePlaysPerActiveDay = 14.2,
        topSongs = emptyList(),
        topArtists = emptyList(),
        topAlbums = emptyList(),
        mostPlayedSong = null,
        mostPlayedArtist = null,
        mostPlayedAlbum = null,
        mostSkippedTrack = null,
        recentlyPlayed = emptyList(),
        emptyState = ListeningAnalyticsEmptyState(ListeningAnalyticsEmptyReason.HAS_ACTIVITY, true, true),
        longestStreak = 0,
        currentStreak = 0,
        mostActiveDayOfWeek = null,
        mostActiveHour = null,
        averageListeningTimePerActiveDayMs = 0L,
        mostReplayedTrack = null,
    )

    private class Capture(val layer: GraphicsLayer, val bitmap: Bitmap, val captureNs: Long)

    private var showHost by mutableStateOf(true)
    private var layerRef: GraphicsLayer? = null

    private fun host(staticRender: Boolean) {
        rule.setContent {
            if (showHost) {
                val layer = rememberGraphicsLayer()
                layerRef = layer
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 2f, fontScale = 1f),
                    LocalWrappedStaticRender provides staticRender,
                ) {
                    WavdropTheme(themeMode = ThemeMode.DARK) {
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
                            OverviewPage(fixture, copy, isVisible = true, reduceMotion = true)
                        }
                    }
                }
            }
        }
    }

    private fun capture(): Capture {
        val layer = layerRef!!
        val t0 = System.nanoTime()
        val bmp = runBlocking { layer.toImageBitmap() }.asAndroidBitmap()
        return Capture(layer, bmp, System.nanoTime() - t0)
    }

    // toImageBitmap yields a HARDWARE bitmap on this device; copy to a readable software bitmap for pixel access.
    private fun pixels(hw: Bitmap): IntArray {
        val b = hw.copy(Bitmap.Config.ARGB_8888, false)
        try { return IntArray(b.width * b.height).also {
            b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) } } finally { b.recycle() }
    }

    private fun save(b: Bitmap, name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null)!!
        return File(dir, name).also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun finalStateRenders720x1280NonBlankAndRepeatable() {
        val tStart = System.nanoTime()
        host(staticRender = true)
        rule.waitForIdle()
        val first = capture()
        val tFirstDone = System.nanoTime()
        val a = first.bitmap
        assertEquals(720, a.width)
        assertEquals(1280, a.height)

        // Final statistic values are present in the composed page (semantics), not just painted.
        rule.onNodeWithText("1234").assertExists()
        rule.onNodeWithText("2d 4h").assertExists()
        rule.onNodeWithText("87").assertExists()
        rule.onNodeWithText("312").assertExists()
        rule.onNodeWithText("96").assertExists()

        val pa = pixels(a)
        val distinct = pa.toHashSet().size
        assertTrue("page must not be a flat fill (distinct colours=$distinct)", distinct > 50)
        assertTrue("every pixel opaque (background drawn into the layer)", pa.all { (it ushr 24) == 255 })
        val bg = pa[0]
        val nonBg = pa.count { it != bg }
        assertTrue("card/text pixels present ($nonBg)", nonBg > pa.size / 4)

        // Repeat capture of the same layer, then a second capture after more time has passed.
        val second = capture()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val third = capture()
        assertTrue("repeat capture identical", pa.contentEquals(pixels(second.bitmap)))
        assertTrue("capture after 2 s identical (no animation in progress)", pa.contentEquals(pixels(third.bitmap)))

        val mem = Runtime.getRuntime()
        val report = buildString {
            appendLine("size=${a.width}x${a.height} config=${a.config} allocBytes=${a.allocationByteCount}")
            appendLine("distinctColours=$distinct nonBackgroundPixels=$nonBg")
            appendLine("hostToFirstCaptureMs=${(tFirstDone - tStart) / 1e6}")
            appendLine("toImageBitmapMs first=${first.captureNs / 1e6} second=${second.captureNs / 1e6} third=${third.captureNs / 1e6}")
            appendLine("nativeHeapAllocatedKB=${Debug.getNativeHeapAllocatedSize() / 1024} javaUsedKB=${(mem.totalMemory() - mem.freeMemory()) / 1024}")
        }
        save(a, "wavdrop_vid02a0_capture1.png")
        save(second.bitmap, "wavdrop_vid02a0_capture2.png")
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "wavdrop_vid02a0_report.txt")
            .writeText(report)
    }

    @Test fun firstFrameIsFinalOnlyBecauseOfStaticRenderMode() {
        // Negative control: with the mode off and the clock paused, the first frame is mid-reveal (translucent card).
        rule.mainClock.autoAdvance = false
        host(staticRender = false)
        rule.waitForIdle()
        val early = pixels(capture().bitmap)
        rule.mainClock.autoAdvance = true
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val late = pixels(capture().bitmap)
        assertFalse("without static mode the first frame differs from the final state", early.contentEquals(late))

        // With the mode on, the very first frame already equals the final state.
    }

    @Test fun staticModeFirstFrameEqualsFinalFrame() {
        rule.mainClock.autoAdvance = false
        host(staticRender = true)
        rule.waitForIdle()
        val early = pixels(capture().bitmap)
        rule.mainClock.autoAdvance = true
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val late = pixels(capture().bitmap)
        assertTrue("static mode: first frame == final frame", early.contentEquals(late))
    }

    @Test fun layerIsReleasedWhenHostLeavesComposition() {
        host(staticRender = true)
        rule.waitForIdle()
        val layer = layerRef!!
        assertFalse(layer.isReleased)
        showHost = false
        rule.waitForIdle()
        assertTrue("layer released on disposal", layer.isReleased)
        showHost = true
    }
}
