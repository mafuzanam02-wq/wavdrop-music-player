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
import com.launchpoint.wavdrop.ui.components.PrimaryDestination
import com.launchpoint.wavdrop.ui.components.PrimaryNavigationBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onHomeClick: () -> Unit = {},
    onSongsClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onNowPlayingClick: () -> Unit = {},
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
        bottomBar = {
            PrimaryNavigationBar(
                selected        = PrimaryDestination.SETTINGS,
                onHomeClick     = onHomeClick,
                onSongsClick    = onSongsClick,
                onLibraryClick  = onLibraryClick,
                onSettingsClick = {},
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            item {
                ClickableSettingsRow(
                    title    = "Playback Settings",
                    subtitle = "Resume, queue, and startup preferences",
                    onClick  = onPlaybackClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Bluetooth & Headphones",
                    subtitle = "Audio disconnect and auto-resume behavior",
                    onClick  = onBluetoothClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Library & Scanning",
                    subtitle = "Scan mode, folders, and minimum track duration",
                    onClick  = onLibrarySettingsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Backup & Migration",
                    subtitle = "Export, import, and BlackPlayer data restore",
                    onClick  = onBackupClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Appearance",
                    subtitle = "Theme, accent color, app icon, and home sections",
                    onClick  = onAppearanceClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Reports & Insights",
                    subtitle = "Statistics, listening reports, monthly reports, and Wrapped",
                    onClick  = onStatisticsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "About",
                    subtitle = "Version, supported formats, and app info",
                    onClick  = onAboutClick,
                )
            }
        }
    }
}
