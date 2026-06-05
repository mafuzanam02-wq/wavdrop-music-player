package com.launchpoint.wavdrop.ui.screen.settings

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
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.HeadphoneResumeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBluetoothScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val resumeBehavior by viewModel.resumeBehaviorSettings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bluetooth & Headphones") },
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
            item { SectionHeader("Audio Output") }
            item {
                ToggleSettingsRow(
                    title           = "Pause when audio output disconnects",
                    subtitle        = "Pause playback when headphones or Bluetooth audio disconnects.",
                    checked         = resumeBehavior.pauseOnAudioDisconnect,
                    onCheckedChange = viewModel::setPauseOnAudioDisconnect,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Bluetooth Auto Resume") }
            item {
                SettingsMessageRow(
                    message = "Choose how Wavdrop behaves when Bluetooth reconnects.",
                )
            }
            HeadphoneResumeMode.entries.forEach { mode ->
                item {
                    ScanModeRow(
                        title    = mode.displayName,
                        subtitle = mode.description,
                        selected = resumeBehavior.bluetoothResumeMode == mode,
                        onClick  = { viewModel.setBluetoothResumeMode(mode) },
                    )
                }
            }
            item { SectionDivider() }

            item { SectionHeader("Wired Headphone Auto Resume") }
            item {
                SettingsMessageRow(
                    message = "Choose how Wavdrop behaves when wired headphones reconnect.",
                )
            }
            HeadphoneResumeMode.entries.forEach { mode ->
                item {
                    ScanModeRow(
                        title    = mode.displayName,
                        subtitle = mode.description,
                        selected = resumeBehavior.wiredResumeMode == mode,
                        onClick  = { viewModel.setWiredResumeMode(mode) },
                    )
                }
            }
        }
    }
}
