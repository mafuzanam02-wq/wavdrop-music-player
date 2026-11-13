package com.launchpoint.wavdrop.ui.screen.wrapped

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.ImageLoader
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import java.io.FileNotFoundException

/**
 * P5-WR-VID-02A1: artwork prepared before an export render. An export host loads it once with
 * [loadWrappedExportArtwork] and provides the result through [LocalWrappedExportArtwork]; the page then composes the
 * final artwork (or the deterministic fallback) in its very first frame, so no capture ever waits on an async image.
 * Not provided by the live screen, which keeps its normal Coil loading and crossfade.
 */
internal sealed interface WrappedExportArtwork {
    /** Decoded artwork. [sourceKey] identifies what was loaded (used only to key page state). */
    class Ready(val sourceKey: String, val bitmap: ImageBitmap) : WrappedExportArtwork

    /** No usable artwork: the page renders its complete fallback presentation. */
    class Unavailable(val reason: Reason) : WrappedExportArtwork

    enum class Reason { NotFound, DecodeFailed }
}

internal val LocalWrappedExportArtwork = staticCompositionLocalOf<WrappedExportArtwork?> { null }

/**
 * Loads [data] (a Coil-supported model such as a content/file URI) at the export canvas size. Returns
 * [WrappedExportArtwork.Unavailable] for a missing or undecodable source rather than waiting or throwing. Coroutine
 * cancellation is never caught: it propagates to the caller.
 */
internal suspend fun loadWrappedExportArtwork(
    context: Context,
    data: Any,
    sourceKey: String,
    widthPx: Int = 720,
    heightPx: Int = 1280,
    loader: ImageLoader = context.imageLoader,
): WrappedExportArtwork {
    val request = ImageRequest.Builder(context)
        .data(data)
        .size(widthPx, heightPx)
        .scale(Scale.FILL)
        .build()
    return when (val result = loader.execute(request)) {
        is SuccessResult -> {
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                WrappedExportArtwork.Ready(sourceKey, bitmap.asImageBitmap())
            } else {
                WrappedExportArtwork.Unavailable(WrappedExportArtwork.Reason.DecodeFailed)
            }
        }
        is ErrorResult -> WrappedExportArtwork.Unavailable(
            if (result.throwable is FileNotFoundException) {
                WrappedExportArtwork.Reason.NotFound
            } else {
                WrappedExportArtwork.Reason.DecodeFailed
            },
        )
    }
}
