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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val suggestedExportName  = remember { "wavdrop-backup-${LocalDate.now()}.json" }
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
                    subtitle = "Create a local JSON backup of your library metadata and stats.",
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
                    subtitle = "Preview a Wavdrop JSON backup before restore support is added.",
                    onClick  = { backupImportLauncher.launch(arrayOf("application/json")) },
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
