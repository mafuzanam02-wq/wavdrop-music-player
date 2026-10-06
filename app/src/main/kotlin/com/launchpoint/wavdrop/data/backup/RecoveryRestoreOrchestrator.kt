package com.launchpoint.wavdrop.data.backup

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Detailed Recovery counts, kept apart from the Merge counters in [WavdropBackupImportApplyResult]. */
data class RecoveryRestoreSummary(
    val matchedTracks: Int = 0,
    val unmatchedTracksPreserved: Int = 0,
    val statsRestored: Int = 0,
    val favoritesRestored: Int = 0,
    /** Local favourites cleared because the backup (authoritative) does not mark them. */
    val favoritesCleared: Int = 0,
    val eventsRestored: Int = 0,
    /** Local exported-source events removed (replaced by the backup's event set). */
    val localEventsReplaced: Int = 0,
    val playlistsRestored: Int = 0,
    val localPlaylistsRemoved: Int = 0,
    val playlistEntriesRestored: Int = 0,
    val playlistEntriesPreserved: Int = 0,
    val lyricsRestored: Int = 0,
    val lyricsPreservedUnmatched: Int = 0,
    val baselinesRestored: Int = 0,
    val desktopOverlayStored: Boolean = false,
    val preferencesRestored: Boolean = false,
    /** The verified pre-restore snapshot that protected this operation (kept after success and failure). */
    val safetySnapshot: VerifiedSafetySnapshot? = null,
)

/**
 * Who may be Recovery-restored. Authoritative replacement carries a higher loss risk than Merge, so only a VERIFIED v2 backup
 * qualifies: a v1 file (even one with a v1 checksum) stays Merge-only, and an invalid/unparseable file never qualifies.
 */
object RecoveryEligibility {
    enum class Reason { INVALID, NOT_VERIFIED, LEGACY_V1 }

    sealed interface Result {
        data class Eligible(val backup: WavdropBackup) : Result
        data class Blocked(val reason: Reason, val message: String) : Result
    }

    const val LEGACY_MESSAGE =
        "Recovery needs a verified current-format backup. This older backup can still be merged."
    const val INVALID_MESSAGE = "This backup could not be verified, so it can't be used for Recovery."

    fun evaluate(parsed: WavdropBackupImportResult): Result {
        val backup = parsed.backup
        if (backup == null || parsed.integrityStatus == BackupIntegrityStatus.INVALID) {
            return Result.Blocked(Reason.INVALID, parsed.error ?: INVALID_MESSAGE)
        }
        if (backup.sourceVersion != BackupFormatVersion.V2) return Result.Blocked(Reason.LEGACY_V1, LEGACY_MESSAGE)
        if (parsed.integrityStatus != BackupIntegrityStatus.VERIFIED) {
            return Result.Blocked(Reason.NOT_VERIFIED, INVALID_MESSAGE)
        }
        return Result.Eligible(backup)
    }
}

/** The restore-operation serializer: at most one restore (Merge, Desktop import or Recovery) runs at a time. */
@Singleton
class RestoreOperationLock @Inject constructor() {
    private val mutex = Mutex()

    /** Runs [block] if no restore is running, otherwise returns null immediately (never queues a second restore). */
    suspend fun <T> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

sealed interface RecoveryRestoreOutcome {
    /** Database and settings both applied. */
    data class Success(
        val result: WavdropBackupImportApplyResult,
        val snapshot: VerifiedSafetySnapshot,
    ) : RecoveryRestoreOutcome

    /** The selected backup is not eligible. Nothing was created, nothing changed. */
    data class InputBackupInvalid(
        val reason: RecoveryEligibility.Reason,
        val message: String,
    ) : RecoveryRestoreOutcome

    /** No verified snapshot could be made, so Recovery never started. Nothing destructive ran; Merge is still available. */
    data class SafetySnapshotFailed(
        val stage: SafetySnapshotFailureStage,
        val detail: String,
    ) : RecoveryRestoreOutcome

    /** The database transaction failed and rolled back: the database is exactly as before. The snapshot is kept. */
    data class RecoveryApplyFailed(
        val snapshot: VerifiedSafetySnapshot,
        val detail: String,
    ) : RecoveryRestoreOutcome

    /**
     * The database was recovered but applying settings failed. Room and DataStore are not one transaction, so this is NOT
     * reported as success. No compensating rollback is attempted; the verified snapshot is retained.
     */
    data class PartialRecovery(
        val result: WavdropBackupImportApplyResult,
        val snapshot: VerifiedSafetySnapshot,
        val detail: String,
    ) : RecoveryRestoreOutcome

    /** Another restore is already running; nothing was started. */
    data object RestoreInProgress : RecoveryRestoreOutcome
}

/**
 * The single authority for Recovery Restore. The UI asks; this class owns correctness and sequencing:
 *
 *  A. re-parse and re-validate the selected backup text (VERIFIED v2 only);
 *  B. create the verified current-state safety snapshot (through [BackupExecutionSerializer], app-private storage);
 *  C/D. require the snapshot to be VERIFIED, else STOP before anything destructive;
 *  E. the database applier builds the Recovery plan (all matching decided before any clear);
 *  F. one Room transaction applies it;
 *  G. supported preferences are applied with explicit failure handling;
 *  H. a detailed outcome is returned.
 *
 * LOCK ORDER (always outermost first, never reversed, never re-entered):
 *   1. [RestoreOperationLock]        — restore ownership, non-queuing (`tryLock`), held for the whole operation;
 *   2. [BackupExecutionSerializer]   — taken by the snapshot creator for the snapshot, RELEASED, then taken again here for
 *                                      steps E–G so "Back up now" / WorkManager auto-backup can never export a half-applied
 *                                      Recovery (database committed, settings not yet applied).
 * The two serializer holds are separate (the Mutex is not re-entrant), so there is no nested acquisition and no deadlock. A
 * backup that runs between the two holds sees the untouched pre-Recovery state.
 */
@Singleton
class RecoveryRestoreOrchestrator @Inject constructor(
    private val snapshots: SafetySnapshotProvider,
    private val database: RecoveryDatabaseApplier,
    private val preferences: RecoveryPreferenceApplier,
    private val restoreLock: RestoreOperationLock,
    private val backupExecutionSerializer: BackupExecutionSerializer,
) {
    suspend fun restore(rawBackupJson: String): RecoveryRestoreOutcome =
        restoreLock.tryRun { restoreOwned(rawBackupJson) } ?: RecoveryRestoreOutcome.RestoreInProgress

    private suspend fun restoreOwned(rawBackupJson: String): RecoveryRestoreOutcome {
        // A. Validate the selected backup again, from its original text, independent of anything the preview showed.
        val backup = when (val e = RecoveryEligibility.evaluate(WavdropBackupParser.parse(rawBackupJson))) {
            is RecoveryEligibility.Result.Eligible -> e.backup
            is RecoveryEligibility.Result.Blocked -> return RecoveryRestoreOutcome.InputBackupInvalid(e.reason, e.message)
        }

        // B–D. No verified snapshot → STOP. Nothing destructive has run.
        val snapshot = when (val s = snapshots.createVerifiedSnapshot()) {
            is SafetySnapshotResult.Verified -> s.snapshot
            is SafetySnapshotResult.Failed -> return RecoveryRestoreOutcome.SafetySnapshotFailed(s.stage, s.detail)
        }
        if (snapshot.verification != BackupIntegrityStatus.VERIFIED) {
            return RecoveryRestoreOutcome.SafetySnapshotFailed(SafetySnapshotFailureStage.VALIDATE, "snapshot not verified")
        }

        // E–G under the backup serializer so no backup can observe a half-applied Recovery.
        return backupExecutionSerializer.withSerializedBackup {
            val dbResult = try {
                database.applyRecovery(backup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withSerializedBackup RecoveryRestoreOutcome.RecoveryApplyFailed(
                    snapshot, e.message ?: "database recovery failed",
                )
            }

            val prefResult = preferences.applyRecoveryPreferences(backup.preferences)
            val summary = (dbResult.recovery ?: RecoveryRestoreSummary()).copy(
                preferencesRestored = prefResult is RecoveryPreferenceResult.Applied,
                safetySnapshot = snapshot,
            )
            val finalResult = dbResult.copy(
                recovery = summary,
                preferencesRestored = prefResult is RecoveryPreferenceResult.Applied,
                preferencesSkipped = false,
                needsAutoBackupFolderSelection =
                    (prefResult as? RecoveryPreferenceResult.Applied)?.needsAutoBackupFolderSelection == true,
                launcherIconRestored = (prefResult as? RecoveryPreferenceResult.Applied)?.launcherIconRestored == true,
                notRestoredOnThisDevice = CleanInstallRecoveryOrchestrator.notRestoredOnThisDevice,
            )
            when (prefResult) {
                is RecoveryPreferenceResult.Failed -> RecoveryRestoreOutcome.PartialRecovery(
                    finalResult, snapshot, prefResult.detail,
                )
                else -> RecoveryRestoreOutcome.Success(finalResult, snapshot)
            }
        }
    }
}
