package com.launchpoint.wavdrop.ui.screen.backupimport

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportApplyResult
import com.launchpoint.wavdrop.data.backup.CleanInstallRecoveryUiText
import com.launchpoint.wavdrop.ui.permission.audioPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupImportPreviewScreen(
    selectedUri: Uri?,
    onNavigateBack: () -> Unit,
    viewModel: BackupImportPreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val backupFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val permissionGranted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            viewModel.saveAutoBackupFolder(uri.toString(), permissionGranted)
        }
    }
    val musicFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val permissionGranted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            viewModel.selectRecoveryMusicFolder(uri.toString(), permissionGranted)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.continueAfterMusicPermission()
    }

    LaunchedEffect(selectedUri) {
        if (selectedUri != null) viewModel.processFile(selectedUri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview Wavdrop Backup") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            BackupImportUiState.Idle ->
                IdleContent(
                    onNavigateBack = onNavigateBack,
                    modifier       = Modifier.padding(innerPadding),
                )

            is BackupImportUiState.Loading ->
                LoadingContent(
                    stage    = state.stage,
                    modifier = Modifier.padding(innerPadding),
                )

            is BackupImportUiState.Preview ->
                PreviewContent(
                    state            = state,
                    onApplyConfirmed = viewModel::applyImport,
                    modifier         = Modifier.padding(innerPadding),
                )

            is BackupImportUiState.Applying ->
                LoadingContent(
                    stage    = state.stage,
                    modifier = Modifier.padding(innerPadding),
                )

            BackupImportUiState.AwaitingMusicPermission ->
                RecoveryActionContent(
                    title = "Music access required",
                    message = "Allow music access so Wavdrop can scan this device before attaching history.",
                    actionLabel = "Allow music access",
                    onAction = { permissionLauncher.launch(audioPermission) },
                    modifier = Modifier.padding(innerPadding),
                )

            BackupImportUiState.AwaitingFolderSelection ->
                RecoveryActionContent(
                    title = CleanInstallRecoveryUiText.FOLDERS_NEED_RESELECTION,
                    message = "Old folder permissions cannot transfer after reinstall. Select a folder on this device to continue the same recovery.",
                    actionLabel = "Select music folder",
                    onAction = { musicFolderPickerLauncher.launch(null) },
                    modifier = Modifier.padding(innerPadding),
                )

            BackupImportUiState.ScanningLibrary ->
                LoadingContent(
                    stage    = BackupLoadingStage(CleanInstallRecoveryUiText.SCAN_IN_PROGRESS),
                    modifier = Modifier.padding(innerPadding),
                )

            is BackupImportUiState.Applied ->
                AppliedContent(
                    result              = state.result,
                    onNavigateBack      = onNavigateBack,
                    onChooseFolder      = { backupFolderPickerLauncher.launch(null) },
                    modifier            = Modifier.padding(innerPadding),
                )

            BackupImportUiState.NoChanges ->
                NoChangesContent(
                    onNavigateBack = onNavigateBack,
                    modifier       = Modifier.padding(innerPadding),
                )

            is BackupImportUiState.Error ->
                ErrorContent(
                    message        = state.message,
                    onNavigateBack = onNavigateBack,
                    modifier       = Modifier.padding(innerPadding),
                )
        }
    }
}

// ── Idle ─────────────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.Description,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("No backup selected", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "Go to Settings to choose a Wavdrop backup file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onNavigateBack) { Text("Back to Settings") }
        }
    }
}

// ── Loading (shared for Reading + Applying) ───────────────────────────────────

@Composable
private fun LoadingContent(stage: BackupLoadingStage, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text  = stage.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            if (stage.step > 0 && stage.totalSteps > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Step ${stage.step} of ${stage.totalSteps}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                )
            }
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Composable
private fun PreviewContent(
    state: BackupImportUiState.Preview,
    onApplyConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        ConfirmApplyDialog(
            isDesktopBackup = state.isDesktopBackup,
            cleanInstallRecovery = state.cleanInstallRecovery,
            onConfirm = { showDialog = false; onApplyConfirmed() },
            onDismiss = { showDialog = false },
        )
    }

    LazyColumn(
        modifier            = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item { SectionLabel("Backup Information", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        item {
            StatRow("Format",      state.format)
            StatRow("Version",     state.version.toString())
            StatRow("Export Date", state.exportedAt)
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

        item { SectionLabel("Contents", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        item {
            StatRow("Songs",            state.songCount.toString())
            StatRow("Statistics",       state.statsCount.toString())
            StatRow("Import history",   state.baselineCount.toString())
            StatRow("Lyrics overrides", state.lyricsOverridesCount.toString())
            StatRow("Preferences",      if (state.hasPreferences) "Included" else "Not included")
            StatRow("Playlists",        state.playlistCount.toString())
            StatRow("Listening history", state.listenEventsCount.toString())
        }
        if (state.isDesktopBackup) {
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Desktop Restore Preview", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            item {
                StatRow("Ready to restore", state.matchedSongs.toString())
                StatRow("Could not match", state.skippedUnmatched.toString())
                StatRow("Needs review", state.skippedAmbiguous.toString())
                StatRow("Stats to update", state.statsWillIncrease.toString())
                StatRow("Favorites to restore", state.favoritesWillApply.toString())
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
        if (state.legacyWarning != null) {
            item {
                LegacyBackupNotice(
                    text     = state.legacyWarning,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.mergeNotice != null) {
            item {
                MergeRestoreNotice(
                    text     = state.mergeNotice,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.tracksToPreserve > 0 || state.tracksAlreadyPreserved > 0) {
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Unmatched history", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            item {
                if (state.tracksToPreserve > 0) {
                    StatRow("Preserved for later matching", state.tracksToPreserve.toString())
                }
                if (state.tracksAlreadyPreserved > 0) {
                    StatRow("Already preserved", state.tracksAlreadyPreserved.toString())
                }
            }
        }
        if (state.capabilityWarnings.isNotEmpty()) {
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Partial restore", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
            state.capabilityWarnings.forEach { w ->
                item {
                    CapabilityWarningNotice(
                        text     = w,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (state.noOpReason != null) {
            item {
                NoOpNotice(
                    text     = state.noOpReason,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        item {
            if (state.warning != null) {
                PreviewNotice(
                    text = state.warning,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (state.mergeNotice == null && state.noOpReason == null) {
                PreviewNotice(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            val applyEnabled = if (state.isDesktopBackup) {
                state.matchedSongs > 0 || state.statsCount > 0 || state.lyricsOverridesCount > 0
                    || state.playlistCount > 0 || state.listenEventsCount > 0
                    || state.baselineCount > 0
            } else {
                state.hasMergeableData
            }
            Button(
                onClick  = { if (applyEnabled) showDialog = true },
                enabled  = applyEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(if (state.cleanInstallRecovery) "Start Recovery" else "Apply Import")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Confirmation dialog ───────────────────────────────────────────────────────

@Composable
private fun ConfirmApplyDialog(
    isDesktopBackup: Boolean,
    cleanInstallRecovery: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    isDesktopBackup -> "Restore desktop backup?"
                    cleanInstallRecovery -> "Recover this clean install?"
                    else -> "Merge backup?"
                },
            )
        },
        text  = {
            Text(
                text  = if (isDesktopBackup) {
                    "Desktop stats, favorites, playlists, and listening history will be restored by matching songs in your library. Play counts and listening time only increase, and backup import does not modify audio files."
                } else if (cleanInstallRecovery) {
                    "Supported settings will be restored first. Wavdrop will then request any required access, scan the library, and merge history once. Old folder permissions, Equalizer, playback mode, and queue position are not transferred."
                } else {
                    "Listening history and stats from this backup will be merged with your " +
                        "current library. Play counts and listening time only increase — " +
                        "existing local data is never replaced. Lyrics use the newer version. " +
                        "Settings are not changed. Backup import does not modify audio files."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Applied ───────────────────────────────────────────────────────────────────

@Composable
private fun AppliedContent(
    result: WavdropBackupImportApplyResult,
    onNavigateBack: () -> Unit,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFolderPrompt by remember { mutableStateOf(result.needsAutoBackupFolderSelection) }

    if (showFolderPrompt) {
        AlertDialog(
            onDismissRequest = { showFolderPrompt = false },
            title = { Text("Choose backup folder") },
            text  = {
                Text(
                    text  = "Automatic backup settings were restored. Choose a folder to continue automatic backups on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            },
            confirmButton = {
                Button(onClick = { showFolderPrompt = false; onChooseFolder() }) {
                    Text("Choose Folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderPrompt = false }) { Text("Not Now") }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.CheckCircle,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (result.cleanInstallRecovery) "Recovery complete" else "Merge complete",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = if (result.cleanInstallRecovery) {
                    "The library scan finished and compatible history was merged with this device."
                } else {
                    "Compatible backup data has been merged into your library."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color    = MaterialTheme.colorScheme.surfaceVariant,
                shape    = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    StatRow("Songs restored",    result.matchedTracks.toString())
                    StatRow("Could not match",   result.unmatchedTracks.toString())
                    if (result.ambiguousTracks > 0) {
                        StatRow("Needs review", result.ambiguousTracks.toString())
                    }
                    StatRow("Stats updated",     result.statsUpdated.toString())
                    if (result.statsRetainedLocal > 0) {
                        val n = result.statsRetainedLocal
                        StatRow(
                            label = "Newer local statistics retained",
                            value = "$n ${if (n == 1) "track" else "tracks"}",
                        )
                    }
                    StatRow("Lyrics merged",     result.lyricsRestored.toString())
                    StatRow("Favorites merged",  result.favoritesRestored.toString())
                    if (result.preferencesSkipped) {
                        StatRow("Settings", "Not applied")
                    }
                    if (result.preferencesRestored) {
                        StatRow(CleanInstallRecoveryUiText.SETTINGS_RESTORED, "Yes")
                    }
                    if (result.libraryScanCompleted) {
                        StatRow("Library scan", "Complete")
                    }
                    if (result.playlistsRestored > 0 || result.playlistSongsRestored > 0) {
                        StatRow("Playlists created", result.playlistsRestored.toString())
                        StatRow("Playlist songs added", result.playlistSongsRestored.toString())
                    }
                    val unmatchedPlaylists = result.playlistRestoreSummaries.filter { it.skippedUnmatched > 0 }
                    if (unmatchedPlaylists.isNotEmpty()) {
                        unmatchedPlaylists.forEach { summary ->
                            val count = summary.skippedUnmatched
                            StatRow(summary.playlistName, "$count song${if (count == 1) "" else "s"} not found")
                        }
                    }
                    if (result.eventsRestored > 0 || result.eventsSkipped > 0) {
                        StatRow("Listening history restored", result.eventsRestored.toString())
                        if (result.currentMonthEventsRestored > 0) {
                            StatRow("This month's listens", result.currentMonthEventsRestored.toString())
                        }
                        if (result.eventsSkippedDuplicate > 0) {
                            StatRow("Already restored", result.eventsSkippedDuplicate.toString())
                        }
                        if (result.eventsSkippedUnmatched > 0) {
                            StatRow("History could not match", result.eventsSkippedUnmatched.toString())
                        }
                    }
                    if (result.pendingTracksPreserved > 0) {
                        StatRow(
                            CleanInstallRecoveryUiText.HISTORY_PRESERVED,
                            result.pendingTracksPreserved.toString(),
                        )
                    }
                    if (result.pendingEventsPreserved > 0) {
                        StatRow("History events preserved", result.pendingEventsPreserved.toString())
                    }
                    if (result.pendingPlaylistEntriesPreserved > 0) {
                        StatRow("Playlist entries preserved", result.pendingPlaylistEntriesPreserved.toString())
                    }
                    result.warnings.forEach { warning ->
                        StatRow("Note", warning)
                    }
                    result.notRestoredOnThisDevice.forEach { item ->
                        StatRow(item, CleanInstallRecoveryUiText.NOT_RESTORED)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick  = onNavigateBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun RecoveryActionContent(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

// ── No changes ────────────────────────────────────────────────────────────────

@Composable
private fun NoChangesContent(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.Info,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.secondary,
                modifier           = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Nothing new to merge", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "All compatible backup data is already present or older than your current library data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNavigateBack) { Text("Done") }
        }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.Warning,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.error,
                modifier           = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Could not complete import", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNavigateBack) { Text("Back to Settings") }
        }
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

@Composable
private fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier              = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Text(
            text       = value,
            style      = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Non-blocking merge-semantics notice shown for all Wavdrop Android backup imports.
 * Kept visually distinct from [LegacyBackupNotice] and [PreviewNotice].
 */
@Composable
private fun MergeRestoreNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.secondaryContainer,
        shape    = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector        = Icons.Default.Info,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text       = "Merge restore",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/**
 * Calm non-blocking notice shown only for UNVERIFIED_LEGACY backups.
 * Kept visually distinct from [PreviewNotice] so users can tell them apart.
 */
@Composable
private fun LegacyBackupNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.tertiaryContainer,
        shape    = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector        = Icons.Default.Info,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text       = "Unverified backup",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/**
 * Shown when a Wavdrop Android backup has no mergeable data (all-no-op, preferences-only, etc.).
 * Explains the reason so users are not confused by a disabled Apply button.
 */
@Composable
private fun NoOpNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.tertiaryContainer,
        shape    = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector        = Icons.Default.Info,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text       = "Nothing to merge",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/**
 * Shown for each unknown optional capability declared by a v2 backup.
 * Non-blocking — the import proceeds, but the user sees the partial-restore scope
 * before committing to apply. Uses a neutral tertiary container rather than error
 * styling so users understand the file is valid but some data will be skipped.
 */
@Composable
private fun CapabilityWarningNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.tertiaryContainer,
        shape    = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector        = Icons.Default.Info,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text       = "Partial restore",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun PreviewNotice(
    text: String = "Backup import does not modify audio files.",
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.secondaryContainer,
        shape    = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector        = Icons.Default.Info,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text       = "Preview only",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}
