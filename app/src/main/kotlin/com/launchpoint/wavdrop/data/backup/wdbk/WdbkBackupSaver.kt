package com.launchpoint.wavdrop.data.backup.wdbk

import com.launchpoint.wavdrop.data.backup.BackupIntegrityStatus
import com.launchpoint.wavdrop.data.backup.BackupSaveValidator
import com.launchpoint.wavdrop.data.backup.WavdropBackupIntegrityV2
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A writable/readable backup destination. The Android implementations wrap a SAF Uri or a [DocumentFile];
 * keeping the save/verify logic behind this seam lets it be tested on the JVM, including every failure mode.
 */
interface BackupFileHandle {
    /** Opens the destination for writing in [mode] ("wt" truncating, "w" fallback); null when it cannot be opened. */
    fun openOutput(mode: String): OutputStream?

    /** Opens the destination for reading; null when it cannot be opened. */
    fun openInput(): InputStream?

    fun delete(): Boolean
}

/** A folder holding backups. Lookup is by exact display name over the directory LISTING (see AutoBackupRepository). */
interface BackupFolder {
    fun findChild(name: String): BackupFileHandle?
    fun createFile(mimeType: String, name: String): BackupFileHandle?
}

/**
 * Writes a WDBK container and proves it: success is reported only after the bytes on disk have been re-opened and
 * decoded by the SAME [WdbkReader] real imports use, with the physical (per-entry SHA-256) and semantic
 * (WavdropBackupIntegrityV2 fingerprint) checks passing AND matching what the writer recorded. `ZipOutputStream`
 * closing without error is never treated as success.
 */
class WdbkBackupSaver(
    private val writer: WdbkWriter = WdbkWriter(),
    private val reader: WdbkReader = WdbkReader(),
) {

    /** Manual export: write [backup] to [target], re-read it, verify it. Throws [IOException] on any failure. */
    suspend fun saveAndVerify(target: BackupFileHandle, snapshot: WdbkExportSnapshot): WdbkWriteReceipt {
        val receipt = writeStreaming(target, snapshot)
        verify(target, receipt)
        return receipt
    }

    /**
     * Folder backup (temp → verify → final → verify). The previous final file is not touched until the temp copy
     * is fully written AND verified. The exact verified temp bytes are then stream-copied into the final file
     * (never regenerated: a rebuild could mint a different backupId/timestamp or observe changed database state),
     * and the final file is verified again. The temp file is always removed. Throws [IOException] on any failure.
     *
     * KNOWN RISK (unchanged from the JSON flow): SAF has no portable atomic replace, so the final write is a
     * truncating overwrite; a crash during that single verified-copy step can still damage the final file.
     */
    suspend fun saveToFolderAndVerify(folder: BackupFolder, fileName: String, snapshot: WdbkExportSnapshot): WdbkWriteReceipt {
        val tempName = "$fileName.tmp"
        // A temp left by an earlier crashed run is removed before writing a fresh one.
        folder.findChild(tempName)?.let { stale -> runCatching { stale.delete() } }

        // Step 1: temp, with a provider-neutral MIME so no extension is appended (it must never look like a backup).
        val temp = folder.createFile(OCTET_STREAM, tempName)
            ?: throw IOException("Could not create a temporary backup file in the selected folder.")
        try {
            val receipt = writeStreaming(temp, snapshot)
            // Step 2: temp must verify BEFORE the previous backup is touched.
            verify(temp, receipt)

            // Step 3: final file (existing one reused so no " (1)" duplicate appears).
            val target = folder.findChild(fileName)
                ?: folder.createFile(OCTET_STREAM, fileName)
                ?: throw IOException("Could not create the backup file in the selected folder.")

            // Step 4: copy the verified bytes, then verify what is actually on disk.
            copyVerifiedBytes(temp, target)
            verify(target, receipt)
            return receipt
        } finally {
            runCatching { temp.delete() }
        }
    }

    private suspend fun writeStreaming(target: BackupFileHandle, snapshot: WdbkExportSnapshot): WdbkWriteReceipt {
        // "wt" truncates; the default "w" does not on every provider (a larger old file would leave trailing
        // garbage). "w" is only a fallback for providers that reject "wt".
        for (mode in WRITE_MODES) {
            val stream = target.openOutput(mode) ?: continue
            try {
                // A retry re-streams the SAME captured snapshot (the event source is re-opened up to the same boundary).
                return stream.use { writer.write(snapshot, it) }
            } catch (_: IOException) {
                continue
            }
        }
        throw IOException("Could not save the backup file. Try a different location.")
    }

    private fun copyVerifiedBytes(from: BackupFileHandle, to: BackupFileHandle) {
        for (mode in WRITE_MODES) {
            val out = to.openOutput(mode) ?: continue
            try {
                val input = from.openInput() ?: throw IOException("Could not read the verified temporary backup.")
                input.use { src -> out.use { dst -> src.copyTo(dst, COPY_BUFFER_BYTES) } }
                return
            } catch (_: IOException) {
                continue
            }
        }
        throw IOException("Could not write the backup file to the selected folder.")
    }

    /** Re-opens [target], decodes it with the production reader and requires it to equal what was written. */
    fun verify(target: BackupFileHandle, receipt: WdbkWriteReceipt) {
        val input = try {
            target.openInput()
        } catch (_: IOException) {
            null
        } ?: throw IOException(BackupSaveValidator.VALIDATION_FAILED_MESSAGE)

        val result = try {
            input.use { reader.read(it) }
        } catch (_: IOException) {
            throw IOException(BackupSaveValidator.VALIDATION_FAILED_MESSAGE)
        }
        val backup = result.backup
        if (backup == null ||
            result.integrityStatus != BackupIntegrityStatus.VERIFIED ||
            backup.backupId != receipt.backupId ||
            WavdropBackupIntegrityV2.fingerprint(backup) != receipt.fingerprint ||
            !receipt.manifest.counts.matchesContentOf(backup)
        ) {
            throw IOException(BackupSaveValidator.VALIDATION_FAILED_MESSAGE)
        }
    }

    private companion object {
        val WRITE_MODES = listOf("wt", "w")
        const val OCTET_STREAM = "application/octet-stream"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
