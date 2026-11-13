package com.launchpoint.wavdrop.ui.screen.wrapped

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "WrappedVideoShare"

internal const val WRAPPED_VIDEO_MIME = "video/mp4"

/** What happened when the chooser was requested. [Opened] only means Android accepted the request, never that anything was sent. */
internal enum class WrappedVideoShareResult { Opened, FileUnavailable, NoActivityAvailable, Failed }

/**
 * ACTION_SEND for a `video/mp4` FileProvider `content://` URI. Read access is granted through both the URI ClipData and
 * FLAG_GRANT_READ_URI_PERMISSION; no `file://` URI and no write grant.
 */
internal fun buildWrappedVideoShareIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = WRAPPED_VIDEO_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/**
 * Only a regular, non-empty `.mp4` that lives directly in the dedicated video directory is shareable. The check uses
 * canonical paths so `..`, symlinks and sibling directories with a shared name prefix cannot pass; FileProvider's
 * `wrapped_video_export/` mapping is the second, independent line of defence.
 */
internal fun isWrappedVideoShareable(file: File, videoDir: File): Boolean = try {
    val parent = file.canonicalFile.parentFile
    parent != null && parent == videoDir.canonicalFile && file.isFile && file.length() > 0L &&
        file.name.startsWith(WRAPPED_VIDEO_FILE_PREFIX) && file.name.endsWith(".mp4")
} catch (e: java.io.IOException) {
    false
}

/** [startChooser] is injectable so tests can observe the exact chooser Intent or simulate a launch failure. */
internal fun launchWrappedVideoShare(
    activity: Activity,
    file: File,
    startChooser: (Intent) -> Unit = activity::startActivity,
): WrappedVideoShareResult {
    if (activity.isFinishing || activity.isDestroyed) return WrappedVideoShareResult.Failed
    if (!isWrappedVideoShareable(file, wrappedVideoDir(activity.cacheDir))) return WrappedVideoShareResult.FileUnavailable
    return try {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
        val chooser = Intent.createChooser(buildWrappedVideoShareIntent(uri), null)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startChooser(chooser)
        WrappedVideoShareResult.Opened
    } catch (e: ActivityNotFoundException) {
        WrappedVideoShareResult.NoActivityAvailable
    } catch (e: SecurityException) {
        Log.w(TAG, "Chooser refused", e)
        WrappedVideoShareResult.Failed
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "FileProvider cannot map the video", e)
        WrappedVideoShareResult.Failed
    }
}

/** User-facing text for a failed share request; null when the chooser opened. Never claims the video was shared. */
internal fun wrappedVideoShareMessage(result: WrappedVideoShareResult): String? = when (result) {
    WrappedVideoShareResult.Opened -> null
    WrappedVideoShareResult.FileUnavailable -> "The video is no longer available. Export it again."
    WrappedVideoShareResult.NoActivityAvailable -> "No app is available to share a video."
    WrappedVideoShareResult.Failed -> "Couldn't open the share sheet."
}
