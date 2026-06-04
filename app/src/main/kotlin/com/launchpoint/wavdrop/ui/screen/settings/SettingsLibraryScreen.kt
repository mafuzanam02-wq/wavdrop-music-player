package com.launchpoint.wavdrop.ui.screen.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.LibraryScanMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLibraryScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val scanSettings by viewModel.libraryScanSettings.collectAsStateWithLifecycle()
    val scanState    by viewModel.libraryScanUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var minimumDurationSeconds by remember(scanSettings.minimumTrackDurationSeconds) {
        mutableFloatStateOf(scanSettings.minimumTrackDurationSeconds.toFloat())
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library & Scanning") },
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
            item { SectionHeader("Scan Mode") }
            item {
                ScanModeRow(
                    title    = "Scan whole device",
                    subtitle = "Find audio from the device media library.",
                    selected = scanSettings.scanMode == LibraryScanMode.WHOLE_DEVICE,
                    onClick  = { viewModel.setScanMode(LibraryScanMode.WHOLE_DEVICE) },
                )
            }
            item {
                ScanModeRow(
                    title    = "Selected folders only",
                    subtitle = "Only include audio from the folders listed below when Wavdrop can match them.",
                    selected = scanSettings.scanMode == LibraryScanMode.SELECTED_FOLDERS,
                    onClick  = { viewModel.setScanMode(LibraryScanMode.SELECTED_FOLDERS) },
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
            item { SectionDivider() }

            item { SectionHeader("Scan Settings") }
            item {
                MinimumDurationRow(
                    seconds          = minimumDurationSeconds.roundToInt(),
                    onSecondsChange  = { minimumDurationSeconds = it.toFloat() },
                    onChangeFinished = {
                        viewModel.setMinimumTrackDurationSeconds(
                            minimumDurationSeconds.roundToInt(),
                        )
                    },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Add folder",
                    subtitle = "Choose a music folder using Android's folder picker.",
                    onClick  = { folderPickerLauncher.launch(null) },
                )
            }
            items(
                items = scanSettings.selectedFolderUris,
                key   = { it },
            ) { folderUri ->
                SelectedFolderRow(
                    folderUri = folderUri,
                    onRemove  = { viewModel.removeSelectedFolderUri(folderUri) },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Rescan library",
                    subtitle = "Scan again using the current library scan settings.",
                    enabled  = scanState != LibraryScanUiState.Scanning,
                    onClick  = viewModel::rescanLibrary,
                )
            }
            when (val state = scanState) {
                LibraryScanUiState.Idle     -> Unit
                LibraryScanUiState.Scanning -> item { SettingsMessageRow("Library scan started...") }
                LibraryScanUiState.Complete -> item { SettingsMessageRow("Library scan complete.") }
                is LibraryScanUiState.Error -> item {
                    SettingsMessageRow(
                        message = "Library scan failed: ${state.message}",
                        isError = true,
                    )
                }
            }
        }
    }
}
