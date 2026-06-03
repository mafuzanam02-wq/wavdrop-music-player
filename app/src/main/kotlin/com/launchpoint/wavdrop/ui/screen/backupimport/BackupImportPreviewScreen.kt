package com.launchpoint.wavdrop.ui.screen.backupimport

import android.net.Uri
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.backup.WavdropBackupImportApplyResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupImportPreviewScreen(
    selectedUri: Uri?,
    onNavigateBack: () -> Unit,
    viewModel: BackupImportPreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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

            BackupImportUiState.Loading ->
                LoadingContent(
                    message  = "Reading backup…",
                    modifier = Modifier.padding(innerPadding),
                )

            is BackupImportUiState.Preview ->
                PreviewContent(
                    state            = state,
                    onApplyConfirmed = viewModel::applyImport,
                    modifier         = Modifier.padding(innerPadding),
                )

            BackupImportUiState.Applying ->
                LoadingContent(
                    message  = "Applying import…",
                    modifier = Modifier.padding(innerPadding),
                )

            is BackupImportUiState.Applied ->
                AppliedContent(
                    result         = state.result,
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
                text  = "Choose Import Wavdrop Data from Settings.",
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
private fun LoadingContent(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
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
            StatRow("Import Baselines", state.baselineCount.toString())
            StatRow("Lyrics overrides", state.lyricsOverridesCount.toString())
            StatRow("Preferences",      if (state.hasPreferences) "Included" else "Not included")
            StatRow("Playlists",        state.playlistCount.toString())
        }

        item { Spacer(Modifier.height(16.dp)) }
        item { PreviewNotice(modifier = Modifier.padding(horizontal = 16.dp)) }
        item {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick  = { showDialog = true },
                enabled  = state.statsCount > 0 || state.lyricsOverridesCount > 0 || state.playlistCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("Apply Import")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Confirmation dialog ───────────────────────────────────────────────────────

@Composable
private fun ConfirmApplyDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply Wavdrop backup?") },
        text  = {
            Text(
                text  = "This will merge statistics and lyrics from this Wavdrop backup " +
                        "into your current library. Existing statistics will not be " +
                        "overwritten. Lyrics are only replaced if the backup copy is newer.",
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
    modifier: Modifier = Modifier,
) {
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
            Text("Import complete", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color    = MaterialTheme.colorScheme.surfaceVariant,
                shape    = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    StatRow("Matched tracks",   result.matchedTracks.toString())
                    StatRow("Unmatched tracks", result.unmatchedTracks.toString())
                    StatRow("Plays added",      result.playsAdded.toString())
                    StatRow("Skips added",      result.skipsAdded.toString())
                    StatRow("Lyrics restored",    result.lyricsRestored.toString())
                    StatRow("Favorites restored", result.favoritesRestored.toString())
                    if (result.preferencesRestored) {
                        StatRow("Preferences", "Restored")
                    }
                    if (result.playlistsRestored > 0 || result.playlistSongsRestored > 0) {
                        StatRow("Playlists created", result.playlistsRestored.toString())
                        StatRow("Playlist songs added", result.playlistSongsRestored.toString())
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

@Composable
private fun PreviewNotice(modifier: Modifier = Modifier) {
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
                    text  = "Tap Apply Import to merge statistics into your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}
