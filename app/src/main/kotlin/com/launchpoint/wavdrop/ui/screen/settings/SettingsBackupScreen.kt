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
            // Backup folder
            item { SectionHeader("Backup") }
            item {
                ClickableSettingsRow(
                    title    = "Backup folder",
                    subtitle = if (autoBackupFolderUri == null) {
                        "Choose where Wavdrop saves backups."
                    } else {
                        folderDisplayName
                            ?.let { name -> "Backups are saved to \"$name\"." }
                            ?: "Backups are saved to your selected folder."
                    },
                    onClick  = { folderPickerLauncher.launch(null) },
                )
            }

            // Backup Now
            item {
                ClickableSettingsRow(
                    title    = "Backup Now",
                    subtitle = if (autoBackupFolderUri == null) {
                        "Choose a backup folder first so Wavdrop can update the same backup file without creating copies."
                    } else {
                        "Save a backup to your selected backup folder."
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
                ExportUiState.Exporting -> item { ExportStatusRow("Creating backup...") }
                ExportUiState.Success   -> item { ExportStatusRow("Backup saved to selected folder.") }
                is ExportUiState.Error  -> item { ExportStatusRow(state.message, isError = true) }
            }
            item { SectionDivider() }

            // Auto backup interval
            item { SectionHeader("Auto backup") }
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
                    subtitle = "Wavdrop creates a backup when you open the app, if your selected interval is due.",
                    selected = autoBackupInterval == AutoBackupInterval.DAILY,
                    onClick  = { viewModel.setAutoBackupInterval(AutoBackupInterval.DAILY) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Weekly",
                    subtitle = "Wavdrop creates a backup when you open the app, if your selected interval is due.",
                    selected = autoBackupInterval == AutoBackupInterval.WEEKLY,
                    onClick  = { viewModel.setAutoBackupInterval(AutoBackupInterval.WEEKLY) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Monthly",
                    subtitle = "Wavdrop creates a backup when you open the app, if your selected interval is due.",
                    selected = autoBackupInterval == AutoBackupInterval.MONTHLY,
                    onClick  = { viewModel.setAutoBackupInterval(AutoBackupInterval.MONTHLY) },
                )
            }
            item { SectionDivider() }

            // Backup file behavior
            item { SectionHeader("Backup file behavior") }
            item {
                ScanModeRow(
                    title    = "Dated backup",
                    subtitle = "Uses today's dated backup file and updates it if it already exists.",
                    selected = backupFileMode == BackupFileMode.DATED,
                    onClick  = { viewModel.setBackupFileMode(BackupFileMode.DATED) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Use fixed filename",
                    subtitle = "Uses wavdrop-backup.json for folder backups. Manual export may still create a copy depending on Android.",
                    selected = backupFileMode == BackupFileMode.REPLACE_PREVIOUS,
                    onClick  = { viewModel.setBackupFileMode(BackupFileMode.REPLACE_PREVIOUS) },
                )
            }
            item { SectionDivider() }

            // Manual export
            item { SectionHeader("Manual export") }
            item {
                ClickableSettingsRow(
                    title    = "Export Backup",
                    subtitle = "Save a backup file to a location you choose. Android may create a copy if a file with the same name already exists.",
                    enabled  = exportStateValue != ExportUiState.Exporting,
                    onClick  = { exportLauncher.launch(suggestedExportName) },
                )
            }
            when (val state = exportStateValue) {
                ExportUiState.Idle      -> Unit
                ExportUiState.Exporting -> item { ExportStatusRow("Exporting Wavdrop data...") }
                ExportUiState.Success   -> item { ExportStatusRow("Export complete.") }
                is ExportUiState.Error  -> item {
                    ExportStatusRow("Export failed: ${state.message}", isError = true)
                }
            }
            item { SectionDivider() }

            // Restore
            item { SectionHeader("Restore") }
            item {
                ClickableSettingsRow(
                    title    = "Restore Backup",
                    subtitle = "Preview and restore a Wavdrop backup to your library stats, playlists, and listening history.",
                    onClick  = { backupImportLauncher.launch(arrayOf("application/json")) },
                )
            }
            item { SectionDivider() }

            // BlackPlayer import
            item { SectionHeader("Import") }
            item {
                ClickableSettingsRow(
                    title    = "Import BlackPlayer Statistics",
                    subtitle = "Import play counts and skip counts from a BlackPlayer .bpstat file.",
                    onClick  = onImportClick,
                )
            }
        }
    }
}
