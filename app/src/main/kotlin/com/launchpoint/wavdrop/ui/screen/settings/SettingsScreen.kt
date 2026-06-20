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
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onPlaybackClick: () -> Unit,
    onBluetoothClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onBackupClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    onStatisticsClick: () -> Unit,
    onAboutClick: () -> Unit,
) {
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
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            item { SectionHeader("Listening") }
            item {
                ClickableSettingsRow(
                    title    = "Playback",
                    subtitle = "Startup screen, session restore, sleep timer, and display",
                    onClick  = onPlaybackClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Bluetooth & Headphones",
                    subtitle = "Auto-resume and audio output disconnect behavior",
                    onClick  = onBluetoothClick,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Library") }
            item {
                ClickableSettingsRow(
                    title    = "Library & Scanning",
                    subtitle = "Scan mode, folders, and track filters",
                    onClick  = onLibrarySettingsClick,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Data") }
            item {
                ClickableSettingsRow(
                    title    = "Backup & Migration",
                    subtitle = "Your data stays on your device — export, restore, or migrate at any time.",
                    onClick  = onBackupClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Reports & Insights",
                    subtitle = "Statistics, listening reports, monthly recaps, and Wrapped",
                    onClick  = onStatisticsClick,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("Display") }
            item {
                ClickableSettingsRow(
                    title    = "Appearance",
                    subtitle = "Theme, colors, display preferences, and app icon",
                    onClick  = onAppearanceClick,
                )
            }
            item { SectionDivider() }

            item { SectionHeader("More") }
            item {
                ClickableSettingsRow(
                    title    = "About",
                    subtitle = "Version info, supported formats, and legal",
                    onClick  = onAboutClick,
                )
            }
        }
    }
}
