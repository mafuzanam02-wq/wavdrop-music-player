package com.launchpoint.wavdrop.data.backup

import android.content.Context
import android.net.Uri
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkLimits
import com.launchpoint.wavdrop.data.backup.wdbk.WdbkReader
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

/** Physical packaging a decoded backup arrived in. */
enum class BackupContainerKind { LEGACY_JSON, WDBK }

/** Why a selected document was rejected before/while being decoded. Callers map these to their own copy. */
enum class BackupRejectReason { EMPTY, NOT_A_BACKUP, TOO_LARGE, UNREADABLE }

/** Outcome of [WavdropBackupDocumentReader]: the single entry point from "a selected file" to backup meaning. */
sealed interface BackupDocumentReadResult {
    /**
     * A Wavdrop Android backup (legacy V1/V2 JSON or WDBK), decoded to the SAME [WavdropBackup] model either way.
     * [result] carries the parser/reader verdict (which may itself be a failure with an error message).
     * [legacyText] is the original JSON text for legacy input (Recovery re-validates from it) and null for WDBK,
     * which Recovery re-reads from its source instead of holding the container in memory.
     */
    data class Wavdrop(
        val result: WavdropBackupImportResult,
        val container: BackupContainerKind,
        val legacyText: String? = null,
    ) : BackupDocumentReadResult

    /** A Wavdrop Desktop JSON backup — routed by the caller to its own parser; never a WDBK. */
    data class DesktopJson(val text: String) : BackupDocumentReadResult

    data class Rejected(val reason: BackupRejectReason) : BackupDocumentReadResult
}

/**
 * Decodes a selected backup document by CONTENT, never by file name, extension or MIME type:
 *
 *  - starts with the ZIP signature (`PK`) → WDBK container → [WdbkReader] (bounded, hash- and fingerprint-verified);
 *  - otherwise → legacy JSON: bounded text read (same 100 MiB cap as before), structural sniff, then the existing
 *    [WavdropBackupParser] (or the Desktop parser's sniff). Legacy JSON is NOT converted through WDBK.
 *
 * A renamed legacy JSON or WDBK file therefore parses, and a fake `.wdbk` that is not a valid container fails
 * safely. UI code never inspects ZIP entries; everything downstream receives the same [WavdropBackup].
 */
object WavdropBackupDocumentReader {

    /** Reads [uri] with the provider-declared-size fast reject and the bounded input cap. Never throws. */
    fun readUri(context: Context, uri: Uri): BackupDocumentReadResult {
        if (BackupInputReader.declaredSizeExceedsLimit(BackupInputReader.declaredSize(context, uri))) {
            return BackupDocumentReadResult.Rejected(BackupRejectReason.TOO_LARGE)
        }
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return BackupDocumentReadResult.Rejected(BackupRejectReason.UNREADABLE)
        return try {
            stream.use { read(it) }
        } catch (_: IOException) {
            BackupDocumentReadResult.Rejected(BackupRejectReason.UNREADABLE)
        }
    }

    fun read(
        input: InputStream,
        limits: WdbkLimits = WdbkLimits.DEFAULT,
        nowMs: Long = System.currentTimeMillis(),
    ): BackupDocumentReadResult {
        val pushback = PushbackInputStream(input, SNIFF_BYTES)
        val head = ByteArray(SNIFF_BYTES)
        var filled = 0
        while (filled < SNIFF_BYTES) {
            val n = pushback.read(head, filled, SNIFF_BYTES - filled)
            if (n < 0) break
            filled += n
        }
        if (filled == 0) return BackupDocumentReadResult.Rejected(BackupRejectReason.EMPTY)
        pushback.unread(head, 0, filled)

        return if (looksLikeZip(head, filled)) {
            BackupDocumentReadResult.Wavdrop(
                result = WdbkReader(limits).read(pushback, nowMs),
                container = BackupContainerKind.WDBK,
            )
        } else {
            readLegacyJson(pushback, limits.maxContainerBytes, nowMs)
        }
    }

    private fun readLegacyJson(input: InputStream, maxBytes: Long, nowMs: Long): BackupDocumentReadResult {
        val text = try {
            BackupInputReader.readBounded(input, maxBytes)
        } catch (_: BackupInputReader.InputTooLargeException) {
            return BackupDocumentReadResult.Rejected(BackupRejectReason.TOO_LARGE)
        }
        if (text.isBlank()) return BackupDocumentReadResult.Rejected(BackupRejectReason.EMPTY)

        // Cheap structural sniff first: rejects arbitrary JSON/binary content without running the full parser.
        if (!ImportFileValidation.isLikelyWavdropBackupContent(text)) {
            return BackupDocumentReadResult.Rejected(BackupRejectReason.NOT_A_BACKUP)
        }
        if (DesktopWavdropBackupParser.isDesktopBackupContent(text)) {
            return BackupDocumentReadResult.DesktopJson(text)
        }
        return BackupDocumentReadResult.Wavdrop(
            result = WavdropBackupParser.parse(text, nowMs),
            container = BackupContainerKind.LEGACY_JSON,
            legacyText = text,
        )
    }

    /** `PK` + a ZIP record marker (local header, end record, spanned/data-descriptor). Legacy JSON can never start so. */
    private fun looksLikeZip(head: ByteArray, count: Int): Boolean =
        count >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte())

    private const val SNIFF_BYTES = 4
}
