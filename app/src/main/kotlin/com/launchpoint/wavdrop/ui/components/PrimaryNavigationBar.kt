package com.launchpoint.wavdrop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class PrimaryDestination {
    HOME,
    LIBRARY,
    NOW_PLAYING,
    SETTINGS,
}

@Composable
fun PrimaryNavigationBar(
    selected: PrimaryDestination,
    onHomeClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == PrimaryDestination.HOME,
            onClick = onHomeClick,
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = selected == PrimaryDestination.LIBRARY,
            onClick = onLibraryClick,
            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
            label = { Text("Library") },
        )
        NavigationBarItem(
            selected = selected == PrimaryDestination.NOW_PLAYING,
            onClick = onNowPlayingClick,
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
            label = { Text("Now Playing") },
        )
        NavigationBarItem(
            selected = selected == PrimaryDestination.SETTINGS,
            onClick = onSettingsClick,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}
