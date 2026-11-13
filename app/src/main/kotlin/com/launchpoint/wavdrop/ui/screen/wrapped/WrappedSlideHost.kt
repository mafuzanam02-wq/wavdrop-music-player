package com.launchpoint.wavdrop.ui.screen.wrapped

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CompletableDeferred

private const val TAG = "WrappedSlideExport"

/**
 * P5-WR-VID-02A2: renders one Wrapped page at a time into a 720x1280 bitmap through a GraphicsLayer (360x640 dp at
 * density 2, font scale 1), reusing the live page composables via [WrappedPageContent].
 *
 * Place [Content] once inside an attached composition that already carries the app theme (the host reads the current
 * MaterialTheme). [capture] then composes the requested page, waits for its draw (a real signal, not a delay) and
 * returns the bitmap. The page lives under `key(pageIndex)` with its own layer, so state, artwork and the layer of one
 * page can never leak into the next, and the layer is released as soon as the capture ends. Captures must be
 * sequential: one page is hosted at a time.
 */
internal class WrappedSlideHost(private val plan: WrappedExportPlan) {

    private class Request(val page: WrappedExportPage, val artwork: WrappedExportArtwork) {
        val drawn = CompletableDeferred<GraphicsLayer>()
    }

    private var request by mutableStateOf<Request?>(null)

    @Composable
    fun Content() {
        val r = request ?: return
        key(r.page.index) {
            val layer = rememberGraphicsLayer()
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2f, fontScale = 1f),
                LocalWrappedStaticRender provides true,
                LocalWrappedExportArtwork provides r.artwork,
                LocalWrappedStyleTokens provides plan.visualStyle.toStyleTokens(),
            ) {
                Box(
                    Modifier
                        .requiredSize(360.dp, 640.dp)
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                            r.drawn.complete(layer)
                        }
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    WrappedPageContent(
                        pageId = r.page.id,
                        wrapped = plan.summary,
                        periodCopy = plan.periodCopy,
                        visualSettings = plan.visualSettings,
                        milestones = plan.milestones,
                        periodLabel = plan.periodLabel,
                        isCurrentPage = true,
                        reduceMotion = true,
                        onTrackDetails = {},
                        onArtistDetails = {},
                        onAlbumDetails = {},
                    )
                }
            }
        }
    }

    /**
     * Hosts [page], returns its final-state bitmap and always detaches the page afterwards (also on cancellation or
     * failure). [artwork] is honoured only if it was loaded from this plan's Intro artwork source; anything else
     * becomes the deterministic fallback, so a previous report's or page's image can never be shown.
     */
    suspend fun capture(page: WrappedExportPage, artwork: WrappedExportArtwork?): Bitmap {
        check(request == null) { "a slide is already being captured" }
        val effective = when {
            artwork is WrappedExportArtwork.Ready && artwork.sourceKey != plan.introArtworkUri ->
                WrappedExportArtwork.Unavailable(WrappedExportArtwork.Reason.NotFound)
            artwork == null -> WrappedExportArtwork.Unavailable(WrappedExportArtwork.Reason.NotFound)
            else -> artwork
        }
        val r = Request(page, effective)
        request = r
        try {
            val layer = r.drawn.await()
            return layer.toImageBitmap().asAndroidBitmap()
        } finally {
            request = null
        }
    }
}

/** Isolated, per-app-private base directory for slide exports (not a FileProvider path, no storage permission). */
internal fun wrappedExportBaseDir(cacheDir: File): File = File(cacheDir, "wrapped_slide_export")

/**
 * Generates every slide of [plan] in order into a new directory under [baseDir]. The Intro artwork is loaded once,
 * before its capture, through the VID-02A1 loader. Cancellation propagates after the partial export is removed.
 */
internal suspend fun exportWrappedSlides(
    context: Context,
    host: WrappedSlideHost,
    plan: WrappedExportPlan,
    baseDir: File,
): WrappedExportOutcome = generateWrappedSlides(
    plan = plan,
    baseDir = baseDir,
    render = { page ->
        val artwork = if (page.id == WrappedPageId.INTRO) {
            plan.introArtworkUri?.let { loadWrappedExportArtwork(context, it, sourceKey = it) }
        } else {
            null
        }
        host.capture(page, artwork)
    },
    sizeOf = { it.width to it.height },
    encode = { bitmap, out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) },
    recycle = { it.recycle() },
    onUnexpected = { stage, e -> Log.e(TAG, "Slide export failed unexpectedly during $stage", e) },
)
