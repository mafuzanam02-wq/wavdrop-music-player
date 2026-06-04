package com.launchpoint.wavdrop.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.BuildConfig
import com.launchpoint.wavdrop.data.backup.WavdropBackupParser
import com.launchpoint.wavdrop.data.local.WAVDROP_DATABASE_NAME
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDiagnosticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsDiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 0.dp,
                top = innerPadding.calculateTopPadding(),
                end = 0.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item { DiagnosticsIntro() }
            item { SectionHeader("App") }
            item {
                AboutInfoRow(label = "Version name", value = BuildConfig.VERSION_NAME)
                AboutInfoRow(label = "Version code", value = BuildConfig.VERSION_CODE.toString())
                AboutInfoRow(label = "Package", value = BuildConfig.APPLICATION_ID)
                AboutInfoRow(label = "Database", value = WAVDROP_DATABASE_NAME)
                AboutInfoRow(
                    label = "Backup version",
                    value = WavdropBackupParser.SUPPORTED_VERSION.toString(),
                )
            }
            item { SectionDivider() }
            item { SectionHeader("Library") }
            if (state.isLoading) {
                item {
                    AboutInfoRow(label = "Status", value = "Loading")
                }
            } else {
                item {
                    AboutInfoRow(label = "Songs", value = state.songCount.formatCount())
                    AboutInfoRow(label = "Albums", value = state.albumCount.formatCount())
                    AboutInfoRow(label = "Artists", value = state.artistCount.formatCount())
                    AboutInfoRow(label = "Playlists", value = state.playlistCount.formatCount())
                    AboutInfoRow(label = "Listen events", value = state.listenEventCount.formatCount())
                }
                item { SectionDivider() }
                item { SectionHeader("Preferences") }
                item {
                    AboutInfoRow(label = "Theme mode", value = state.themeMode.displayName)
                    AboutInfoRow(label = "Accent", value = state.accentColor.displayName)
                    AboutInfoRow(
                        label = "Startup destination",
                        value = state.startupDestination.displayName,
                    )
                    AboutInfoRow(label = "Scan mode", value = state.scanSettings.scanMode.displayName())
                    AboutInfoRow(
                        label = "Selected folders",
                        value = state.scanSettings.selectedFolderUris.size.formatCount(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsIntro(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "Read-only tester snapshot",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Counts and settings only. No titles, folders, file paths, or lyrics are shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            )
        }
    }
}

private fun Int.formatCount(): String =
    NUMBER_FORMAT.format(this)

private val NUMBER_FORMAT: NumberFormat = NumberFormat.getIntegerInstance(Locale.getDefault())
