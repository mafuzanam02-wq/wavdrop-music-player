package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * P5-WR-VID-02A2: sequential slide generation into an isolated per-export directory. Platform-independent: the
 * bitmap type and the PNG encoder are injected so ordering, ownership and cleanup are unit-testable without graphics.
 *
 * Contract:
 *  - one new directory `wavdrop_wrapped_export_<exportId>` under [baseDir]; an existing one is never reused or
 *    overwritten (the export fails instead);
 *  - pages are rendered and written strictly in order, each through a `.part` file renamed only after a complete,
 *    non-empty write, and each bitmap is recycled before the next page starts (at most one is resident);
 *  - on failure OR cancellation the whole directory this call created is deleted, nothing else is touched, and
 *    cancellation is rethrown after cleanup;
 *  - rendering runs on the caller's context (Compose capture must stay on its UI-capable dispatcher); sizing, PNG
 *    encoding and every filesystem operation run on [ioDispatcher] inside `withContext`, which only returns once the
 *    blocking native encode has finished, so a bitmap is never recycled while compression is still using it, even when
 *    the export is cancelled mid-encode;
 *  - `CancellationException` is never converted into a result: it is rethrown from every stage after cleanup;
 *  - on success the directory holds only the PNGs and `manifest.json`, which the future encoder can consume in order.
 */
internal const val WRAPPED_EXPORT_DIR_PREFIX = "wavdrop_wrapped_export_"

internal data class WrappedExportEntry(
    val index: Int,
    val pageId: WrappedPageId,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
    val durationMs: Int,
)

internal sealed interface WrappedExportOutcome {
    class Success(val directory: File, val entries: List<WrappedExportEntry>) : WrappedExportOutcome
    /** [pageIndex] is the page being produced when it failed, or -1 for a setup failure. */
    class Failed(val pageIndex: Int, val reason: Reason) : WrappedExportOutcome

    enum class Reason { SetupFailed, RenderFailed, EncodeFailed, Unexpected }
}

internal fun wrappedExportDirectory(baseDir: File, exportId: String): File =
    File(baseDir, WRAPPED_EXPORT_DIR_PREFIX + exportId)

internal fun wrappedExportManifestJson(plan: WrappedExportPlan, entries: List<WrappedExportEntry>): String = buildString {
    append("{\"exportId\":\"").append(plan.exportId).append("\",")
    append("\"reportKey\":\"").append(plan.reportKey).append("\",")
    append("\"scope\":\"").append(plan.scope.name).append("\",")
    append("\"pageCount\":").append(entries.size).append(",")
    append("\"totalDurationMs\":").append(entries.sumOf { it.durationMs }).append(",")
    append("\"slides\":[")
    entries.forEachIndexed { i, e ->
        if (i > 0) append(',')
        append("{\"index\":").append(e.index)
        append(",\"page\":\"").append(e.pageId.name).append("\"")
        append(",\"file\":\"").append(e.fileName).append("\"")
        append(",\"width\":").append(e.widthPx)
        append(",\"height\":").append(e.heightPx)
        append(",\"durationMs\":").append(e.durationMs).append('}')
    }
    append("]}")
}

internal suspend fun <B : Any> generateWrappedSlides(
    plan: WrappedExportPlan,
    baseDir: File,
    render: suspend (WrappedExportPage) -> B?,
    sizeOf: (B) -> Pair<Int, Int>,
    encode: (B, OutputStream) -> Boolean,
    recycle: (B) -> Unit,
    onUnexpected: (stage: String, error: Exception) -> Unit = { _, _ -> },
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): WrappedExportOutcome {
    val dir = wrappedExportDirectory(baseDir, plan.exportId)
    // Created by this call and nothing else: mkdir (not mkdirs) fails if it already exists, so no earlier export is
    // ever reused or overwritten. `created` is true only when THIS call's mkdir created the directory, which is the sole
    // ownership signal the cleanup below relies on. The step uses the documented nested form
    // `withContext(NonCancellable) { withContext(io) { .. } }` (NOT `NonCancellable + io`, whose dispatcher return can
    // still observe the caller's cancellation): the outer NonCancellable block keeps the result across the hop back to
    // the caller, so a cancellation arriving at that moment cannot hide a directory this call just created. The very
    // next statements (no suspension point) either report the setup failure or enter the try/finally that owns the
    // cleanup. The shared base directory is never deleted, and a directory that already existed is never touched.
    val created = withContext(NonCancellable) {
        withContext(ioDispatcher) { (baseDir.isDirectory || baseDir.mkdirs()) && dir.mkdir() }
    }
    if (!created) {
        // Nothing is owned. A caller that was cancelled meanwhile gets the cancellation, not a stale failure result.
        currentCoroutineContext().ensureActive()
        return WrappedExportOutcome.Failed(-1, WrappedExportOutcome.Reason.SetupFailed)
    }
    var finished = false
    try {
        val entries = ArrayList<WrappedExportEntry>(plan.pageCount)
        for (page in plan.pages) {
            currentCoroutineContext().ensureActive() // a cancelled export never starts another page (or its first)
            val bitmap = try {
                render(page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onUnexpected("render", e)
                return WrappedExportOutcome.Failed(page.index, WrappedExportOutcome.Reason.Unexpected)
            } ?: return WrappedExportOutcome.Failed(page.index, WrappedExportOutcome.Reason.RenderFailed)
            try {
                // Blocking sizing + encode + file write off the caller's thread. withContext does not complete until
                // the block returns, so the finally-recycle below cannot race an in-flight native compress.
                val size = withContext(ioDispatcher) {
                    val dims = sizeOf(bitmap)
                    if (writeAtomically(dir, page.fileName) { out -> encode(bitmap, out) }) dims else null
                } ?: return WrappedExportOutcome.Failed(page.index, WrappedExportOutcome.Reason.EncodeFailed)
                entries += WrappedExportEntry(page.index, page.id, page.fileName, size.first, size.second, page.durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onUnexpected("encode", e)
                return WrappedExportOutcome.Failed(page.index, WrappedExportOutcome.Reason.Unexpected)
            } finally {
                recycle(bitmap) // before the next page: only one slide bitmap is ever resident
            }
        }
        val manifest = wrappedExportManifestJson(plan, entries)
        val manifestWritten = withContext(ioDispatcher) {
            writeAtomically(dir, "manifest.json") { out -> out.write(manifest.toByteArray()); true }
        }
        if (!manifestWritten) {
            return WrappedExportOutcome.Failed(-1, WrappedExportOutcome.Reason.EncodeFailed)
        }
        finished = true
        return WrappedExportOutcome.Success(dir, entries)
    } finally {
        if (!finished) {
            // Failure or cancellation: remove only what this export created. NonCancellable so a cancelled export
            // still completes its cleanup.
            withContext(NonCancellable) { withContext(ioDispatcher) { dir.deleteRecursively() } }
        }
    }
}

private fun writeAtomically(dir: File, name: String, write: (OutputStream) -> Boolean): Boolean {
    val target = File(dir, name)
    val part = File(dir, "$name.part")
    try {
        val ok = part.outputStream().use { out ->
            val written = write(out)
            out.flush()
            written
        }
        return ok && part.length() > 0L && part.renameTo(target)
    } catch (e: IOException) {
        return false
    } finally {
        if (part.exists()) part.delete()
    }
}
