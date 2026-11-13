package com.launchpoint.wavdrop.ui.screen.wrapped

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "WrappedShare"

/**
 * Shares the current Wrapped slide as a PNG: PixelCopy of the pager rectangle -> PNG in cacheDir/wrapped_share
 * (off the main thread) -> FileProvider URI -> chooser. Must be called from the main thread (a composition-scoped
 * coroutine), which also makes the screen's scope cancel it when the screen leaves composition.
 *
 * [isCurrent] reports whether the request still matches the screen (same report and page, not scrolling).
 */
internal suspend fun shareWrappedSlide(
    activity: Activity,
    srcRect: Rect,
    gate: WrappedShareGate,
    isCurrent: () -> Boolean,
): WrappedShareOutcome {
    val outcome = runWrappedShare(
        gate = gate,
        isCurrent = { isCurrent() && activity.isUsable() },
        capture = { captureWindowRegion(activity, srcRect) },
        encode = { bitmap -> encodeToShareFile(activity.cacheDir, bitmap) },
        recycle = { it.recycle() },
        launch = { file -> launchShareChooser(activity, file) },
        onUnexpected = { stage, e -> Log.e(TAG, "Wrapped share failed unexpectedly during $stage", e) },
    )
    if (activity.isUsable()) {
        when (outcome) {
            WrappedShareOutcome.CaptureFailed -> toast(activity, "Couldn't capture slide")
            WrappedShareOutcome.EncodeFailed -> toast(activity, "Couldn't save slide image")
            WrappedShareOutcome.LaunchFailed -> toast(activity, "Couldn't open the share sheet")
            else -> Unit
        }
    }
    return outcome
}

private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

private fun toast(activity: Activity, message: String) =
    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

/** PNG encode + prune of expired exports, on the IO dispatcher. PNG ignores the quality argument. */
private suspend fun encodeToShareFile(cacheDir: File, bitmap: Bitmap): File? = withContext(Dispatchers.IO) {
    val dir = File(cacheDir, WRAPPED_SHARE_DIR)
    val now = System.currentTimeMillis()
    pruneWrappedShareDir(dir, now)
    writeWrappedSharePng(dir, now) { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
}

/**
 * Copies [srcRect] (window coordinates) of the activity window. Returns null for an invalid rectangle, an
 * allocation failure or a PixelCopy failure. If the coroutine is cancelled while PixelCopy is pending, the bitmap is
 * left to the GC rather than recycled, because the platform may still be writing to it.
 */
private suspend fun captureWindowRegion(activity: Activity, srcRect: Rect): Bitmap? {
    val decor = activity.window.decorView
    if (!isCaptureRectValid(srcRect.left, srcRect.top, srcRect.right, srcRect.bottom, decor.width, decor.height)) {
        return null
    }
    val bitmap = try {
        Bitmap.createBitmap(srcRect.width(), srcRect.height(), Bitmap.Config.ARGB_8888)
    } catch (e: OutOfMemoryError) {
        return null
    } catch (e: IllegalArgumentException) {
        return null
    }
    val copied = suspendCancellableCoroutine { cont ->
        fun finish(success: Boolean) {
            if (cont.isActive) cont.resume(success)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val request = PixelCopy.Request.Builder
                    .ofWindow(activity.window)
                    .setSourceRect(srcRect)
                    .setDestinationBitmap(bitmap)
                    .build()
                // API 34+: callback is Consumer<PixelCopy.Result>, run on the main executor.
                PixelCopy.request(request, ContextCompat.getMainExecutor(activity)) { result ->
                    finish(result.status == PixelCopy.SUCCESS)
                }
            } else {
                // API 26–33: OnPixelCopyFinishedListener, delivered on the main looper.
                @Suppress("DEPRECATION")
                PixelCopy.request(activity.window, srcRect, bitmap, { result ->
                    finish(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            }
        } catch (e: IllegalArgumentException) {
            // e.g. the window has no valid surface.
            finish(false)
        }
    }
    if (!copied) {
        bitmap.recycle() // PixelCopy has finished (with failure), so the bitmap is no longer in use.
        return null
    }
    return bitmap
}

/** ACTION_SEND for an image/png, read-only grant via both the URI ClipData and FLAG_GRANT_READ_URI_PERMISSION. */
internal fun buildWrappedShareIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

private fun launchShareChooser(activity: Activity, file: File): Boolean = try {
    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
    val chooser = Intent.createChooser(buildWrappedShareIntent(uri), null)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    activity.startActivity(chooser)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
} catch (e: IllegalArgumentException) {
    false // FileProvider cannot map the file to a configured path
}
