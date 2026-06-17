package com.launchpoint.wavdrop.ui.screen.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStatisticsScreen(
    onNavigateBack: () -> Unit,
    onStatisticsClick: () -> Unit,
    onReportsClick: () -> Unit,
    onMonthlyReportsClick: () -> Unit,
    onWrappedClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val showMilestoneCelebrations by viewModel.showMilestoneCelebrations.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports & Insights") },
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
            item {
                ClickableSettingsRow(
                    title    = "Statistics Dashboard",
                    subtitle = "View listening totals, most played tracks, recent plays, and skips.",
                    onClick  = onStatisticsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Listening Reports",
                    subtitle = "See top songs, artists, albums, habits, and recent activity.",
                    onClick  = onReportsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Monthly Reports",
                    subtitle = "Browse listening activity grouped by calendar month.",
                    onClick  = onMonthlyReportsClick,
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Wrapped",
                    subtitle = "Review your year in music.",
                    onClick  = onWrappedClick,
                )
            }
            item { SectionDivider() }
            item { SectionHeader("Wrapped") }
            item {
                ToggleSettingsRow(
                    title           = "Show milestone celebrations",
                    subtitle        = "Display a milestone summary inside your yearly Wrapped recap.",
                    checked         = showMilestoneCelebrations,
                    onCheckedChange = viewModel::setShowMilestoneCelebrations,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
