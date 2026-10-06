package com.launchpoint.wavdrop.ui.screen.backupimport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.launchpoint.wavdrop.data.backup.BackupInputReader
import com.launchpoint.wavdrop.data.backup.BackupRestoreMode
import com.launchpoint.wavdrop.data.backup.RecoveryEligibility
import com.launchpoint.wavdrop.data.backup.RecoveryImpact
import com.launchpoint.wavdrop.data.backup.RecoveryRestoreOrchestrator
import com.launchpoint.wavdrop.data.backup.RecoveryRestoreOutcome
import com.launchpoint.wavdrop.data.backup.RecoveryRestoreRepository
import com.launchpoint.wavdrop.data.backup.RestoreOperationLock
import com.launchpoint.wavdrop.data.backup.BackupIntegrityStatus
import com.launchpoint.wavdrop.data.backup.CleanInstallPreferenceRestoreResult
import com.launchpoint.wavdrop.data.backup.CleanInstallPreferenceRestorer
import com.launchpoint.wavdrop.data.backup.CleanInstallRecoveryPolicy
import com.launchpoint.wavdrop.data.backup.CleanInstallRecoveryOrchestrator
import com.launchpoint.wavdrop.data.backup.ImportFileValidation
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackup
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackupImportRepository
import com.launchpoint.wavdrop.data.backup.DesktopWavdropBackupParser
import com.launchpoint.wavdrop.data.backup.AutoBackupWorkScheduler
import com.launchpoint.wavdrop.data.backup.WavdropBackup
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportApplyResult
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportRepository
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import com.launchpoint.wavdrop.data.settings.AppSettingsRepository
import com.launchpoint.wavdrop.data.repository.SongRepository
import com.launchpoint.wavdrop.data.settings.LibraryScanSettingsRepository
import com.launchpoint.wavdrop.ui.permission.hasAudioPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecoveryBlockedKind {
    /** No verified safety snapshot could be created: Recovery never started. Offer Retry / Use Merge / Cancel. */
    SAFETY_SNAPSHOT_FAILED,

    /** The selected backup is not eligible for Recovery (invalid, unverified or legacy). */
    INPUT_BACKUP_INVALID,

    /** The database transaction failed and rolled back; nothing changed and the safety snapshot is kept. */
    RECOVERY_APPLY_FAILED,

    /** Another restore is running. */
    RESTORE_IN_PROGRESS,
}

/** Wording kept in one place so the screen and the structural tests agree. */
object RecoveryRestoreCopy {
    const val MODE_MERGE_TITLE = "Merge"
    const val MODE_MERGE_BODY = "Keep what's on this device and add compatible data from the backup."
    const val MODE_RECOVERY_TITLE = "Recovery"
    const val MODE_RECOVERY_BODY =
        "Replace WavDrop's recoverable state with this backup where it can be matched to music on this device."
    const val RECOVERY_WARNING =
        "Your current WavDrop history, favourites, playlists, lyrics and settings may be replaced by this backup. " +
            "A verified safety backup of your current data is created first. Audio files are not changed or restored. " +
            "Backup history that can't be matched to music on this device is preserved, not guessed."
    const val CONFIRM_TITLE = "Replace WavDrop data with this backup?"
    const val CONFIRM_BODY =
        "WavDrop will first create and verify a safety backup of your current data. If that fails, nothing is changed. " +
            "Then your recoverable WavDrop state (statistics, favourites, listening history, playlists, lyrics and supported " +
            "settings) is replaced by this backup. Audio files are not changed. Unmatched backup history is preserved."
    const val CONFIRM_BUTTON = "Create safety backup and replace"
    const val SNAPSHOT_FAILED_TITLE = "Recovery wasn't started"
    const val SNAPSHOT_FAILED_BODY =
        "Recovery wasn't started because WavDrop couldn't create a verified safety backup of your current data. " +
            "Nothing was changed."
    const val APPLY_FAILED_BODY =
        "Recovery couldn't be completed and your WavDrop data was left exactly as it was. The safety backup was kept."
    const val PARTIAL_TITLE = "Recovery partly complete"
    const val PARTIAL_BODY =
        "Your history, favourites, playlists and lyrics were recovered, but some settings could not be applied. " +
            "The safety backup of your previous data was kept."
}

data class BackupLoadingStage(
    val label: String,
    val step: Int = 0,
    val totalSteps: Int = 0,
)

sealed interface BackupImportUiState {
    data object Idle : BackupImportUiState
    data class Loading(val stage: BackupLoadingStage) : BackupImportUiState

    data class Preview(
        val format               : String,
        val version              : Int,
        val exportedAt           : String,
        val songCount            : Int,
        val statsCount           : Int,
        val baselineCount        : Int,
        val lyricsOverridesCount : Int,
        val hasPreferences       : Boolean,
        val playlistCount        : Int,
        val listenEventsCount    : Int,
        val isDesktopBackup      : Boolean = false,
        val matchedSongs         : Int = 0,
        val skippedUnmatched     : Int = 0,
        val skippedAmbiguous     : Int = 0,
        val statsWillIncrease    : Int = 0,
        val favoritesWillApply   : Int = 0,
        val warning              : String? = null,
        /**
         * Non-null only for UNVERIFIED_LEGACY backups (v1 without a checksum).
         * Shown as a separate calm notice in the preview UI; VERIFIED backups
         * leave this null and show no integrity notice.
         */
        val legacyWarning        : String? = null,
        /**
         * Compact merge semantics notice shown for Wavdrop Android backup imports.
         * Kept separate from legacyWarning and warning so users can distinguish them.
         * Null for desktop imports.
         */
        val mergeNotice          : String? = null,
        /**
         * True when the backup contains at least one item that would produce a DB write.
         * False for empty backups, preferences-only backups, or all-no-op data.
         * Drives the Apply button enabled state for Wavdrop Android backups.
         */
        val hasMergeableData     : Boolean = true,
        /**
         * Non-null when [hasMergeableData] is false. Explains why in plain language.
         * Shown as a separate notice; kept distinct from [mergeNotice] and [legacyWarning].
         */
        val noOpReason           : String? = null,
        /** Unmatched backup tracks that will be preserved in quarantine on apply. */
        val tracksToPreserve     : Int = 0,
        /** Unmatched backup tracks already preserved from a prior import of this backup. */
        val tracksAlreadyPreserved: Int = 0,
        /**
         * Warnings from unknown optional capabilities declared by the backup.
         * Non-blocking: import proceeds, but the user must see these before applying.
         */
        val capabilityWarnings: List<String> = emptyList(),
        /** True only when the destination has no local listening/history state. */
        val cleanInstallRecovery: Boolean = false,
        /** Recovery must discard old SAF URIs and wait for a new folder selection. */
        val requiresFolderReselection: Boolean = false,
        /**
         * The explicit restore mode. Every import starts at [BackupRestoreMode.MERGE]; Recovery needs a deliberate selection and
         * is never remembered, inferred from a clean install, or defaulted.
         */
        val selectedRestoreMode: BackupRestoreMode = BackupRestoreMode.MERGE,
        /** True when the mode choice is shown: Wavdrop Android backups that are not the clean-install flow. */
        val recoveryOffered: Boolean = false,
        /** True only for a VERIFIED v2 backup. Independent of [hasMergeableData]: Recovery can be meaningful when Merge is a no-op. */
        val recoveryEligible: Boolean = false,
        /** Why Recovery is unavailable (v1 / unverified); null when eligible or not offered. */
        val recoveryUnavailableReason: String? = null,
        /** Concise destructive-impact summary for the selected backup; null unless eligible. */
        val recoveryImpact: RecoveryImpact? = null,
    ) : BackupImportUiState

    data class Applying(val stage: BackupLoadingStage) : BackupImportUiState
    data object AwaitingMusicPermission : BackupImportUiState
    data object AwaitingFolderSelection : BackupImportUiState
    data object ScanningLibrary : BackupImportUiState

    /**
     * [partialRecoveryDetail] is non-null only when a Recovery committed its database changes but applying settings failed:
     * the screen must then say so instead of announcing a complete Recovery.
     */
    data class Applied(
        val result: WavdropBackupImportApplyResult,
        val partialRecoveryDetail: String? = null,
    ) : BackupImportUiState

    /** Recovery did not run or did not complete. Distinct from a generic import [Error]; nothing is hidden behind one message. */
    data class RecoveryBlocked(
        val kind: RecoveryBlockedKind,
        val message: String,
    ) : BackupImportUiState

    /**
     * Returned when the apply-time authoritative recheck determined that no persistent
     * state change was possible (all backup data already present, all-lower stats,
     * preferences-only, etc.). No data was written. UI shows a calm distinct result,
     * not "Merge complete".
     */
    data object NoChanges : BackupImportUiState

    data class Error(val message: String) : BackupImportUiState
}

@HiltViewModel
class BackupImportPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importRepository: WavdropBackupImportRepository,
    private val desktopImportRepository: DesktopWavdropBackupImportRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val preferenceRestorer: CleanInstallPreferenceRestorer,
    private val autoBackupWorkScheduler: AutoBackupWorkScheduler,
    private val songRepository: SongRepository,
    private val scanSettingsRepository: LibraryScanSettingsRepository,
    private val recoveryRepository: RecoveryRestoreRepository,
    private val recoveryOrchestrator: RecoveryRestoreOrchestrator,
    private val restoreLock: RestoreOperationLock,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupImportUiState>(BackupImportUiState.Idle)
    val uiState: StateFlow<BackupImportUiState> = _uiState.asStateFlow()

    private var parsedBackup: WavdropBackup? = null
    private var parsedDesktopBackup: DesktopWavdropBackup? = null
    private var recoveryPreferenceResult: CleanInstallPreferenceRestoreResult? = null

    /** Original text of the selected Android backup. Recovery re-validates from THIS, never from the parsed preview model. */
    private var parsedBackupText: String? = null

    // ── File loading ──────────────────────────────────────────────────────────

    fun processFile(uri: Uri) {
        parsedBackupText = null // each import starts from scratch, and therefore from the safe default mode (Merge)
        _uiState.value = BackupImportUiState.Loading(BackupLoadingStage("Reading backup file…"))
        viewModelScope.launch {
            _uiState.value = runCatching { readAndParse(uri) }.getOrElse { e ->
                BackupImportUiState.Error(e.message ?: "Failed to read backup file.")
            }
        }
    }

    private suspend fun readAndParse(uri: Uri): BackupImportUiState {
        fun setStage(label: String, step: Int = 0, total: Int = 0) {
            _uiState.value = BackupImportUiState.Loading(BackupLoadingStage(label, step, total))
        }

        // Gate by file name first: only .json files belong here. Files without a
        // usable display name fall through to the content sniff below.
        val displayName = ImportFileValidation.displayName(context, uri)
        val nameLooksRight = ImportFileValidation.isLikelyWavdropBackupFileName(displayName)
        if (displayName != null && !nameLooksRight) {
            return BackupImportUiState.Error(ImportFileValidation.WAVDROP_WRONG_FILE_MESSAGE)
        }

        val content = try {
            withContext(Dispatchers.IO) {
                BackupInputReader.readBackupText(context, uri)
            }
        } catch (_: BackupInputReader.InputTooLargeException) {
            return BackupImportUiState.Error(BackupInputReader.TOO_LARGE_MESSAGE)
        } ?: return BackupImportUiState.Error("Could not open the selected file.")

        // Structural sniff before full parsing — rejects arbitrary JSON/binary
        // content without running the parser over it.
        if (!ImportFileValidation.isLikelyWavdropBackupContent(content)) {
            return BackupImportUiState.Error(ImportFileValidation.WAVDROP_NOT_A_BACKUP_MESSAGE)
        }

        if (DesktopWavdropBackupParser.isDesktopBackupContent(content)) {
            setStage("Parsing backup data…", 2, 3)
            val result = DesktopWavdropBackupParser.parse(content)
            val backup = result.backup
                ?: return BackupImportUiState.Error(userFacingParseError(result.error))
            setStage("Matching tracks to your library…", 3, 3)
            val plan = withContext(Dispatchers.IO) {
                desktopImportRepository.previewImport(backup)
            }
            parsedBackup = null
            parsedDesktopBackup = backup
            return BackupImportUiState.Preview(
                format               = DesktopWavdropBackupParser.APP_NAME,
                version              = backup.schemaVersion,
                exportedAt           = backup.exportedAt.ifBlank { "Unknown" },
                songCount            = backup.songs.size,
                statsCount           = backup.songs.size,
                baselineCount        = 0,
                lyricsOverridesCount = 0,
                hasPreferences       = false,
                playlistCount        = backup.playlists.size,
                listenEventsCount    = backup.listenEvents.size,
                isDesktopBackup      = true,
                matchedSongs         = plan.matchedCount,
                skippedUnmatched     = plan.unmatchedCount,
                skippedAmbiguous     = plan.ambiguousCount,
                statsWillIncrease    = plan.statsWillIncreaseCount,
                favoritesWillApply   = plan.favoritesWillApplyCount,
                warning              = "Desktop stats, favorites, playlists, and listening history are matched by title, artist, and album. Backup import does not modify audio files.",
            )
        }

        setStage("Parsing backup data…")
        val result = withContext(Dispatchers.Default) { WavdropBackupParser.parse(content) }
        val backup = result.backup
            ?: return BackupImportUiState.Error(userFacingParseError(result.error))

        parsedBackup = backup
        parsedDesktopBackup = null
        parsedBackupText = content

        val legacyWarning = if (result.integrityStatus == BackupIntegrityStatus.UNVERIFIED_LEGACY) {
            "This is an older Wavdrop backup without integrity verification. " +
                "It can be restored, but its contents could not be verified."
        } else {
            null
        }

        val hasDesktopOverlay = backup.desktopOverlay != null
        val totalSteps = if (hasDesktopOverlay) 6 else 5
        setStage("Verifying backup integrity…", 3, totalSteps)

        var nextRepoStep = 4
        val mergePreview = withContext(Dispatchers.IO) {
            importRepository.previewMerge(backup) { label ->
                _uiState.value = BackupImportUiState.Loading(
                    BackupLoadingStage(label, nextRepoStep++, totalSteps)
                )
            }
        }
        val cleanInstallRecovery = withContext(Dispatchers.IO) {
            importRepository.isCleanInstallRecoveryCandidate()
        }
        val requiresFolderReselection =
            cleanInstallRecovery && CleanInstallRecoveryPolicy.requiresFolderReselection(backup.preferences)

        // Recovery is offered for Wavdrop Android backups outside the clean-install flow (which is itself the recovery path for an
        // empty install and has nothing to replace). Eligibility is VERIFIED v2 only and is independent of Merge's no-op result.
        val recoveryOffered = !cleanInstallRecovery
        val eligibility = RecoveryEligibility.evaluate(result)
        val recoveryEligible = recoveryOffered && eligibility is RecoveryEligibility.Result.Eligible
        val recoveryImpact = if (recoveryEligible) {
            withContext(Dispatchers.IO) { runCatching { recoveryRepository.previewImpact(backup) }.getOrNull() }
        } else {
            null
        }

        return BackupImportUiState.Preview(
            format               = WavdropBackupParser.SUPPORTED_FORMAT,
            version              = WavdropBackupParser.SUPPORTED_VERSION,
            exportedAt           = backup.exportedAt.ifBlank { "Unknown" },
            songCount            = backup.songs.size,
            statsCount           = backup.trackStats.size,
            baselineCount        = backup.importBaselines.size,
            lyricsOverridesCount = backup.lyricsOverrides.size,
            hasPreferences       = backup.preferences != null,
            playlistCount        = backup.playlists.size,
            listenEventsCount    = backup.listenEvents.size,
            warning              = null,
            legacyWarning        = legacyWarning,
            mergeNotice          = if (cleanInstallRecovery) {
                "Clean-install recovery will restore supported settings, scan this device, " +
                    "then merge history once against the populated library. Device folder permissions " +
                    "and playback mode are not transferred."
            } else {
                "This import keeps newer local listening history and adds compatible data from the backup. " +
                    "It does not replace your current history or settings."
            },
            hasMergeableData      = mergePreview.hasMergeableData ||
                (cleanInstallRecovery && backup.preferences != null),
            noOpReason            = mergePreview.noOpReason,
            tracksToPreserve      = mergePreview.newPendingTrackCount,
            tracksAlreadyPreserved = mergePreview.alreadyPendingTrackCount,
            capabilityWarnings    = result.warnings,
            cleanInstallRecovery  = cleanInstallRecovery,
            requiresFolderReselection = requiresFolderReselection,
            selectedRestoreMode   = BackupRestoreMode.MERGE,
            recoveryOffered       = recoveryOffered,
            recoveryEligible      = recoveryEligible,
            recoveryUnavailableReason =
                (eligibility as? RecoveryEligibility.Result.Blocked)?.message.takeIf { recoveryOffered },
            recoveryImpact        = recoveryImpact,
        )
    }

    // ── Restore mode (explicit, never sticky) ─────────────────────────────────

    /** Recovery can only be selected for an eligible backup; anything else keeps Merge. */
    fun selectRestoreMode(mode: BackupRestoreMode) {
        val preview = _uiState.value as? BackupImportUiState.Preview ?: return
        if (!preview.recoveryOffered) return
        if (mode == BackupRestoreMode.RECOVERY && !preview.recoveryEligible) return
        _uiState.value = preview.copy(selectedRestoreMode = mode)
    }

    private var recoveryPreview: BackupImportUiState.Preview? = null

    /** From the blocked screen: go back to the preview with Merge selected. Recovery is not retried implicitly. */
    fun useMergeInstead() {
        val preview = recoveryPreview ?: return
        _uiState.value = preview.copy(selectedRestoreMode = BackupRestoreMode.MERGE)
    }

    /** From the blocked screen: back to the preview (Recovery still selected) so the user can retry deliberately. */
    fun backToPreview() {
        _uiState.value = recoveryPreview ?: return
    }

    /**
     * Maps technical parser errors (malformed JSON, wrong format, missing schema
     * fields) to a calm user-facing message. Version errors stay specific so users
     * know a newer backup needs a newer app.
     */
    private fun userFacingParseError(error: String?): String = when {
        error == null -> ImportFileValidation.WAVDROP_NOT_A_BACKUP_MESSAGE
        // Surfaced verbatim: created by a newer Wavdrop that supports a higher version.
        error == WavdropBackupParser.NEWER_VERSION_ERROR -> error
        // Versioned but lower than supported — still surfaces version number.
        error.startsWith("Unsupported backup version") -> error
        // Already plain language; tells the user the file is damaged rather than wrong.
        error.startsWith("Backup integrity check failed") -> error
        else -> ImportFileValidation.WAVDROP_NOT_A_BACKUP_MESSAGE
    }

    // ── Post-restore folder selection ─────────────────────────────────────────

    /**
     * Saves the folder picked right after a restore. The persistent pending flag is
     * cleared only when the persistable URI permission was actually granted; a save
     * without permission would still leave auto-backup unable to write.
     */
    fun saveAutoBackupFolder(uri: String, permissionGranted: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setAutoBackupFolderUri(uri)
            appSettingsRepository.setLastAutoBackupAtMillis(0L)
            if (permissionGranted) {
                appSettingsRepository.setNeedsAutoBackupFolderSelectionAfterRestore(false)
            }
            autoBackupWorkScheduler.reconcile()
        }
    }

    fun continueAfterMusicPermission() {
        if (_uiState.value != BackupImportUiState.AwaitingMusicPermission) return
        if (!context.hasAudioPermission()) return
        continueRecovery()
    }

    fun selectRecoveryMusicFolder(uri: String, permissionGranted: Boolean) {
        if (_uiState.value != BackupImportUiState.AwaitingFolderSelection) return
        viewModelScope.launch {
            if (!permissionGranted) {
                _uiState.value = BackupImportUiState.Error(
                    "Wavdrop could not keep access to that folder. Choose the folder again.",
                )
                return@launch
            }
            scanSettingsRepository.addSelectedFolderUri(uri)
            appSettingsRepository.setNeedsFolderReselectionAfterRestore(false)
            scanThenApplyRecovery()
        }
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    fun applyImport() {
        val preview = _uiState.value as? BackupImportUiState.Preview ?: return

        if (preview.selectedRestoreMode == BackupRestoreMode.RECOVERY) {
            // Defence in depth: the mode selector already refuses this, but the destructive path re-checks.
            if (!preview.recoveryOffered || !preview.recoveryEligible || preview.isDesktopBackup) return
            applyRecovery(preview)
            return
        }

        _uiState.value = BackupImportUiState.Applying(BackupLoadingStage("Checking what changed…"))
        viewModelScope.launch {
            if (preview.cleanInstallRecovery && parsedDesktopBackup == null) {
                beginCleanInstallRecovery()
            } else {
                applyCurrentImport()
            }
        }
    }

    private fun applyRecovery(preview: BackupImportUiState.Preview) {
        val raw = parsedBackupText ?: return setError("No parsed backup to recover.")
        recoveryPreview = preview
        _uiState.value = BackupImportUiState.Applying(BackupLoadingStage("Creating a verified safety backup…"))
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { recoveryOrchestrator.restore(raw) }
            }.getOrElse {
                _uiState.value = BackupImportUiState.RecoveryBlocked(
                    RecoveryBlockedKind.RECOVERY_APPLY_FAILED,
                    it.message ?: RecoveryRestoreCopy.APPLY_FAILED_BODY,
                )
                return@launch
            }
            _uiState.value = when (outcome) {
                is RecoveryRestoreOutcome.Success -> BackupImportUiState.Applied(outcome.result)
                is RecoveryRestoreOutcome.PartialRecovery ->
                    BackupImportUiState.Applied(outcome.result, partialRecoveryDetail = outcome.detail)
                is RecoveryRestoreOutcome.SafetySnapshotFailed -> BackupImportUiState.RecoveryBlocked(
                    RecoveryBlockedKind.SAFETY_SNAPSHOT_FAILED, RecoveryRestoreCopy.SNAPSHOT_FAILED_BODY,
                )
                is RecoveryRestoreOutcome.InputBackupInvalid -> BackupImportUiState.RecoveryBlocked(
                    RecoveryBlockedKind.INPUT_BACKUP_INVALID, outcome.message,
                )
                is RecoveryRestoreOutcome.RecoveryApplyFailed -> BackupImportUiState.RecoveryBlocked(
                    RecoveryBlockedKind.RECOVERY_APPLY_FAILED, RecoveryRestoreCopy.APPLY_FAILED_BODY,
                )
                RecoveryRestoreOutcome.RestoreInProgress -> BackupImportUiState.RecoveryBlocked(
                    RecoveryBlockedKind.RESTORE_IN_PROGRESS, "Another restore is already running.",
                )
            }
        }
    }

    private suspend fun beginCleanInstallRecovery() {
        val backup = parsedBackup
            ?: return setError("No parsed backup to recover.")
        recoveryPreferenceResult = preferenceRestorer.restore(backup.preferences)
        autoBackupWorkScheduler.reconcile()
        continueRecovery()
    }

    private fun continueRecovery() {
        when (
            CleanInstallRecoveryOrchestrator.nextStep(
                hasMusicPermission = context.hasAudioPermission(),
                needsFolderReselection = recoveryPreferenceResult?.needsFolderReselection == true,
            )
        ) {
            CleanInstallRecoveryOrchestrator.NextStep.REQUEST_MUSIC_PERMISSION ->
                _uiState.value = BackupImportUiState.AwaitingMusicPermission
            CleanInstallRecoveryOrchestrator.NextStep.REQUEST_FOLDER_SELECTION ->
                _uiState.value = BackupImportUiState.AwaitingFolderSelection
            CleanInstallRecoveryOrchestrator.NextStep.SCAN_LIBRARY ->
                viewModelScope.launch { scanThenApplyRecovery() }
        }
    }

    private suspend fun scanThenApplyRecovery() {
        _uiState.value = BackupImportUiState.ScanningLibrary
        val backup = parsedBackup
            ?: return setError("No parsed backup to recover.")
        val result = runCatching {
            CleanInstallRecoveryOrchestrator.scanThenApply(
                scanLibrary = {
                    withContext(Dispatchers.IO) { songRepository.sync() }
                },
                applyImport = {
                    withContext(Dispatchers.IO) { importRepository.applyImport(backup) }
                },
            )
        }.getOrElse {
            setError(it.message ?: "Library scan or import failed. Please try again.")
            return
        }
        val prefs = recoveryPreferenceResult
        _uiState.value = BackupImportUiState.Applied(
            result.copy(
                preferencesRestored = prefs?.restored == true,
                preferencesSkipped = false,
                needsAutoBackupFolderSelection = prefs?.needsAutoBackupFolderSelection == true,
                launcherIconRestored = prefs?.launcherIconRestored == true,
                cleanInstallRecovery = true,
                libraryScanCompleted = true,
                needsMusicFolderReselection = false,
                notRestoredOnThisDevice =
                    CleanInstallRecoveryOrchestrator.notRestoredOnThisDevice,
            ),
        )
    }

    private suspend fun applyCurrentImport() {
        _uiState.value = runCatching {
            withContext(Dispatchers.IO) {
                // One restore at a time: a Merge/Desktop import never overlaps a Recovery (and vice versa).
                restoreLock.tryRun {
                    parsedDesktopBackup?.let { desktopImportRepository.applyImport(it) }
                        ?: parsedBackup?.let { backup ->
                            importRepository.applyImport(backup) { label ->
                                _uiState.value = BackupImportUiState.Applying(BackupLoadingStage(label))
                            }
                        }
                        ?: error("No parsed backup to import.")
                } ?: error("Another restore is already running. Try again when it has finished.")
            }
        }.fold(
            onSuccess = { result ->
                if (result.isNoOp) BackupImportUiState.NoChanges
                else BackupImportUiState.Applied(result)
            },
            onFailure = { e ->
                BackupImportUiState.Error(e.message ?: "Import failed. Please try again.")
            },
        )
    }

    private fun setError(message: String) {
        _uiState.value = BackupImportUiState.Error(message)
    }
}
