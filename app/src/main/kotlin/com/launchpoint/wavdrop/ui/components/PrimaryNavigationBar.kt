package com.launchpoint.wavdrop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

enum class PrimaryDestination {
    HOME,
    SONGS,
    LIBRARY,
    INSIGHTS,
}

@Composable
fun PrimaryNavigationBar(
    selected: PrimaryDestination?,
    onHomeClick: () -> Unit,
    onSongsClick: () -> Unit,
    onLibraryClick: () -> Unit,
    onInsightsClick: () -> Unit,
) {
    NavigationBar {
        val navLabelStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp)
        NavigationBarItem(
            selected = selected == PrimaryDestination.HOME,
            onClick = onHomeClick,
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Home", maxLines = 1, overflow = TextOverflow.Ellipsis, style = navLabelStyle) },
        )
        NavigationBarItem(
            selected = selected == PrimaryDestination.SONGS,
            onClick = onSongsClick,
            icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
            label = { Text("Songs", maxLines = 1, overflow = TextOverflow.Ellipsis, style = navLabelStyle) },
        )
        NavigationBarItem(
            selected = selected == PrimaryDestination.LIBRARY,
            onClick = onLibraryClick,
            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
            label = { Text("Library", maxLines = 1, overflow = TextOverflow.Ellipsis, style = navLabelStyle) },
        )
        NavigationBarItem(
            selected = selected == PrimaryDestination.INSIGHTS,
            onClick = onInsightsClick,
            icon = { Icon(Icons.Default.Insights, contentDescription = null) },
            label = { Text("Insights", maxLines = 1, overflow = TextOverflow.Ellipsis, style = navLabelStyle) },
        )
    }
}
