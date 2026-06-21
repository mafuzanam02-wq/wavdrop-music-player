package com.launchpoint.wavdrop.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
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
                SettingsGroupCard {
                    ClickableSettingsRow(
                        title       = "Playback",
                        subtitle    = "How music starts, resumes, and behaves during a session.",
                        onClick     = onPlaybackClick,
                        leadingIcon = Icons.Default.Tune,
                    )
                    CardInternalDivider()
                    ClickableSettingsRow(
                        title       = "Bluetooth & Headphones",
                        subtitle    = "Auto-resume when headphones reconnect or Bluetooth connects.",
                        onClick     = onBluetoothClick,
                        leadingIcon = Icons.Default.Headphones,
                    )
                }
            }

            item { SectionHeader("Library") }
            item {
                SettingsGroupCard {
                    ClickableSettingsRow(
                        title       = "Library & Scanning",
                        subtitle    = "Scan mode, folder selection, and track filters.",
                        onClick     = onLibrarySettingsClick,
                        leadingIcon = Icons.Default.LibraryMusic,
                    )
                }
            }

            item { SectionHeader("Data") }
            item {
                SettingsGroupCard {
                    ClickableSettingsRow(
                        title       = "Backup & Migration",
                        subtitle    = "Your data stays on your device — export, restore, or migrate at any time.",
                        onClick     = onBackupClick,
                        leadingIcon = Icons.Default.Backup,
                    )
                    CardInternalDivider()
                    ClickableSettingsRow(
                        title       = "Insights",
                        subtitle    = "Wrapped, listening history, top lists, and monthly recaps.",
                        onClick     = onStatisticsClick,
                        leadingIcon = Icons.Default.Insights,
                    )
                }
            }

            item { SectionHeader("Display") }
            item {
                SettingsGroupCard {
                    ClickableSettingsRow(
                        title       = "Appearance",
                        subtitle    = "Theme, accent color, artwork style, and app icon.",
                        onClick     = onAppearanceClick,
                        leadingIcon = Icons.Default.Palette,
                    )
                }
            }

            item { SectionHeader("More") }
            item {
                SettingsGroupCard {
                    ClickableSettingsRow(
                        title       = "About",
                        subtitle    = "App version, privacy, formats, and support.",
                        onClick     = onAboutClick,
                        leadingIcon = Icons.Default.Info,
                    )
                }
            }
        }
    }
}
