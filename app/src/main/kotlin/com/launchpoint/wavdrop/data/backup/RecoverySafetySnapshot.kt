package com.launchpoint.wavdrop.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Identity and verification evidence of the pre-Recovery safety snapshot (kept for future snapshot management / receipts). */
data class VerifiedSafetySnapshot(
    val path: String,
    val createdAtMs: Long,
    val sizeBytes: Long,
    val backupId: String?,
    /** v2 payload fingerprint recomputed from the re-parsed file. */
    val integrityFingerprint: String?,
    val verification: BackupIntegrityStatus,
    val songCount: Int,
    val statsCount: Int,
    val eventCount: Int,
    val playlistCount: Int,
)

enum class SafetySnapshotFailureStage { BUILD, WRITE, READ_BACK, VALIDATE, REPLACE }

sealed interface SafetySnapshotResult {
    data class Verified(val snapshot: VerifiedSafetySnapshot) : SafetySnapshotResult
    data class Failed(val stage: SafetySnapshotFailureStage, val detail: String) : SafetySnapshotResult
}

/** Creates the mandatory pre-Recovery safety snapshot. Anything other than [SafetySnapshotResult.Verified] blocks Recovery. */
interface SafetySnapshotProvider {
    suspend fun createVerifiedSnapshot(): SafetySnapshotResult
}

/** File operations behind the snapshot, injectable so tests can prove each failure mode blocks Recovery. */
interface SafetySnapshotIo {
    fun write(file: File, text: String)
    fun read(file: File): String?
    fun replace(from: File, to: File)

    object Default : SafetySnapshotIo {
        override fun write(file: File, text: String) {
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }

        override fun read(file: File): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null

        override fun replace(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/**
 * Builds the pre-Recovery safety snapshot in the app-private recovery-safety directory, with no dependency on a selected SAF
 * folder, external storage, network or cloud.
 *
 * The snapshot is an ordinary current-format (v2) Wavdrop backup produced by [WavdropBackupRepository.buildBackupJson] (the same
 * exporter as "Back up now"): no second format, no Room entity dumps, importable through the normal parser.
 *
 * Steps, inside [BackupExecutionSerializer] so it never races "Back up now", automatic backup or another snapshot:
 *  1. build the JSON;  2. write it to a temp file;  3. read the temp file back;  4. require the bytes read equal the bytes
 *  written and that [BackupSaveValidator] AND the v2 parser (integrity fingerprint, manifest, plausibility) accept it as a
 *  VERIFIED v2 backup;  5. atomically replace the single latest snapshot with the verified temp file;  6. re-read the final file.
 * The previous verified snapshot is replaced only after the new one has verified, so a failed attempt never destroys it.
 */
class RecoverySafetySnapshotCreator(
    private val directory: File,
    private val serializer: BackupExecutionSerializer,
    private val buildJson: suspend () -> String,
    private val io: SafetySnapshotIo = SafetySnapshotIo.Default,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SafetySnapshotProvider {

    val latestFile: File get() = File(directory, LATEST_NAME)
    private val tempFile: File get() = File(directory, TEMP_NAME)

    override suspend fun createVerifiedSnapshot(): SafetySnapshotResult =
        serializer.withSerializedBackup { create() }

    private suspend fun create(): SafetySnapshotResult {
        val json = try {
            buildJson()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SafetySnapshotResult.Failed(SafetySnapshotFailureStage.BUILD, e.message ?: "export failed")
        }

        try {
            io.write(tempFile, json)
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            return SafetySnapshotResult.Failed(SafetySnapshotFailureStage.WRITE, e.message ?: "write failed")
        }

        val verified = when (val v = verifyFile(tempFile, expected = json)) {
            is Verification.Ok -> v
            is Verification.Bad -> {
                runCatching { tempFile.delete() }
                return SafetySnapshotResult.Failed(v.stage, v.detail)
            }
        }

        try {
            io.replace(tempFile, latestFile)
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            return SafetySnapshotResult.Failed(SafetySnapshotFailureStage.REPLACE, e.message ?: "replace failed")
        }

        // Final check on the file Recovery will rely on: it must read back identical to what was verified.
        val finalText = try {
            io.read(latestFile)
        } catch (e: Exception) {
            return SafetySnapshotResult.Failed(SafetySnapshotFailureStage.READ_BACK, e.message ?: "read failed")
        }
        if (finalText != json) {
            return SafetySnapshotResult.Failed(SafetySnapshotFailureStage.READ_BACK, "final snapshot differs from verified content")
        }

        val backup = verified.backup
        return SafetySnapshotResult.Verified(
            VerifiedSafetySnapshot(
                path                 = latestFile.absolutePath,
                createdAtMs          = nowMs(),
                sizeBytes            = json.toByteArray(Charsets.UTF_8).size.toLong(),
                backupId             = backup.backupId,
                integrityFingerprint = WavdropBackupIntegrityV2.fingerprint(backup),
                verification         = BackupIntegrityStatus.VERIFIED,
                songCount            = backup.songs.size,
                statsCount           = backup.trackStats.size,
                eventCount           = backup.listenEvents.size,
                playlistCount        = backup.playlists.size,
            ),
        )
    }

    private sealed interface Verification {
        data class Ok(val backup: WavdropBackup) : Verification
        data class Bad(val stage: SafetySnapshotFailureStage, val detail: String) : Verification
    }

    private fun verifyFile(file: File, expected: String): Verification {
        val readBack = try {
            io.read(file)
        } catch (e: Exception) {
            return Verification.Bad(SafetySnapshotFailureStage.READ_BACK, e.message ?: "read failed")
        } ?: return Verification.Bad(SafetySnapshotFailureStage.READ_BACK, "snapshot file missing after write")

        if (readBack != expected) {
            return Verification.Bad(SafetySnapshotFailureStage.READ_BACK, "read-back differs from written content")
        }
        if (!BackupSaveValidator.isSavedBackupValid(readBack)) {
            return Verification.Bad(SafetySnapshotFailureStage.VALIDATE, BackupSaveValidator.VALIDATION_FAILED_MESSAGE)
        }
        val parsed = WavdropBackupParser.parse(readBack)
        val backup = parsed.backup
        if (backup == null ||
            parsed.integrityStatus != BackupIntegrityStatus.VERIFIED ||
            backup.sourceVersion != BackupFormatVersion.V2
        ) {
            return Verification.Bad(SafetySnapshotFailureStage.VALIDATE, parsed.error ?: "snapshot is not a verified v2 backup")
        }
        return Verification.Ok(backup)
    }

    companion object {
        const val DIRECTORY_NAME = "recovery-safety"
        const val LATEST_NAME = "pre-recovery-latest.json"
        const val TEMP_NAME = "pre-recovery-latest.json.tmp"
    }
}

/** Hilt-wired snapshot provider: app-private `filesDir/recovery-safety/`, the shared [BackupExecutionSerializer]. */
@Singleton
class RecoverySafetySnapshotService @Inject constructor(
    @ApplicationContext context: Context,
    backupRepository: WavdropBackupRepository,
    serializer: BackupExecutionSerializer,
) : SafetySnapshotProvider {
    private val creator = RecoverySafetySnapshotCreator(
        directory = File(context.filesDir, RecoverySafetySnapshotCreator.DIRECTORY_NAME),
        serializer = serializer,
        buildJson = { backupRepository.buildBackupJson() },
    )

    override suspend fun createVerifiedSnapshot(): SafetySnapshotResult = creator.createVerifiedSnapshot()
}
