package com.launchpoint.wavdrop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class PrimaryDestination {
    HOME,
    SONGS,
    LIBRARY,
    SETTINGS,
}

@Composable
fun PrimaryNavigationBar(
    selected: PrimaryDestination?,
    onHomeClick: () -> Unit,
    onSongsClick: () -> Unit,
    onLibraryClick: () -> Unit,
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
            selected = selected == PrimaryDestination.SONGS,
            onClick = onSongsClick,
            icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
            label = { Text("Songs") },
        )
        NavigationBarItem(
            selected = selected == PrimaryDestination.LIBRARY,
            onClick = onLibraryClick,
            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
            label = { Text("Library") },
        )
        NavigationBarItem(
            selected = selected == PrimaryDestination.SETTINGS,
            onClick = onSettingsClick,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}
