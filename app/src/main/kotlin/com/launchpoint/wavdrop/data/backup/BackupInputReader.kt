package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Whole-file reader for untrusted backup input with a hard size cap (WD-02).
 *
 * Backup import, verification, and desktopOverlay parsing all build a full
 * in-memory JSON object graph from the file's text. Without a bound, an
 * implausibly large file can exhaust memory before a normal error can be shown.
 * This reader rejects oversized input *before* the whole file is materialised.
 *
 * Bytes vs characters: URI/`DocumentFile` metadata reports **bytes**, and the
 * cap is enforced against the raw byte stream — we accumulate bytes through a
 * bounded buffer and stop the moment the running total exceeds the limit, then
 * UTF-8-decode only the bounded byte array. This is the conservative option:
 * UTF-8 encodes every character in ≥ 1 byte, so a byte-bounded read can never
 * admit a character sequence larger than the byte cap. We never rely on
 * `String.length` measured after allocating the full input.
 *
 * This cap applies only to untrusted *input* reads. Export write-back
 * verification (the app re-reading a file it just wrote) is intentionally not
 * capped — that output is bounded by what the app itself produced.
 */
object BackupInputReader {

    /**
     * Maximum accepted backup input size. 100 MiB sits comfortably above current
     * and foreseeable Beta 9 backups while still bounding worst-case String /
     * object-graph memory expansion. Revisit if measured real backups approach it.
     */
    const val MAX_BACKUP_INPUT_BYTES: Long = 100L * 1024L * 1024L

    const val TOO_LARGE_MESSAGE: String =
        "Backup file is too large. The maximum supported size is 100 MiB."

    /**
     * Generic variant of [TOO_LARGE_MESSAGE] for non-backup untrusted imports
     * (e.g. the BlackPlayer `.bpstat` importer) that share the same byte cap but
     * should not surface a Wavdrop-backup-specific string.
     */
    const val IMPORT_TOO_LARGE_MESSAGE: String =
        "Import file is too large. The maximum supported size is 100 MiB."

    /** Thrown when declared or actual input size exceeds [MAX_BACKUP_INPUT_BYTES]. */
    class InputTooLargeException : Exception(TOO_LARGE_MESSAGE)

    /**
     * Reads the full text of [uri], rejecting input larger than the cap.
     *
     * Returns `null` if the stream could not be opened (callers map this to their
     * own "could not open" message, preserving existing behavior). Throws
     * [InputTooLargeException] if the declared size (when available) or the actual
     * byte stream exceeds [MAX_BACKUP_INPUT_BYTES] — the file is never fully
     * materialised in that case. The stream is always closed.
     */
    fun readBackupText(context: Context, uri: Uri): String? = readBoundedText(context, uri)

    /**
     * Generic bounded whole-file read shared by every untrusted-input caller
     * (Wavdrop backups and the `.bpstat` importer). Same semantics as
     * [readBackupText]; callers pick their own user-facing message
     * ([TOO_LARGE_MESSAGE] vs [IMPORT_TOO_LARGE_MESSAGE]) when catching
     * [InputTooLargeException].
     */
    fun readBoundedText(context: Context, uri: Uri): String? {
        // Fast path: reject on declared size before opening the stream at all.
        if (declaredSizeExceedsLimit(declaredSize(context, uri))) {
            throw InputTooLargeException()
        }
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { readBounded(it) }
    }

    /**
     * Declared byte size of [uri] via `OpenableColumns.SIZE`, or `null` when the
     * provider does not report it (unknown size — handled by the bounded read).
     */
    internal fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }
    }.getOrNull()

    /** True only when a size is known and strictly above the cap. Unknown → false. */
    internal fun declaredSizeExceedsLimit(
        size: Long?,
        maxBytes: Long = MAX_BACKUP_INPUT_BYTES,
    ): Boolean = size != null && size > maxBytes

    /**
     * Reads [input] into a UTF-8 string, throwing [InputTooLargeException] as soon
     * as the cumulative byte count exceeds [maxBytes]. Input exactly [maxBytes]
     * long is accepted; one byte over is rejected. At most [maxBytes] bytes are
     * ever accumulated — the over-limit chunk is detected before it is buffered,
     * so the full oversized input is never allocated.
     */
    internal fun readBounded(
        input: InputStream,
        maxBytes: Long = MAX_BACKUP_INPUT_BYTES,
    ): String {
        val buffer = ByteArray(64 * 1024)
        val accumulated = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw InputTooLargeException()
            accumulated.write(buffer, 0, read)
        }
        return String(accumulated.toByteArray(), Charsets.UTF_8)
    }
}
