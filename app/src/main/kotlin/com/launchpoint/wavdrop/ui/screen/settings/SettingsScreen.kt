package com.launchpoint.wavdrop.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onHomeClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onNowPlayingClick: () -> Unit = {},
    onImportClick: () -> Unit,
    onBackupImportClick: (Uri) -> Unit,
    onStatisticsClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMonthlyReportsClick: () -> Unit,
    onWrappedClick: () -> Unit,
    onHomeCustomizationClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val exportState by viewModel.exportUiState.collectAsStateWithLifecycle()
    val scanSettings by viewModel.libraryScanSettings.collectAsStateWithLifecycle()
    val scanState by viewModel.libraryScanUiState.collectAsStateWithLifecycle()
    val startupDestination by viewModel.startupDestination.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val suggestedExportName = remember { "wavdrop-backup-${LocalDate.now()}.json" }
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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addSelectedFolderUri(uri.toString())
        }
    }
    var minimumDurationSeconds by remember(scanSettings.minimumTrackDurationSeconds) {
        mutableFloatStateOf(scanSettings.minimumTrackDurationSeconds.toFloat())
    }
    var showStartupDestinationDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        bottomBar = {
            PrimaryNavigationBar(
                selected = PrimaryDestination.SETTINGS,
                onHomeClick = onHomeClick,
                onLibraryClick = onLibraryClick,
                onSettingsClick = {},
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            item { SectionHeader("Library") }
            item {
                ClickableSettingsRow(
                    title    = "Import BlackPlayer Statistics",
                    subtitle = "Import play counts and skip counts from a BlackPlayer .bpstat file.",
                    onClick  = onImportClick,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Library Scan") }
            item {
                ScanModeRow(
                    title = "Scan whole device",
                    subtitle = "Find audio from the device media library.",
                    selected = scanSettings.scanMode == LibraryScanMode.WHOLE_DEVICE,
                    onClick = { viewModel.setScanMode(LibraryScanMode.WHOLE_DEVICE) },
                )
            }
            item {
                ScanModeRow(
                    title = "Selected folders only",
                    subtitle = "Only include audio from the folders listed below when Wavdrop can match them.",
                    selected = scanSettings.scanMode == LibraryScanMode.SELECTED_FOLDERS,
                    onClick = { viewModel.setScanMode(LibraryScanMode.SELECTED_FOLDERS) },
                )
            }
            if (
                scanSettings.scanMode == LibraryScanMode.SELECTED_FOLDERS &&
                scanSettings.selectedFolderUris.isEmpty()
            ) {
                item {
                    SettingsMessageRow(
                        message = "No folders selected. Wavdrop will not find music until you add a folder.",
                        isError = true,
                    )
                }
            }
            item {
                MinimumDurationRow(
                    seconds = minimumDurationSeconds.roundToInt(),
                    onSecondsChange = { minimumDurationSeconds = it.toFloat() },
                    onChangeFinished = {
                        viewModel.setMinimumTrackDurationSeconds(
                            minimumDurationSeconds.roundToInt(),
                        )
                    },
                )
            }
            item {
                ClickableSettingsRow(
                    title = "Add folder",
                    subtitle = "Choose a music folder using Android's folder picker.",
                    onClick = { folderPickerLauncher.launch(null) },
                )
            }
            items(
                items = scanSettings.selectedFolderUris,
                key = { it },
            ) { folderUri ->
                SelectedFolderRow(
                    folderUri = folderUri,
                    onRemove = { viewModel.removeSelectedFolderUri(folderUri) },
                )
            }
            item {
                ClickableSettingsRow(
                    title = "Rescan library",
                    subtitle = "Scan again using the current library scan settings.",
                    enabled = scanState != LibraryScanUiState.Scanning,
                    onClick = viewModel::rescanLibrary,
                )
            }
            when (val state = scanState) {
                LibraryScanUiState.Idle -> Unit
                LibraryScanUiState.Scanning -> item {
                    SettingsMessageRow("Library scan started...")
                }
                LibraryScanUiState.Complete -> item {
                    SettingsMessageRow("Library scan complete.")
                }
                is LibraryScanUiState.Error -> item {
                    SettingsMessageRow(
                        message = "Library scan failed: ${state.message}",
                        isError = true,
                    )
                }
            }
            item { SectionDivider() }

            item { SectionHeader("Backup & Restore") }
            item {
                ClickableSettingsRow(
                    title    = "Export Wavdrop Data",
                    subtitle = "Create a local JSON backup of your library metadata and stats.",
                    enabled  = exportState != ExportUiState.Exporting,
                    onClick  = { exportLauncher.launch(suggestedExportName) },
                )
            }
            when (val state = exportState) {
                ExportUiState.Idle -> Unit
                ExportUiState.Exporting -> item {
                    ExportStatusRow("Exporting Wavdrop data...")
                }
                ExportUiState.Success -> item {
                    ExportStatusRow("Export complete.")
                }
                is ExportUiState.Error -> item {
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

            item { SectionHeader("Statistics") }
            item {
                ClickableSettingsRow(
                    title    = "Statistics Dashboard",
                    subtitle = "View listening totals, most played tracks, recent plays, and skips.",
                    onClick  = onStatisticsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Listening Reports",
                    subtitle = "See top songs, artists, albums, habits, and recent activity.",
                    onClick  = onReportsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Monthly Reports",
                    subtitle = "Browse listening activity grouped by calendar month.",
                    onClick  = onMonthlyReportsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title = "Wrapped",
                    subtitle = "Review yearly event-backed listening highlights.",
                    onClick = onWrappedClick,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Appearance") }
            item {
                ClickableSettingsRow(
                    title = "Open app to",
                    subtitle = startupDestination.displayName,
                    onClick = { showStartupDestinationDialog = true },
                )
            }
            item {
                ClickableSettingsRow(
                    title   = "Home Sections",
                    subtitle = "Choose which sections appear on your Home screen.",
                    onClick  = onHomeCustomizationClick,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("About") }
            item {
                AboutInfoRow(label = "App",     value = "Wavdrop")
                AboutInfoRow(label = "Package", value = "com.launchpoint.wavdrop")
                AboutInfoRow(label = "Database", value = "wavdrop.db")
                AboutInfoRow(label = "Version", value = "Development build")
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showStartupDestinationDialog) {
        StartupDestinationDialog(
            selected = startupDestination,
            onSelect = { destination ->
                viewModel.setStartupDestination(destination)
                showStartupDestinationDialog = false
            },
            onDismiss = { showStartupDestinationDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(horizontal = 16.dp),
        color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun ScanModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun MinimumDurationRow(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "Ignore audio shorter than $seconds seconds",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = seconds.toFloat(),
            onValueChange = {
                onSecondsChange(it.roundToInt().coerceIn(1, 60))
            },
            onValueChangeFinished = onChangeFinished,
            valueRange = 1f..60f,
            steps = 58,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "1 s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text = "60 s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun StartupDestinationDialog(
    selected: StartupDestination,
    onSelect: (StartupDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open app to") },
        text = {
            Column {
                StartupDestination.entries.forEach { destination ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(destination) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = destination == selected,
                            onClick = { onSelect(destination) },
                        )
                        Text(
                            text = destination.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun SelectedFolderRow(
    folderUri: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decodedUri = remember(folderUri) { Uri.decode(folderUri) ?: folderUri }
    val displayName = remember(decodedUri) {
        decodedUri
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifBlank { decodedUri }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = decodedUri,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove selected folder",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun SettingsMessageRow(
    message: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    )
}

@Composable
private fun ClickableSettingsRow(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier           = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ExportStatusRow(
    message: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    )
}

@Composable
private fun DisabledSettingsRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(0.45f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text  = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text  = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier          = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
