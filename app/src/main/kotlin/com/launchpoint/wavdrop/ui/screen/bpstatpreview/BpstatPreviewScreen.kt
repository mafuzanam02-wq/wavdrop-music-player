package com.launchpoint.wavdrop.ui.screen.bpstatpreview

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.legacy.BpstatApplyResult
import com.launchpoint.wavdrop.data.legacy.BlackPlayerStatImportRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpstatPreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: BpstatPreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.processFile(uri) else viewModel.reset()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import BlackPlayer Stats") },
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
            BpstatPreviewUiState.Idle ->
                IdleContent(
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    modifier   = Modifier.padding(innerPadding),
                )

            BpstatPreviewUiState.Loading ->
                LoadingContent(
                    message  = "Reading file…",
                    modifier = Modifier.padding(innerPadding),
                )

            is BpstatPreviewUiState.Preview ->
                PreviewContent(
                    state          = state,
                    onPickAnother  = { viewModel.reset(); filePicker.launch(arrayOf("*/*")) },
                    onApplyConfirmed = viewModel::applyImport,
                    onNavigateBack = onNavigateBack,
                    modifier       = Modifier.padding(innerPadding),
                )

            is BpstatPreviewUiState.Applying ->
                LoadingContent(
                    message  = "Importing…",
                    modifier = Modifier.padding(innerPadding),
                )

            is BpstatPreviewUiState.Applied ->
                AppliedContent(
                    result         = state.result,
                    onNavigateBack = onNavigateBack,
                    modifier       = Modifier.padding(innerPadding),
                )

            is BpstatPreviewUiState.Error ->
                ErrorContent(
                    message        = state.message,
                    onRetry        = { viewModel.reset(); filePicker.launch(arrayOf("*/*")) },
                    onNavigateBack = onNavigateBack,
                    modifier       = Modifier.padding(innerPadding),
                )
        }
    }
}

// ── Idle ─────────────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector        = Icons.Default.FolderOpen,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text("Select a .bpstat export file", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text  = "Export from BlackPlayer EX via Settings → Backup & restore → Export statistics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onPickFile) { Text("Select .bpstat file") }
        }
    }
}

// ── Loading / Applying (shared spinner) ───────────────────────────────────────

@Composable
private fun LoadingContent(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Composable
private fun PreviewContent(
    state: BpstatPreviewUiState.Preview,
    onPickAnother: () -> Unit,
    onApplyConfirmed: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parse = state.parseResult
    val match = state.matchResult
    val canApply = match.matchedCount > 0

    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        ConfirmImportDialog(
            matchedCount = match.matchedCount,
            matchedPlays = match.matchedRows.sumOf { it.second.playCount.toLong() },
            matchedSkips = match.matchedRows.sumOf { it.second.skipCount.toLong() },
            onConfirm    = { showDialog = false; onApplyConfirmed() },
            onDismiss    = { showDialog = false },
        )
    }

    LazyColumn(
        modifier            = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ── Parse results ─────────────────────────────────────────────────────
        item { SectionLabel("Parse results", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        item {
            StatRow("Valid rows", parse.validRows.size.toString())
            if (parse.invalidRows.isNotEmpty()) {
                StatRow("Invalid rows", parse.invalidRows.size.toString(),
                    valueColor = MaterialTheme.colorScheme.error)
                Text(
                    text     = "Some lines could not be parsed and were skipped.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

        // ── Totals ────────────────────────────────────────────────────────────
        item { SectionLabel("Totals found in file", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        item {
            StatRow("Total play count", parse.totalPlayCount.toString())
            StatRow("Total skip count", parse.totalSkipCount.toString())
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

        // ── Matching results ──────────────────────────────────────────────────
        item { SectionLabel("Matching against your library", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        item {
            StatRow(
                label      = "Matched",
                value      = match.matchedCount.toString(),
                valueColor = if (match.matchedCount > 0)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            StatRow("Not matched", match.unmatchedCount.toString())
        }
        item {
            Text(
                text     = "Matched by title + artist + album (case-insensitive).",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
            )
        }

        // ── Unmatched sample ──────────────────────────────────────────────────
        if (match.unmatchedSample.isNotEmpty()) {
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                val label = if (match.unmatchedCount > 10)
                    "Unmatched songs (showing 10 of ${match.unmatchedCount})"
                else "Unmatched songs"
                SectionLabel(label, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            items(match.unmatchedSample, key = { it.filePath + it.title }) { row ->
                UnmatchedRowItem(row)
            }
        }

        // ── Apply error banner (shown if a previous apply attempt failed) ─────
        if (state.applyError != null) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier          = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Warning,
                            contentDescription = null,
                            tint               = MaterialTheme.colorScheme.onErrorContainer,
                            modifier           = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text       = "Import failed",
                                style      = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color      = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text  = state.applyError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }

        // ── Preview notice ────────────────────────────────────────────────────
        item { Spacer(Modifier.height(12.dp)) }
        item { PreviewNotice(modifier = Modifier.padding(horizontal = 16.dp)) }

        // ── Actions ───────────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onPickAnother, modifier = Modifier.weight(1f)) {
                    Text("Different file")
                }
                Button(
                    onClick  = { showDialog = true },
                    enabled  = canApply,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Import")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Confirmation dialog ───────────────────────────────────────────────────────

@Composable
private fun ConfirmImportDialog(
    matchedCount: Int,
    matchedPlays: Long,
    matchedSkips: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import BlackPlayer stats?") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("Matched tracks", matchedCount.toString())
                StatRow("Matched file plays", matchedPlays.toString())
                StatRow("Matched file skips", matchedSkips.toString())
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "This import only adds new counts since your last import. Re-importing the same file will not duplicate stats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Applied ───────────────────────────────────────────────────────────────────

@Composable
private fun AppliedContent(
    result: BpstatApplyResult,
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
                    StatRow("Tracks matched", result.tracksMatched.toString())
                    StatRow("Tracks updated", result.tracksUpdated.toString())
                    StatRow(
                        "Tracks skipped (no new stats)",
                        result.tracksSkippedNoNewStats.toString(),
                    )
                    StatRow("Plays added", result.playsImported.toString())
                    StatRow("Skips added", result.skipsImported.toString())
                    StatRow("Unmatched skipped", result.unmatchedSkipped.toString())
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = onNavigateBack, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
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
            Text("Could not load file", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text  = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onRetry) { Text("Try a different file") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateBack) { Text("Back") }
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
private fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier              = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
            color      = valueColor,
        )
    }
}

@Composable
private fun UnmatchedRowItem(row: BlackPlayerStatImportRow, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text     = row.title.ifBlank { "(no title)" },
            style    = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = listOf(row.artist, row.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                text     = subtitle,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.filePath.isNotBlank()) {
            Text(
                text     = row.filePath,
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            modifier  = Modifier.padding(top = 8.dp),
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            thickness = 0.5.dp,
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
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector        = Icons.Default.Info,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text       = "Preview only",
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = "No Wavdrop stats have been changed yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}
