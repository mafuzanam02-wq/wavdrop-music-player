package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * P5-WR-EX-01: platform-independent core of the Wrapped image-share pipeline
 * (capture -> PNG file -> chooser). Android-specific steps (PixelCopy, FileProvider, chooser) are injected by
 * WrappedShareHelper.kt, which keeps this logic unit-testable.
 */
internal const val WRAPPED_SHARE_DIR = "wrapped_share"
internal const val WRAPPED_SHARE_PREFIX = "wavdrop_wrapped"

/**
 * PROVISIONAL, best-effort cache retention (24 h) pending product-owner approval. It is not a guarantee that an
 * external consumer can read an export for that long: the cache can also be cleared by the user or the system.
 * Within the window, exports are not deleted by this code, so a receiver that reads the URI later is less likely
 * to be cut off. Older exports, partial `.part` files and the pre-P5-WR-EX-01 fixed-name file are pruned at the
 * start of the next export. Age-only on purpose: a count cap could delete a file still in use.
 */
internal const val WRAPPED_SHARE_RETENTION_MS = 24L * 60 * 60 * 1000

/** Single in-flight guard for one Wrapped screen (held with `remember`, never global). */
internal class WrappedShareGate {
    private val active = AtomicBoolean(false)
    val isActive: Boolean get() = active.get()
    fun tryAcquire(): Boolean = active.compareAndSet(false, true)
    fun release() = active.set(false)
}

internal enum class WrappedShareOutcome {
    Shared,
    /** Another export on this screen is still running; this tap was ignored. */
    AlreadyRunning,
    CaptureFailed,
    EncodeFailed,
    LaunchFailed,
    /** The report/page changed or the screen/activity went away while the export was pending. */
    Stale,
}

/** True when the source rectangle is non-empty and fully inside the window; checked before any allocation. */
internal fun isCaptureRectValid(left: Int, top: Int, right: Int, bottom: Int, windowWidth: Int, windowHeight: Int): Boolean =
    windowWidth > 0 && windowHeight > 0 &&
        left >= 0 && top >= 0 && right > left && bottom > top &&
        right <= windowWidth && bottom <= windowHeight

/** Collision-resistant per-export name; the nonce (a UUID in production) keeps same-millisecond exports apart. */
internal fun wrappedShareFileName(nowMs: Long, nonce: String): String =
    "${WRAPPED_SHARE_PREFIX}_${nowMs}_$nonce.png"

/**
 * Deletes our own export files older than [retentionMs]. Only names starting with [WRAPPED_SHARE_PREFIX] are touched.
 * Returns the number of files deleted.
 */
internal fun pruneWrappedShareDir(dir: File, nowMs: Long, retentionMs: Long = WRAPPED_SHARE_RETENTION_MS): Int {
    var deleted = 0
    try {
        val files = dir.listFiles() ?: return 0
        for (f in files) {
            if (!f.isFile || !f.name.startsWith(WRAPPED_SHARE_PREFIX)) continue
            if (nowMs - f.lastModified() > retentionMs && f.delete()) deleted++
        }
    } catch (e: SecurityException) {
        // Cleanup is best-effort; a file-access failure here must not block the export.
    }
    return deleted
}

/**
 * Writes via [compress] into a temporary `.part` file and renames it into place only after a successful, non-empty
 * write, so a failed export never leaves a half-written PNG that a share URI could point at. Returns null (and
 * removes the partial) when [compress] reports failure, throws an I/O error, or the rename fails.
 */
internal fun writeWrappedSharePng(
    dir: File,
    nowMs: Long,
    nonce: String = UUID.randomUUID().toString(),
    compress: (OutputStream) -> Boolean,
): File? {
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
    val target = File(dir, wrappedShareFileName(nowMs, nonce))
    val part = File(dir, target.name + ".part")
    try {
        val ok = part.outputStream().use { out ->
            val written = compress(out)
            out.flush()
            written
        }
        if (!ok || part.length() == 0L || !part.renameTo(target)) return null
        return target
    } catch (e: IOException) {
        return null
    } catch (e: SecurityException) {
        return null
    } finally {
        if (part.exists()) part.delete()
    }
}

/**
 * The single narrow final boundary of the pipeline: runs one stage, rethrows cancellation, and turns any other
 * [Exception] (not Throwable, so Errors such as OOM are not masked) into a null result after reporting it through
 * [onUnexpected] so a programming error is logged rather than silently swallowed. Expected platform failures are
 * handled at their source (capture, file write, chooser launch) and never reach this.
 */
private inline fun <T> recoverable(
    stage: String,
    onUnexpected: (String, Exception) -> Unit,
    block: () -> T,
): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onUnexpected(stage, e)
    null
}

/**
 * The full share pipeline under a single in-flight [gate]. The gate is held for the whole asynchronous operation and
 * released on every path (success, failure, stale, cancellation).
 *
 * [isCurrent] must be false when the screen, activity, report or page no longer matches the request; it is checked
 * after capture (before spending work on encoding) and again before the chooser is launched, so a stale request never
 * launches a chooser. Unexpected exceptions in a stage become that stage's failure outcome (reported via
 * [onUnexpected]); cancellation is never caught: it propagates after the `finally` blocks run. A cancelled
 * in-flight capture's bitmap is deliberately not recycled (the platform may still be writing to it); an encoding
 * that was running when the coroutine was cancelled completes before the bitmap is recycled.
 */
internal suspend fun <B : Any> runWrappedShare(
    gate: WrappedShareGate,
    isCurrent: () -> Boolean,
    capture: suspend () -> B?,
    encode: suspend (B) -> File?,
    recycle: (B) -> Unit,
    launch: (File) -> Boolean,
    deleteFile: (File) -> Unit = { it.delete() },
    onUnexpected: (stage: String, error: Exception) -> Unit = { _, _ -> },
): WrappedShareOutcome {
    if (!gate.tryAcquire()) return WrappedShareOutcome.AlreadyRunning
    try {
        val bitmap = recoverable("capture", onUnexpected) { capture() }
            ?: return WrappedShareOutcome.CaptureFailed
        val file = try {
            if (!isCurrent()) return WrappedShareOutcome.Stale
            recoverable("encode", onUnexpected) { encode(bitmap) }
        } finally {
            recycle(bitmap)
        }
        if (file == null) return WrappedShareOutcome.EncodeFailed
        if (!isCurrent()) {
            deleteFile(file)
            return WrappedShareOutcome.Stale
        }
        if (recoverable("launch", onUnexpected) { launch(file) } != true) {
            deleteFile(file)
            return WrappedShareOutcome.LaunchFailed
        }
        return WrappedShareOutcome.Shared
    } finally {
        gate.release()
    }
}
