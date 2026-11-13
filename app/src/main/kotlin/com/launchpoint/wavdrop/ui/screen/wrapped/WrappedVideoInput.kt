package com.launchpoint.wavdrop.ui.screen.wrapped

import java.io.File
import java.io.RandomAccessFile

/**
 * P5-WR-VID-02B: validated, ordered input for the MP4 encoder. It is built ONLY from the typed, successful result of
 * `generateWrappedSlides` (its ordered entries), never by listing the export directory, and nothing is recalculated:
 * names, order, dimensions and durations come from the entries; the files are merely proven to match them.
 * Pure JVM: no Android classes, so every rule is unit-testable.
 */
internal const val WRAPPED_VIDEO_FRAME_RATE = 30

internal data class WrappedVideoSlide(
    val index: Int,
    val file: File,
    val fileName: String,
    val durationMs: Int,
)

internal class WrappedVideoInput(
    val directory: File,
    val slides: List<WrappedVideoSlide>,
    val widthPx: Int,
    val heightPx: Int,
) {
    val totalDurationMs: Long get() = slides.sumOf { it.durationMs.toLong() }
}

internal enum class WrappedVideoInputProblem {
    NoSlides, NotOrdered, UnsafeFileName, DuplicateFileName, OutsideExportDirectory, NotOwnedDirectory,
    MissingFile, EmptyFile, NotPng, Truncated, NotDecodable, DimensionMismatch, BadDuration, ManifestMissing,
    ManifestMismatch,
}

internal sealed interface WrappedVideoInputResult {
    class Valid(val input: WrappedVideoInput) : WrappedVideoInputResult
    class Invalid(val problem: WrappedVideoInputProblem, val detail: String) : WrappedVideoInputResult
}

internal class WrappedPngHeader(val width: Int, val height: Int)

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val PNG_IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
private const val PNG_MIN_BYTES = 8 + 25 + 12 // signature + IHDR chunk + IEND chunk

/**
 * Reads only the first 24 and last 12 bytes. Returns null for a non-PNG; throws [IllegalStateException] for a PNG
 * that is cut short (no IEND chunk), which is how a truncated write is recognised without decoding any pixels.
 */
internal fun readWrappedPngHeader(file: File): WrappedPngHeader? {
    RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < PNG_MIN_BYTES) return null
        val head = ByteArray(24)
        raf.readFully(head)
        if (!head.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
        if (String(head, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        fun int(at: Int) = ((head[at].toInt() and 0xFF) shl 24) or ((head[at + 1].toInt() and 0xFF) shl 16) or
            ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)
        val header = WrappedPngHeader(int(16), int(20))
        val tail = ByteArray(12)
        raf.seek(raf.length() - 12)
        raf.readFully(tail)
        check(tail.contentEquals(PNG_IEND)) { "PNG has no IEND chunk" }
        return header
    }
}

private fun isSimpleFileName(name: String): Boolean =
    name.isNotEmpty() && name == File(name).name && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && ':' !in name && '\u0000' !in name

/**
 * Validates everything the encoder relies on BEFORE any Media3 object is created. Files are inspected one at a time by
 * header only (no pixel decode), so memory stays flat regardless of slide count. [decodable] is the optional
 * platform-level proof that the image can actually be decoded (bounds decode on device).
 */
internal fun validateWrappedVideoInput(
    export: WrappedExportOutcome.Success,
    expectedWidthPx: Int = WRAPPED_EXPORT_WIDTH_PX,
    expectedHeightPx: Int = WRAPPED_EXPORT_HEIGHT_PX,
    readHeader: (File) -> WrappedPngHeader? = ::readWrappedPngHeader,
    decodable: (File) -> Boolean = { true },
): WrappedVideoInputResult {
    fun bad(p: WrappedVideoInputProblem, d: String) = WrappedVideoInputResult.Invalid(p, d)
    val entries = export.entries
    val dir = export.directory
    if (entries.isEmpty()) return bad(WrappedVideoInputProblem.NoSlides, "no slides")
    if (!dir.name.startsWith(WRAPPED_EXPORT_DIR_PREFIX) || !dir.isDirectory) {
        return bad(WrappedVideoInputProblem.NotOwnedDirectory, "not a slide export directory: ${dir.name}")
    }
    val dirCanonical = dir.canonicalFile
    val seen = HashSet<String>()
    val slides = ArrayList<WrappedVideoSlide>(entries.size)
    for ((position, e) in entries.withIndex()) {
        if (e.index != position) return bad(WrappedVideoInputProblem.NotOrdered, "entry $position has index ${e.index}")
        if (!isSimpleFileName(e.fileName) || !e.fileName.endsWith(".png")) {
            return bad(WrappedVideoInputProblem.UnsafeFileName, "unsafe file name at $position")
        }
        if (!seen.add(e.fileName.lowercase())) return bad(WrappedVideoInputProblem.DuplicateFileName, e.fileName)
        if (e.durationMs <= 0) return bad(WrappedVideoInputProblem.BadDuration, "${e.fileName}: ${e.durationMs} ms")
        if (e.widthPx != expectedWidthPx || e.heightPx != expectedHeightPx) {
            return bad(WrappedVideoInputProblem.DimensionMismatch, "${e.fileName} declares ${e.widthPx}x${e.heightPx}")
        }
        val file = File(dir, e.fileName)
        if (file.canonicalFile.parentFile != dirCanonical) {
            return bad(WrappedVideoInputProblem.OutsideExportDirectory, e.fileName)
        }
        if (!file.isFile) return bad(WrappedVideoInputProblem.MissingFile, e.fileName)
        if (file.length() == 0L) return bad(WrappedVideoInputProblem.EmptyFile, e.fileName)
        val header = try {
            readHeader(file)
        } catch (ex: IllegalStateException) {
            return bad(WrappedVideoInputProblem.Truncated, e.fileName)
        } catch (ex: java.io.IOException) {
            return bad(WrappedVideoInputProblem.MissingFile, e.fileName)
        } ?: return bad(WrappedVideoInputProblem.NotPng, e.fileName)
        if (header.width != e.widthPx || header.height != e.heightPx) {
            return bad(WrappedVideoInputProblem.DimensionMismatch, "${e.fileName} is ${header.width}x${header.height}")
        }
        if (!decodable(file)) return bad(WrappedVideoInputProblem.NotDecodable, e.fileName)
        slides += WrappedVideoSlide(e.index, file, e.fileName, e.durationMs)
    }
    // The typed result stays authoritative; the manifest is only a consistency cross-check.
    val manifest = File(dir, "manifest.json")
    if (!manifest.isFile) return bad(WrappedVideoInputProblem.ManifestMissing, "manifest.json")
    val text = manifest.readText()
    if (!text.contains("\"pageCount\":${entries.size},")) {
        return bad(WrappedVideoInputProblem.ManifestMismatch, "page count differs from the manifest")
    }
    var from = 0
    for (s in slides) {
        val at = text.indexOf("\"file\":\"${s.fileName}\"", from)
        if (at < 0) return bad(WrappedVideoInputProblem.ManifestMismatch, "${s.fileName} missing or out of order")
        from = at
    }
    return WrappedVideoInputResult.Valid(WrappedVideoInput(dir, slides, expectedWidthPx, expectedHeightPx))
}
