package com.launchpoint.wavdrop.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(
    onNavigateBack: () -> Unit,
    onImportClick: () -> Unit,
    onBackupImportClick: (Uri) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val exportStateValue    by viewModel.exportUiState.collectAsStateWithLifecycle()
    val folderBackupState   by viewModel.folderBackupUiState.collectAsStateWithLifecycle()
    val backupFileMode      by viewModel.backupFileMode.collectAsStateWithLifecycle()
    val autoBackupFolderUri by viewModel.autoBackupFolderUri.collectAsStateWithLifecycle()
    val autoBackupInterval  by viewModel.autoBackupInterval.collectAsStateWithLifecycle()

    val suggestedExportName by remember {
        derivedStateOf {
            when (backupFileMode) {
                BackupFileMode.DATED            -> "wavdrop-backup-${LocalDate.now()}.json"
                BackupFileMode.REPLACE_PREVIOUS -> "wavdrop-backup.json"
            }
        }
    }

    val folderDisplayName by remember {
        derivedStateOf {
            autoBackupFolderUri?.let { uriString ->
                runCatching {
                    Uri.parse(uriString).lastPathSegment?.substringAfterLast(':')
                }.getOrNull()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onBackupImportClick(uri)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setAutoBackupFolderUri(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Migration") },
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
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // ── Backup Folder ──────────────────────────────────────────────────
            item { SectionHeader("Backup Folder") }
            item {
                ClickableSettingsRow(
                    title    = "Backup folder",
                    subtitle = if (autoBackupFolderUri == null) {
                        "Choose where Wavdrop saves backups."
                    } else {
                        folderDisplayName
                            ?.let { name -> "Saving backups to \"$name\"." }
                            ?: "Backups are saved to your selected folder."
                    },
                    onClick  = { folderPickerLauncher.launch(null) },
                )
            }
            item { SectionDivider() }

            // ── Back Up Now ────────────────────────────────────────────────────
            item { SectionHeader("Back Up Now") }
            item {
                ClickableSettingsRow(
                    title    = "Back up now",
                    subtitle = if (autoBackupFolderUri == null) {
                        "Choose a backup folder first."
                    } else {
                        "Save a backup to your selected folder."
                    },
                    enabled  = folderBackupState != ExportUiState.Exporting,
                    onClick  = {
                        if (autoBackupFolderUri == null) {
                            folderPickerLauncher.launch(null)
                        } else {
                            viewModel.backupNowToFolder()
                        }
                    },
                )
            }
            when (val state = folderBackupState) {
                ExportUiState.Idle      -> Unit
                ExportUiState.Exporting -> item { ExportStatusRow("Creating backup…") }
                ExportUiState.Success   -> item { ExportStatusRow("Backup saved to selected folder.") }
                is ExportUiState.Error  -> item { ExportStatusRow(state.message, isError = true) }
            }
            item { SectionDivider() }

            // ── Automatic Backup ───────────────────────────────────────────────
            item { SectionHeader("Automatic Backup") }
            item {
                SettingsMessageRow(
                    message = "Wavdrop backs up automatically when you open the app, if the selected interval has passed.",
                )
            }
            item {
                ScanModeRow(
                    title    = "Off",
                    subtitle = "No automatic backups.",
                    selected = autoBackupInterval == AutoBackupInterval.OFF,
                    onClick  = { viewModel.setAutoBackupInterval(AutoBackupInterval.OFF) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Daily",
                    subtitle = "Back up once per day.",
                    selected = autoBackupInterval == AutoBackupInterval.DAILY,
                    onClick  = { viewModel.setAutoBackupInterval(AutoBackupInterval.DAILY) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Weekly",
                    subtitle = "Back up once per week.",
                    selected = autoBackupInterval == AutoBackupInterval.WEEKLY,
                    onClick  = { viewModel.setAutoBackupInterval(AutoBackupInterval.WEEKLY) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Monthly",
                    subtitle = "Back up once per month.",
                    selected = autoBackupInterval == AutoBackupInterval.MONTHLY,
                    onClick  = { viewModel.setAutoBackupInterval(AutoBackupInterval.MONTHLY) },
                )
            }
            item { SectionDivider() }

            // ── Backup File ────────────────────────────────────────────────────
            item { SectionHeader("Backup File") }
            item {
                ScanModeRow(
                    title    = "Dated file",
                    subtitle = "Each backup uses today's date in the filename, updating the same file if it already exists.",
                    selected = backupFileMode == BackupFileMode.DATED,
                    onClick  = { viewModel.setBackupFileMode(BackupFileMode.DATED) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Fixed filename",
                    subtitle = "Always saves as wavdrop-backup.json, replacing the previous file.",
                    selected = backupFileMode == BackupFileMode.REPLACE_PREVIOUS,
                    onClick  = { viewModel.setBackupFileMode(BackupFileMode.REPLACE_PREVIOUS) },
                )
            }
            item { SectionDivider() }

            // ── Manual Export ──────────────────────────────────────────────────
            item { SectionHeader("Manual Export") }
            item {
                ClickableSettingsRow(
                    title    = "Save backup file",
                    subtitle = "Choose where to save a backup file on your device.",
                    enabled  = exportStateValue != ExportUiState.Exporting,
                    onClick  = { exportLauncher.launch(suggestedExportName) },
                )
            }
            when (val state = exportStateValue) {
                ExportUiState.Idle      -> Unit
                ExportUiState.Exporting -> item { ExportStatusRow("Saving backup…") }
                ExportUiState.Success   -> item { ExportStatusRow("Backup saved.") }
                is ExportUiState.Error  -> item {
                    ExportStatusRow("Save failed: ${state.message}", isError = true)
                }
            }
            item { SectionDivider() }

            // ── Restore ────────────────────────────────────────────────────────
            item { SectionHeader("Restore") }
            item {
                ClickableSettingsRow(
                    title    = "Restore from backup",
                    subtitle = "Preview and restore a Wavdrop backup to your library stats, playlists, and listening history.",
                    onClick  = { backupImportLauncher.launch(arrayOf("application/json")) },
                )
            }
            item { SectionDivider() }

            // ── Import from BlackPlayer ────────────────────────────────────────
            item { SectionHeader("Import from BlackPlayer") }
            item {
                ClickableSettingsRow(
                    title    = "Import BlackPlayer data",
                    subtitle = "Import play counts and skip counts from a BlackPlayer .bpstat file.",
                    onClick  = onImportClick,
                )
            }
        }
    }
}
