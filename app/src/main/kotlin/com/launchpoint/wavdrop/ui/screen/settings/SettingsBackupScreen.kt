package com.launchpoint.wavdrop.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.AutoBackupInterval
import com.launchpoint.wavdrop.data.settings.BackupFileMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val lastBackupAtMillis  by viewModel.lastBackupAtMillis.collectAsStateWithLifecycle()
    val needsFolderAfterRestore by viewModel.needsBackupFolderAfterRestore.collectAsStateWithLifecycle()

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
            val permissionGranted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            viewModel.onBackupFolderPicked(uri.toString(), permissionGranted)
        }
    }

    // A stored URI alone is not enough: the persisted permission may have been revoked
    // (or never granted, e.g. when restore happened on another device).
    val folderPermissionValid = remember(autoBackupFolderUri) {
        hasValidFolderPermission(context, autoBackupFolderUri)
    }
    val folderSelectionPending =
        needsFolderAfterRestore &&
            autoBackupInterval != AutoBackupInterval.OFF &&
            !folderPermissionValid

    // Post-restore prompt: dismissing with "Not Now" hides it for this screen visit only;
    // the persistent flag (and the warning card below) stay until a folder is granted.
    var promptDismissedThisVisit by remember { mutableStateOf(false) }
    if (folderSelectionPending && !promptDismissedThisVisit) {
        AlertDialog(
            onDismissRequest = { promptDismissedThisVisit = true },
            title = { Text("Choose backup folder") },
            text  = {
                Text(
                    text  = "Automatic backup settings were restored. Choose a folder so Wavdrop can continue creating backups on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            },
            confirmButton = {
                Button(onClick = {
                    promptDismissedThisVisit = true
                    folderPickerLauncher.launch(null)
                }) { Text("Choose Folder") }
            },
            dismissButton = {
                TextButton(onClick = { promptDismissedThisVisit = true }) { Text("Not Now") }
            },
        )
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
            // ── Post-restore folder warning ────────────────────────────────────
            if (folderSelectionPending) {
                item {
                    BackupFolderNeededCard(
                        onChooseFolder = { folderPickerLauncher.launch(null) },
                    )
                }
            }

            // ── Backup status ──────────────────────────────────────────────────
            item {
                BackupStatusCard(
                    lastBackupAtMillis = lastBackupAtMillis,
                    folderSelected     = autoBackupFolderUri != null,
                    folderName         = folderDisplayName,
                    interval           = autoBackupInterval,
                )
            }

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
                    subtitle = "Choose a Wavdrop backup JSON file to preview and restore.",
                    // Narrow filter so the picker hides non-JSON files. Post-selection
                    // validation still rejects anything a provider lets through.
                    onClick  = { backupImportLauncher.launch(arrayOf("application/json")) },
                )
            }
            item { SectionDivider() }

            // ── Import from BlackPlayer ────────────────────────────────────────
            item { SectionHeader("Import from BlackPlayer") }
            item {
                ClickableSettingsRow(
                    title    = "Import BlackPlayer data",
                    subtitle = "Choose a BlackPlayer .bpstat file to import play and skip counts.",
                    onClick  = onImportClick,
                )
            }
        }
    }
}

// ── Folder permission validation ────────────────────────────────────────────

/**
 * True only when [uriString] is set AND a persisted read+write URI permission for it
 * still exists. A stored URI without a live permission cannot be written to.
 */
private fun hasValidFolderPermission(context: Context, uriString: String?): Boolean {
    if (uriString == null) return false
    return runCatching {
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri.toString() == uriString &&
                permission.isReadPermission &&
                permission.isWritePermission
        }
    }.getOrDefault(false)
}

// ── Post-restore folder needed card ──────────────────────────────────────────

@Composable
private fun BackupFolderNeededCard(onChooseFolder: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text  = "Backup folder needed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = "Automatic backup is enabled, but Wavdrop needs a folder on this device before backups can continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onChooseFolder) { Text("Choose Folder") }
        }
    }
}

// ── Backup status card ─────────────────────────────────────────────────────

private const val RECENT_BACKUP_WINDOW_MS = 8L * 24 * 60 * 60 * 1000 // ~8 days

@Composable
private fun BackupStatusCard(
    lastBackupAtMillis: Long,
    folderSelected: Boolean,
    folderName: String?,
    interval: AutoBackupInterval,
) {
    val hasBackup    = lastBackupAtMillis > 0L
    val recentBackup = hasBackup &&
        System.currentTimeMillis() - lastBackupAtMillis < RECENT_BACKUP_WINDOW_MS
    val healthy = recentBackup && folderSelected

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (healthy) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text  = if (healthy) "Backups are up to date" else "Backup status",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            BackupStatusLine(
                label = "Last backup",
                value = if (hasBackup) formatBackupTime(lastBackupAtMillis) else "Never",
            )
            BackupStatusLine(
                label = "Backup folder",
                value = when {
                    !folderSelected     -> "Not selected"
                    folderName != null  -> "“$folderName”"
                    else                -> "Selected"
                },
            )
            BackupStatusLine(
                label = "Automatic backup",
                value = when (interval) {
                    AutoBackupInterval.OFF     -> "Off"
                    AutoBackupInterval.DAILY   -> "Daily"
                    AutoBackupInterval.WEEKLY  -> "Weekly"
                    AutoBackupInterval.MONTHLY -> "Monthly"
                },
            )
            if (!healthy) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        !folderSelected -> "Choose a backup folder below to protect your stats, playlists, and settings."
                        !hasBackup      -> "No backup yet. Use Back Up Now below to create your first backup."
                        else            -> "Your last backup is getting old. Consider running Back Up Now."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun BackupStatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatBackupTime(epochMillis: Long): String {
    val dateTime = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    return dateTime.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
}
