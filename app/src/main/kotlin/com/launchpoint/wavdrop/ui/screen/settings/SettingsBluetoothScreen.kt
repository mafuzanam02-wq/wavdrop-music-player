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

            item { SectionHeader("Auto Resume") }
            item {
                ToggleSettingsRow(
                    title           = "Auto Resume on wired headphones",
                    subtitle        = "Resume playback when wired headphones are connected. Not yet available.",
                    checked         = resumeBehavior.autoResumeOnHeadphones,
                    onCheckedChange = {},
                    enabled         = false,
                )
            }
            item {
                ToggleSettingsRow(
                    title           = "Auto Resume on Bluetooth",
                    subtitle        = "Resume playback when a Bluetooth audio device connects.",
                    checked         = resumeBehavior.autoResumeOnBluetooth,
                    onCheckedChange = viewModel::setAutoResumeOnBluetooth,
                )
            }
        }
    }
}
