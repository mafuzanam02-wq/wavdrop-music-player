package com.launchpoint.wavdrop.ui.screen.wrapped

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

private const val FILEPROVIDER_AUTHORITY = "com.launchpoint.wavdrop.fileprovider"
private const val SHARE_DIR = "wrapped_share"
private const val SHARE_FILE = "wavdrop_wrapped.png"

internal fun shareWrappedSlide(activity: Activity, srcRect: Rect) {
    if (srcRect.isEmpty) return
    val bitmap = Bitmap.createBitmap(srcRect.width(), srcRect.height(), Bitmap.Config.ARGB_8888)
    val handler = Handler(Looper.getMainLooper())

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val request = PixelCopy.Request.Builder
            .ofWindow(activity.window)
            .setSourceRect(srcRect)
            .setDestinationBitmap(bitmap)
            .build()
        // API 34+: callback is Consumer<PixelCopy.Result>; run on main executor.
        PixelCopy.request(request, ContextCompat.getMainExecutor(activity)) { result ->
            if (result.status == PixelCopy.SUCCESS) {
                dispatchShare(activity, bitmap)
            } else {
                Toast.makeText(activity, "Couldn't capture slide", Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        // API 26–33: callback is OnPixelCopyFinishedListener; posted on handler.
        @Suppress("DEPRECATION")
        PixelCopy.request(activity.window, srcRect, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                dispatchShare(activity, bitmap)
            } else {
                Toast.makeText(activity, "Couldn't capture slide", Toast.LENGTH_SHORT).show()
            }
        }, handler)
    }
}

private fun dispatchShare(activity: Activity, bitmap: Bitmap) {
    val shareDir = File(activity.cacheDir, SHARE_DIR).also { it.mkdirs() }
    // Overwrite a single fixed file so cacheDir does not accumulate unbounded exports.
    val file = File(shareDir, SHARE_FILE)
    runCatching {
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }.onFailure {
        Toast.makeText(activity, "Couldn't save slide image", Toast.LENGTH_SHORT).show()
        return
    }

    val uri = FileProvider.getUriForFile(activity, FILEPROVIDER_AUTHORITY, file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    activity.startActivity(Intent.createChooser(shareIntent, null))
}
