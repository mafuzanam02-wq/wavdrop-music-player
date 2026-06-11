package com.launchpoint.wavdrop.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.backup.BackupVerificationResult
import com.launchpoint.wavdrop.data.backup.VerificationStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupVerificationScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupVerificationViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val backupNowState by viewModel.backupNowState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.verifyOnEntry() }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup Verification") },
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
            BackupVerificationUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is BackupVerificationUiState.Ready -> {
                VerificationContent(
                    result            = state.result,
                    backupNowState    = backupNowState,
                    onVerifyAgain     = viewModel::verifyAgain,
                    onCreateBackupNow = viewModel::createBackupNow,
                    onSelectFolder    = { folderPickerLauncher.launch(null) },
                    modifier          = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun VerificationContent(
    result: BackupVerificationResult,
    backupNowState: ExportUiState,
    onVerifyAgain: () -> Unit,
    onCreateBackupNow: () -> Unit,
    onSelectFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        // ── Backup Status ───────────────────────────────────────────────────
        item { VerificationStatusCard(result) }

        if (result.status == VerificationStatus.VERIFIED ||
            result.status == VerificationStatus.PARTIAL
        ) {
            // ── Content Summary ─────────────────────────────────────────────
            item { SectionHeader("Content Summary") }
            item { SummaryLine("Tracks", result.trackCount.toString()) }
            item { SummaryLine("Playlists", result.playlistCount.toString()) }
            item { SummaryLine("Favorites", result.favoriteCount.toString()) }
            item { SummaryLine("Track statistics", result.statsCount.toString()) }
            item { SummaryLine("Listening events", result.eventCount.toString()) }
            item { SummaryLine("Lyrics overrides", result.lyricsCount.toString()) }
            item { SummaryLine("Settings", if (result.hasPreferences) "Included" else "Not included") }
            item { SectionDivider() }

            // ── Restore Coverage ────────────────────────────────────────────
            item { SectionHeader("Restore Coverage") }
            item { CoverageLine("Statistics", result.statsCount > 0) }
            item { CoverageLine("Monthly Reports", result.eventCount > 0) }
            item { CoverageLine("Wrapped", result.eventCount > 0) }
            item { CoverageLine("Playlists", result.playlistCount > 0) }
            item { CoverageLine("Favorites", result.favoriteCount > 0) }
            item { CoverageLine("Lyrics", result.lyricsCount > 0) }
            item { SectionDivider() }
        }

        // ── Warnings ────────────────────────────────────────────────────────
        if (result.warnings.isNotEmpty()) {
            item { SectionHeader("Warnings") }
            result.warnings.forEach { warning ->
                item { SettingsMessageRow(message = warning) }
            }
            item { SectionDivider() }
        }

        // ── Actions ─────────────────────────────────────────────────────────
        item { SectionHeader("Actions") }
        item {
            ClickableSettingsRow(
                title    = "Verify again",
                subtitle = "Check the latest backup file again.",
                onClick  = onVerifyAgain,
            )
        }
        item {
            ClickableSettingsRow(
                title    = "Create backup now",
                subtitle = "Save a fresh backup to your selected folder.",
                enabled  = backupNowState != ExportUiState.Exporting,
                onClick  = onCreateBackupNow,
            )
        }
        when (val state = backupNowState) {
            ExportUiState.Idle      -> Unit
            ExportUiState.Exporting -> item { ExportStatusRow("Creating backup…") }
            ExportUiState.Success   -> item { ExportStatusRow("Backup saved to selected folder.") }
            is ExportUiState.Error  -> item { ExportStatusRow(state.message, isError = true) }
        }
        item {
            ClickableSettingsRow(
                title    = "Select backup folder",
                subtitle = "Choose where Wavdrop saves backups.",
                onClick  = onSelectFolder,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Status card ──────────────────────────────────────────────────────────────

@Composable
private fun VerificationStatusCard(result: BackupVerificationResult) {
    val (title, message) = when (result.status) {
        VerificationStatus.VERIFIED ->
            "Backup verified" to "Your latest backup looks healthy and can be restored."
        VerificationStatus.PARTIAL ->
            "Backup needs attention" to "Your backup can be restored, but some data is missing. See warnings below."
        VerificationStatus.FAILED ->
            "Backup verification failed" to (result.errors.firstOrNull()
                ?: "The backup file could not be verified.")
        VerificationStatus.NO_BACKUP_FOUND ->
            "No verified backup found" to (result.warnings.firstOrNull()
                ?: "No backup was found. Create one to protect your data.")
    }
    val healthy = result.status == VerificationStatus.VERIFIED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (result.status) {
                VerificationStatus.VERIFIED ->
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                VerificationStatus.FAILED ->
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                else ->
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text  = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            if (result.fileName != null || result.backupTimestampMillis != null) {
                Spacer(Modifier.height(10.dp))
                result.backupTimestampMillis?.let {
                    StatusDetailLine("Last backup", formatVerificationTime(it))
                }
                result.fileName?.let { StatusDetailLine("Backup file", it) }
                result.fileSizeBytes?.let { StatusDetailLine("Size", formatFileSize(it)) }
            }
            if (healthy) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = "If you lose this device, your stats, playlists, and settings can be restored from this file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun StatusDetailLine(label: String, value: String) {
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

// ── Summary / coverage rows ──────────────────────────────────────────────────

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun CoverageLine(label: String, covered: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text  = if (covered) "Restorable" else "Not in backup",
            style = MaterialTheme.typography.bodyMedium,
            color = if (covered) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            },
        )
    }
}

// ── Formatting ───────────────────────────────────────────────────────────────

private fun formatVerificationTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024     -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else               -> "$bytes B"
}
