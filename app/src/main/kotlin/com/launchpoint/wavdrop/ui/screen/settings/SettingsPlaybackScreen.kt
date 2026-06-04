package com.launchpoint.wavdrop.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.launchpoint.wavdrop.data.settings.StartupDestination
import com.launchpoint.wavdrop.playback.SleepTimerOption
import com.launchpoint.wavdrop.playback.SleepTimerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPlaybackScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val startupDestination by viewModel.startupDestination.collectAsStateWithLifecycle()
    val resumeBehavior     by viewModel.resumeBehaviorSettings.collectAsStateWithLifecycle()
    val sleepTimerState    by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    var showStartupDialog  by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playback Settings") },
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
            item { SectionHeader("Startup") }
            item {
                ClickableSettingsRow(
                    title    = "Open app to",
                    subtitle = startupDestination.displayName,
                    onClick  = { showStartupDialog = true },
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Session") }
            item {
                ToggleSettingsRow(
                    title           = "Remember last played track",
                    subtitle        = "Restore the last playing song when you reopen Wavdrop.",
                    checked         = resumeBehavior.rememberLastTrack,
                    onCheckedChange = viewModel::setRememberLastTrack,
                )
            }
            item {
                ToggleSettingsRow(
                    title           = "Remember playback position",
                    subtitle        = "Resume from where you left off instead of starting from the beginning.",
                    checked         = resumeBehavior.rememberPosition,
                    onCheckedChange = viewModel::setRememberPosition,
                )
            }
            item {
                ToggleSettingsRow(
                    title           = "Restore last queue",
                    subtitle        = "Reload the full queue from your previous session.",
                    checked         = resumeBehavior.restoreQueue,
                    onCheckedChange = viewModel::setRestoreQueue,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Sleep Timer") }
            item {
                ClickableSettingsRow(
                    title = "Sleep Timer",
                    subtitle = sleepTimerState.summary(),
                    onClick = { showSleepTimerDialog = true },
                )
            }
        }
    }

    if (showStartupDialog) {
        StartupDestinationDialog(
            selected  = startupDestination,
            onSelect  = { destination ->
                viewModel.setStartupDestination(destination)
                showStartupDialog = false
            },
            onDismiss = { showStartupDialog = false },
        )
    }
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            selected = sleepTimerState.option,
            onSelect = { option ->
                viewModel.setSleepTimer(option)
                showSleepTimerDialog = false
            },
            onDismiss = { showSleepTimerDialog = false },
        )
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
        title            = { Text("Open app to") },
        text             = {
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
                            onClick  = { onSelect(destination) },
                        )
                        Text(
                            text     = destination.displayName,
                            style    = MaterialTheme.typography.bodyLarge,
                            color    = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SleepTimerDialog(
    selected: SleepTimerOption,
    onSelect: (SleepTimerOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer") },
        text = {
            Column {
                SleepTimerOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                        )
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun SleepTimerState.summary(): String =
    if (isActive) option.displayName else SleepTimerOption.OFF.displayName
