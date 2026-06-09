package com.launchpoint.wavdrop.ui.screen.settings

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val exportState          = viewModel.exportUiState.collectAsStateWithLifecycle()
    val exportStateValue     by exportState
    val backupFileMode       by viewModel.backupFileMode.collectAsStateWithLifecycle()
    val suggestedExportName  by remember {
        derivedStateOf {
            when (backupFileMode) {
                BackupFileMode.DATED            -> "wavdrop-backup-${LocalDate.now()}.json"
                BackupFileMode.REPLACE_PREVIOUS -> "wavdrop-backup.json"
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
            item { SectionHeader("Wavdrop Backup") }
            item {
                ClickableSettingsRow(
                    title    = "Export Wavdrop Data",
                    subtitle = "Save your stats, playlists, listening history, and lyrics overrides to a local JSON file.",
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
            item {
                ClickableSettingsRow(
                    title    = "Import Wavdrop Data",
                    subtitle = "Preview and restore a Wavdrop backup to your library stats, playlists, and listening history.",
                    onClick  = { backupImportLauncher.launch(arrayOf("application/json")) },
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Backup file behavior") }
            item {
                ScanModeRow(
                    title    = "Create new dated backup",
                    subtitle = "Each export creates a separate backup file with the current date.",
                    selected = backupFileMode == BackupFileMode.DATED,
                    onClick  = { viewModel.setBackupFileMode(BackupFileMode.DATED) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Replace previous backup",
                    subtitle = "Each export uses the same backup filename so your previous Wavdrop backup can be replaced.",
                    selected = backupFileMode == BackupFileMode.REPLACE_PREVIOUS,
                    onClick  = { viewModel.setBackupFileMode(BackupFileMode.REPLACE_PREVIOUS) },
                )
            }
            item { SectionDivider() }

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
